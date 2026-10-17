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
    SPOTIFY("sp", "Spotify");

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
