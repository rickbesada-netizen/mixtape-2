package app.mixtape.player

import androidx.media3.common.PlaybackParameters

/** Speed + pitch as Media3 parameters. Pitch follows speed when [keepPitch] is off. */
fun speedParams(speed: Float, keepPitch: Boolean): PlaybackParameters =
    PlaybackParameters(speed, if (keepPitch) 1f else speed)

/** 1f -> "1x", 1.25f -> "1.25x", 0.5f -> "0.5x" */
fun formatSpeed(speed: Float): String {
    val rounded = Math.round(speed * 100) / 100f
    val text = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString().trimEnd('0').trimEnd('.')
    return text + "x"
}
