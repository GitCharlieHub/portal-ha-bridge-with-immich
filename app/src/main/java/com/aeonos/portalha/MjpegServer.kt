package com.aeonos.portalha

import android.util.Base64
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class MjpegServer(val port: Int = 8080) {

    companion object {
        private const val TAG = "PortalHA"
        private const val BOUNDARY = "PORTALFRAME"
    }

    @Volatile var latestJpeg: ByteArray? = null
    var username: String = ""
    var password: String = ""

    private val clients = CopyOnWriteArrayList<StreamClient>()
    private var serverSocket: ServerSocket? = null

    fun start() {
        val ss = try { ServerSocket(port) } catch (e: IOException) {
            Log.e(TAG, "MJPEG server failed to bind :$port — ${e.message}"); return
        }
        serverSocket = ss
        Thread({
            Log.i(TAG, "MJPEG server on :$port${if (username.isNotEmpty()) " (auth enabled)" else " (no auth)"}")
            while (!ss.isClosed) {
                try { handleConnection(ss.accept()) } catch (_: IOException) { break }
            }
        }, "mjpeg-accept").also { it.isDaemon = true }.start()
    }

    private fun handleConnection(socket: Socket) {
        Thread({
            try {
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return@Thread
                val path = requestLine.split(" ").getOrElse(1) { "/" }

                // Read all headers until blank line
                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                while (line != null && line.isNotEmpty()) {
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                    line = reader.readLine()
                }

                // Basic auth check
                if (username.isNotEmpty() && !checkAuth(headers["authorization"])) {
                    socket.getOutputStream().apply {
                        write(("HTTP/1.0 401 Unauthorized\r\n" +
                               "WWW-Authenticate: Basic realm=\"Portal Camera\"\r\n\r\n").toByteArray())
                        flush()
                    }
                    socket.close()
                    return@Thread
                }

                when {
                    path.endsWith("/stream") || path.endsWith("/") || path.contains("mjpeg") -> {
                        val out = socket.getOutputStream()
                        out.write(
                            ("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace;" +
                             "boundary=$BOUNDARY\r\nCache-Control: no-cache\r\n\r\n").toByteArray()
                        )
                        val client = StreamClient(socket, out)
                        clients.add(client)
                        latestJpeg?.let { client.push(it) }
                        client.runUntilClosed()
                        clients.remove(client)
                    }
                    path.endsWith("/snapshot") || path.endsWith("/camera.jpg") -> {
                        val frame = latestJpeg
                        val out = socket.getOutputStream()
                        if (frame != null) {
                            out.write(
                                ("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\n" +
                                 "Content-Length: ${frame.size}\r\nCache-Control: no-cache\r\n\r\n").toByteArray()
                            )
                            out.write(frame)
                        } else {
                            out.write("HTTP/1.0 503 Service Unavailable\r\n\r\n".toByteArray())
                        }
                        out.flush()
                        socket.close()
                    }
                    else -> {
                        socket.getOutputStream().write("HTTP/1.0 404 Not Found\r\n\r\n".toByteArray())
                        socket.close()
                    }
                }
            } catch (_: Exception) { socket.runCatching { close() } }
        }, "mjpeg-conn").also { it.isDaemon = true }.start()
    }

    private fun checkAuth(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ")) return false
        return try {
            val decoded = String(Base64.decode(header.removePrefix("Basic "), Base64.DEFAULT))
            val colon = decoded.indexOf(':')
            colon >= 0 && decoded.substring(0, colon) == username && decoded.substring(colon + 1) == password
        } catch (_: Exception) { false }
    }

    fun pushFrame(jpeg: ByteArray) {
        latestJpeg = jpeg
        val dead = mutableListOf<StreamClient>()
        for (c in clients) {
            try { c.push(jpeg) } catch (_: IOException) { dead.add(c) }
        }
        clients.removeAll(dead)
    }

    fun stop() {
        clients.forEach { it.close() }
        clients.clear()
        serverSocket?.close()
        serverSocket = null
    }

    private inner class StreamClient(private val socket: Socket, private val out: OutputStream) {
        private val lock = Object()
        @Volatile private var pending: ByteArray? = null
        @Volatile private var closed = false

        fun push(jpeg: ByteArray) {
            pending = jpeg
            synchronized(lock) { lock.notifyAll() }
        }

        fun runUntilClosed() {
            while (!closed && !socket.isClosed) {
                val frame = synchronized(lock) {
                    while (pending == null && !closed) lock.wait(5_000)
                    pending.also { pending = null }
                } ?: continue
                try {
                    out.write(("--$BOUNDARY\r\nContent-Type: image/jpeg\r\n" +
                               "Content-Length: ${frame.size}\r\n\r\n").toByteArray())
                    out.write(frame)
                    out.write("\r\n".toByteArray())
                    out.flush()
                } catch (_: IOException) { break }
            }
        }

        fun close() { closed = true; socket.runCatching { close() } }
    }
}
