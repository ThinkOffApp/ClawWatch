package com.thinkoff.clawwatch

import android.system.Os
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private val filesDir: File,
    private val shimBinary: File
) : AutoCloseable {

    companion object {
        private const val TAG = "NullClawCurlBridge"
        private const val POLL_INTERVAL_MS = 50L
        private val NEXT_BRIDGE_ID = AtomicInteger(1)
    }

    private val bridgeRoot = File(filesDir, ".nullclaw-curl-bridge")
    private val bridgeDir = File(bridgeRoot, "req-${NEXT_BRIDGE_ID.getAndIncrement()}")
    private val curlShim = File(filesDir, "curl")
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun prepare() {
        bridgeDir.mkdirs()
        installCurlShim()
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
        bridgeDir.listFiles()?.forEach { it.delete() }
        bridgeDir.delete()
    }

    private fun installCurlShim() {
        require(shimBinary.exists()) { "curl shim binary missing at ${shimBinary.absolutePath}" }
        curlShim.delete()
        Os.symlink(shimBinary.absolutePath, curlShim.absolutePath)
    }

    private fun handleRequest(requestFile: File) {
        val requestId = requestFile.name.removePrefix("ready-").removeSuffix(".txt")
        val bodyFile = File(bridgeDir, "body-$requestId.bin")
        val stdoutFile = File(bridgeDir, "stdout-$requestId.txt")
        val stderrFile = File(bridgeDir, "stderr-$requestId.txt")
        val statusFile = File(bridgeDir, "status-$requestId.txt")

        try {
            val args = requestFile.readLines()
            Log.i(TAG, "Handling curl bridge request $requestId argv=${args.joinToString(" ").take(400)}")
            val parsed = parseCurlArgs(args, bodyFile)
            val result = execute(parsed)
            stdoutFile.writeBytes(result.stdout)
            stderrFile.writeText(result.stderr)
            statusFile.writeText(result.exitCode.toString())
        } catch (e: Exception) {
            Log.e(TAG, "curl bridge request failed", e)
            stdoutFile.writeText("")
            stderrFile.writeText(e.message ?: "curl bridge failure")
            statusFile.writeText("1")
        } finally {
            requestFile.delete()
            bodyFile.delete()
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
        val outputFile: String?,
        val warnings: List<String>
    )

    private data class CurlResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: String
    )

    private fun parseCurlArgs(args: List<String>, stdinBodyFile: File): CurlRequest {
        var method = "GET"
        var url: String? = null
        val headers = mutableListOf<Pair<String, String>>()
        var body: ByteArray? = null
        var connectTimeoutMs = 30_000
        var readTimeoutMs = 30_000
        var failOnHttpError = false
        var failWithBody = false
        var outputFile: String? = null
        val warnings = mutableListOf<String>()

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
                    val value = args.getOrNull(i + 1).orEmpty()
                    body = if (value == "@-") {
                        stdinBodyFile.takeIf { it.exists() }?.readBytes() ?: ByteArray(0)
                    } else {
                        readDataArg(value)
                    }
                    if (method == "GET") method = "POST"
                    i += 2
                }
                "--data-urlencode", "-F", "--form", "-u", "--user", "-G", "-T", "--upload-file" -> {
                    val warning = "curl bridge encountered unsupported argument '$arg'"
                    warnings += warning
                    Log.w(TAG, warning)
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
                    } else if (arg.startsWith("-")) {
                        val warning = "curl bridge ignoring unrecognized argument '$arg'"
                        warnings += warning
                        Log.w(TAG, warning)
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
            outputFile = outputFile,
            warnings = warnings
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
            outFile.writeBytes(responseBody)
        }

        val exitCode = if (responseCode !in 200..299 && request.failOnHttpError) 22 else 0
        val stdout = if (targetFile == null && (responseCode in 200..299 || request.failWithBody)) responseBody else ByteArray(0)
        val stderrBody = if (responseCode !in 200..299 && !request.failWithBody) responseBody.decodeToString() else ""
        val stderr = buildString {
            request.warnings.forEach { append(it).append('\n') }
            append(stderrBody)
        }.trimEnd()
        return CurlResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }

    private fun readBody(conn: HttpURLConnection, success: Boolean): ByteArray {
        val stream = if (success) conn.inputStream else conn.errorStream
        if (stream == null) return ByteArray(0)
        val encoding = conn.contentEncoding?.lowercase()
        return if (encoding == "gzip") {
            GZIPInputStream(stream).use { it.readBytes() }
        } else {
            stream.use { it.readBytes() }
        }
    }
}
