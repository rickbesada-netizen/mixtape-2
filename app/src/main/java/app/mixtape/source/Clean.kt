package app.mixtape.source

import app.mixtape.core.model.SearchFilter
import app.mixtape.core.model.Track
import app.mixtape.core.model.display
import app.mixtape.core.net.YouTubeLimits
import app.mixtape.data.LibraryRepository
import app.mixtape.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

enum class CleanStatus(val label: String) {
    CLEAN("Already clean"),
    SWAPPED("Swapped for clean version"),
    REMOVED("Left out (no clean version found)"),
    KEPT("Kept (explicit, no clean version found)"),
    UNCHECKED("Kept (lyrics not found, couldn't check)"),
}

data class CleanItem(
    val original: Track,
    val result: Track?,
    val status: CleanStatus,
    /** Which words made it count as explicit (for the summary). */
    val reason: String? = null,
)

data class CleanJob(
    val name: String,
    val total: Int,
    val done: Int = 0,
    val finished: Boolean = false,
    val items: List<CleanItem> = emptyList(),
    val playlistId: Long? = null,
    val error: String? = null,
) {
    fun count(s: CleanStatus) = items.count { it.status == s }
}

/**
 * Makes a clean copy of a playlist. For every song it:
 *  1. checks the title and the lyrics (from LRCLIB, a free lyrics database) for swearing, slurs and
 *     any words you've added in Settings;
 *  2. if the song is explicit, looks on YouTube Music / YouTube for a version labelled clean,
 *     radio edit, edited or censored, by the same artist and about the same length;
 *  3. saves the result as a new playlist "<name> (Clean)". Songs with no clean version are left out
 *     (or kept, if you choose).
 */
