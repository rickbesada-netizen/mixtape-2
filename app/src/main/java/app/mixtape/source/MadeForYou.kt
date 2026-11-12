package app.mixtape.source

import android.content.Context
import app.mixtape.core.model.CollectionDetail
import app.mixtape.core.model.RemoteCollection
import app.mixtape.core.model.SearchFilter
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.core.model.display
import app.mixtape.core.net.YouTubeLimits
import app.mixtape.data.LibraryRepository
import app.mixtape.data.RadioSource
import app.mixtape.data.db.HistoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.random.Random

/** A playlist Mixtape made for you, like Spotify's Daily Mix or Discover Weekly. */
data class GeneratedMix(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val tracks: List<Track>,
    val group: Group,
) {
    enum class Group { MADE_FOR_YOU, ARTISTS }

    val url: String get() = MadeForYou.urlFor(id)

    fun toCollection() = RemoteCollection(url, title, subtitle, artworkUrl, tracks.size.toLong())
    fun toDetail() = CollectionDetail(toCollection(), tracks)
}

/**
 * Builds Spotify-style personal playlists from what you play and like:
 *
 *  - Daily Mix 1-6: one per artist you play most, each mixing your songs by them with similar songs
 *    (from YouTube Music radio). New every day.
 *  - Discover Weekly: 30 songs like the ones you love that you've never played. New every Monday.
 *  - On Repeat: what you've played most in the last 30 days.
 *  - Repeat Rewind: songs you played a lot before that, and haven't lately.
 *  - Best of <artist>: the top songs of your top artists.
 *
 * Kept in a file so they stay the same all day and don't need rebuilding each time the app opens.
 */
