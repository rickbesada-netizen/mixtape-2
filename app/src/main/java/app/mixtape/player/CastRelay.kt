package app.mixtape.player

import android.content.Context
import android.net.ConnectivityManager
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.core.net.YoutubeStreamHttp
import app.mixtape.core.net.executeChecked
import app.mixtape.core.net.rangedGet
import app.mixtape.download.CryptoManager
import app.mixtape.download.DownloadController
import app.mixtape.download.EncryptedFileReader
import app.mixtape.matcher.TrackMatcher
import app.mixtape.source.SourceRegistry
import app.mixtape.source.YouTubeVideo
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A tiny HTTP server on the phone that a Chromecast on the same Wi-Fi streams from.
 *
 * The TV can't play our encrypted offline files, and YouTube's stream links only work when
 * requested the way the phone requests them, so the phone fetches/decrypts and passes the bytes
 * on. Byte ranges are supported so seeking on the TV works. A random token in every URL keeps
 * other devices on the network from using it.
 */
class CastRelay(
    private val context: Context,
    private val http: OkHttpClient,
    private val sources: SourceRegistry,
    private val matcher: TrackMatcher,
    private val downloads: DownloadController,
    private val crypto: CryptoManager,
) {
    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val remoteUrls = ConcurrentHashMap<String, Pair<String, Long>>()

    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0)
        server = s
        pool.execute {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { handle(socket) }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** The URL a Chromecast should load for a track key, or null if not on Wi-Fi. */
    fun urlFor(key: String): String? {
        val ip = lanAddress() ?: return null
        val port = start()
        return "http://${ip.hostAddress}:$port/$token/m?k=" + URLEncoder.encode(key, "UTF-8")
    }

    /** Content type to announce to the Chromecast for a key. */
    fun mimeFor(key: String): String {
        val offline = runBlocking { downloads.completedFile(key) }
        if (offline != null) return sniff(offline, video = SourceType.fromKey(key) == SourceType.YOUTUBE_VIDEO)
        return when (SourceType.fromKey(key)) {
            SourceType.YOUTUBE_VIDEO -> "video/mp4"
            SourceType.SOUNDCLOUD -> "audio/mpeg"
            else -> "audio/mp4"
        }
    }

    private fun sniff(file: File, video: Boolean): String = runCatching {
        EncryptedFileReader(file, crypto.dataKey).use { r ->
            r.seek(0)
            val h = ByteArray(12)
            r.read(h, 0, 12)
            when {
                h[0] == 0x1A.toByte() && h[1] == 0x45.toByte() -> if (video) "video/webm" else "audio/webm"
                h[4] == 'f'.code.toByte() && h[5] == 't'.code.toByte() -> if (video) "video/mp4" else "audio/mp4"
                h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() -> "audio/mpeg"
                (h[0].toInt() and 0xFF) == 0xFF -> "audio/mpeg"
                else -> if (video) "video/mp4" else "audio/mp4"
            }
        }
    }.getOrDefault(if (video) "video/mp4" else "audio/mp4")

    private fun lanAddress(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?: lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
    }

    // ------------------------------------------------------------------------------- HTTP

    private fun handle(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]
                val out = s.getOutputStream()
                if (method == "OPTIONS") {
                    writeHead(out, 204, "No Content", mapOf("Access-Control-Allow-Origin" to "*", "Access-Control-Allow-Headers" to "*"))
                    return
                }
                if (!path.startsWith("/$token/m?k=")) {
                    writeHead(out, 404, "Not Found", mapOf("Content-Length" to "0"))
                    return
                }
                val key = URLDecoder.decode(path.substringAfter("k=").substringBefore('&'), "UTF-8")
                val range = parseRange(headers["range"])
                serve(key, range, method == "HEAD", out)
            }
        }
    }

    private fun serve(key: String, range: LongRange?, headOnly: Boolean, out: OutputStream) {
        val mime = mimeFor(key)
        val offline = runBlocking { downloads.completedFile(key) }
        if (offline != null) {
            EncryptedFileReader(offline, crypto.dataKey).use { r ->
                val total = r.plainLength
                val start = range?.first ?: 0L
                val end = (range?.last?.takeIf { it >= 0 } ?: (total - 1)).coerceAtMost(total - 1)
                writeRangeHead(out, mime, total, start, end, partial = range != null)
                if (headOnly) return
                r.seek(start)
                val buf = ByteArray(64 * 1024)
                var left = end - start + 1
                while (left > 0) {
                    val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            return
        }

        val source = SourceType.fromKey(key)
        if (source == SourceType.YOUTUBE_VIDEO) {
            val url = remoteUrls[key]?.takeIf { it.second > System.currentTimeMillis() }?.first
                ?: runBlocking { YouTubeVideo.options(Track.sourceIdOf(key)) }.castUrl?.also {
                    remoteUrls[key] = it to System.currentTimeMillis() + 60 * 60 * 1000L
                }
                ?: throw IOException("No castable stream")
            relayRanged(url, youtube = true, mime, range, headOnly, out)
            return
        }

        val playableKey = if (source.matchedToYouTube) {
            Track.keyOf(SourceType.YOUTUBE, runBlocking { matcher.resolveYoutubeId(key) })
        } else key
        val stream = runBlocking {
            sources.forType(SourceType.fromKey(playableKey)).resolveStream(Track.sourceIdOf(playableKey), forDownload = true)
        }
        if (stream.isHls) {
            relayHlsAsMp3(stream.url, headOnly, out)
        } else {
            relayRanged(stream.url, stream.isYoutube, mime, range, headOnly, out)
        }
    }

    /** Fetches the requested byte range from upstream in 1 MB pieces and passes it on. */
    private fun relayRanged(url: String, youtube: Boolean, mime: String, range: LongRange?, headOnly: Boolean, out: OutputStream) {
        val start = range?.first ?: 0L
        val chunk = 1L * 1024 * 1024
        var pos = start
        var total = -1L
        var headerSent = false
        val buf = ByteArray(64 * 1024)
        while (true) {
            val wantEnd = range?.last?.takeIf { it >= 0 }
            val pieceEnd = minOf(pos + chunk - 1, wantEnd ?: Long.MAX_VALUE)
            val req = if (youtube) YoutubeStreamHttp.build(url, pos, pieceEnd) else rangedGet(url, pos, pieceEnd)
            http.executeChecked(req).use { resp ->
                if (total < 0) {
                    total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: resp.body?.contentLength()?.takeIf { it > 0 } ?: -1L
                }
                if (!headerSent) {
                    val end = (wantEnd ?: (total - 1)).let { if (total > 0) it.coerceAtMost(total - 1) else it }
                    if (total > 0) writeRangeHead(out, mime, total, start, end, partial = range != null)
                    else writeHead(out, 200, "OK", mapOf("Content-Type" to mime, "Access-Control-Allow-Origin" to "*"))
                    headerSent = true
                    if (headOnly) return
                }
                val body = resp.body?.byteStream() ?: return
                var got = 0L
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                }
                pos += got
                if (got == 0L) return
            }
            val end = wantEnd ?: (total - 1)
            if (total > 0 && pos > end) return
            if (total <= 0) return
        }
    }

    /** SoundCloud HLS: send the MP3 segments back-to-back as one continuous MP3 stream. */
    private fun relayHlsAsMp3(playlistUrl: String, headOnly: Boolean, out: OutputStream) {
        writeHead(out, 200, "OK", mapOf("Content-Type" to "audio/mpeg", "Access-Control-Allow-Origin" to "*"))
        if (headOnly) return
        var url = playlistUrl
        var text = http.executeChecked(rangedGet(url, 0, null)).use { it.body?.string().orEmpty() }
        if (text.contains("#EXT-X-STREAM-INF")) {
            val v = text.lines().first { it.isNotBlank() && !it.startsWith("#") }.trim()
            url = url.toHttpUrl().resolve(v)!!.toString()
            text = http.executeChecked(rangedGet(url, 0, null)).use { it.body?.string().orEmpty() }
        }
        val base = url.toHttpUrl()
        val buf = ByteArray(64 * 1024)
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { seg ->
            http.executeChecked(rangedGet(base.resolve(seg)!!.toString(), 0, null)).use { r ->
                val inp = r.body?.byteStream() ?: return@use
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
    }

    private fun writeRangeHead(out: OutputStream, mime: String, total: Long, start: Long, end: Long, partial: Boolean) {
        val h = linkedMapOf(
            "Content-Type" to mime,
            "Accept-Ranges" to "bytes",
            "Content-Length" to (end - start + 1).toString(),
            "Access-Control-Allow-Origin" to "*",
        )
        if (partial) {
            h["Content-Range"] = "bytes $start-$end/$total"
            writeHead(out, 206, "Partial Content", h)
        } else {
            writeHead(out, 200, "OK", h)
        }
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\nConnection: close\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun parseRange(h: String?): LongRange? {
        if (h == null || !h.startsWith("bytes=")) return null
        val spec = h.removePrefix("bytes=").substringBefore(',')
        val a = spec.substringBefore('-').toLongOrNull() ?: return null
        val b = spec.substringAfter('-').toLongOrNull() ?: -1L
        return a..b
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) throw IOException("Header too long")
        }
    }
}
