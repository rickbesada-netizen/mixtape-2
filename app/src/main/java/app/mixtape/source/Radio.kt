package app.mixtape.source

import app.mixtape.core.model.SearchFilter
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.core.model.display
import app.mixtape.core.net.YouTubeLimits
import app.mixtape.data.RadioSource
import app.mixtape.matcher.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.text.Normalizer

/**
 * "Autoplay" / song radio: what plays after the song you picked.
 *
 * YouTube songs (and Spotify songs, through their YouTube match) use YouTube Music's own song radio
 * (the RDAMVM mix, the same list the YouTube Music app plays), falling back to a normal YouTube
 * Mix and then related videos. SoundCloud songs use SoundCloud's related tracks.
 *
 * The raw lists are then tidied: no repeats of songs already queued or just played, no second
 * upload of the same song, no hour-long compilations, reactions, sped-up / slowed edits (unless the
 * song you picked was one), and the same artist is never played twice in a row.
 */
class RadioEngine(
    private val youtube: YouTubeSource,
    private val soundcloud: SoundCloudSource,
    private val matcher: TrackMatcher,
    private val spotify: app.mixtape.spotify.SpotifyApi,
    private val spotifyAuth: app.mixtape.spotify.SpotifyAuth,
    private val settings: app.mixtape.data.Settings,
) {
    /**
     * @param avoid songs already in the queue or played recently (by key and by title)
     */
    suspend fun similarTo(
        seed: Track,
        avoid: List<Track>,
        limit: Int = 25,
        sourceOverride: RadioSource? = null,
    ): List<Track> = withContext(Dispatchers.IO) {
        val source = sourceOverride ?: settings.radioSource.value
        val spotifyOn = spotifyAuth.loggedIn.value
        val raw = when (source) {
            RadioSource.YOUTUBE_MUSIC -> fromYouTube(seed)
            RadioSource.SOUNDCLOUD -> fromSoundCloud(seed).ifEmpty { fromYouTube(seed) }
            RadioSource.SPOTIFY ->
                if (spotifyOn) interleave(fromSpotify(seed), fromYouTube(seed)) else fromYouTube(seed)
            RadioSource.MIX -> interleave(
                fromYouTube(seed),
                fromSoundCloud(seed),
                if (spotifyOn) fromSpotify(seed) else emptyList(),
            )
        }
        curate(seed, raw, avoid, limit)
    }

    private suspend fun fromYouTube(seed: Track): List<Track> = guarded {
        when (seed.source) {
            SourceType.SOUNDCLOUD -> youtubeRadioBySearch(seed)
            else -> videoIdFor(seed)?.let { youtubeRadio(it) } ?: youtubeRadioBySearch(seed)
        }
    }

    private suspend fun fromSoundCloud(seed: Track): List<Track> = guarded {
        val scSeed = if (seed.source == SourceType.SOUNDCLOUD) seed else {
            val shown = seed.display()
            soundcloud.search("${shown.artist} ${shown.title}".trim(), SearchFilter.SOUNDCLOUD).firstOrNull()
        }
        scSeed?.let { soundcloudRelated(it) }.orEmpty()
    }

    private suspend fun fromSpotify(seed: Track): List<Track> = guarded { spotify.similarTo(seed) }

    private suspend fun guarded(block: suspend () -> List<Track>): List<Track> =
        runCatching { block() }.getOrElse {
            if (YouTubeLimits.isBotBlock(it)) YouTubeLimits.markBlocked()
            emptyList()
        }

    /** a1, b1, c1, a2, b2, c2... */
    private fun interleave(vararg lists: List<Track>): List<Track> {
        val out = mutableListOf<Track>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { out += it }
        return out
    }

    private suspend fun videoIdFor(seed: Track): String? = when (seed.source) {
        SourceType.YOUTUBE, SourceType.YOUTUBE_VIDEO -> seed.sourceId
        SourceType.SPOTIFY, SourceType.DEEZER -> runCatching { matcher.resolveYoutubeId(seed.key) }.getOrNull()
        SourceType.SOUNDCLOUD -> null
    }

    private fun youtubeRadio(videoId: String): List<Track> {
        val service = ServiceList.YouTube
        // 1. YouTube Music song radio, 2. YouTube Mix, 3. related videos.
        for (list in listOf("RDAMVM$videoId", "RD$videoId")) {
            val url = "https://www.youtube.com/watch?v=$videoId&list=$list"
            val tracks = runCatching {
                val info = PlaylistInfo.getInfo(service, url)
                val items = info.relatedItems.toMutableList()
                var page: Page? = info.nextPage
                if (items.size < 40 && Page.isValid(page)) {
                    runCatching { PlaylistInfo.getMoreItems(service, url, page) }.getOrNull()?.let { items += it.items }
                }
                items.mapNotNull { NewPipeCollections.trackFromItem(it, service) }
            }.getOrElse {
                if (YouTubeLimits.isBotBlock(it)) throw it
                emptyList()
            }
            if (tracks.size >= 8) return tracks
        }
        val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        return info.relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { NewPipeCollections.trackFromItem(it, service) }
    }

    /** For a song we can't map to a YouTube id directly: find it on YouTube Music, then use its radio. */
    private suspend fun youtubeRadioBySearch(seed: Track): List<Track> {
        val shown = seed.display()
        val hit = youtube.search("${shown.artist} ${shown.title}".trim(), SearchFilter.YT_MUSIC_SONGS).firstOrNull()
            ?: return emptyList()
        return youtubeRadio(hit.sourceId)
    }

    private fun soundcloudRelated(seed: Track): List<Track> {
        val service = ServiceList.SoundCloud
        val info = StreamInfo.getInfo(service, seed.sourceId)
        return info.relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { NewPipeCollections.trackFromItem(it, service) }
    }

    private fun curate(seed: Track, raw: List<Track>, avoid: List<Track>, limit: Int): List<Track> {
        val seedText = (seed.title + " " + seed.artist).lowercase()
        val unwanted = UNWANTED.filter { !containsWord(seedText, it) }
        val avoidKeys = avoid.map { it.key }.toHashSet().apply { add(seed.key) }
        val seenTitles = (avoid + seed).map { songKey(it) }.toHashSet()

        val picked = mutableListOf<Track>()
        for (t in raw) {
            if (t.key in avoidKeys) continue
            if (t.durationSec > 0 && (t.durationSec < 60 || t.durationSec > 600)) continue
            val text = (t.title + " " + t.artist).lowercase()
            if (unwanted.any { containsWord(text, it) }) continue
            val k = songKey(t)
            if (k.isBlank() || !seenTitles.add(k)) continue
            avoidKeys += t.key
            picked += t
        }
        return spreadArtists(picked).take(limit)
    }

    /** Reorders so the same artist never plays twice in a row and no artist takes over the batch. */
    private fun spreadArtists(list: List<Track>): List<Track> {
        val perArtist = HashMap<String, Int>()
        val capped = list.filter {
            val a = artistKey(it)
            val n = perArtist.getOrDefault(a, 0)
            perArtist[a] = n + 1
            n < 3
        }.toMutableList()
        val out = mutableListOf<Track>()
        while (capped.isNotEmpty()) {
            val last = out.lastOrNull()?.let { artistKey(it) }
            val i = capped.indexOfFirst { artistKey(it) != last }.takeIf { it >= 0 } ?: 0
            out += capped.removeAt(i)
        }
        return out
    }

    private fun artistKey(t: Track) = normalize(t.display().artist.split(",", "&", " x ", " feat").first())

    /** "Same song" key: the cleaned song title plus first artist, so re-uploads collapse together. */
    private fun songKey(t: Track): String {
        val d = t.display()
        val title = normalize(d.title.replace(Regex("(?i)\\s*[(\\[].*?[)\\]]"), "").replace(Regex("(?i)\\b(feat|ft)\\.?\\b.*$"), ""))
        return title + "|" + artistKey(t)
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun containsWord(text: String, word: String) =
        Regex("(^|[^a-z0-9])" + Regex.escape(word) + "($|[^a-z0-9])").containsMatchIn(text)

    private companion object {
        val UNWANTED = listOf(
            "full album", "compilation", "1 hour", "10 hours", "hour loop", "playlist", "nonstop", "non-stop",
            "reaction", "reacts", "reacting", "karaoke", "instrumental", "8d", "8d audio", "nightcore",
            "sped up", "speed up", "slowed", "reverb", "bass boosted", "tutorial", "lesson", "cover",
            "megamix", "mashup", "type beat", "interview", "podcast", "behind the scenes", "live stream",
        )
    }
}