class MadeForYou(
    context: Context,
    private val library: LibraryRepository,
    private val history: HistoryDao,
    private val radio: RadioEngine,
    private val youtube: YouTubeSource,
) {
    private val file = File(context.filesDir, "made_for_you.json")
    private val lock = Mutex()
    @Volatile private var mixes: List<GeneratedMix> = emptyList()
    @Volatile private var builtDay: String = ""
    @Volatile private var builtWeek: String = ""

    init {
        runCatching { readFile() }
    }

    fun peek(): List<GeneratedMix> = mixes

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var building: kotlinx.coroutines.Deferred<List<GeneratedMix>>? = null

    /** Starts (or joins) today's build without tying it to whoever asked, so leaving Home doesn't stop it. */
    fun prefetch(force: Boolean = false): kotlinx.coroutines.Deferred<List<GeneratedMix>> {
        building?.takeIf { it.isActive }?.let { return it }
        return scope.async { load(force) }.also { building = it }
    }

    /** Today's mixes; builds them if they're from an earlier day (or [force]). */
    suspend fun load(force: Boolean = false): List<GeneratedMix> = lock.withLock {
        val today = LocalDate.now().toString()
        if (!force && builtDay == today && mixes.isNotEmpty()) return@withLock mixes
        val built = runCatching { withContext(Dispatchers.IO) { build(force) } }.getOrNull()
        if (!built.isNullOrEmpty()) {
            mixes = built
            builtDay = today
            builtWeek = weekId()
            runCatching { writeFile() }
        }
        mixes
    }

    /** For opening a mix (and for a saved copy of one, which refreshes from here each day). */
    suspend fun detail(id: String): CollectionDetail? {
        mixes.firstOrNull { it.id == id }?.let { return it.toDetail() }
        return load().firstOrNull { it.id == id }?.toDetail()
    }

    // ---------------------------------------------------------------- building

    private suspend fun build(force: Boolean): List<GeneratedMix> = coroutineScope {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val recent = history.playCounts(now - 30 * day, now + day, 300).map { it.track.toTrack() to it.plays }
        val older = history.playCounts(now - 180 * day, now - 30 * day, 300).map { it.track.toTrack() to it.plays }
        val liked = library.likedTracks().first()
        if (recent.isEmpty() && liked.isEmpty() && older.isEmpty()) return@coroutineScope emptyList()

        val played = history.allPlayedKeys().toHashSet()
        val rnd = Random(LocalDate.now().toEpochDay())

        // How much you like each artist: plays this month count most, liked songs a lot, older plays a little.
        val weight = HashMap<String, Double>()
        val songsBy = HashMap<String, MutableMap<String, Pair<Track, Double>>>()
        val names = HashMap<String, String>()
        fun add(t: Track, w: Double) {
            val name = primaryArtist(t)
            val k = norm(name)
            if (k.isBlank()) return
            names.putIfAbsent(k, name)
            weight[k] = (weight[k] ?: 0.0) + w
            val m = songsBy.getOrPut(k) { LinkedHashMap() }
            val prev = m[t.key]?.second ?: 0.0
            m[t.key] = t to (prev + w)
        }
        recent.forEach { (t, n) -> add(t, n.toDouble()) }
        liked.forEach { add(it, 2.0) }
        older.forEach { (t, n) -> add(t, n * 0.3) }

        val topArtists = weight.entries.sortedByDescending { it.value }.filter { it.value >= 2.0 }.map { it.key }
        val gate = Semaphore(2)

        // Daily Mixes, one per top artist.
        val daily = topArtists.take(6).mapIndexed { i, artist ->
            async {
                gate.withPermit {
                    val yours = songsBy[artist].orEmpty().values.sortedByDescending { it.second }.map { it.first }
                    if (yours.isEmpty()) return@withPermit null
                    val seeds = yours.take(3).shuffled(rnd).take(2)
                    val similar = seeds.flatMap { s ->
                        safe { radio.similarTo(s, yours, limit = 25, sourceOverride = RadioSource.YOUTUBE_MUSIC) }
                    }.distinctBy { it.key }
                    if (similar.isEmpty() && yours.size < 5) return@withPermit null
                    val tracks = blend(yours.shuffled(rnd).take(10), similar.shuffled(rnd), 1, 3).take(40)
                    val featured = (listOf(names[artist] ?: "") + tracks.map { primaryArtist(it) })
                        .filter { it.isNotBlank() }.distinctBy { norm(it) }.take(3)
                    GeneratedMix(
                        id = "daily-${i + 1}",
                        title = "Daily Mix ${i + 1}",
                        subtitle = featured.joinToString(", ") + " and more",
                        artworkUrl = yours.first().artworkUrl ?: tracks.firstOrNull()?.artworkUrl,
                        tracks = tracks,
                        group = GeneratedMix.Group.MADE_FOR_YOU,
                    )
                }
            }
        }

        // Discover Weekly: the same songs all week; only rebuilt on a new week (or a manual refresh).
        val thisWeek = weekId()
        val keptDiscover = mixes.firstOrNull { it.id == "discover-weekly" }?.takeIf { builtWeek == thisWeek && !force }
        val discover = async {
            keptDiscover ?: gate.withPermit {
                val loved = (recent.map { it.first } + liked).distinctBy { it.key }
                if (loved.isEmpty()) return@withPermit null
                val weekRnd = Random(thisWeek.hashCode())
                val seeds = loved.take(30).shuffled(weekRnd).take(5)
                val known = (recent.map { it.first } + liked + older.map { it.first })
                val found = seeds.map { s ->
                    safe { radio.similarTo(s, known, limit = 15, sourceOverride = RadioSource.YOUTUBE_MUSIC) }
                        .filter { it.key !in played }
                }
                val tracks = interleave(found).distinctBy { it.key }.take(30)
                if (tracks.size < 8) return@withPermit null
                GeneratedMix(
                    "discover-weekly", "Discover Weekly", "New songs picked for you. Changes every Monday",
                    tracks.first().artworkUrl, tracks, GeneratedMix.Group.MADE_FOR_YOU,
                )
            }
        }

        // Best of your top artists.
        val bestOf = topArtists.take(4).map { artist ->
            async {
                gate.withPermit {
                    val name = names[artist] ?: return@withPermit null
                    val hits = safe { youtube.search(name, SearchFilter.YT_MUSIC_SONGS) }
                        .filter { norm(primaryArtist(it)).contains(artist) || artist.contains(norm(primaryArtist(it))) }
                    val yours = songsBy[artist].orEmpty().values.sortedByDescending { it.second }.map { it.first }
                    val tracks = (hits + yours).distinctBy { songKey(it) }.take(25)
                    if (tracks.size < 5) return@withPermit null
                    GeneratedMix(
                        "best-of-$artist".replace(Regex("[^a-z0-9-]"), "-"),
                        "Best of $name", "Their biggest songs and your favourites",
                        tracks.first().artworkUrl, tracks, GeneratedMix.Group.ARTISTS,
                    )
                }
            }
        }

        val onRepeat = recent.filter { it.second >= 2 }.map { it.first }.take(30).takeIf { it.size >= 5 }?.let {
            GeneratedMix("on-repeat", "On Repeat", "The songs you can't stop playing", it.first().artworkUrl, it, GeneratedMix.Group.MADE_FOR_YOU)
        }
        val recentKeys = recent.map { it.first.key }.toHashSet()
        val rewind = older.filter { it.second >= 3 && it.first.key !in recentKeys }.map { it.first }.take(30)
            .takeIf { it.size >= 5 }?.let {
                GeneratedMix("repeat-rewind", "Repeat Rewind", "Songs you used to play on repeat", it.first().artworkUrl, it, GeneratedMix.Group.MADE_FOR_YOU)
            }

        val dailyMixes = daily.awaitAll().filterNotNull()
        buildList {
            addAll(dailyMixes)
            discover.await()?.let { add(it) }
            onRepeat?.let { add(it) }
            rewind?.let { add(it) }
            addAll(bestOf.awaitAll().filterNotNull())
        }
    }

    private suspend fun safe(block: suspend () -> List<Track>): List<Track> =
        runCatching { block() }.getOrElse {
            if (YouTubeLimits.isBotBlock(it)) YouTubeLimits.markBlocked()
            emptyList()
        }

    /** [a] songs from the first list for every [b] from the second, like Spotify's mixes. */
    private fun blend(first: List<Track>, second: List<Track>, a: Int, b: Int): List<Track> {
        val out = mutableListOf<Track>()
        val seen = HashSet<String>()
        var i = 0
        var j = 0
        while (i < first.size || j < second.size) {
            repeat(a) { if (i < first.size) first[i++].let { if (seen.add(songKey(it))) out += it } }
            repeat(b) { if (j < second.size) second[j++].let { if (seen.add(songKey(it))) out += it } }
        }
        return out
    }

    private fun interleave(lists: List<List<Track>>): List<Track> {
        val out = mutableListOf<Track>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { out += it }
        return out
    }

    private fun primaryArtist(t: Track) =
        t.display().artist.split(",", "&", " feat", " ft.", " x ", " X ").first().trim().removeSuffix(" - Topic")

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun songKey(t: Track) = norm(t.display().title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "")) + "|" + norm(primaryArtist(t))

    private fun weekId(): String {
        val d = LocalDate.now()
        return "${d.get(IsoFields.WEEK_BASED_YEAR)}-W${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
    }

    // ---------------------------------------------------------------- cache file

    private fun writeFile() {
        val arr = JSONArray()
        for (m in mixes) {
            val tracks = JSONArray()
            m.tracks.forEach { tracks.put(trackJson(it)) }
            arr.put(
                JSONObject().put("id", m.id).put("title", m.title).put("subtitle", m.subtitle)
                    .put("art", m.artworkUrl ?: "").put("group", m.group.name).put("tracks", tracks)
            )
        }
        file.writeText(JSONObject().put("day", builtDay).put("week", builtWeek).put("mixes", arr).toString())
    }

    private fun readFile() {
        if (!file.exists()) return
        val o = JSONObject(file.readText())
        val arr = o.optJSONArray("mixes") ?: return
        mixes = (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val ta = m.optJSONArray("tracks") ?: JSONArray()
            GeneratedMix(
                id = m.optString("id"),
                title = m.optString("title"),
                subtitle = m.optString("subtitle"),
                artworkUrl = m.optString("art").ifEmpty { null },
                tracks = (0 until ta.length()).mapNotNull { j -> ta.optJSONObject(j)?.let { trackFrom(it) } },
                group = runCatching { GeneratedMix.Group.valueOf(m.optString("group")) }.getOrDefault(GeneratedMix.Group.MADE_FOR_YOU),
            )
        }
        builtDay = o.optString("day")
        builtWeek = o.optString("week")
    }

    private fun trackJson(t: Track) = JSONObject()
        .put("s", t.source.prefix).put("id", t.sourceId).put("t", t.title).put("a", t.artist)
        .put("al", t.album ?: "").put("d", t.durationSec).put("art", t.artworkUrl ?: "")

    private fun trackFrom(o: JSONObject): Track? {
        val source = SourceType.entries.firstOrNull { it.prefix == o.optString("s") } ?: return null
        return Track(
            source = source,
            sourceId = o.optString("id"),
            title = o.optString("t"),
            artist = o.optString("a"),
            album = o.optString("al").ifEmpty { null },
            durationSec = o.optLong("d"),
            artworkUrl = o.optString("art").ifEmpty { null },
        )
    }

    companion object {
        const val SCHEME = "mixtape-mix:"
        fun urlFor(id: String) = SCHEME + id
    }
}
