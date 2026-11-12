package app.mixtape.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.BuildConfig
import app.mixtape.source.StreamQuality
import app.mixtape.data.RadioSource
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
    val videoQuality by container.settings.videoQuality.collectAsStateWithLifecycle()
    val hiResMp4 by container.settings.hiResAsMp4.collectAsStateWithLifecycle()
    val autoplay by container.settings.autoplay.collectAsStateWithLifecycle()
    val radioSource by container.settings.radioSource.collectAsStateWithLifecycle()
    val autoSync by container.settings.autoSyncPlaylists.collectAsStateWithLifecycle()
    val savedWords by container.settings.cleanWords.collectAsStateWithLifecycle()
    var words by remember { mutableStateOf(savedWords) }
    var checking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AccountSection()
        HorizontalDivider(Modifier.padding(top = 8.dp))
        BackupSection()
        HorizontalDivider(Modifier.padding(top = 8.dp))
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

        Text("Video quality", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Text(
            "Highest quality for watching and MP4 downloads (uses the next one down if a video doesn't have it)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        listOf(360, 480, 720, 1080, 1440, 2160).forEach { h ->
            ListItem(
                headlineContent = { Text(if (h == 2160) "4K (2160p)" else "${h}p") },
                supportingContent = if (h >= 1440) {
                    { Text("Needs a phone that can play VP9 at this size. Downloads save as WebM unless converted below.") }
                } else null,
                leadingContent = { RadioButton(selected = videoQuality == h, onClick = null) },
                modifier = Modifier.clickable { container.settings.setVideoQuality(h) },
            )
        }
        ListItem(
            headlineContent = { Text("Convert 1440p / 4K downloads to MP4") },
            supportingContent = {
                Text(
                    "Off: saved as WebM straight away, original quality. On: re-encoded to MP4 on your phone " +
                        "after downloading, slower and uses battery, but plays in any app."
                )
            },
            trailingContent = { Switch(checked = hiResMp4, onCheckedChange = { container.settings.setHiResAsMp4(it) }) },
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("Download on Wi-Fi only") },
            supportingContent = { Text("Applies to downloads queued from now on") },
            trailingContent = { Switch(checked = wifiOnly, onCheckedChange = { container.settings.setWifiOnlyDownloads(it) }) },
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("Autoplay similar songs") },
            supportingContent = { Text("When your queue ends (or you play one song from search), keep going with songs like it") },
            trailingContent = { Switch(checked = autoplay, onCheckedChange = { container.player.setAutoplay(it) }) },
        )
        if (autoplay) {
            Text("Autoplay songs come from", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            RadioSource.entries.forEach { src ->
                ListItem(
                    headlineContent = { Text(src.label) },
                    supportingContent = { Text(src.detail) },
                    leadingContent = { RadioButton(selected = radioSource == src, onClick = null) },
                    modifier = Modifier.clickable { container.player.setRadioSource(src) },
                )
            }
        }
        ListItem(
            headlineContent = { Text("Auto-download new playlist songs") },
            supportingContent = {
                Text(
                    "When a song is added to a YouTube, Spotify or SoundCloud playlist you've downloaded, " +
                        "it's added and downloaded here too. Checks every few hours."
                )
            },
            trailingContent = {
                Switch(checked = autoSync, onCheckedChange = {
                    container.settings.setAutoSyncPlaylists(it)
                    container.playlistSync.schedule()
                })
            },
        )
        ListItem(
            headlineContent = { Text(if (checking) "Checking playlists..." else "Check playlists for new songs now") },
            modifier = Modifier.clickable(enabled = !checking) {
                checking = true
                scope.launch {
                    val r = runCatching { container.playlistSync.syncAll() }.getOrDefault(emptyList())
                    val n = r.sumOf { it.added.size }
                    Toast.makeText(
                        context,
                        if (n == 0) "No new songs" else "$n new songs from ${r.size} playlists, downloading",
                        Toast.LENGTH_LONG,
                    ).show()
                    checking = false
                }
            },
        )
        HorizontalDivider()

        Text("Clean versions", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Text(
            "\"Clean version\" on a playlist already catches common swear words and slurs. Add any other words " +
                "you don't want, separated by commas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedTextField(
            value = words,
            onValueChange = { words = it },
            label = { Text("Extra words to filter") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (words != savedWords) {
            TextButton(
                onClick = {
                    container.settings.setCleanWords(words.trim())
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Save words") }
        }
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
