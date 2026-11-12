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
import kotlinx.coroutines.awaitAll
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
    private val madeForYou: MadeForYou,
    private val http: okhttp3.OkHttpClient,
    private val browse: BrowseRepository,
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
        val mixes = async {
            // Building the mixes can take a while the first time each day; show what's ready.
            kotlinx.coroutines.withTimeoutOrNull(15_000) { runCatching { madeForYou.prefetch().await() }.getOrNull() }
                ?: madeForYou.peek()
        }
        val charts = async { safe { spotifyEditorial() } }
        val deezerTop = async {
            safe { listOf(FeedSection("deezer-top", "Top playlists right now", FeedKind.COLLECTIONS, collections = browse.topPlaylists().take(30))) }
        }

        val generated = mixes.await()
        val madeSection = FeedSection(
            "made-for-you", "Made for you", FeedKind.COLLECTIONS,
            collections = generated.filter { it.group == GeneratedMix.Group.MADE_FOR_YOU }.map { it.toCollection() },
        )
        val artistSection = FeedSection(
            "top-artists", "Best of your top artists", FeedKind.COLLECTIONS,
            collections = generated.filter { it.group == GeneratedMix.Group.ARTISTS }.map { it.toCollection() },
        )
        val (chartSections, popularSections) = charts.await().partition { it.id == "spotify-charts" }

        (listOf(madeSection) + chartSections + forYou.await() + artistSection + deezerTop.await() + music.await() + popularSections +
            spotifyLists.await() + trending.await() + soundcloud.await() + gaming.await())
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
                SourceType.SPOTIFY, SourceType.DEEZER -> matches.get(t.key)?.youtubeId
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

    /**
     * Spotify's own charts and big playlists (Top 50, Today's Top Hits...). Spotify doesn't let new
     * apps read these through its API, but they open through the public embed page like any shared
     * link. Each is checked through Spotify's oEmbed endpoint (also gives the cover), and any that
     * Spotify has retired are left out.
     */
    private suspend fun spotifyEditorial(): List<FeedSection> = coroutineScope {
        val country = java.util.Locale.getDefault().country.uppercase()
        val local = LOCAL_TOP_50[country]
        val chartIds = listOfNotNull(
            "37i9dQZEVXbMDoHDwVN2tF" to "Top 50 – Global",
            local?.let { it to "Top 50 – ${java.util.Locale("", country).displayCountry}" },
            "37i9dQZEVXbLiRSasKsNU9" to "Viral 50 – Global",
            "37i9dQZF1DXcBWIGoYBM5M" to "Today's Top Hits",
        )
        val charts = chartIds.map { (id, name) -> async { spotifyTile(id, name) } }
        val popular = POPULAR.map { (id, name) -> async { spotifyTile(id, name) } }
        listOf(
            FeedSection("spotify-charts", "Charts", FeedKind.COLLECTIONS, collections = charts.awaitAll().filterNotNull()),
            FeedSection("spotify-popular", "Popular playlists on Spotify", FeedKind.COLLECTIONS, collections = popular.awaitAll().filterNotNull()),
        )
    }

    private suspend fun spotifyTile(id: String, fallbackName: String): RemoteCollection? = withContext(Dispatchers.IO) {
        val url = "https://open.spotify.com/playlist/$id"
        val o = runCatching {
            val req = okhttp3.Request.Builder()
                .url("https://open.spotify.com/oembed?url=" + java.net.URLEncoder.encode(url, "UTF-8"))
                .header("User-Agent", "Mozilla/5.0")
                .build()
            http.newCall(req).execute().use { r ->
                when {
                    r.code == 404 -> null // retired playlist
                    !r.isSuccessful -> org.json.JSONObject() // Spotify unreachable right now; still show the tile
                    else -> org.json.JSONObject(r.body?.string().orEmpty())
                }
            }
        }.getOrElse { org.json.JSONObject() } ?: return@withContext null
        RemoteCollection(
            url = url,
            title = o.optString("title").ifBlank { fallbackName },
            subtitle = "Spotify",
            artworkUrl = o.optString("thumbnail_url").ifBlank { null },
        )
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

        /** Spotify's Top 50 chart for your country (from the phone's region setting). */
        val LOCAL_TOP_50 = mapOf(
            "AU" to "37i9dQZEVXbJPcfkRz0wJ0",
            "US" to "37i9dQZEVXbLRQDuF5jeBp",
            "GB" to "37i9dQZEVXbLnolsZ8PSNw",
            "CA" to "37i9dQZEVXbKj23U1GF4IR",
            "NZ" to "37i9dQZEVXbM8SIrkERIYl",
            "DE" to "37i9dQZEVXbJiZcmkrIHGU",
            "FR" to "37i9dQZEVXbIPWwFssbupI",
            "IE" to "37i9dQZEVXbKM896FDX8L1",
        )

        val POPULAR = listOf(
            "37i9dQZF1DX0XUsuxWHRQd" to "RapCaviar",
            "37i9dQZF1DX4WYpdgoIcn6" to "Chill Hits",
            "37i9dQZF1DX76Wlfdnj7AP" to "Beast Mode",
            "37i9dQZF1DWWMOmoXKqHTD" to "Songs to Sing in the Car",
            "37i9dQZF1DX5Ejj0EkURtP" to "All Out 2010s",
            "37i9dQZF1DX4o1oenSJRJd" to "All Out 2000s",
            "37i9dQZF1DX4UtSsGT1Sbe" to "All Out 80s",
            "37i9dQZF1DWXRqgorJj26U" to "Rock Classics",
            "37i9dQZF1DX4dyzvuaRJ0n" to "mint",
            "37i9dQZF1DX10zKzsJ2jva" to "Viva Latino",
            "37i9dQZF1DX4sWSpwq3LiO" to "Peaceful Piano",
        )
    }
}
