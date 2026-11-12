package app.mixtape.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mixtape.core.model.display
import app.mixtape.source.CleanJob
import app.mixtape.source.CleanStatus

/** Asks what to do with explicit songs that have no clean version, then starts the converter. */
@Composable
fun CleanStartDialog(
    name: String,
    count: Int,
    onStart: (keepIfNoClean: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var keep by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make a clean version") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Checks the lyrics of all $count songs in \"$name\" for swearing, slurs and any words you've " +
                        "added in Settings. Explicit songs are swapped for their clean / radio edit version. " +
                        "The result is saved as a new playlist, \"$name (Clean)\". The original isn't changed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "If an explicit song has no clean version:",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.fillMaxWidth().clickable { keep = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !keep, onClick = { keep = false })
                    Text("Leave it out")
                }
                Row(Modifier.fillMaxWidth().clickable { keep = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = keep, onClick = { keep = true })
                    Text("Keep it anyway")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(keep) }) { Text("Make clean version") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Progress while checking, then a summary of what changed. */
@Composable
fun CleanProgressDialog(
    job: CleanJob,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onOpen: (Long) -> Unit,
    onDownload: (Long) -> Unit,
    onEdit: (app.mixtape.core.model.Track) -> Unit,
) {
    if (!job.finished) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Making \"${job.name}\" clean") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Checked ${job.done} of ${job.total} songs")
                    LinearProgressIndicator(
                        progress = { if (job.total > 0) job.done.toFloat() / job.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "You can leave this screen open or keep using the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Hide") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Stop") } },
        )
        return
    }

    val changed = job.items.filter { it.status != CleanStatus.CLEAN }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (job.error != null) "Couldn't finish" else "Clean version ready") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (job.error != null) {
                    Text(job.error)
                } else {
                    val kept = job.items.count { it.result != null }
                    Text("\"${job.name} (Clean)\" has $kept songs.", fontWeight = FontWeight.SemiBold)
                    listOf(
                        CleanStatus.CLEAN, CleanStatus.SWAPPED, CleanStatus.REMOVED, CleanStatus.KEPT, CleanStatus.UNCHECKED,
                    ).forEach { s ->
                        val n = job.count(s)
                        if (n > 0) Text("$n: ${s.label}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (changed.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(top = 8.dp)) {
                            items(changed, key = { it.original.key }) { item ->
                                val shown = item.original.display()
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        "${shown.title} · ${shown.artist}",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    val detail = buildString {
                                        append(item.status.label)
                                        if (item.status == CleanStatus.SWAPPED && item.result != null) append(": ${item.result.title}")
                                        if (item.reason != null) append(" (${item.reason})")
                                    }
                                    Text(
                                        detail, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (item.status == CleanStatus.REMOVED || item.status == CleanStatus.KEPT ||
                                        item.status == CleanStatus.UNCHECKED
                                    ) {
                                        TextButton(onClick = { onEdit(item.original) }) { Text("Make my own clean edit") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val id = job.playlistId
            if (id != null) {
                Row {
                    TextButton(onClick = { onDownload(id) }) { Text("Download") }
                    TextButton(onClick = { onOpen(id) }) { Text("Open") }
                }
            } else TextButton(onClick = onClose) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
