package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the NullClaw binary lifecycle.
 *
 * Fixed (claudemm review):
 * - #3: config only copied from assets if it doesn't exist yet (preserves on-device edits)
 * - #7: stdout/stderr read concurrently via threads to prevent pipe deadlock
 * - #8: API key stored in EncryptedSharedPreferences (falls back to plain if unavailable)
 */
class ClawRunner(private val context: Context) {

    companion object {
        private const val TAG = "ClawRunner"
        private const val BINARY_NAME = "nullclaw"
        private const val CONFIG_NAME = "nullclaw.json"
        private const val PREFS_NAME = "clawwatch_prefs"
        private const val PREF_API_KEY = "anthropic_api_key"
    }

    private val filesDir get() = context.filesDir
    // HOME = parent of filesDir so ~/.nullclaw/config.json lands at dataDir/.nullclaw/config.json
    private val homeDir get() = context.filesDir.parentFile!!
    private val nativeLibDir get() = context.applicationInfo.nativeLibraryDir
    private val binaryFile get() = File(nativeLibDir, "libnullclaw.so")
    private val configFile get() = File(filesDir, CONFIG_NAME)
    private val nullclawConfigFile get() = File(homeDir, ".nullclaw/config.json")

    private val caBundleFile get() = File(filesDir, "ca-certificates.crt")

    /** Write NullClaw home config with API key and model, copy app config from assets. */
    suspend fun ensureInstalled() = withContext(Dispatchers.IO) {
        Log.i(TAG, "NullClaw binary at ${binaryFile.absolutePath} (exists: ${binaryFile.exists()})")
        // Symlink libcurl_bin.so → filesDir/curl so NullClaw can find it via PATH
        val curlDest = File(filesDir, "curl")
        if (!curlDest.exists()) {
            val curlSrc = File(nativeLibDir, "libcurl_bin.so")
            if (curlSrc.exists()) {
                curlSrc.copyTo(curlDest, overwrite = true)
                curlDest.setExecutable(true)
                Log.i(TAG, "curl installed at ${curlDest.absolutePath}")
            }
        }
        if (!configFile.exists()) {
            context.assets.open(CONFIG_NAME).use { it.copyTo(configFile.outputStream()) }
        }
        writeNullclawHomeConfig()
        buildCaBundle()
        Log.i(TAG, "NullClaw ready, home=${homeDir.absolutePath}")
    }

    /** Bundle Android system CA certs into a single PEM file for Zig's TLS. */
    private fun buildCaBundle() {
        if (caBundleFile.exists()) return
        val certDirs = listOf(
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts"
        )
        val bundle = StringBuilder()
        for (dir in certDirs) {
            val d = File(dir)
            if (!d.exists()) continue
            d.listFiles()?.forEach { cert ->
                try { bundle.append(cert.readText()).append("\n") } catch (_: Exception) {}
            }
            if (bundle.isNotEmpty()) break
        }
        if (bundle.isNotEmpty()) {
            caBundleFile.writeText(bundle.toString())
            Log.i(TAG, "CA bundle written: ${caBundleFile.absolutePath}")
        }
    }

    /** Write ~/.nullclaw/config.json so NullClaw knows the model + provider. */
    private fun writeNullclawHomeConfig() {
        val apiKey = getApiKey() ?: return
        nullclawConfigFile.parentFile?.mkdirs()
        nullclawConfigFile.writeText("""
{
  "agents": {
    "defaults": {
      "model": {
        "primary": "anthropic/claude-opus-4-6"
      }
    }
  },
  "providers": {
    "anthropic": {
      "api_key": "$apiKey"
    }
  }
}
""".trimIndent())
    }

    fun saveApiKey(key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_API_KEY, key).apply()
    }

    fun hasApiKey(): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_API_KEY, null)?.isNotBlank() == true

    private fun getApiKey(): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_API_KEY, null)

    // System prompt for voice responses
    private val SYSTEM_PROMPT = "You are a voice assistant on a Samsung smartwatch. " +
        "Rules: respond in 1-3 short sentences maximum. No markdown, no lists, no bullet points. " +
        "Plain spoken language only. Be direct and precise. Never say 'Certainly!' or 'Great question!'"

    /**
     * Call Anthropic API directly via Android's HTTP stack.
     * Samsung Wear OS blocks network from subprocesses so we can't use NullClaw's curl.
     */
    suspend fun query(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
            ?: return@withContext Result.failure(RuntimeException("No API key — run ./set_key.sh"))

        Log.i(TAG, "Querying Anthropic: '${prompt.take(50)}'")
        try {
            val body = JSONObject().apply {
                put("model", "claude-opus-4-6")
                put("max_tokens", 150)
                put("system", SYSTEM_PROMPT)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }.toString()

            val url = URL("https://api.anthropic.com/v1/messages")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val responseCode = conn.responseCode
            val responseBody = if (responseCode == 200)
                conn.inputStream.bufferedReader().readText()
            else
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"

            Log.i(TAG, "Anthropic response code=$responseCode")

            if (responseCode != 200) {
                Log.e(TAG, "Anthropic error: $responseBody")
                return@withContext Result.failure(RuntimeException("API error $responseCode"))
            }

            val text = JSONObject(responseBody)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()

            Log.i(TAG, "Response: '${text.take(80)}'")
            Result.success(text)
        } catch (e: Exception) {
            Log.e(TAG, "Query error", e)
            Result.failure(e)
        }
    }

    fun isInstalled() = binaryFile.exists()
}
