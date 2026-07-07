package com.aeonos.portalha

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread

/**
 * DIAL (Discovery and Launch) receiver advertising a "YouTube" app, so the
 * YouTube app on any phone (Android or iOS) on the LAN lists this Portal in
 * its cast menu — the same mechanism smart TVs without Chromecast use.
 *
 * Why DIAL and not the lounge "Link with TV code" pairing: Google culls
 * unofficial screens from the phone's cast menu a couple of minutes after a
 * session ends (verified on-device 2026-07-06 — screen stays registered and
 * bound, still delisted). LAN discovery answers live on every cast-menu open,
 * so the Portal is always visible while the app runs.
 *
 * Flow: phone multicasts an SSDP M-SEARCH for the DIAL service → we point it
 * at /dd.xml (device description; its Application-URL header names the app
 * endpoint) → phone GETs /apps/YouTube for app state → on tap it POSTs launch
 * params (pairingCode=…) → TvAppActivity loads youtube.com/tv?<params> and
 * the lounge links phone↔screen automatically. No code entry, works cloudless.
 */
class DialServer(
    private val context: Context,
    private val friendlyName: () -> String,
    private val onLaunch: (query: String) -> Unit,
    private val onStopApp: () -> Unit
) {
    companion object {
        private const val TAG = "PortalHA"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val DIAL_ST = "urn:dial-multiscreen-org:service:dial:1"
        // Not 8080 (MjpegServer) / 8554 (RTSP); also distinct from the 8009 the
        // throwaway de-risk app used, so a leftover install can't collide.
        const val HTTP_PORT = 8060
    }

    private val uuid = UUID.nameUUIDFromBytes(
        "portal-ha-dial-${Prefs(context).deviceId}".toByteArray()).toString()
    @Volatile private var running = false
    // Whether the YouTube "app" (TvAppActivity) is up — reported to the phone,
    // which uses it to decide between launching fresh and joining the session.
    @Volatile var appRunning = false
    private var ssdpSocket: MulticastSocket? = null
    private var httpSocket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true
        // Portals drop multicast without a lock — SSDP M-SEARCHes never arrive.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("portal-ha-dial").apply {
            setReferenceCounted(false)
            acquire()
        }
        thread(name = "dial-ssdp", isDaemon = true) { ssdpLoop() }
        thread(name = "dial-http", isDaemon = true) { httpLoop() }
        Log.i(TAG, "cast: DIAL receiver started (port $HTTP_PORT)")
    }

    fun stop() {
        running = false
        runCatching { ssdpSocket?.close() }
        runCatching { httpSocket?.close() }
        runCatching { multicastLock?.release() }
    }

    private fun localIp(): String {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            ni.inetAddresses.toList().forEach { a ->
                if (!a.isLoopbackAddress && a.address.size == 4 && a.isSiteLocalAddress)
                    return a.hostAddress ?: "127.0.0.1"
            }
        }
        return "127.0.0.1"
    }

    // ── SSDP discovery ────────────────────────────────────────────────────────

    private fun ssdpLoop() {
        try {
            val sock = MulticastSocket(SSDP_PORT)
            ssdpSocket = sock
            sock.joinGroup(InetAddress.getByName(SSDP_ADDR))
            val buf = ByteArray(4096)
            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val msg = String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8)
                if (!msg.startsWith("M-SEARCH")) continue
                val st = Regex("(?im)^ST:\\s*(.+?)\\s*$").find(msg)?.groupValues?.get(1) ?: continue
                if (st != DIAL_ST && st != "ssdp:all" && st != "upnp:rootdevice") continue
                val resp = "HTTP/1.1 200 OK\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "EXT:\r\n" +
                    "LOCATION: http://${localIp()}:$HTTP_PORT/dd.xml\r\n" +
                    "SERVER: Android UPnP/1.1 PortalHA/1.0\r\n" +
                    "ST: $DIAL_ST\r\n" +
                    "USN: uuid:$uuid::$DIAL_ST\r\n" +
                    "BOOTID.UPNP.ORG: 1\r\n" +
                    "CONFIGID.UPNP.ORG: 1\r\n\r\n"
                sock.send(DatagramPacket(resp.toByteArray(), resp.length, pkt.address, pkt.port))
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "cast: ssdp loop died: ${e.message}")
        }
    }

    // ── DIAL REST endpoints ───────────────────────────────────────────────────

    private fun httpLoop() {
        try {
            val server = ServerSocket(HTTP_PORT)
            httpSocket = server
            while (running) {
                val client = server.accept()
                thread(isDaemon = true) {
                    runCatching { handle(client) }
                        .onFailure { Log.w(TAG, "cast: http request failed: ${it.message}") }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "cast: http loop died: ${e.message}")
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 5000
            val input = BufferedInputStream(s.getInputStream())
            val head = readHead(input) ?: return
            val requestLine = head.substringBefore("\r\n").split(" ")
            val method = requestLine.getOrElse(0) { "" }
            val path = requestLine.getOrElse(1) { "/" }
            val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)").find(head)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val body = readBody(input, contentLength)

            val out = s.getOutputStream()
            when {
                method == "GET" && path.startsWith("/dd.xml") -> {
                    val xml = """
                        <?xml version="1.0"?>
                        <root xmlns="urn:schemas-upnp-org:device-1-0">
                          <specVersion><major>1</major><minor>0</minor></specVersion>
                          <device>
                            <deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>
                            <friendlyName>${xmlEscape(friendlyName())}</friendlyName>
                            <manufacturer>Meta</manufacturer>
                            <modelName>Portal</modelName>
                            <UDN>uuid:$uuid</UDN>
                          </device>
                        </root>
                    """.trimIndent()
                    respond(out, "200 OK", "text/xml; charset=utf-8", xml,
                        "Application-URL: http://${localIp()}:$HTTP_PORT/apps/\r\n")
                }
                method == "GET" && path.startsWith("/apps/YouTube") && !path.contains("/run") -> {
                    val state = if (appRunning) "running" else "stopped"
                    val link = if (appRunning) """<link rel="run" href="run"/>""" else ""
                    val xml = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="1.7">
                          <name>YouTube</name>
                          <options allowStop="true"/>
                          <state>$state</state>
                          $link
                        </service>
                    """.trimIndent()
                    respond(out, "200 OK", "text/xml; charset=utf-8", xml)
                }
                method == "POST" && path.startsWith("/apps/YouTube") -> {
                    Log.i(TAG, "cast: DIAL launch from ${s.inetAddress.hostAddress}")
                    appRunning = true
                    onLaunch(body)
                    respond(out, "201 Created", "text/plain", "",
                        "Location: http://${localIp()}:$HTTP_PORT/apps/YouTube/run\r\n")
                }
                method == "DELETE" && path.startsWith("/apps/YouTube/run") -> {
                    Log.i(TAG, "cast: DIAL stop from ${s.inetAddress.hostAddress}")
                    appRunning = false
                    onStopApp()
                    respond(out, "200 OK", "text/plain", "")
                }
                else -> respond(out, "404 Not Found", "text/plain", "")
            }
            out.flush()
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = 0
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(c.toChar())
            if (c == '\n'.code && prev == '\n'.code) break   // blank line = end of headers
            if (c != '\r'.code) prev = c
            if (sb.length > 16384) return null
        }
        return sb.toString()
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val b = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(b, off, length - off)
            if (n < 0) break
            off += n
        }
        return String(b, 0, off, StandardCharsets.UTF_8)
    }

    private fun respond(
        out: OutputStream, status: String, contentType: String, body: String,
        extraHeaders: String = ""
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        out.write(("HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            extraHeaders +
            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
