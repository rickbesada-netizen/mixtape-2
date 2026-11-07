package app.mixtape.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Picture-in-picture for the video player. The video screen registers itself while it's open;
 * the activity asks it whether to shrink into a floating window when you leave the app.
 */
object Pip {
    /** True while the app is showing as a floating video window. */
    val inPip = MutableStateFlow(false)

    /** Set by the video screen while a video is open and playing. */
    @Volatile var videoPlaying = false

    fun supported(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun params(autoEnter: Boolean, aspect: Rational = Rational(16, 9)): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val b = PictureInPictureParams.Builder().setAspectRatio(aspect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(autoEnter)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    /** Shrinks the app into a floating video window now. */
    fun enter(activity: Activity): Boolean {
        if (!supported(activity)) return false
        val p = params(autoEnter = videoPlaying) ?: return false
        return runCatching { activity.enterPictureInPictureMode(p) }.getOrDefault(false)
    }

    /** Keeps Android 12+'s automatic PiP (swipe home while playing) in step with playback. */
    fun update(activity: Activity) {
        if (!supported(activity) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        params(autoEnter = videoPlaying)?.let { runCatching { activity.setPictureInPictureParams(it) } }
    }
}
