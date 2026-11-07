package app.mixtape.core.model

/**
 * Where a track comes from. The prefix is used in every track key, e.g. "yt:dQw4w9WgXcQ".
 *  - YOUTUBE: sourceId is the video id
 *  - SOUNDCLOUD: sourceId is the track's soundcloud.com URL
 *  - SPOTIFY: sourceId is the Spotify track id (metadata only; audio comes from a YouTube match)
 */
enum class SourceType(val prefix: String, val label: String) {
    YOUTUBE("yt", "YouTube"),
    SOUNDCLOUD("sc", "SoundCloud"),
    SPOTIFY("sp", "Spotify"),

    /** A YouTube video kept as video (MP4 download / video player); sourceId is the video id. */
    YOUTUBE_VIDEO("ytv", "Video");

    companion object {
        fun fromPrefix(prefix: String): SourceType =
            entries.firstOrNull { it.prefix == prefix }
                ?: throw IllegalArgumentException("Unknown source prefix: $prefix")

        fun fromKey(key: String): SourceType = fromPrefix(key.substringBefore(':'))
    }
}

data class Track(
    val source: SourceType,
    val sourceId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Long = 0,
    val artworkUrl: String? = null,
    val isrc: String? = null,
) {
    val key: String get() = keyOf(source, sourceId)

    companion object {
        fun keyOf(source: SourceType, sourceId: String) = "${source.prefix}:$sourceId"
        fun sourceIdOf(key: String) = key.substringAfter(':')
    }
}

/** A playable audio stream resolved right before playback or download. URLs expire. */
data class ResolvedStream(
    val url: String,
    val mimeType: String?,
    val suffix: String,
    val bitrate: Int,
    val isHls: Boolean,
    val isYoutube: Boolean,
    val expiresAtMs: Long,
)

enum class SearchFilter(val label: String, val collections: Boolean = false) {
    YT_MUSIC_SONGS("Songs"),
    YT_VIDEOS("Videos"),
    YT_MUSIC_ALBUMS("Albums", collections = true),
    YT_PLAYLISTS("Playlists", collections = true),
    YT_CHANNELS("Channels"),
    SOUNDCLOUD("SoundCloud"),
    SOUNDCLOUD_PLAYLISTS("SC playlists", collections = true),
}

/** A playlist or album found in search or from a pasted link (not yet saved to the library). */
data class RemoteCollection(
    val url: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val trackCount: Long = -1,
)

data class CollectionDetail(val info: RemoteCollection, val tracks: List<Track>)

fun formatDuration(sec: Long): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** How a track should read on screen: song name first, artist second. */
data class DisplayInfo(val title: String, val artist: String)

private val TITLE_SPLIT = Regex("\\s+[-–—]\\s+")
private val NOISE_BRACKETS = Regex(
    "(?i)\\s*[(\\[{][^)\\]}]*\\b(official|video|audio|lyrics?|visuali[sz]er|music video|mv|hd|hq|4k|clean|explicit|" +
        "dirty|remaster(ed)?|full song|topic)\\b[^)\\]}]*[)\\]}]"
)
private val NOISE_TAIL = Regex("(?i)\\s*[|/]\\s*(official|lyrics?|audio|video).*$")

/**
 * YouTube and SoundCloud uploads are usually titled "Artist - Song (Official Video)". This turns
 * that into title = "Song", artist = "Artist", and drops the noise in brackets. Spotify titles are
 * already clean and pass through unchanged.
 */
fun Track.display(): DisplayInfo {
    if (source == SourceType.SPOTIFY) return DisplayInfo(title, artist)
    var t = title.replace(NOISE_BRACKETS, "").replace(NOISE_TAIL, "").trim()
    var a = artist
    val parts = TITLE_SPLIT.split(t, limit = 2)
    if (parts.size == 2) {
        val left = parts[0].trim()
        val right = parts[1].trim()
        if (left.isNotEmpty() && right.isNotEmpty() && left.length <= 80) {
            t = right
            a = left
        }
    }
    t = t.trim().trim('"', '\'', '“', '”').ifEmpty { title }
    return DisplayInfo(t, a)
}
