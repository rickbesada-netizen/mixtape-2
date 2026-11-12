package app.mixtape.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.core.UpdateState
import app.mixtape.ui.components.LocalContainer
import kotlinx.coroutines.launch

/**
 * Checks for a new version when the app opens, offers it with what's new, downloads it with a
 * progress bar and opens the installer. After updating, shows what changed once.
 */
@Composable
fun UpdateDialogs() {
    val container = LocalContainer.current
    val updater = container.updater
    val scope = rememberCoroutineScope()
    val state by updater.state.collectAsStateWithLifecycle()
    var whatsNew by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        whatsNew = updater.takeWhatsNew()
        runCatching { updater.check(force = false) }
    }

    whatsNew?.let { notes ->
        AlertDialog(
            onDismissRequest = { whatsNew = null },
            title = { Text("Updated to ${updater.currentVersion}") },
            text = { Notes("What's new", notes) },
            confirmButton = { TextButton(onClick = { whatsNew = null }) { Text("Nice") } },
        )
    }

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Update available") },
            text = {
                Column {
                    Text("Version ${s.info.versionName} is ready (you have ${updater.currentVersion}). It installs over this one, so your downloads and playlists stay.")
                    if (s.info.notes.isNotBlank()) Notes("What's new", s.info.notes)
                }
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { updater.downloadAndInstall(s.info) } }) { Text("Update now") }
            },
            dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update") },
            text = {
                Column {
                    Text("${(s.progress * 100).toInt()}%")
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {},
        )
        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Ready to install") },
            text = {
                Text(
                    if (updater.canInstall()) "Tap Install, then Update on Android's screen."
                    else "Android needs you to allow Mixtape to install updates (one time). Tap Allow, switch it on, come back and tap Install."
                )
            },
            confirmButton = {
                TextButton(onClick = { updater.install(s.file) }) { Text(if (updater.canInstall()) "Install" else "Install") }
            },
            dismissButton = {
                if (!updater.canInstall()) TextButton(onClick = { updater.openInstallPermission() }) { Text("Allow") }
                else TextButton(onClick = { updater.dismiss() }) { Text("Later") }
            },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Update didn't download") },
            text = { Text(s.message) },
            confirmButton = {
                val info = s.info
                if (info != null) TextButton(onClick = { scope.launch { updater.downloadAndInstall(info) } }) { Text("Try again") }
                else TextButton(onClick = { updater.dismiss() }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("Close") } },
        )
        UpdateState.Idle -> Unit
    }
}

@Composable
private fun Notes(title: String, notes: String) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            Text(notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
