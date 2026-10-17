package app.mixtape.player

import app.mixtape.core.model.ResolvedStream
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.download.DownloadController
import app.mixtape.matcher.TrackMatcher
import app.mixtape.source.SourceRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface PlaybackTarget {
    data class Offline(val file: File) : PlaybackTarget
    data class Remote(val stream: ResolvedStream, val playableKey: String) : PlaybackTarget
}

/**
 * Turns a track key into something playable: an offline file if one exists, otherwise a fresh
 * stream URL. Spotify keys go through the matcher first. Stream URLs are cached until expiry.
 */
class StreamResolver(
    private val sources: SourceRegistry,
    private val matcher: TrackMatcher,
    private val downloads: DownloadController,
) {
    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val playableKeys = ConcurrentHashMap<String, String>()

    suspend fun resolve(key: String): PlaybackTarget {
        downloads.completedFile(key)?.let { return PlaybackTarget.Offline(it) }

        val playableKey = if (SourceType.fromKey(key) == SourceType.SPOTIFY) {
            Track.keyOf(SourceType.YOUTUBE, matcher.resolveYoutubeId(key))
        } else key
        playableKeys[key] = playableKey
        if (playableKey != key) downloads.completedFile(playableKey)?.let { return PlaybackTarget.Offline(it) }

        cache[playableKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() + 60_000 }?.let {
            return PlaybackTarget.Remote(it, playableKey)
        }
        val source = sources.forType(SourceType.fromKey(playableKey))
        val stream = source.resolveStream(Track.sourceIdOf(playableKey), forDownload = false)
        cache[playableKey] = stream
        return PlaybackTarget.Remote(stream, playableKey)
    }

    /** Drops the cached stream for a key (and for the YouTube match behind a Spotify key). */
    fun invalidate(key: String) {
        cache.remove(key)
        playableKeys[key]?.let { cache.remove(it) }
    }

    fun invalidateAll() = cache.clear()
}
