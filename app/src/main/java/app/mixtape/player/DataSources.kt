package app.mixtape.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import app.mixtape.core.net.BROWSER_USER_AGENT
import app.mixtape.core.net.HttpStatusException
import app.mixtape.core.net.YoutubeStreamHttp
import app.mixtape.core.net.executeChecked
import app.mixtape.core.net.rangedGet
import app.mixtape.download.EncryptedFileReader
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.crypto.SecretKey

/**
 * Player URIs:
 *  mixtape://track?k=KEY  progressive playback (YouTube, Spotify-via-YouTube, offline files)
 *  mixtape://hls?k=KEY    SoundCloud streaming, always answered with an HLS playlist
 *  mixtape://file?k=KEY   an offline file, referenced from a synthesized HLS playlist
 */
object TrackUri {
    const val SCHEME = "mixtape"
    const val PROGRESSIVE = "track"
    const val HLS = "hls"
    const val FILE = "file"

    fun of(key: String, hls: Boolean = false): Uri = build(if (hls) HLS else PROGRESSIVE, key)

    fun build(kind: String, key: String): Uri = Uri.Builder().scheme(SCHEME).authority(kind)
        .appendQueryParameter("k", key).build()

    fun keyOf(uri: Uri): String? = if (uri.scheme == SCHEME) uri.getQueryParameter("k") else null
}

/**
 * The single DataSource the player uses. It routes each request:
 *  - mixtape:// URIs   -> offline encrypted file, YouTube stream, SoundCloud stream or HLS playlist
 *  - googlevideo URLs  -> YouTube-shaped requests
 *  - anything else     -> plain HTTP (e.g. HLS segments)
 */
class MixtapeDataSource(
    private val resolver: StreamResolver,
    private val http: OkHttpClient,
    private val key: SecretKey,
    private val durationOf: (String) -> Long,
) : DataSource {

    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val trackKey = TrackUri.keyOf(dataSpec.uri)
        if (trackKey == null) {
            val url = dataSpec.uri.toString()
            val ds = if (YoutubeStreamHttp.isGoogleVideo(url)) YoutubeDataSource(http) else httpSource()
            return openDelegate(ds, dataSpec)
        }
        return try {
            openTrack(trackKey, dataSpec)
        } catch (e: HttpStatusException) {
            if (e.code != 403 && e.code != 410) throw e
            // URL expired or was rejected: resolve again once.
            closeQuietly()
            resolver.invalidate(trackKey)
            openTrack(trackKey, dataSpec)
        }
    }

    private fun openTrack(trackKey: String, dataSpec: DataSpec): Long {
        val target = try {
            runBlocking { resolver.resolve(trackKey) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Could not resolve stream", e)
        }
        val kind = dataSpec.uri.authority

        if (kind == TrackUri.HLS) {
            val playlist = when {
                target is PlaybackTarget.Offline ->
                    singleSegmentPlaylist(TrackUri.build(TrackUri.FILE, trackKey).toString(), trackKey)
                target is PlaybackTarget.Remote && target.stream.isHls -> absolutizedPlaylist(target.stream.url)
                target is PlaybackTarget.Remote -> singleSegmentPlaylist(target.stream.url, trackKey)
                else -> throw IOException("Unplayable track")
            }
            return openDelegate(ByteArrayDataSource(playlist), dataSpec)
        }

        return when (target) {
            is PlaybackTarget.Offline -> openDelegate(
                EncryptedFileDataSource(key),
                dataSpec.withUri(Uri.fromFile(target.file)),
            )
            is PlaybackTarget.Remote -> {
                val s = target.stream
                when {
                    kind == TrackUri.FILE -> throw IOException("Offline file is missing")
                    s.isHls -> throw IOException("This stream needs HLS playback, please replay the track")
                    s.isYoutube -> openDelegate(YoutubeDataSource(http), dataSpec.withUri(Uri.parse(s.url)))
                    else -> openDelegate(httpSource(), dataSpec.withUri(Uri.parse(s.url)))
                }
            }
        }
    }

    private fun singleSegmentPlaylist(segmentUri: String, trackKey: String): ByteArray {
        val dur = durationOf(trackKey).takeIf { it > 0 } ?: 600L
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            append("#EXT-X-TARGETDURATION:").append(dur).append("\n")
            append("#EXTINF:").append(dur).append(".0,\n")
            append(segmentUri).append("\n#EXT-X-ENDLIST\n")
        }.toByteArray()
    }

    /** HLS playlists are served under our own URI, so relative segment paths must be made absolute. */
    private fun absolutizedPlaylist(url: String): ByteArray {
        val text = http.executeChecked(rangedGet(url, 0, null)).use { it.body?.string().orEmpty() }
        val base = url.toHttpUrl()
        return text.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> line.replace(Regex("URI=\"([^\"]+)\"")) { m ->
                    "URI=\"" + (base.resolve(m.groupValues[1])?.toString() ?: m.groupValues[1]) + "\""
                }
                else -> base.resolve(t)?.toString() ?: t
            }
        }.toByteArray()
    }

    private fun httpSource(): DataSource = DefaultHttpDataSource.Factory()
        .setUserAgent(BROWSER_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .createDataSource()

    private fun openDelegate(ds: DataSource, spec: DataSpec): Long {
        listeners.forEach { ds.addTransferListener(it) }
        current = ds
        return ds.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    private fun closeQuietly() {
        runCatching { current?.close() }
        current = null
    }
}

/** Progressive googlevideo requests: POST + &rn= + client user agent, honouring byte ranges. */
class YoutubeDataSource(private val http: OkHttpClient) : BaseDataSource(true) {
    private var response: Response? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length - 1 else null
        val r = http.executeChecked(YoutubeStreamHttp.build(dataSpec.uri.toString(), dataSpec.position, end))
        response = r
        val body = r.body ?: throw IOException("Empty YouTube response")
        val stream = body.byteStream()
        input = stream

        var skip = if (r.code == 200 && dataSpec.position > 0) dataSpec.position else 0L
        val contentLength = body.contentLength()
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLength >= 0 -> contentLength - skip
            else -> C.LENGTH_UNSET.toLong()
        }
        while (skip > 0) {
            val s = stream.skip(skip)
            if (s <= 0) throw IOException("Could not seek in stream")
            skip -= s
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
        else minOf(length.toLong(), bytesRemaining).toInt()
        val n = input?.read(buffer, offset, toRead) ?: return C.RESULT_END_OF_INPUT
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            input?.close()
            response?.close()
        } finally {
            input = null
            response = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** Reads and decrypts an offline file at any position. */
class EncryptedFileDataSource(private val key: SecretKey) : BaseDataSource(false) {
    private var reader: EncryptedFileReader? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val file = File(dataSpec.uri.path ?: throw IOException("Bad offline path"))
        val r = EncryptedFileReader(file, key)
        reader = r
        if (dataSpec.position > r.plainLength) throw IOException("Seek past end of offline file")
        r.seek(dataSpec.position)
        val available = r.plainLength - dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, available) else available
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val n = reader!!.read(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            reader?.close()
        } finally {
            reader = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
