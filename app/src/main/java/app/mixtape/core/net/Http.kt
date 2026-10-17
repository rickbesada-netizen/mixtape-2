package app.mixtape.core.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

fun buildHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for ${url.take(80)}")

/** Bridges NewPipeExtractor's networking onto OkHttp. */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: NpRequest): NpResponse {
        val method = request.httpMethod()
        val url = request.url()
        val data = request.dataToSend()

        val body = when {
            data != null -> data.toRequestBody(null)
            method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        val builder = Request.Builder()
            .url(url)
            .method(method, body)
            .header("User-Agent", BROWSER_USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha challenge requested", url)
            }
            val text = response.body?.string()
            return NpResponse(
                response.code,
                response.message,
                response.headers.toMultimap(),
                text,
                response.request.url.toString(),
            )
        }
    }
}

/**
 * YouTube's googlevideo servers expect the same request shape the official clients send:
 * POST with a tiny protobuf body, a request counter (&rn=), and the user agent of the
 * InnerTube client that produced the URL. Mirrors NewPipe's YoutubeHttpDataSource.
 */
object YoutubeStreamHttp {
    private val requestNumber = AtomicLong(0)
    private val POST_BODY = byteArrayOf(0x78, 0)

    fun isGoogleVideo(url: String): Boolean =
        url.toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") == true

    fun build(url: String, start: Long, endInclusive: Long?): Request {
        var finalUrl = url
        val isPlayback = url.toHttpUrlOrNull()?.encodedPath?.startsWith("/videoplayback") == true
        if (isPlayback && !finalUrl.contains("&rn=")) {
            finalUrl += "&rn=" + requestNumber.incrementAndGet()
        }

        val builder = Request.Builder().url(finalUrl)
        if (start > 0 || endInclusive != null) {
            builder.header("Range", "bytes=$start-${endInclusive ?: ""}")
        }
        if (YoutubeParsingHelper.isWebStreamingUrl(finalUrl)) {
            builder.header("Origin", "https://www.youtube.com")
            builder.header("Referer", "https://www.youtube.com")
        }
        val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(finalUrl)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            BROWSER_USER_AGENT
        }
        builder.header("User-Agent", ua)
        builder.header("Accept-Encoding", "identity")
        builder.post(POST_BODY.toRequestBody(null))
        return builder.build()
    }
}

/** A plain ranged GET for non-YouTube hosts. */
fun rangedGet(url: String, start: Long, endInclusive: Long?): Request {
    val b = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT)
        .header("Accept-Encoding", "identity")
    if (start > 0 || endInclusive != null) b.header("Range", "bytes=$start-${endInclusive ?: ""}")
    return b.build()
}

fun OkHttpClient.executeChecked(request: Request): Response {
    val response = newCall(request).execute()
    if (!response.isSuccessful) {
        val code = response.code
        response.close()
        throw HttpStatusException(code, request.url.toString())
    }
    return response
}
