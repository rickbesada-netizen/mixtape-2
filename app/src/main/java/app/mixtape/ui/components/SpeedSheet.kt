package app.mixtape.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mixtape.data.Settings
import app.mixtape.player.formatSpeed
import kotlin.math.roundToInt

private val PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f)

private fun snap(v: Float): Float =
    ((v * 20).roundToInt() / 20f).coerceIn(Settings.MIN_SPEED, Settings.MAX_SPEED)

/**
 * Playback speed picker: 0.25x to 4x in 0.05 steps, quick presets, and a pitch switch.
 * Pass null for [onKeepPitch] to hide the pitch switch.
 */
@Composable
fun SpeedSheet(
    speed: Float,
    keepPitch: Boolean,
    onSpeed: (Float) -> Unit,
    onKeepPitch: ((Boolean) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var dragValue by remember { mutableFloatStateOf(speed) }
    var dragging by remember { mutableStateOf(false) }
    val shown = if (dragging) dragValue else speed

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Playback speed", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = { onSpeed(snap(speed - 0.05f)) }, enabled = speed > Settings.MIN_SPEED) {
                    Icon(Icons.Filled.Remove, "Slower")
                }
                Text(
                    formatSpeed(shown),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                IconButton(onClick = { onSpeed(snap(speed + 0.05f)) }, enabled = speed < Settings.MAX_SPEED) {
                    Icon(Icons.Filled.Add, "Faster")
                }
            }
            Slider(
                value = shown,
                onValueChange = { dragValue = snap(it); dragging = true },
                onValueChangeFinished = { onSpeed(dragValue); dragging = false },
                valueRange = Settings.MIN_SPEED..Settings.MAX_SPEED,
                steps = 74,
            )
            Row(Modifier.fillMaxWidth()) {
                Text("0.25x", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text("4x", style = MaterialTheme.typography.labelSmall)
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESETS.forEach { p ->
                    FilterChip(
                        selected = kotlin.math.abs(speed - p) < 0.001f,
                        onClick = { onSpeed(p) },
                        label = { Text(formatSpeed(p)) },
                    )
                }
            }
            if (onKeepPitch != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep normal pitch", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (keepPitch) "Voices sound normal at any speed"
                            else "Pitch follows speed: faster = higher (nightcore), slower = deeper (slowed)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = keepPitch, onCheckedChange = onKeepPitch)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onSpeed(1f) }, enabled = kotlin.math.abs(speed - 1f) > 0.001f) {
                Text("Reset to normal (1x)")
            }
        }
    }
}
