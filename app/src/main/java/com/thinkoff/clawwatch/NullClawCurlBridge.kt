package com.thinkoff.clawwatch

import android.util.Log
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * Replaces NullClaw's external curl dependency with a file-based bridge.
 *
 * The spawned NullClaw process still executes a `curl` command, but the shim written into
 * filesDir serializes the argv into a request file. The parent app watches that directory
 * and performs the actual HTTP request from the main app process, which still has network
 * access on Wear OS.
 */
internal class NullClawCurlBridge(
    private val filesDir: File
) : AutoCloseable {

    companion object {
        private const val TAG = "NullClawCurlBridge"
        private const val POLL_INTERVAL_MS = 50L
    }

    private val bridgeDir = File(filesDir, ".nullclaw-curl-bridge")
    private val curlShim = File(filesDir, "curl")
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun prepare() {
        bridgeDir.mkdirs()
        bridgeDir.listFiles()?.forEach { it.delete() }
        writeCurlShim()
    }

    fun directoryPath(): String = bridgeDir.absolutePath

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            while (running.get()) {
                val pending = bridgeDir.listFiles { file ->
                    file.isFile && file.name.startsWith("ready-") && file.name.endsWith(".txt")
                }?.sortedBy { it.lastModified() }.orEmpty()

                if (pending.isEmpty()) {
                    try {
                        Thread.sleep(POLL_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                    }
                    continue
                }

                for (requestFile in pending) {
                    handleRequest(requestFile)
                }
            }
        }, "nullclaw-curl-bridge").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        running.set(false)
        worker?.interrupt()
        try {
            worker?.join(500)
        } catch (_: InterruptedException) {
        }
    }

    private fun writeCurlShim() {
        val script = """
            #!/system/bin/sh
            set -eu
            
            BRIDGE_DIR="${'$'}{NULLCLAW_CURL_BRIDGE_DIR:?}"
            REQ_ID="${'$'}PPID-${'$'}${'$'}"
            TMP_FILE="${'$'}BRIDGE_DIR/.req-${'$'}REQ_ID.txt"
            READY_FILE="${'$'}BRIDGE_DIR/ready-${'$'}REQ_ID.txt"
            OUT_FILE="${'$'}BRIDGE_DIR/stdout-${'$'}REQ_ID.txt"
            ERR_FILE="${'$'}BRIDGE_DIR/stderr-${'$'}REQ_ID.txt"
            STATUS_FILE="${'$'}BRIDGE_DIR/status-${'$'}REQ_ID.txt"
            
            : > "${'$'}TMP_FILE"
            for arg in "${'$'}@"; do
              printf '%s\n' "${'$'}arg" >> "${'$'}TMP_FILE"
            done
            mv "${'$'}TMP_FILE" "${'$'}READY_FILE"
            
            while [ ! -f "${'$'}STATUS_FILE" ]; do
              sleep 0.1
            done
            
            if [ -f "${'$'}OUT_FILE" ]; then
              cat "${'$'}OUT_FILE"
            fi
            if [ -f "${'$'}ERR_FILE" ]; then
              cat "${'$'}ERR_FILE" >&2
            fi
            
            CODE=$(cat "${'$'}STATUS_FILE" 2>/dev/null || echo 1)
            rm -f "${'$'}READY_FILE" "${'$'}OUT_FILE" "${'$'}ERR_FILE" "${'$'}STATUS_FILE"
            exit "${'$'}CODE"
        """.trimIndent()

        curlShim.writeText(script)
        curlShim.setExecutable(true)
    }

    private fun handleRequest(requestFile: File) {
        val requestId = requestFile.name.removePrefix("ready-").removeSuffix(".txt")
        val stdoutFile = File(bridgeDir, "stdout-$requestId.txt")
        val stderrFile = File(bridgeDir, "stderr-$requestId.txt")
        val statusFile = File(bridgeDir, "status-$requestId.txt")

        try {
            val args = requestFile.readLines()
            val parsed = parseCurlArgs(args)
            val result = execute(parsed)
            stdoutFile.writeText(result.stdout)
            stderrFile.writeText(result.stderr)
            statusFile.writeText(result.exitCode.toString())
        } catch (e: Exception) {
            Log.e(TAG, "curl bridge request failed", e)
            stdoutFile.writeText("")
            stderrFile.writeText(e.message ?: "curl bridge failure")
            statusFile.writeText("1")
        } finally {
            requestFile.delete()
        }
    }

    private data class CurlRequest(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray?,
        val connectTimeoutMs: Int,
        val readTimeoutMs: Int,
        val failOnHttpError: Boolean,
        val failWithBody: Boolean,
        val outputFile: String?
    )

    private data class CurlResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    private fun parseCurlArgs(args: List<String>): CurlRequest {
        var method = "GET"
        var url: String? = null
        val headers = mutableListOf<Pair<String, String>>()
        var body: ByteArray? = null
        var connectTimeoutMs = 30_000
        var readTimeoutMs = 30_000
        var failOnHttpError = false
        var failWithBody = false
        var outputFile: String? = null

        fun readDataArg(value: String): ByteArray {
            if (value.startsWith("@")) {
                val path = value.removePrefix("@")
                val file = if (File(path).isAbsolute) File(path) else File(filesDir, path)
                return file.readBytes()
            }
            return value.toByteArray(Charsets.UTF_8)
        }

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "-s", "-S", "-sS", "--silent", "--show-error", "-L", "--location", "--compressed" -> {
                    i += 1
                }
                "-X", "--request" -> {
                    method = args.getOrNull(i + 1)?.uppercase() ?: method
                    i += 2
                }
                "-H", "--header" -> {
                    val value = args.getOrNull(i + 1).orEmpty()
                    val idx = value.indexOf(':')
                    if (idx >= 0) {
                        headers += value.substring(0, idx).trim() to value.substring(idx + 1).trim()
                    }
                    i += 2
                }
                "-d", "--data", "--data-raw", "--data-binary" -> {
                    body = readDataArg(args.getOrNull(i + 1).orEmpty())
                    if (method == "GET") method = "POST"
                    i += 2
                }
                "--connect-timeout" -> {
                    val secs = args.getOrNull(i + 1)?.toDoubleOrNull() ?: 30.0
                    connectTimeoutMs = (secs * 1000).toInt()
                    i += 2
                }
                "-m", "--max-time" -> {
                    val secs = args.getOrNull(i + 1)?.toDoubleOrNull() ?: 30.0
                    readTimeoutMs = (secs * 1000).toInt()
                    i += 2
                }
                "--fail" -> {
                    failOnHttpError = true
                    i += 1
                }
                "--fail-with-body" -> {
                    failOnHttpError = true
                    failWithBody = true
                    i += 1
                }
                "-o", "--output" -> {
                    outputFile = args.getOrNull(i + 1)
                    i += 2
                }
                "--url" -> {
                    url = args.getOrNull(i + 1)
                    i += 2
                }
                else -> {
                    if (arg.startsWith("http://") || arg.startsWith("https://")) {
                        url = arg
                    }
                    i += 1
                }
            }
        }

        return CurlRequest(
            method = method,
            url = requireNotNull(url) { "curl bridge missing URL in argv=${args.joinToString(" ")}" },
            headers = headers,
            body = body,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            failOnHttpError = failOnHttpError,
            failWithBody = failWithBody,
            outputFile = outputFile
        )
    }

    private fun execute(request: CurlRequest): CurlResult {
        val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = request.connectTimeoutMs
            readTimeout = request.readTimeoutMs
            request.headers.forEach { (name, value) ->
                setRequestProperty(name, value)
            }
            if (request.body != null) {
                doOutput = true
                outputStream.use { it.write(request.body) }
            }
        }

        val responseCode = conn.responseCode
        val responseBody = readBody(conn, responseCode in 200..299)
        val targetFile = request.outputFile?.takeUnless { it == "-" }
        if (targetFile != null) {
            val outFile = if (File(targetFile).isAbsolute) File(targetFile) else File(filesDir, targetFile)
            outFile.parentFile?.mkdirs()
            outFile.writeText(responseBody)
        }

        val exitCode = if (responseCode !in 200..299 && request.failOnHttpError) 22 else 0
        val stdout = if (targetFile == null && (responseCode in 200..299 || request.failWithBody)) responseBody else ""
        val stderr = if (responseCode !in 200..299 && !request.failWithBody) responseBody else ""
        return CurlResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }

    private fun readBody(conn: HttpURLConnection, success: Boolean): String {
        val stream = if (success) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        val encoding = conn.contentEncoding?.lowercase()
        return if (encoding == "gzip") {
            GZIPInputStream(stream).bufferedReader().use { it.readText() }
        } else {
            stream.bufferedReader().use { it.readText() }
        }
    }
}
