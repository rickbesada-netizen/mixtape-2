package app.mixtape.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import app.mixtape.ui.components.SectionTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings > Backup & restore: export the library (optionally with downloads) and import it. */
@Composable
fun BackupSection() {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val backup = container.backup
    val state by backup.state.collectAsStateWithLifecycle()
    val bytes by produceState(0L, state.running) { value = backup.downloadedBytes() }
    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    val exportLibrary = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) backup.export(uri, includeMedia = false)
    }
    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) backup.export(uri, includeMedia = true)
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) backup.importBackup(uri)
    }

    SectionTitle("Backup & restore")
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Save a backup file to your phone's storage or Google Drive. If you reinstall the app or change phones, " +
                "import it to get everything back, with or without logging in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(enabled = !state.running, onClick = { exportAll.launch("Mixtape-backup-$stamp.mixtape") }) {
            Icon(Icons.Filled.Archive, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("Back up everything (" + "%.0f MB".format(bytes / 1_048_576.0) + ")")
        }
        Text(
            "Playlists, liked songs and all downloaded songs and videos. Nothing needs downloading again after importing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(enabled = !state.running, onClick = { exportLibrary.launch("Mixtape-library-$stamp.json") }) {
            Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("Back up library only (small)")
        }
        Text(
            "Playlists, liked songs and the list of downloads. After importing, the app downloads the songs again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(enabled = !state.running, onClick = { importFile.launch(arrayOf("*/*")) }) {
            Icon(Icons.Filled.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("Import a backup")
        }

        if (state.running) {
            Text(state.label, style = MaterialTheme.typography.bodySmall)
            if (state.total > 0) {
                LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                Text("${state.done} of ${state.total} · keep the app open", style = MaterialTheme.typography.labelSmall)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.message?.let { msg ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        msg,
                        color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.missing.isNotEmpty()) {
                            TextButton(onClick = {
                                actions.download(state.missing)
                                backup.clearMessage()
                            }) { Text("Download those ${state.missing.size} again") }
                        }
                        TextButton(onClick = { backup.clearMessage() }) { Text("OK") }
                    }
                }
            }
        }
    }
}
