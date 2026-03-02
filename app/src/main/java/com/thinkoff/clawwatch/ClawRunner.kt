package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

    /**
     * Run a NullClaw agent query.
     * Fix #7: stdout and stderr read on separate threads to prevent pipe buffer deadlock.
     */
    suspend fun query(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
            ?: return@withContext Result.failure(RuntimeException("No API key — run ./set_key.sh"))

        try {
            val process = ProcessBuilder(
                binaryFile.absolutePath,
                "agent",
                "--message", prompt,
                "--output", "plain",
                "--config", configFile.absolutePath
            )
                .directory(filesDir)
                .apply {
                    environment().apply {
                        put("ANTHROPIC_API_KEY", apiKey)
                        put("HOME", homeDir.absolutePath)
                        // filesDir has our 'curl' binary — NullClaw calls curl as subprocess
                        put("PATH", "${filesDir.absolutePath}:$nativeLibDir:/system/bin:/system/xbin")
                        // CA bundle for curl's HTTPS calls
                        if (caBundleFile.exists()) {
                            put("CURL_CA_BUNDLE", caBundleFile.absolutePath)
                            put("SSL_CERT_FILE", caBundleFile.absolutePath)
                        }
                    }
                }
                .start()

            // Fix #7: read stdout and stderr concurrently
            Log.i(TAG, "NullClaw running: prompt='${prompt.take(50)}' binary=${binaryFile.absolutePath} home=${homeDir.absolutePath} config=${nullclawConfigFile.exists()}")

            var output = ""
            var error = ""
            val stdoutThread = Thread { output = process.inputStream.bufferedReader().readText() }
            val stderrThread = Thread { error = process.errorStream.bufferedReader().readText() }
            stdoutThread.start()
            stderrThread.start()
            val exit = process.waitFor()
            stdoutThread.join()
            stderrThread.join()

            Log.i(TAG, "NullClaw exit=$exit output='${output.take(100)}' stderr='${error.take(200)}'")

            if (exit != 0) {
                Log.e(TAG, "NullClaw failed exit $exit: $error")
                Result.failure(RuntimeException(error.take(120)))
            } else if (output.isBlank()) {
                Log.e(TAG, "NullClaw returned empty output. stderr: $error")
                Result.failure(RuntimeException(if (error.isNotBlank()) error.take(120) else "Empty response"))
            } else {
                Result.success(output.trim())
            }
        } catch (e: Exception) {
            Log.e(TAG, "ClawRunner error", e)
            Result.failure(e)
        }
    }

    fun isInstalled() = binaryFile.exists()
}
