package io.github.crkarthik11.ottplayer

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom

/** Where the channels come from. An empty [guideUrl] means "the guide the playlist names". */
data class Source(val playlistUrl: String, val guideUrl: String)

/**
 * A tiny web page on the TV for entering the playlist from a phone or laptop: typing a
 * long URL with a remote is miserable. It runs only while the setup screen is open, and
 * the page's path holds a random token, so other devices on the network can't change
 * the playlist by guessing the address.
 */
class SetupServer(
    private val current: () -> Source,
    private val onSave: (Source) -> Unit,
    // How a saved source reaches [onSave]: on the main thread, unless a test says otherwise.
    private val deliver: (() -> Unit) -> Unit = { work -> Handler(Looper.getMainLooper()).post(work) },
) {
    private val token = randomToken()
    private var socket: ServerSocket? = null

    /** The page's address, e.g. "http://192.168.1.20:8090/k3x9q", or null when not on a network. */
    var address: String? = null
        private set

    /** Starts serving on [ip], the TV's home-network address by default. */
    fun start(ip: InetAddress? = lanAddress()) {
        if (socket != null || ip == null) return
        val s = runCatching { ServerSocket(PORT) }.getOrElse { runCatching { ServerSocket(0) }.getOrNull() } ?: return
        socket = s
        address = "http://${ip.hostAddress}:${s.localPort}/$token"
        Thread({ serve(s) }, "setup-server").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        address = null
    }

    private fun serve(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                return // closed by stop()
            }
            runCatching { client.use { handle(it) } }.onFailure { Log.w(TAG, "setup request failed", it) }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val request = readLine(input) ?: return
        val (method, path) = request.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        var length = 0
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            if (header.startsWith("content-length:", ignoreCase = true)) {
                length = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val out = client.getOutputStream()
        fun reply(status: String, html: String) {
            val body = html.toByteArray()
            out.write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
        if (path.substringBefore('?') != "/$token") { reply("404 Not Found", page("Not found", "<p>Scan the code on the TV again.</p>")); return }
        if (method != "POST") { reply("200 OK", form(current(), null)); return }
        if (length !in 0..MAX_BODY) { reply("413 Payload Too Large", page("Too long", "")); return }
        val body = ByteArray(length).also { buf ->
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
        }
        val fields = parseForm(String(body))
        val source = Source(fields["playlist"].orEmpty().trim(), fields["guide"].orEmpty().trim())
        val error = validate(source)
        if (error != null) { reply("200 OK", form(source, error)); return }
        deliver { onSave(source) }
        reply("200 OK", page("Saved", "<p>Your TV is loading the playlist now.</p><p>You can close this page.</p>"))
    }

    private fun form(s: Source, error: String?) = page(
        "Add your playlist",
        """
        ${if (error != null) "<p class=err>${esc(error)}</p>" else ""}
        <form method=post>
          <label>Playlist URL (M3U)<input name=playlist type=url required value="${esc(s.playlistUrl)}" placeholder="https://example.com/playlist.m3u"></label>
          <label>Guide URL (XMLTV, optional)<input name=guide type=url value="${esc(s.guideUrl)}" placeholder="Leave empty to use the one in the playlist"></label>
          <button>Save</button>
        </form>
        """,
    )

    private fun page(title: String, body: String) = """
        <!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <title>OTT Player · ${esc(title)}</title>
        <style>
          body{font:16px/1.4 system-ui,sans-serif;background:#0B0E13;color:#EDEFF2;margin:0;padding:24px 16px;max-width:560px}
          h1{font-size:22px;margin:0 0 16px}label{display:block;margin:0 0 16px;color:#A3ACB8;font-size:14px}
          input{display:block;box-sizing:border-box;width:100%;margin-top:6px;padding:12px;font-size:16px;border-radius:8px;border:1px solid #2E3540;background:#171B22;color:#EDEFF2}
          button{padding:12px 28px;font-size:16px;font-weight:600;border:0;border-radius:999px;background:#F2A93B;color:#141413}
          .err{color:#FF8A7A}
        </style></head><body><h1>${esc(title)}</h1>$body</body></html>
    """.trimIndent()

    companion object {
        private const val TAG = "OTTPlayer"
        private const val PORT = 8090
        private const val MAX_BODY = 16 * 1024

        /** Returns what's wrong with [s], or null if it can be saved. */
        fun validate(s: Source): String? = when {
            !isWebUrl(s.playlistUrl) -> "The playlist URL must start with http:// or https://"
            s.guideUrl.isNotEmpty() && !isWebUrl(s.guideUrl) -> "The guide URL must start with http:// or https://"
            else -> null
        }

        private fun isWebUrl(u: String) =
            (u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)) && u.length > 10 && ' ' !in u

        /** application/x-www-form-urlencoded body to a map. */
        fun parseForm(body: String): Map<String, String> = body.split('&').filter { '=' in it }.associate { pair ->
            fun dec(v: String) = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            dec(pair.substringBefore('=')) to dec(pair.substringAfter('='))
        }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** One line of an HTTP head, without its line ending; null at end of stream. */
        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString()
                if (b == '\n'.code) return buf.toString().trimEnd('\r')
                if (buf.size() < 8 * 1024) buf.write(b)
            }
        }

        private fun randomToken(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return String(CharArray(5) { chars[rnd.nextInt(chars.length)] })
        }

        /** The TV's address on the home network: a private IPv4 address on an interface that is up. */
        private fun lanAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()

        /** A QR code for [text], black on white with a quiet zone, [size] pixels square. */
        fun qr(text: String, size: Int): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
            val w = m.width
            val h = m.height
            val px = IntArray(w * h) { i -> if (m[i % w, i / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
