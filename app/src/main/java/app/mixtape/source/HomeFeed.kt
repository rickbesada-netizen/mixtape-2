package app.mixtape.source

import app.mixtape.core.model.RemoteCollection
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.core.net.YouTubeLimits
import app.mixtape.data.LibraryRepository
import app.mixtape.data.db.MatchDao
import app.mixtape.spotify.SpotifyApi
import app.mixtape.spotify.SpotifyAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

enum class FeedKind {
    /** Tap plays the songs as audio. */
    MUSIC,

    /** Tap opens the video player. */
    VIDEO,

    /** Playlists / albums / mixes to open. */
    COLLECTIONS,
}

data class FeedSection(
    val id: String,
    val title: String,
    val kind: FeedKind,
    val tracks: List<Track> = emptyList(),
    val collections: List<RemoteCollection> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && collections.isEmpty()
}

/**
 * The Home tab: picks based on what you've played (YouTube's own "up next" suggestions for your
 * recent songs, including YouTube Mixes), YouTube trending music and videos, SoundCloud's
 * New & hot chart, and your Spotify playlists. Each section loads on its own, so one failing
 * never blanks the page. Results are cached for 30 minutes to keep requests to YouTube low.
 */
class HomeFeedRepository(
    private val library: LibraryRepository,
    private val matches: MatchDao,
    private val spotify: SpotifyApi,
    private val spotifyAuth: SpotifyAuth,
) {
    private val lock = Mutex()
    private var cached: List<FeedSection> = emptyList()
    private var cachedAt = 0L

    fun peek(): List<FeedSection> = cached

    suspend fun load(force: Boolean): List<FeedSection> = lock.withLock {
        val fresh = System.currentTimeMillis() - cachedAt < CACHE_MS
        if (!force && fresh && cached.isNotEmpty()) return@withLock cached
        val sections = withContext(Dispatchers.IO) { loadAll() }
        if (sections.isNotEmpty()) {
            cached = sections
            cachedAt = System.currentTimeMillis()
        }
        sections
    }

    private suspend fun loadAll(): List<FeedSection> = coroutineScope {
        val youtubeOk = !YouTubeLimits.isPaused()
        val forYou = async { if (youtubeOk) safe { forYou() } else emptyList() }
        val music = async {
            if (youtubeOk) safe { listOf(kiosk("trending_music", "Trending music", FeedKind.MUSIC)) } else emptyList()
        }
        val trending = async {
            if (youtubeOk) safe { listOf(kiosk("Trending", "Trending videos", FeedKind.VIDEO)) } else emptyList()
        }
        val gaming = async {
            if (youtubeOk) safe { listOf(kiosk("trending_gaming", "Gaming", FeedKind.VIDEO)) } else emptyList()
        }
        val soundcloud = async { safe { listOf(soundcloudCharts()) } }
        val spotifyLists = async { safe { listOfNotNull(spotifyPlaylists()) } }

        (forYou.await() + music.await() + spotifyLists.await() + trending.await() +
            soundcloud.await() + gaming.await())
            .filter { !it.isEmpty }
    }

    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> =
        runCatching { block() }.getOrElse {
            if (YouTubeLimits.isBotBlock(it)) YouTubeLimits.markBlocked()
            emptyList()
        }

    /** "Because you played …": YouTube's related songs and Mixes for your two most recent plays. */
    private suspend fun forYou(): List<FeedSection> {
        val recent = library.recent().first()
        val seeds = mutableListOf<Pair<Track, String>>()
        for (t in recent) {
            val videoId = when (t.source) {
                SourceType.YOUTUBE, SourceType.YOUTUBE_VIDEO -> t.sourceId
                SourceType.SPOTIFY -> matches.get(t.key)?.youtubeId
                else -> null
            } ?: continue
            if (seeds.none { it.second == videoId }) seeds += t to videoId
            if (seeds.size == 2) break
        }
        val service = ServiceList.YouTube
        val out = mutableListOf<FeedSection>()
        val mixes = mutableListOf<RemoteCollection>()
        for ((seed, videoId) in seeds) {
            val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
            val tracks = info.relatedItems.filterIsInstance<StreamInfoItem>()
                .mapNotNull { NewPipeCollections.trackFromItem(it, service) }
                .filter { it.durationSec in 1..900 }
                .distinctBy { it.key }
                .take(20)
            if (tracks.isNotEmpty()) {
                out += FeedSection("related-$videoId", "Because you played ${seed.title.take(40)}", FeedKind.MUSIC, tracks = tracks)
            }
            mixes += info.relatedItems.filterIsInstance<PlaylistInfoItem>().mapNotNull { it.toCollection() }
        }
        if (mixes.isNotEmpty()) {
            out += FeedSection("mixes", "Mixes for you", FeedKind.COLLECTIONS, collections = mixes.distinctBy { it.url }.take(12))
        }
        return out
    }

    private fun kiosk(id: String, title: String, kind: FeedKind): FeedSection {
        val service: StreamingService = ServiceList.YouTube
        val ex = service.kioskList.getExtractorById(id, null)
        ex.fetchPage()
        val tracks = ex.initialPage.items.filterIsInstance<StreamInfoItem>()
            .mapNotNull { NewPipeCollections.trackFromItem(it, service) }
            .distinctBy { it.key }
            .take(30)
        return FeedSection("kiosk-$id", title, kind, tracks = tracks)
    }

    private fun soundcloudCharts(): FeedSection {
        val service: StreamingService = ServiceList.SoundCloud
        val ex = service.kioskList.defaultKioskExtractor
        ex.fetchPage()
        val tracks = ex.initialPage.items.filterIsInstance<StreamInfoItem>()
            .mapNotNull { NewPipeCollections.trackFromItem(it, service) }
            .distinctBy { it.key }
            .take(30)
        return FeedSection("soundcloud-hot", "SoundCloud: New & hot", FeedKind.MUSIC, tracks = tracks)
    }

    private suspend fun spotifyPlaylists(): FeedSection? {
        if (!spotifyAuth.loggedIn.value) return null
        val lists = spotify.myPlaylists().map { p ->
            RemoteCollection(
                url = "https://open.spotify.com/playlist/${p.id}",
                title = p.name,
                subtitle = listOf(p.owner, "${p.trackCount} songs").filter { it.isNotBlank() }.joinToString(" · "),
                artworkUrl = p.imageUrl,
                trackCount = p.trackCount.toLong(),
            )
        }
        return FeedSection("spotify", "Your Spotify playlists", FeedKind.COLLECTIONS, collections = lists)
    }

    private fun PlaylistInfoItem.toCollection(): RemoteCollection? {
        val u = url ?: return null
        return RemoteCollection(
            url = u,
            title = name ?: "Mix",
            subtitle = uploaderName?.takeIf { it.isNotBlank() } ?: "YouTube Mix",
            artworkUrl = thumbnails.bestUrl(),
            trackCount = streamCount,
        )
    }

    private companion object {
        const val CACHE_MS = 30L * 60 * 1000
    }
}
