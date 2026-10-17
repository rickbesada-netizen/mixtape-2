package app.mixtape.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException

/**
 * One playable/downloadable video quality. YouTube serves 720p and up as separate video-only and
 * audio streams; [muxed] is the old combined 360p stream that needs no merging.
 */
data class VideoChoice(
    val height: Int,
    val videoUrl: String,
    val audioUrl: String?,
    val muxed: Boolean,
) {
    val label: String get() = "${height}p"
}

data class VideoOptions(
    val videoId: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val durationSec: Long,
    val choices: List<VideoChoice>,
) {
    /** Best quality at or below [maxHeight], else the lowest available. */
    fun pick(maxHeight: Int): VideoChoice =
        choices.filter { it.height <= maxHeight }.maxByOrNull { it.height } ?: choices.minBy { it.height }
}

/**
 * Only H.264 (avc1) in MP4 is used: it plays on every phone and Android's MediaMuxer can merge it
 * with the M4A audio into a normal .mp4 file.
 */
object YouTubeVideo {
    suspend fun options(videoId: String): VideoOptions = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")

        val audios = info.audioStreams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.format == MediaFormat.M4A
        }
        val audio = (audios.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { audios })
            .maxByOrNull { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate / 1000 }

        val choices = mutableListOf<VideoChoice>()
        if (audio != null) {
            info.videoOnlyStreams
                .filter {
                    it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl &&
                        it.format == MediaFormat.MPEG_4 && (it.codec ?: "").startsWith("avc1") && it.height > 0
                }
                .groupBy { it.height }
                .forEach { (height, streams) ->
                    // Prefer 30fps over 60fps at the same height: smaller files, same picture.
                    val s = streams.minWith(compareBy({ it.fps }, { -it.bitrate }))
                    choices += VideoChoice(height, s.content, audio.content, muxed = false)
                }
        }
        info.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && !it.isVideoOnly && it.format == MediaFormat.MPEG_4 }
            .maxByOrNull { it.height }
            ?.let { muxed ->
                val h = muxed.height.takeIf { it > 0 } ?: 360
                if (choices.none { it.height == h }) choices += VideoChoice(h, muxed.content, null, muxed = true)
            }

        if (choices.isEmpty()) throw IOException("No MP4 video available for this video")

        VideoOptions(
            videoId = videoId,
            title = info.name,
            uploader = info.uploaderName?.removeSuffix(" - Topic") ?: "",
            thumbnailUrl = info.thumbnails.bestUrl(),
            durationSec = info.duration.coerceAtLeast(0),
            choices = choices.sortedBy { it.height },
        )
    }
}
