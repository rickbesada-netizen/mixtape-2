package app.mixtape.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

/** One line of time-synced lyrics. */
data class LyricLine(val startMs: Long, val text: String)

data class Lyrics(
    val plain: String,
    /** Empty if the song only has plain lyrics. */
    val synced: List<LyricLine>,
    val instrumental: Boolean,
)

/** Lyrics from LRCLIB (lrclib.net), a free, open lyrics database with time-synced lyrics. */
class LyricsClient(private val http: OkHttpClient) {

    suspend fun find(title: String, artist: String, durationSec: Long): Lyrics? = withContext(Dispatchers.IO) {
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
            val best = pick(arr, title, durationSec) ?: continue
            if (best.optBoolean("instrumental")) return@withContext Lyrics("", emptyList(), true)
            val syncedText = best.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            val synced = syncedText?.let { parseLrc(it) } ?: emptyList()
            val plain = best.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                ?: synced.joinToString("\n") { it.text }
            if (plain.isNotBlank()) return@withContext Lyrics(plain, synced, false)
        }
        null
    }

    private fun pick(arr: JSONArray, title: String, durationSec: Long): JSONObject? {
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
            val synced = o.optString("syncedLyrics").let { it.isNotBlank() && it != "null" }
            val score = overlap + (if (durOk) 0.5 else 0.0) + (if (synced) 0.1 else 0.0)
            if (score > bestScore) { bestScore = score; best = o }
        }
        return best
    }

    companion object {
        private val LRC_TIME = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")

        fun parseLrc(text: String): List<LyricLine> = text.lines().flatMap { line ->
            val times = LRC_TIME.findAll(line).toList()
            if (times.isEmpty()) return@flatMap emptyList()
            val words = line.replace(LRC_TIME, "").trim()
            times.map { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                LyricLine(min * 60_000 + sec * 1000 + ms, words)
            }
        }.sortedBy { it.startMs }

        private val STOP = setOf("the", "a", "an", "and", "of", "feat", "ft", "official", "video", "audio", "lyrics", "lyric")

        fun tokens(s: String): Set<String> =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .split(" ")
                .filter { it.isNotBlank() && it !in STOP }
                .toSet()
    }
}
