package com.vibemusic.nativeapp.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class SyncHttpServer(
    private val onHandleRequest: (RequestContext) -> Unit
) {
    class RequestContext(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val bodyStream: InputStream,
        private val out: OutputStream
    ) {
        fun header(name: String): String = headers[name.lowercase()].orEmpty()

        fun readAllBody(maxBytes: Int = 64 * 1024 * 1024): ByteArray {
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = bodyStream.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("body exceeds limit")
                baos.write(buf, 0, n)
            }
            return baos.toByteArray()
        }

        fun sendJson(status: Int, json: JSONObject) {
            sendBytes(status, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))
        }

        fun sendError(status: Int, msg: String) {
            sendJson(status, JSONObject().apply { put("error", msg) })
        }

        fun sendBytes(status: Int, contentType: String, data: ByteArray, extraHeaders: Map<String, String> = emptyMap()) {
            sendHeaders(status, contentType, data.size.toLong(), extraHeaders)
            out.write(data)
            out.flush()
        }

        fun sendHeaders(
            status: Int,
            contentType: String,
            contentLength: Long,
            extraHeaders: Map<String, String> = emptyMap()
        ): OutputStream {
            val reason = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                429 -> "Too Many Requests"
                else -> "Internal Server Error"
            }
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            sb.append("Content-Type: ").append(contentType).append("\r\n")
            if (contentLength >= 0L) {
                sb.append("Content-Length: ").append(contentLength).append("\r\n")
            }
            for ((k, v) in extraHeaders) {
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            return out
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    var port: Int = 0
        private set

    @Synchronized
    fun start(): Int {
        if (serverSocket != null && serverSocket?.isClosed == false) return port
        val ss = try {
            ServerSocket(SyncConstants.DEFAULT_PORT)
        } catch (_: Exception) {
            ServerSocket(0)
        }
        serverSocket = ss
        port = ss.localPort
        acceptJob = scope.launch {
            while (!ss.isClosed) {
                try {
                    val sock = ss.accept()
                    launch { handleClient(sock) }
                } catch (_: Exception) {
                    if (ss.isClosed) break
                }
            }
        }
        return port
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        port = 0
    }

    private fun readHeaderBlock(bis: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream(1024)
        var state = 0 // 1:\r, 2:\r\n, 3:\r\n\r
        while (baos.size() < 65536) {
            val b = bis.read()
            if (b < 0) return null
            baos.write(b)
            when (b) {
                '\r'.code -> state = if (state == 2) 3 else 1
                '\n'.code -> {
                    if (state == 3) {
                        return baos.toString(Charsets.US_ASCII.name())
                    }
                    state = if (state == 1) 2 else 0
                }
                else -> state = 0
            }
        }
        return null
    }

    private fun handleClient(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 120_000
            s.tcpNoDelay = true
            val bis = BufferedInputStream(s.getInputStream(), 65536)
            val out = s.getOutputStream()
            val headerBlock = readHeaderBlock(bis) ?: return
            val lines = headerBlock.split("\r\n")
            if (lines.isEmpty()) return
            val reqLineParts = lines[0].split(' ', limit = 3)
            if (reqLineParts.size < 2) return
            val method = reqLineParts[0].uppercase()
            val path = reqLineParts[1]
            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase()
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                }
            }

            val transferEnc = headers["transfer-encoding"].orEmpty().lowercase()
            val bodyStream: InputStream = if (transferEnc.contains("chunked")) {
                ChunkedBodyInputStream(bis)
            } else {
                val contentLength = headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                FixedLengthInputStream(bis, contentLength)
            }

            val ctx = RequestContext(method, path, headers, bodyStream, out)
            try {
                onHandleRequest(ctx)
            } catch (e: Exception) {
                try {
                    ctx.sendError(500, e.message ?: "internal error")
                } catch (_: Exception) {}
            }
        }
    }

    private class FixedLengthInputStream(
        input: InputStream,
        private var remaining: Long
    ) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val r = super.read()
            if (r >= 0) remaining--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = super.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }
    }

    private class ChunkedBodyInputStream(private val input: InputStream) : InputStream() {
        private var chunkRemaining = 0L
        private var eof = false

        private fun readAsciiLine(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) throw EOFException("unexpected EOF in chunked header")
                if (c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return sb.toString()
        }

        private fun nextChunk(): Boolean {
            if (eof) return false
            val line = readAsciiLine().substringBefore(';').trim()
            val size = line.toLongOrNull(16) ?: throw IOException("invalid chunk size: $line")
            if (size == 0L) {
                while (readAsciiLine().isNotEmpty()) {
                    // skip trailers
                }
                eof = true
                return false
            }
            chunkRemaining = size
            return true
        }

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (chunkRemaining == 0L) {
                if (!nextChunk()) return -1
            }
            val toRead = minOf(len.toLong(), chunkRemaining).toInt()
            val n = input.read(b, off, toRead)
            if (n < 0) throw EOFException("unexpected EOF in chunk body")
            chunkRemaining -= n
            if (chunkRemaining == 0L) {
                readAsciiLine() // trailing CRLF after chunk payload
            }
            return n
        }
    }
}
