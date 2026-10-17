package app.mixtape.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.BuildConfig
import app.mixtape.source.StreamQuality
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.SectionTitle
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val quality by container.settings.quality.collectAsStateWithLifecycle()
    val wifiOnly by container.settings.wifiOnlyDownloads.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle("Settings")

        Text("Streaming quality", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
        listOf(
            StreamQuality.HIGH to "High (best available)",
            StreamQuality.DATA_SAVER to "Data saver (lowest bitrate)",
        ).forEach { (q, label) ->
            ListItem(
                headlineContent = { Text(label) },
                leadingContent = { RadioButton(selected = quality == q, onClick = null) },
                modifier = Modifier.clickable {
                    container.settings.setQuality(q)
                    container.resolver.invalidateAll()
                },
            )
        }
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("Download on Wi-Fi only") },
            supportingContent = { Text("Applies to downloads queued from now on") },
            trailingContent = { Switch(checked = wifiOnly, onCheckedChange = { container.settings.setWifiOnlyDownloads(it) }) },
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("Re-match Spotify songs") },
            supportingContent = { Text("Clears automatic YouTube matches (your manual fixes are kept)") },
            modifier = Modifier.clickable {
                scope.launch {
                    container.matcher.clearAutomaticMatches()
                    container.resolver.invalidateAll()
                    Toast.makeText(context, "Automatic matches cleared", Toast.LENGTH_SHORT).show()
                }
            },
        )
        ListItem(
            headlineContent = { Text("Clear listening history") },
            modifier = Modifier.clickable {
                scope.launch {
                    container.library.clearHistory()
                    Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                }
            },
        )
        HorizontalDivider()

        SectionTitle("About")
        Text(
            "Mixtape ${BuildConfig.VERSION_NAME}\n\n" +
                "Streams audio from YouTube, YouTube Music and SoundCloud using NewPipeExtractor. Spotify is " +
                "used for playlist metadata only. Offline files are encrypted and play only inside this app.\n\n" +
                "Downloading from these services may break their terms of service. Use it for personal listening.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
