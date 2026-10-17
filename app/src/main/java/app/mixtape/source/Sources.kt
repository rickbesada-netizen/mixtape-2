package app.mixtape.source

import app.mixtape.core.model.ResolvedStream
import app.mixtape.core.model.SearchFilter
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException

/** Every streaming source implements this. Spotify does not: it only supplies metadata. */
interface MusicSource {
    val type: SourceType
    suspend fun search(query: String, filter: SearchFilter): List<Track>

    /**
     * @param sourceId the id part of the track key
     * @param forDownload prefer formats that can be written to a single file
     */
    suspend fun resolveStream(sourceId: String, forDownload: Boolean): ResolvedStream
}

enum class StreamQuality { HIGH, DATA_SAVER }

internal fun List<Image>.bestUrl(): String? =
    maxByOrNull { if (it.height > 0) it.height else 0 }?.url ?: lastOrNull()?.url

private const val DEFAULT_TTL_MS = 5L * 60 * 60 * 1000

private fun expiryFromUrl(url: String): Long {
    val expire = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
    return if (expire != null) expire * 1000 else System.currentTimeMillis() + DEFAULT_TTL_MS
}

private fun AudioStream.kbps(): Int = when {
    averageBitrate > 0 -> averageBitrate
    bitrate > 0 -> bitrate / 1000
    else -> 0
}

class YouTubeSource(private val quality: () -> StreamQuality) : MusicSource {
    override val type = SourceType.YOUTUBE
    private val service: StreamingService = ServiceList.YouTube

    override suspend fun search(query: String, filter: SearchFilter): List<Track> =
        withContext(Dispatchers.IO) {
            val contentFilter = when (filter) {
                SearchFilter.YT_VIDEOS -> YoutubeSearchQueryHandlerFactory.VIDEOS
                else -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
            }
            val extractor = service.getSearchExtractor(query, listOf(contentFilter), "")
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toTrack() }
        }

    private fun StreamInfoItem.toTrack(): Track? {
        val id = runCatching { service.streamLHFactory.getId(url) }.getOrNull() ?: return null
        return Track(
            source = SourceType.YOUTUBE,
            sourceId = id,
            title = name ?: return null,
            artist = uploaderName?.removeSuffix(" - Topic") ?: "",
            durationSec = duration.coerceAtLeast(0),
            artworkUrl = thumbnails.bestUrl(),
        )
    }

    override suspend fun resolveStream(sourceId: String, forDownload: Boolean): ResolvedStream =
        withContext(Dispatchers.IO) {
            val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$sourceId")
            val candidates = info.audioStreams.filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl &&
                    (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL)
            }.ifEmpty {
                info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            }
            if (candidates.isEmpty()) throw IOException("No playable audio stream for $sourceId")

            // Downloads prefer M4A (AAC) because it plays everywhere; streaming takes the best.
            val pool = if (forDownload) {
                candidates.filter { it.format == MediaFormat.M4A }.ifEmpty { candidates }
            } else candidates

            val chosen = when (quality()) {
                StreamQuality.HIGH -> pool.maxByOrNull { it.kbps() }
                StreamQuality.DATA_SAVER -> pool.minByOrNull { it.kbps() }
            }!!

            ResolvedStream(
                url = chosen.content,
                mimeType = chosen.format?.mimeType,
                suffix = chosen.format?.suffix ?: "m4a",
                bitrate = chosen.kbps(),
                isHls = false,
                isYoutube = true,
                expiresAtMs = expiryFromUrl(chosen.content),
            )
        }
}

class SoundCloudSource : MusicSource {
    override val type = SourceType.SOUNDCLOUD
    private val service: StreamingService = ServiceList.SoundCloud

    override suspend fun search(query: String, filter: SearchFilter): List<Track> =
        withContext(Dispatchers.IO) {
            val extractor = service.getSearchExtractor(
                query, listOf(SoundcloudSearchQueryHandlerFactory.TRACKS), ""
            )
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { item ->
                    Track(
                        source = SourceType.SOUNDCLOUD,
                        sourceId = item.url ?: return@mapNotNull null,
                        title = item.name ?: return@mapNotNull null,
                        artist = item.uploaderName ?: "",
                        durationSec = item.duration.coerceAtLeast(0),
                        artworkUrl = item.thumbnails.bestUrl(),
                    )
                }
        }

    override suspend fun resolveStream(sourceId: String, forDownload: Boolean): ResolvedStream =
        withContext(Dispatchers.IO) {
            val info = StreamInfo.getInfo(service, sourceId)
            val streams = info.audioStreams.filter { it.isUrl }
            val progressive = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            val hls = streams.filter { it.deliveryMethod == DeliveryMethod.HLS }

            // Downloads of HLS are only supported for MP3 segments (they concatenate cleanly).
            val chosen = progressive.maxByOrNull { it.kbps() }
                ?: hls.firstOrNull { it.format == MediaFormat.MP3 }
                ?: (if (!forDownload) hls.maxByOrNull { it.kbps() } else null)
                ?: throw IOException("No usable SoundCloud stream for this track")

            ResolvedStream(
                url = chosen.content,
                mimeType = chosen.format?.mimeType,
                suffix = chosen.format?.suffix ?: "mp3",
                bitrate = chosen.kbps(),
                isHls = chosen.deliveryMethod == DeliveryMethod.HLS,
                isYoutube = false,
                expiresAtMs = System.currentTimeMillis() + 20L * 60 * 1000,
            )
        }
}

class SourceRegistry(val youtube: YouTubeSource, val soundcloud: SoundCloudSource) {
    fun forType(type: SourceType): MusicSource = when (type) {
        SourceType.YOUTUBE -> youtube
        SourceType.SOUNDCLOUD -> soundcloud
        SourceType.SPOTIFY -> throw IllegalArgumentException("Spotify tracks must be matched first")
    }

    suspend fun search(query: String, filter: SearchFilter): List<Track> = when (filter) {
        SearchFilter.SOUNDCLOUD -> soundcloud.search(query, filter)
        SearchFilter.YT_VIDEOS -> youtube.search(query, filter)
        else -> youtube.search(query, SearchFilter.YT_MUSIC_SONGS)
    }

    suspend fun searchCollections(query: String, filter: SearchFilter) =
        NewPipeCollections.search(query, filter)
}
