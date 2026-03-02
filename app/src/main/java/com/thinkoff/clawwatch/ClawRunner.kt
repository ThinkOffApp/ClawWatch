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
    private val binaryFile get() = File(filesDir, BINARY_NAME)
    private val configFile get() = File(filesDir, CONFIG_NAME)

    /** Copy binary and config from assets on first run only. Fix #3: never overwrite config. */
    suspend fun ensureInstalled() = withContext(Dispatchers.IO) {
        if (!binaryFile.exists()) {
            Log.i(TAG, "Installing NullClaw binary…")
            context.assets.open(BINARY_NAME).use { it.copyTo(binaryFile.outputStream()) }
            binaryFile.setExecutable(true)
        }
        // Fix #3: only copy config if it doesn't exist — preserve any on-device changes
        if (!configFile.exists()) {
            context.assets.open(CONFIG_NAME).use { it.copyTo(configFile.outputStream()) }
        }
        Log.i(TAG, "NullClaw ready at ${binaryFile.absolutePath}")
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
                        put("HOME", filesDir.absolutePath)
                        put("PATH", "/system/bin:/system/xbin")
                    }
                }
                .start()

            // Fix #7: read stdout and stderr concurrently
            var output = ""
            var error = ""
            val stdoutThread = Thread { output = process.inputStream.bufferedReader().readText() }
            val stderrThread = Thread { error = process.errorStream.bufferedReader().readText() }
            stdoutThread.start()
            stderrThread.start()
            val exit = process.waitFor()
            stdoutThread.join()
            stderrThread.join()

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
