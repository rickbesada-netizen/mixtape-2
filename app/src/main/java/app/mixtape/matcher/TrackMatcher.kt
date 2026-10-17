package app.mixtape.matcher

import app.mixtape.core.model.SearchFilter
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.data.LibraryRepository
import app.mixtape.data.db.MatchDao
import app.mixtape.data.db.MatchEntity
import app.mixtape.source.YouTubeSource
import java.io.IOException
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ScoredCandidate(val track: Track, val score: Double)

/**
 * Maps Spotify metadata to a YouTube upload. Scores on title, artist and duration, and penalises
 * versions the original isn't (live, remix, sped up...). Results are cached; manual picks win.
 */
class TrackMatcher(
    private val youtube: YouTubeSource,
    private val matches: MatchDao,
    private val library: LibraryRepository,
) {
    private val variantWords = listOf(
        "live", "cover", "remix", "sped up", "speed up", "slowed", "reverb", "nightcore", "karaoke",
        "instrumental", "8d", "acoustic", "lyrics video", "edit", "mashup", "bass boosted", "loop",
    )

    suspend fun resolveYoutubeId(spotifyKey: String): String {
        matches.get(spotifyKey)?.let { return it.youtubeId }
        val original = library.getTrack(spotifyKey)
            ?: throw IOException("Track details missing, re-import the playlist")
        val best = candidates(original).firstOrNull()
            ?: throw IOException("No YouTube match found for \"${original.title}\"")
        if (best.score < 0.35) throw IOException("No confident YouTube match for \"${original.title}\"")
        library.saveTracks(listOf(best.track))
        matches.upsert(MatchEntity(spotifyKey, best.track.sourceId, best.score, manual = false))
        return best.track.sourceId
    }

    suspend fun currentMatch(spotifyKey: String): MatchEntity? = matches.get(spotifyKey)

    suspend fun setManualMatch(spotifyKey: String, youtube: Track) {
        library.saveTracks(listOf(youtube))
        matches.upsert(MatchEntity(spotifyKey, youtube.sourceId, 1.0, manual = true))
    }

    suspend fun clearAutomaticMatches() = matches.clearAutomatic()

    /** Ranked YouTube candidates for a Spotify track, best first. */
    suspend fun candidates(original: Track): List<ScoredCandidate> {
        val primaryArtist = original.artist.split(",").first().trim()
        val songs = runCatching {
            youtube.search("$primaryArtist ${original.title}", SearchFilter.YT_MUSIC_SONGS)
        }.getOrDefault(emptyList())
        var scored = songs.take(10).map { ScoredCandidate(it, score(original, it)) }

        if ((scored.maxOfOrNull { it.score } ?: 0.0) < 0.6) {
            val videos = runCatching {
                youtube.search("$primaryArtist - ${original.title} audio", SearchFilter.YT_VIDEOS)
            }.getOrDefault(emptyList())
            scored = scored + videos.take(8).map { ScoredCandidate(it, score(original, it) - 0.05) }
        }
        return scored.distinctBy { it.track.sourceId }.sortedByDescending { it.score }
    }

    fun score(original: Track, candidate: Track): Double {
        require(candidate.source == SourceType.YOUTUBE)
        val origTitle = tokens(cleanTitle(original.title))
        val candTitle = tokens(cleanTitle(candidate.title))
        val titleScore = if (origTitle.isEmpty() || candTitle.isEmpty()) 0.0 else {
            val inter = origTitle.intersect(candTitle).size.toDouble()
            val containment = inter / origTitle.size
            val dice = 2 * inter / (origTitle.size + candTitle.size)
            0.7 * containment + 0.3 * dice
        }

        val artists = original.artist.split(",").map { normalize(it) }.filter { it.isNotBlank() }
        val candArtistText = normalize(candidate.artist + " " + candidate.title)
        val artistScore = when {
            artists.isEmpty() -> 0.5
            normalize(candidate.artist).contains(artists.first()) -> 1.0
            artists.any { candArtistText.contains(it) } -> 0.85
            else -> {
                val a = tokens(artists.first())
                if (a.isEmpty()) 0.0 else a.intersect(tokens(candArtistText)).size.toDouble() / a.size * 0.6
            }
        }

        val durationScore = if (original.durationSec <= 0 || candidate.durationSec <= 0) 0.5 else {
            when (abs(original.durationSec - candidate.durationSec)) {
                in 0L..3L -> 1.0
                in 4L..8L -> 0.8
                in 9L..20L -> 0.4
                in 21L..45L -> 0.1
                else -> 0.0
            }
        }

        val origLower = original.title.lowercase()
        val candLower = candidate.title.lowercase()
        var penalty = 0.0
        for (w in variantWords) {
            if (containsWord(candLower, w) && !containsWord(origLower, w)) penalty += 0.35
        }
        penalty = min(penalty, 0.7)

        val raw = 0.45 * titleScore + 0.30 * artistScore + 0.25 * durationScore - penalty
        return max(0.0, min(1.0, raw))
    }

    private fun containsWord(text: String, word: String) =
        Regex("(^|[^a-z0-9])" + Regex.escape(word) + "($|[^a-z0-9])").containsMatchIn(text)

    /** Drops featuring credits, "Remastered 2011", "(Official Video)" etc. before comparing. */
    private fun cleanTitle(s: String): String = s
        .replace(Regex("(?i)\\s*[(\\[][^)\\]]*(official|video|audio|lyric|visualizer|remaster|feat|ft\\.|hd|4k|mv)[^)\\]]*[)\\]]"), " ")
        .replace(Regex("(?i)\\s+-\\s+.*remaster(ed)?.*$"), " ")
        .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*$"), " ")

    private fun normalize(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return n.replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    private fun tokens(s: String): Set<String> =
        normalize(s).split(" ").filter { it.isNotBlank() && it !in STOP_WORDS }.toSet()

    companion object {
        private val STOP_WORDS = setOf("the", "a", "an", "and", "of", "x", "vs", "topic")
    }
}
