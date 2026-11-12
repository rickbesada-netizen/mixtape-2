package app.mixtape.data

import android.content.Context
import app.mixtape.source.StreamQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _quality = MutableStateFlow(
        runCatching { StreamQuality.valueOf(prefs.getString("quality", null) ?: "HIGH") }
            .getOrDefault(StreamQuality.HIGH)
    )
    val quality: StateFlow<StreamQuality> = _quality.asStateFlow()

    private val _wifiOnlyDownloads = MutableStateFlow(prefs.getBoolean("wifi_only", false))
    val wifiOnlyDownloads: StateFlow<Boolean> = _wifiOnlyDownloads.asStateFlow()

    private val _videoQuality = MutableStateFlow(prefs.getInt("video_quality", 720))
    /** Max video height for playback and MP4 downloads: 360, 480, 720 or 1080. */
    val videoQuality: StateFlow<Int> = _videoQuality.asStateFlow()

    private val _hiResAsMp4 = MutableStateFlow(prefs.getBoolean("hires_mp4", false))
    /** Convert 1440p/4K (VP9) downloads to MP4 on the phone instead of keeping them as WebM. */
    val hiResAsMp4: StateFlow<Boolean> = _hiResAsMp4.asStateFlow()

    private val _speed = MutableStateFlow(prefs.getFloat("playback_speed", 1f).coerceIn(MIN_SPEED, MAX_SPEED))
    /** Music playback speed, 0.25x to 4x. Kept between sessions. */
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _keepPitch = MutableStateFlow(prefs.getBoolean("keep_pitch", true))
    /** true = voices sound normal at any speed; false = pitch follows speed (nightcore / slowed). */
    val keepPitch: StateFlow<Boolean> = _keepPitch.asStateFlow()

    private val _autoplay = MutableStateFlow(prefs.getBoolean("autoplay", true))
    /** When the queue runs out, keep going with similar songs (like Spotify's autoplay). */
    val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()

    private val _autoSync = MutableStateFlow(prefs.getBoolean("auto_sync_playlists", true))
    /** Check downloaded YouTube / Spotify / SoundCloud playlists for new songs and download them. */
    val autoSyncPlaylists: StateFlow<Boolean> = _autoSync.asStateFlow()

    private val _cleanWords = MutableStateFlow(prefs.getString("clean_words", "") ?: "")
    /** Extra words (comma separated) that make a song count as explicit in the clean converter. */
    val cleanWords: StateFlow<String> = _cleanWords.asStateFlow()

    private val _censorOffset = MutableStateFlow(prefs.getLong("censor_offset_ms", 0L))
    /** Moves bleeps earlier (+) or later (-) to make up for headphone / speaker lag. */
    val censorOffsetMs: StateFlow<Long> = _censorOffset.asStateFlow()

    fun setCensorOffsetMs(v: Long) {
        val c = v.coerceIn(-500L, 500L)
        prefs.edit().putLong("censor_offset_ms", c).apply()
        _censorOffset.value = c
    }

    private val _radioSource = MutableStateFlow(
        runCatching { RadioSource.valueOf(prefs.getString("radio_source", null) ?: "YOUTUBE_MUSIC") }
            .getOrDefault(RadioSource.YOUTUBE_MUSIC)
    )
    /** Where autoplay's next songs come from. */
    val radioSource: StateFlow<RadioSource> = _radioSource.asStateFlow()

    fun setRadioSource(v: RadioSource) {
        prefs.edit().putString("radio_source", v.name).apply()
        _radioSource.value = v
    }

    var lastPlaylistSyncAt: Long
        get() = prefs.getLong("last_playlist_sync", 0L)
        set(v) = prefs.edit().putLong("last_playlist_sync", v).apply()

    fun setAutoplay(v: Boolean) {
        prefs.edit().putBoolean("autoplay", v).apply()
        _autoplay.value = v
    }

    fun setAutoSyncPlaylists(v: Boolean) {
        prefs.edit().putBoolean("auto_sync_playlists", v).apply()
        _autoSync.value = v
    }

    fun setCleanWords(v: String) {
        prefs.edit().putString("clean_words", v).apply()
        _cleanWords.value = v
    }

    fun setSpeed(v: Float) {
        val clamped = v.coerceIn(MIN_SPEED, MAX_SPEED)
        prefs.edit().putFloat("playback_speed", clamped).apply()
        _speed.value = clamped
    }

    fun setKeepPitch(v: Boolean) {
        prefs.edit().putBoolean("keep_pitch", v).apply()
        _keepPitch.value = v
    }

    fun setHiResAsMp4(v: Boolean) {
        prefs.edit().putBoolean("hires_mp4", v).apply()
        _hiResAsMp4.value = v
    }

    fun setVideoQuality(h: Int) {
        prefs.edit().putInt("video_quality", h).apply()
        _videoQuality.value = h
    }

    fun setQuality(q: StreamQuality) {
        prefs.edit().putString("quality", q.name).apply()
        _quality.value = q
    }

    fun setWifiOnlyDownloads(v: Boolean) {
        prefs.edit().putBoolean("wifi_only", v).apply()
        _wifiOnlyDownloads.value = v
    }

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
    }
}

enum class RadioSource(val label: String, val detail: String) {
    YOUTUBE_MUSIC("YouTube Music", "YouTube Music's song radio"),
    SPOTIFY("Spotify", "Spotify search for similar songs, topped up with YouTube Music radio (needs Spotify connected)"),
    SOUNDCLOUD("SoundCloud", "SoundCloud's related tracks"),
    MIX("Mix of all", "Takes turns between YouTube Music, SoundCloud and Spotify"),
}
