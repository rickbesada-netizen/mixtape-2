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
}
