package app.mixtape.source

import app.mixtape.core.model.RemoteCollection
import app.mixtape.core.model.SearchFilter
import app.mixtape.core.net.YouTubeLimits
import app.mixtape.spotify.SpotifyApi
import app.mixtape.spotify.SpotifyAuth
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/** A genre or mood to browse playlists by. [deezerGenre] = Deezer's chart for that genre, if it has one. */
data class BrowseCategory(
    val id: String,
    val title: String,
    val query: String,
    val color: Long,
    val deezerGenre: Long? = null,
)

/** Playlists grouped by where they're from. */
data class BrowseGroup(val title: String, val playlists: List<RemoteCollection>)

/**
 * Hundreds of playlists from every platform Mixtape can read: Deezer (charts and playlists, no
 * account needed), YouTube Music, Spotify (when connected) and SoundCloud. Every category shows the
 * best from each, so one category alone is often 80-100 playlists.
 */
class BrowseRepository(
    private val deezer: DeezerApi,
    private val spotify: SpotifyApi,
    private val spotifyAuth: SpotifyAuth,
) {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<BrowseGroup>>>()

    val categories: List<BrowseCategory> = listOf(
        BrowseCategory("top", "Top hits", "top hits 2026", 0xFFE13300, deezerGenre = 0),
        BrowseCategory("hiphop", "Hip-Hop", "hip hop", 0xFFBA5D07, deezerGenre = 116),
        BrowseCategory("rap", "Rap", "rap hits", 0xFF8D67AB),
        BrowseCategory("drill", "Drill", "drill", 0xFF477D95),
        BrowseCategory("pop", "Pop", "pop hits", 0xFF148A08, deezerGenre = 132),
        BrowseCategory("rnb", "R&B", "r&b", 0xFFDC148C, deezerGenre = 165),
        BrowseCategory("dance", "Dance & EDM", "edm dance", 0xFF0D73EC, deezerGenre = 113),
        BrowseCategory("electronic", "Electronic", "electronic", 0xFF1E3264, deezerGenre = 106),
        BrowseCategory("house", "House", "house music", 0xFF503750),
        BrowseCategory("afro", "Afrobeats", "afrobeats", 0xFFE8115B, deezerGenre = 2),
        BrowseCategory("amapiano", "Amapiano", "amapiano", 0xFF27856A),
        BrowseCategory("latin", "Latin", "reggaeton latin", 0xFFE1118C, deezerGenre = 197),
        BrowseCategory("kpop", "K-Pop", "k-pop", 0xFFF037A5),
        BrowseCategory("rock", "Rock", "rock classics", 0xFF8C1932, deezerGenre = 152),
        BrowseCategory("alt", "Alternative", "alternative", 0xFF608108, deezerGenre = 85),
        BrowseCategory("indie", "Indie", "indie", 0xFF7358FF),
        BrowseCategory("metal", "Metal", "metal", 0xFF777777, deezerGenre = 464),
        BrowseCategory("country", "Country", "country hits", 0xFFD84000, deezerGenre = 84),
        BrowseCategory("soul", "Soul & Funk", "soul funk", 0xFFA56752, deezerGenre = 169),
        BrowseCategory("jazz", "Jazz", "jazz", 0xFF1E3264, deezerGenre = 129),
        BrowseCategory("classical", "Classical", "classical", 0xFF7D4B32, deezerGenre = 98),
        BrowseCategory("reggae", "Reggae", "reggae", 0xFF148A08, deezerGenre = 144),
        BrowseCategory("lofi", "Lo-fi", "lofi beats", 0xFF2D46B9),
        BrowseCategory("chill", "Chill", "chill vibes", 0xFF477D95),
        BrowseCategory("workout", "Workout", "workout gym", 0xFF777777),
        BrowseCategory("focus", "Focus", "focus study", 0xFF503750),
        BrowseCategory("sleep", "Sleep", "sleep", 0xFF1E3264),
        BrowseCategory("party", "Party", "party hits", 0xFFAF2896),
        BrowseCategory("roadtrip", "Road trip", "road trip", 0xFFE91429),
        BrowseCategory("love", "Love songs", "love songs", 0xFFDC148C),
        BrowseCategory("sad", "Sad songs", "sad songs", 0xFF477D95),
        BrowseCategory("happy", "Feel good", "feel good", 0xFFE8115B),
        BrowseCategory("summer", "Summer", "summer hits", 0xFFF59B23),
        BrowseCategory("2010s", "2010s", "2010s hits", 0xFF8D67AB),
        BrowseCategory("2000s", "2000s", "2000s hits", 0xFFBA5D07),
        BrowseCategory("90s", "90s", "90s hits", 0xFF0D73EC),
        BrowseCategory("80s", "80s", "80s hits", 0xFFE13300),
        BrowseCategory("gaming", "Gaming", "gaming music", 0xFF27856A),
        BrowseCategory("worship", "Worship & Gospel", "worship gospel", 0xFF608108),
        BrowseCategory("arabic", "Arabic", "arabic hits", 0xFFA56752),
        BrowseCategory("bollywood", "Bollywood", "bollywood hits", 0xFFE1118C),
        BrowseCategory("aussie", "Australian", "australian hits", 0xFF148A08),
    )

    fun cached(key: String): List<BrowseGroup>? =
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.second

    /** Playlists for a category (or, with a null category, for anything you type). */
    suspend fun load(category: BrowseCategory?, query: String = category?.query.orEmpty()): List<BrowseGroup> {
        val key = category?.id ?: "q:$query"
        cached(key)?.let { return it }
        val groups = coroutineScope {
            val dz = async {
                safe {
                    val chart = category?.deezerGenre?.let { g ->
                        if (g == 0L) deezer.topPlaylists(100) else deezer.genrePlaylists(g, 25)
                    }.orEmpty()
                    (chart + deezer.searchPlaylists(query, 40)).distinctBy { it.url }
                }
            }
            val yt = async { if (YouTubeLimits.isPaused()) emptyList() else safe { NewPipeCollections.youtubeMusicPlaylists(query) } }
            val sp = async { if (spotifyAuth.loggedIn.value) safe { spotify.searchPlaylists(query, 3) } else emptyList() }
            val sc = async { safe { NewPipeCollections.search(query, SearchFilter.SOUNDCLOUD_PLAYLISTS) } }
            listOf(
                BrowseGroup("Deezer", dz.await()),
                BrowseGroup("YouTube Music", yt.await()),
                BrowseGroup("Spotify", sp.await()),
                BrowseGroup("SoundCloud", sc.await()),
            ).filter { it.playlists.isNotEmpty() }
        }
        if (groups.isNotEmpty()) cache[key] = System.currentTimeMillis() to groups
        return groups
    }

    /** Deezer's 100 most popular playlists right now, for Home. */
    suspend fun topPlaylists(): List<RemoteCollection> {
        cached("deezer-top")?.firstOrNull()?.let { return it.playlists }
        val list = safe { deezer.topPlaylists(100) }
        if (list.isNotEmpty()) cache["deezer-top"] = System.currentTimeMillis() to listOf(BrowseGroup("Deezer", list))
        return list
    }

    private suspend fun safe(block: suspend () -> List<RemoteCollection>): List<RemoteCollection> =
        runCatching { block() }.getOrElse {
            if (YouTubeLimits.isBotBlock(it)) YouTubeLimits.markBlocked()
            emptyList()
        }

    private companion object {
        const val CACHE_MS = 60L * 60 * 1000
    }
}
