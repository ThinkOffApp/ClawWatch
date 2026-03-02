package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the NullClaw binary lifecycle.
 *
 * - Copies binary + config from assets to app private dir on first run
 * - Injects API key from BuildConfig / SharedPreferences
 * - Runs queries via ProcessBuilder (stdin/stdout)
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
    private val binaryFile get() = File(filesDir, BINARY_NAME)
    private val configFile get() = File(filesDir, CONFIG_NAME)

    /** Copy binary and config from assets, inject API key. */
    suspend fun ensureInstalled() = withContext(Dispatchers.IO) {
        // Binary
        if (!binaryFile.exists()) {
            Log.i(TAG, "Installing NullClaw binary…")
            context.assets.open(BINARY_NAME).use { it.copyTo(binaryFile.outputStream()) }
            binaryFile.setExecutable(true)
        }

        // Config — always rewrite so updates to assets take effect
        context.assets.open(CONFIG_NAME).use { it.copyTo(configFile.outputStream()) }

        Log.i(TAG, "NullClaw ready at ${binaryFile.absolutePath}")
    }

    /** Store API key securely in SharedPreferences (call from settings UI). */
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
     * Run a single NullClaw agent query and return the response.
     *
     * nullclaw agent --message "<prompt>" --output plain --config nullclaw.json
     */
    suspend fun query(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
            ?: return@withContext Result.failure(RuntimeException("No API key — tap ⚙ to add one"))

        try {
            val env = mutableMapOf(
                "ANTHROPIC_API_KEY" to apiKey,
                "HOME" to filesDir.absolutePath,
                "PATH" to "/system/bin:/system/xbin"
            )

            val process = ProcessBuilder(
                binaryFile.absolutePath,
                "agent",
                "--message", prompt,
                "--output", "plain",
                "--config", configFile.absolutePath
            )
                .directory(filesDir)
                .apply { environment().putAll(env) }
                .redirectErrorStream(false)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val error  = process.errorStream.bufferedReader().readText()
            val exit   = process.waitFor()

            if (exit != 0) {
                Log.e(TAG, "NullClaw exit $exit: $error")
                Result.failure(RuntimeException(error.take(120)))
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
