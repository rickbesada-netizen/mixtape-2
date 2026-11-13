package app.mixtape.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.core.model.Track
import app.mixtape.core.model.display
import app.mixtape.player.CensorMode
import app.mixtape.player.CensorRange
import app.mixtape.player.CensorStore
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.LocalContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

private fun fmt(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return "%d:%02d.%d".format(t / 60000, (t / 1000) % 60, (t % 1000) / 100)
}

/**
 * Make your own clean version of a song: play it, hold the button while a word you don't want
 * plays, and that part is bleeped (or muted) every time the song plays in Mixtape, streamed or
 * downloaded.
 */
@Composable
fun CensorScreen(trackKey: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    val offset by container.settings.censorOffsetMs.collectAsStateWithLifecycle()

    val track by produceState<Track?>(null, trackKey) { value = container.library.getTrack(trackKey) }
    val saved = remember(trackKey) { container.censor.get(trackKey) }
    var ranges by remember(trackKey) { mutableStateOf(saved?.ranges ?: emptyList()) }
    var mode by remember(trackKey) { mutableStateOf(saved?.mode ?: CensorMode.INSTRUMENTAL) }
    var holding by remember { mutableStateOf(false) }
    var holdStart by remember { mutableLongStateOf(0L) }
    var autoBusy by remember { mutableStateOf(false) }

    // Smooth play position while this screen is open.
    var pos by remember { mutableLongStateOf(0L) }
    var engine by remember { mutableStateOf("") }
    var engineError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            pos = player.positionNow()
            // What the sound engine is doing, so you can see the edit being applied.
            val d = app.mixtape.player.CleanDiagnostics
            val now = System.currentTimeMillis()
            engineError = d.lastError
            engine = when {
                !player.state.value.isPlaying -> "Sound engine: paused"
                now - d.lastSeenAt > 2000 -> "Sound engine: waiting for audio"
                d.editKey != trackKey && ranges.isNotEmpty() -> "Sound engine: hasn't picked up this edit yet"
                kotlin.math.abs(d.lastMediaMs - pos) > 5000 ->
                    "Sound engine: at ${fmt(d.lastMediaMs)} but the song is at ${fmt(pos)} (timing problem, please tell Claude)"
                now - d.lastAppliedAt < 600 -> "Sound engine: cleaning now"
                else -> "Sound engine: listening (${ranges.size} marked parts)"
            }
            delay(50)
        }
    }

    fun persist(newRanges: List<CensorRange>, newMode: CensorMode = mode) {
        val merged = CensorStore.merge(newRanges)
        ranges = merged
        mode = newMode
        scope.launch { container.censor.save(trackKey, merged, newMode) }
    }

    val isThis = state.nowPlaying?.key == trackKey
    val durationMs = if (isThis && state.durationMs > 0) state.durationMs else (track?.durationSec ?: 0) * 1000

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text("Make a clean edit") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        val t = track
        if (t == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                val shown = t.display()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(t.artworkUrl, 56)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(shown.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(shown.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "Play the song and hold the button below while a word you don't want plays. That part gets " +
                        "cleaned every time this song plays in Mixtape, streamed or downloaded. Tap it once for a " +
                        "quick mark around that moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                if (!isThis) {
                    Button(onClick = { player.playTracks(listOf(t), 0) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play this song to start")
                    }
                } else {
                    // Timeline: red = bleeped, orange = guessed from lyrics (check it), line = now.
                    val red = MaterialTheme.colorScheme.error
                    val barColor = MaterialTheme.colorScheme.surfaceVariant
                    val head = MaterialTheme.colorScheme.primary
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .pointerInput(durationMs) {
                                detectTapGestures { o ->
                                    if (durationMs > 0) player.seekTo((o.x / size.width * durationMs).toLong())
                                }
                            },
                    ) {
                        drawRect(barColor)
                        if (durationMs > 0) {
                            for (r in ranges) {
                                val x0 = r.startMs.toFloat() / durationMs * size.width
                                val x1 = r.endMs.toFloat() / durationMs * size.width
                                drawRect(
                                    if (r.auto) Color(0xFFFFA000) else red,
                                    topLeft = Offset(x0, 0f),
                                    size = Size(max(x1 - x0, 3f), size.height),
                                )
                            }
                            val x = pos.toFloat() / durationMs * size.width
                            drawRect(head, topLeft = Offset(x - 2f, 0f), size = Size(4f, size.height))
                        }
                    }
                    Text(
                        engineError?.let { "Sound engine error: $it" } ?: engine,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (engineError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(fmt(pos), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(fmt(durationMs), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { player.seekTo(max(0, pos - 5000)) }) { Icon(Icons.Filled.Replay5, "Back 5 seconds") }
                        TextButton(onClick = { player.seekTo(max(0, pos - 1000)) }) { Text("-1s") }
                        FilledIconButton(onClick = { player.togglePlay() }, modifier = Modifier.size(56.dp)) {
                            Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause")
                        }
                        TextButton(onClick = { player.seekTo(pos + 1000) }) { Text("+1s") }
                        IconButton(onClick = { player.seekTo(pos + 5000) }) { Icon(Icons.Filled.Forward5, "Forward 5 seconds") }
                    }

                    Surface(
                        color = if (holding) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .pointerInput(trackKey) {
                                detectTapGestures(onPress = {
                                    val start = player.positionNow()
                                    holdStart = start
                                    holding = true
                                    tryAwaitRelease()
                                    holding = false
                                    val end = player.positionNow()
                                    val r = if (end - start > 150) {
                                        // People press a moment after the word starts.
                                        CensorRange(max(0, start - REACTION_MS), end + 80)
                                    } else {
                                        CensorRange(max(0, start - 700), start + 250)
                                    }
                                    persist(ranges + r)
                                })
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (holding) "Bleeping from ${fmt(holdStart)}… let go when the word ends" else "HOLD while the word plays",
                                fontWeight = FontWeight.Bold,
                                color = if (holding) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                Text("What plays in the marked parts", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CensorMode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { persist(ranges, m) }, label = { Text(m.label) })
                    }
                }
                Text(
                    when (mode) {
                        CensorMode.INSTRUMENTAL ->
                            "The vocals drop out and the beat keeps going, like the instrumental. Works on most studio songs; " +
                                "if a song was recorded in mono only the bass is left, so use Bleep for that one."
                        CensorMode.INSTRUMENTAL_BEEP -> "The beat keeps going with a soft bleep over it, like a radio edit."
                        CensorMode.BEEP -> "The whole sound is replaced by a bleep, like on TV."
                        CensorMode.SILENCE -> "The whole sound goes quiet for that moment."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                OutlinedButton(
                    enabled = !autoBusy,
                    onClick = {
                        autoBusy = true
                        scope.launch {
                            container.censor.autoMark(t)
                                .onSuccess { found ->
                                    if (found.isEmpty()) {
                                        Toast.makeText(context, "No bad words found in the lyrics", Toast.LENGTH_SHORT).show()
                                    } else {
                                        persist(ranges + found)
                                        Toast.makeText(
                                            context,
                                            "Marked ${found.size} spots from the lyrics (orange). Listen to each and adjust.",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                            autoBusy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    if (autoBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.AutoFixHigh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Find bad words from the lyrics")
                }

                Text(
                    "Timing: " + when {
                        offset > 0 -> "${offset} ms earlier"
                        offset < 0 -> "${-offset} ms later"
                        else -> "normal"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    "If all your marks are a little late, slide right to move them all earlier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = offset.toFloat(),
                    onValueChange = { container.settings.setCensorOffsetMs(((it / 25).toLong()) * 25) },
                    valueRange = -500f..500f,
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ranges.isEmpty()) "Nothing bleeped yet" else "${ranges.size} bleeped parts",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (ranges.isNotEmpty()) TextButton(onClick = { persist(emptyList()) }) { Text("Clear all") }
                }
            }

            itemsIndexed(ranges, key = { i, r -> "${r.startMs}-$i" }) { i, r ->
                fun update(newR: CensorRange) {
                    val list = ranges.toMutableList()
                    list[i] = newR.copy(auto = false)
                    persist(list)
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (isThis) player.seekToAndPlay(r.startMs - 1500) else player.playTracks(listOf(t), 0) }) {
                            Icon(Icons.Filled.PlayArrow, "Hear this part")
                        }
                        Column(Modifier.weight(1f)) {
                            Text("${fmt(r.startMs)} to ${fmt(r.endMs)}", fontWeight = FontWeight.SemiBold)
                            if (r.auto) {
                                Text(
                                    "Guessed from lyrics, check it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFFA000),
                                )
                            }
                        }
                        IconButton(onClick = { persist(ranges.filterIndexed { j, _ -> j != i }) }) {
                            Icon(Icons.Filled.Delete, "Remove")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Start", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { update(r.copy(startMs = max(0, r.startMs - 100))) }) { Text("-0.1") }
                        TextButton(onClick = { if (r.startMs + 100 < r.endMs) update(r.copy(startMs = r.startMs + 100)) }) { Text("+0.1") }
                        Spacer(Modifier.width(8.dp))
                        Text("End", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { if (r.endMs - 100 > r.startMs) update(r.copy(endMs = r.endMs - 100)) }) { Text("-0.1") }
                        TextButton(onClick = { update(r.copy(endMs = r.endMs + 100)) }) { Text("+0.1") }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private const val REACTION_MS = 200L