class CleanConverter(
    private val http: OkHttpClient,
    private val youtube: YouTubeSource,
    private val library: LibraryRepository,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val youtubeLock = Mutex()

    private val _state = MutableStateFlow<CleanJob?>(null)
    val state: StateFlow<CleanJob?> = _state.asStateFlow()

    fun start(name: String, tracks: List<Track>, keepIfNoClean: Boolean) {
        if (job?.isActive == true || tracks.isEmpty()) return
        val list = tracks.distinctBy { it.key }
        _state.value = CleanJob(name = name, total = list.size)
        val filter = buildFilter(settings.cleanWords.value)
        job = scope.launch {
            runCatching {
                val gate = Semaphore(3)
                val items = coroutineScope {
                    list.map { t ->
                        async {
                            gate.withPermit {
                                val item = runCatching { check(t, filter, keepIfNoClean) }
                                    .getOrElse { CleanItem(t, t, CleanStatus.UNCHECKED) }
                                _state.update { it?.copy(done = it.done + 1) }
                                item
                            }
                        }
                    }.awaitAll()
                }
                val out = items.mapNotNull { it.result }.distinctBy { it.key }
                library.saveTracks(out)
                val id = library.createPlaylist("$name (Clean)")
                library.addAllToPlaylist(id, out)
                _state.update { it?.copy(finished = true, items = items, playlistId = id, done = list.size) }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it?.copy(finished = true, error = e.message ?: "Something went wrong") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = null
    }

    fun dismiss() {
        if (job?.isActive != true) _state.value = null
    }

    // ---- Checking one song ----

    private suspend fun check(track: Track, filter: Regex, keepIfNoClean: Boolean): CleanItem {
        val shown = track.display()
        val rawTitle = track.title.lowercase()

        if (CLEAN_LABEL.containsMatchIn(rawTitle) && !EXPLICIT_LABEL.containsMatchIn(rawTitle)) {
            return CleanItem(track, track, CleanStatus.CLEAN)
        }

        val titleHit = filter.find(shown.title)?.value
            ?: EXPLICIT_LABEL.find(rawTitle)?.let { "marked explicit" }
        var reason = titleHit
        var lyricsFound = false
        if (reason == null) {
            val lyrics = fetchLyrics(shown.title, shown.artist, track.durationSec)
            if (lyrics != null) {
                lyricsFound = true
                reason = filter.findAll(lyrics).map { it.value.lowercase() }.distinct().take(4)
                    .toList().takeIf { it.isNotEmpty() }?.joinToString(", ")
            }
        }

        if (reason == null) {
            return CleanItem(track, track, if (lyricsFound) CleanStatus.CLEAN else CleanStatus.UNCHECKED)
        }

        val clean = findCleanVersion(track)
        return when {
            clean != null -> CleanItem(track, clean, CleanStatus.SWAPPED, reason)
            keepIfNoClean -> CleanItem(track, track, CleanStatus.KEPT, reason)
            else -> CleanItem(track, null, CleanStatus.REMOVED, reason)
        }
    }

    /** Lyrics from LRCLIB (lrclib.net). Returns "" for instrumentals, null if not found. */
    private suspend fun fetchLyrics(title: String, artist: String, durationSec: Long): String? = withContext(Dispatchers.IO) {
        val primaryArtist = artist.split(",", "&", " feat", " ft.", " x ").first().trim()
        val attempts = listOf(
            "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", primaryArtist)
                .build(),
            "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$primaryArtist $title")
                .build(),
        )
        for (url in attempts) {
            val arr = runCatching {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mixtape (https://github.com/rickbesada-netizen/mixtape-2)")
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else JSONArray(r.body?.string().orEmpty())
                }
            }.getOrNull() ?: continue
            val best = pickLyrics(arr, title, durationSec) ?: continue
            if (best.optBoolean("instrumental")) return@withContext ""
            val plain = best.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                ?: best.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                    ?.replace(Regex("\\[\\d+:\\d+(\\.\\d+)?]"), "")
            if (plain != null) return@withContext plain
        }
        null
    }

    private fun pickLyrics(arr: JSONArray, title: String, durationSec: Long): JSONObject? {
        val want = tokens(title)
        var best: JSONObject? = null
        var bestScore = -1.0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val got = tokens(o.optString("trackName"))
            if (want.isEmpty() || got.isEmpty()) continue
            val overlap = want.intersect(got).size.toDouble() / want.size
            if (overlap < 0.6) continue
            val d = o.optDouble("duration", 0.0)
            val durOk = durationSec <= 0 || d <= 0 || abs(d - durationSec) <= 8
            val score = overlap + (if (durOk) 0.5 else 0.0)
            if (score > bestScore) { bestScore = score; best = o }
        }
        return best
    }

    /** A version of the same song labelled clean / radio edit / edited / censored. */
    private suspend fun findCleanVersion(original: Track): Track? = youtubeLock.withLock {
        if (YouTubeLimits.isPaused()) return@withLock null
        val shown = original.display()
        val base = "${shown.artist.split(",").first().trim()} ${shown.title}".trim()
        val queries = listOf(
            "$base clean" to SearchFilter.YT_MUSIC_SONGS,
            "$base clean version" to SearchFilter.YT_VIDEOS,
            "$base radio edit" to SearchFilter.YT_VIDEOS,
        )
        for ((q, filter) in queries) {
            val results = runCatching { youtube.search(q, filter) }.getOrElse {
                if (YouTubeLimits.isBotBlock(it)) { YouTubeLimits.markBlocked(); return@withLock null }
                emptyList()
            }
            delay(700) // gentle on YouTube
            val pick = results.take(12)
                .filter { isCleanCandidate(original, it) }
                .minByOrNull { if (original.durationSec > 0 && it.durationSec > 0) abs(original.durationSec - it.durationSec) else 30 }
            if (pick != null) return@withLock pick
        }
        null
    }

    private fun isCleanCandidate(original: Track, c: Track): Boolean {
        val title = c.title.lowercase()
        if (!CLEAN_LABEL.containsMatchIn(title) || EXPLICIT_LABEL.containsMatchIn(title)) return false
        val origText = (original.title + " " + original.artist).lowercase()
        if (VARIANTS.any { containsWord(title, it) && !containsWord(origText, it) }) return false

        val want = tokens(original.display().title)
        val got = tokens(c.display().title.replace(CLEAN_LABEL, " ")) + tokens(c.title.replace(CLEAN_LABEL, " "))
        if (want.isEmpty() || want.intersect(got).size.toDouble() / want.size < 0.75) return false

        val artist = tokens(original.display().artist.split(",").first())
        val candArtist = tokens(c.artist + " " + c.title)
        if (artist.isNotEmpty() && artist.intersect(candArtist).size.toDouble() / artist.size < 0.5) return false

        if (original.durationSec > 0 && c.durationSec > 0 && abs(original.durationSec - c.durationSec) > 45) return false
        return true
    }

    private fun containsWord(text: String, word: String) =
        Regex("(^|[^a-z0-9])" + Regex.escape(word) + "($|[^a-z0-9])").containsMatchIn(text)

    private fun tokens(s: String): Set<String> =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(" ")
            .filter { it.isNotBlank() && it !in STOP }
            .toSet()

    companion object {
        private val STOP = setOf("the", "a", "an", "and", "of", "feat", "ft", "official", "video", "audio", "lyrics", "lyric")

        val CLEAN_LABEL = Regex("(?i)\\b(clean|radio edit|radio version|edited|censored|kidz bop)\\b")
        val EXPLICIT_LABEL = Regex("(?i)\\b(explicit|dirty|uncensored|uncut)\\b")

        private val VARIANTS = listOf(
            "live", "cover", "remix", "sped up", "slowed", "reverb", "nightcore", "karaoke", "instrumental", "8d",
            "acoustic", "mashup", "bass boosted",
        )

        /** Common swear words, sexual terms and slurs, with their usual endings. */
        private val BUILT_IN = listOf(
            "f+u+c+k\\w*", "\\w*fucker\\w*", "motherf\\w*", "fck\\w*", "fuk\\w*", "fuq\\w*",
            "sh+i+t+\\w*", "bullshit\\w*", "shitt\\w*",
            "bitch\\w*", "biatch\\w*",
            "cunt\\w*", "dick(s|head\\w*)?", "cock(s|sucker\\w*)?", "puss(y|ies)", "asshole\\w*", "ass(es)?", "dumbass\\w*", "jackass\\w*",
            "bastard\\w*", "whore\\w*", "slut\\w*", "hoes?", "thots?", "twat\\w*", "wank\\w*", "tits?", "titties",
            "n[i1]gg(a|as|az|er|ers|uh)", "f[a@]g(s|got\\w*)?", "retard\\w*",
            "porn\\w*", "cum(ming|shot)?", "blowjob\\w*", "dildo\\w*", "horny",
            // Lyrics sites often star words out; the song itself still says them.
            "[fsbcn]\\*+[a-z]*",
        )

        /** Built-in list plus your own words (comma or new-line separated). */
        fun buildFilter(extra: String): Regex {
            val own = extra.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                .map { Regex.escape(it.lowercase()) + "\\w*" }
            val all = BUILT_IN + own
            return Regex("(?i)(?<![\\p{L}\\p{N}])(" + all.joinToString("|") + ")(?![\\p{L}\\p{N}])")
        }
    }
}
