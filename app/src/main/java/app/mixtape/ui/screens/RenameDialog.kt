package app.mixtape.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mixtape.core.model.Track
import app.mixtape.core.text.Latinize

/**
 * Rename a song. "English letters" writes an Arabic (or other alphabet) name in English letters
 * so it's easy to read and search; the original name is kept and can be searched too.
 */
@Composable
fun RenameDialog(track: Track, onSave: (title: String?, artist: String?) -> Unit, onDismiss: () -> Unit) {
    val origTitle = track.originalTitle ?: track.title
    val origArtist = track.originalArtist ?: track.artist
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    val canLatinize = Latinize.needs(origTitle) || Latinize.needs(origArtist)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename song") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Song name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artist") }, modifier = Modifier.fillMaxWidth())
                if (canLatinize) {
                    FilledTonalButton(onClick = {
                        title = Latinize.of(origTitle) ?: origTitle
                        artist = Latinize.of(origArtist) ?: origArtist
                    }) { Text("Write in English letters") }
                    Text(
                        "Arabic usually leaves vowels out, so check the spelling and fix it if you like. " +
                            "You can still search by the original name too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (track.originalTitle != null || track.originalArtist != null) {
                    Text("Original: $origTitle · $origArtist", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Saving the original name again just clears the rename.
                onSave(title.takeIf { it.isNotBlank() && it != origTitle }, artist.takeIf { it.isNotBlank() && it != origArtist })
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (track.originalTitle != null || track.originalArtist != null) {
                    TextButton(onClick = { onSave(null, null) }) { Text("Use original") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
