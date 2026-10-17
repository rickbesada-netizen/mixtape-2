package app.mixtape.source

import app.mixtape.core.model.CollectionDetail
import app.mixtape.core.model.RemoteCollection
import app.mixtape.core.model.SearchFilter
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Playlists and albums on YouTube / YouTube Music / SoundCloud, via NewPipeExtractor. */
object NewPipeCollections {
    private const val MAX_TRACKS = 1000

    private fun sourceOf(service: StreamingService): SourceType =
        if (service === ServiceList.SoundCloud) SourceType.SOUNDCLOUD else SourceType.YOUTUBE

    fun trackFromItem(item: StreamInfoItem, service: StreamingService): Track? {
        val source = sourceOf(service)
        val url = item.url ?: return null
        val id = if (source == SourceType.YOUTUBE) {
            runCatching { service.streamLHFactory.getId(url) }.getOrNull() ?: return null
        } else url
        return Track(
            source = source,
            sourceId = id,
            title = item.name ?: return null,
            artist = item.uploaderName?.removeSuffix(" - Topic") ?: "",
            durationSec = item.duration.coerceAtLeast(0),
            artworkUrl = item.thumbnails.bestUrl(),
        )
    }

    suspend fun search(query: String, filter: SearchFilter): List<RemoteCollection> =
        withContext(Dispatchers.IO) {
            val (service, contentFilter) = when (filter) {
                SearchFilter.YT_MUSIC_ALBUMS -> ServiceList.YouTube to YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
                SearchFilter.SOUNDCLOUD_PLAYLISTS -> ServiceList.SoundCloud to SoundcloudSearchQueryHandlerFactory.PLAYLISTS
                else -> ServiceList.YouTube to YoutubeSearchQueryHandlerFactory.PLAYLISTS
            }
            val extractor = service.getSearchExtractor(query, listOf(contentFilter), "")
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<PlaylistInfoItem>()
                .mapNotNull { p ->
                    val url = p.url ?: return@mapNotNull null
                    val count = p.streamCount
                    RemoteCollection(
                        url = url,
                        title = p.name ?: "Playlist",
                        subtitle = listOfNotNull(
                            p.uploaderName?.takeIf { it.isNotBlank() },
                            if (count > 0) "$count tracks" else null,
                        ).joinToString(" · "),
                        artworkUrl = p.thumbnails.bestUrl(),
                        trackCount = count,
                    )
                }
        }

    suspend fun loadPlaylist(service: StreamingService, url: String): CollectionDetail =
        withContext(Dispatchers.IO) {
            val info = PlaylistInfo.getInfo(service, url)
            val items = info.relatedItems.toMutableList()
            var page: Page? = info.nextPage
            while (Page.isValid(page) && items.size < MAX_TRACKS) {
                val more = PlaylistInfo.getMoreItems(service, url, page)
                if (more.items.isEmpty()) break
                items += more.items
                page = more.nextPage
            }
            val tracks = items.mapNotNull { trackFromItem(it, service) }.distinctBy { it.key }
            CollectionDetail(
                RemoteCollection(
                    url = url,
                    title = info.name ?: "Playlist",
                    subtitle = listOfNotNull(
                        info.uploaderName?.takeIf { it.isNotBlank() },
                        sourceOf(service).label,
                    ).joinToString(" · "),
                    artworkUrl = info.thumbnails.bestUrl() ?: tracks.firstOrNull()?.artworkUrl,
                    trackCount = tracks.size.toLong(),
                ),
                tracks,
            )
        }

    suspend fun loadSingle(service: StreamingService, url: String): Track = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(service, url)
        val source = sourceOf(service)
        Track(
            source = source,
            sourceId = if (source == SourceType.YOUTUBE) service.streamLHFactory.getId(url) else info.url,
            title = info.name,
            artist = info.uploaderName?.removeSuffix(" - Topic") ?: "",
            durationSec = info.duration.coerceAtLeast(0),
            artworkUrl = info.thumbnails.bestUrl(),
        )
    }
}
