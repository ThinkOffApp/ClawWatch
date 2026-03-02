package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the NullClaw binary lifecycle.
 * - Copies binary from assets to app private dir on first run
 * - Runs it via ProcessBuilder
 * - Communicates via stdin/stdout JSON
 */
class ClawRunner(private val context: Context) {

    companion object {
        private const val TAG = "ClawRunner"
        private const val BINARY_NAME = "nullclaw"
    }

    private val binaryFile: File
        get() = File(context.filesDir, BINARY_NAME)

    /** Copy nullclaw binary from assets and make it executable. */
    suspend fun ensureInstalled() = withContext(Dispatchers.IO) {
        if (binaryFile.exists()) return@withContext
        Log.i(TAG, "Installing NullClaw binary...")
        context.assets.open(BINARY_NAME).use { input ->
            binaryFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        binaryFile.setExecutable(true)
        Log.i(TAG, "NullClaw installed at ${binaryFile.absolutePath}")
    }

    /**
     * Run a single NullClaw agent query and return the response text.
     * Uses: nullclaw agent --message "<prompt>" --output plain
     */
    suspend fun query(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                binaryFile.absolutePath,
                "agent",
                "--message", prompt,
                "--output", "plain"
            )
                .directory(context.filesDir)
                .redirectErrorStream(false)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                Log.e(TAG, "NullClaw error (exit $exitCode): $error")
                Result.failure(RuntimeException("NullClaw failed: $error"))
            } else {
                Result.success(output.trim())
            }
        } catch (e: Exception) {
            Log.e(TAG, "ClawRunner exception", e)
            Result.failure(e)
        }
    }

    fun isInstalled() = binaryFile.exists()
}
