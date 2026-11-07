package app.mixtape.source

import android.media.MediaCodecList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

/**
 * Video codecs YouTube serves:
 *  - AVC (H.264) in MP4: up to 1080p, plays everywhere, merges straight into .mp4
 *  - VP9 in WebM: up to 4K, merges into .webm (or converted to .mp4 on the phone)
 *  - AV1 in MP4: up to 4K/8K, streaming only, on phones with an AV1 decoder
 */
enum class VideoCodec(val mime: String, val label: String) {
    AVC("video/avc", "H.264"),
    VP9("video/x-vnd.on2.vp9", "VP9"),
    AV1("video/av01", "AV1"),
}

data class VideoChoice(
    val height: Int,
    val width: Int,
    val codec: VideoCodec,
    val videoUrl: String,
    /** Audio for playback (any format ExoPlayer can merge). */
    val audioUrl: String?,
    /** Audio that can go into this codec's container: M4A for MP4, Opus for WebM. */
    val downloadAudioUrl: String?,
    val muxed: Boolean,
) {
    val label: String get() = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        else -> "${height}p"
    }
    val canDownload: Boolean get() = codec != VideoCodec.AV1 && (muxed || downloadAudioUrl != null)
    val downloadExtension: String get() = if (codec == VideoCodec.VP9) "webm" else "mp4"
}

data class VideoOptions(
    val videoId: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val durationSec: Long,
    /** Qualities this phone can play, lowest first. */
    val choices: List<VideoChoice>,
    val channelUrl: String? = null,
    val channelAvatar: String? = null,
    /** Best stream that has picture and sound together (for casting). */
    val castUrl: String? = null,
    val viewCount: Long = -1,
    val description: String = "",
) {
    /** Best playable quality at or below [maxHeight], else the lowest available. */
    fun pick(maxHeight: Int): VideoChoice =
        choices.filter { it.height <= maxHeight }.maxByOrNull { it.height } ?: choices.minBy { it.height }

    /** Best downloadable quality at or below [maxHeight]. */
    fun pickDownload(maxHeight: Int): VideoChoice? {
        val ok = choices.filter { it.canDownload }
        return ok.filter { it.height <= maxHeight }.maxByOrNull { it.height } ?: ok.minByOrNull { it.height }
    }
}

object YouTubeVideo {
    suspend fun options(videoId: String): VideoOptions = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")

        val audios = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        val original = audios.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { audios }
        val m4a = original.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.kbps() }
        val opus = original.filter { it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.WEBMA }
            .maxByOrNull { it.kbps() }
        val anyAudio = (m4a ?: opus)?.content

        val videoOnly = info.videoOnlyStreams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.height > 0
        }

        fun codecOf(v: VideoStream): VideoCodec? {
            val c = (v.codec ?: "").lowercase()
            return when {
                c.startsWith("avc1") && v.format == MediaFormat.MPEG_4 -> VideoCodec.AVC
                (c.startsWith("vp9") || c.startsWith("vp09")) && v.format == MediaFormat.WEBM -> VideoCodec.VP9
                c.startsWith("av01") && v.format == MediaFormat.MPEG_4 -> VideoCodec.AV1
                else -> null
            }
        }

        val choices = mutableListOf<VideoChoice>()
        if (anyAudio != null) {
            videoOnly.mapNotNull { v -> codecOf(v)?.let { it to v } }
                .filter { (codec, v) -> DecoderSupport.canDecode(codec.mime, v.width, v.height) }
                .groupBy { (_, v) -> v.height }
                .forEach { (height, list) ->
                    // At each height prefer H.264 (best compatibility), then VP9, then AV1;
                    // and 30fps over 60fps (half the size, same picture).
                    val (codec, v) = list.minWith(
                        compareBy<Pair<VideoCodec, VideoStream>>({ it.first.ordinal }, { it.second.fps }, { -it.second.bitrate })
                    )
                    choices += VideoChoice(
                        height = height,
                        width = v.width,
                        codec = codec,
                        videoUrl = v.content,
                        audioUrl = anyAudio,
                        downloadAudioUrl = when (codec) {
                            VideoCodec.AVC -> m4a?.content
                            VideoCodec.VP9 -> opus?.content
                            VideoCodec.AV1 -> null
                        },
                        muxed = false,
                    )
                }
        }
        info.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && !it.isVideoOnly && it.format == MediaFormat.MPEG_4 }
            .maxByOrNull { it.height }
            ?.let { m ->
                val h = m.height.takeIf { it > 0 } ?: 360
                if (choices.none { it.height == h }) {
                    choices += VideoChoice(h, m.width, VideoCodec.AVC, m.content, null, null, muxed = true)
                }
            }

        if (choices.isEmpty()) throw IOException("No playable video stream for this video")

        VideoOptions(
            videoId = videoId,
            title = info.name,
            uploader = info.uploaderName?.removeSuffix(" - Topic") ?: "",
            thumbnailUrl = info.thumbnails.bestUrl(),
            durationSec = info.duration.coerceAtLeast(0),
            choices = choices.sortedBy { it.height },
            channelUrl = info.uploaderUrl,
            channelAvatar = info.uploaderAvatars.bestUrl(),
            castUrl = info.videoStreams
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && !it.isVideoOnly && it.format == MediaFormat.MPEG_4 }
                .maxByOrNull { it.height }?.content,
            viewCount = info.viewCount,
            description = info.description?.content.orEmpty(),
        )
    }

    private fun AudioStream.kbps(): Int = if (averageBitrate > 0) averageBitrate else bitrate / 1000
}

/** Checks the phone has a decoder for a codec at a given size (4K needs a capable chip). */
object DecoderSupport {
    private val codecs by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }
    private val cache = HashMap<String, Boolean>()

    @Synchronized
    fun canDecode(mime: String, width: Int, height: Int): Boolean {
        if (mime == VideoCodec.AVC.mime && height <= 1080) return true
        val key = "$mime:$width:$height"
        return cache.getOrPut(key) {
            codecs.any { info ->
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && runCatching {
                    val caps = info.getCapabilitiesForType(mime).videoCapabilities
                    val w = if (width > 0) width else height * 16 / 9
                    caps.isSizeSupported(w, height) || caps.isSizeSupported(height, w)
                }.getOrDefault(false)
            }
        }
    }
}
