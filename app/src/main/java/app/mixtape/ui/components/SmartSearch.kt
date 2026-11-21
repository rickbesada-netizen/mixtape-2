package app.mixtape.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * A search box like YouTube's: a mic for voice search, suggestions as you type, and your recent
 * searches when it's empty. While you're typing, the suggestions cover [content] (the results).
 *
 * @param box which recent-search list to use ("music", "videos", "playlists")
 */
@Composable
fun SmartSearch(
    box: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    placeholder: String,
    isLink: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val recent by container.searchHistory.recent(box).collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }

    fun submit(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        focus.clearFocus()
        focused = false
        if (!isLink) container.searchHistory.add(box, text)
        onSubmit(text)
    }

    // Voice search: Android's own speech recogniser (Google), no extra permissions.
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                onQueryChange(spoken)
                submit(spoken)
            }
        }
    }

    LaunchedEffect(query, focused) {
        if (!focused || query.isBlank() || isLink) { suggestions = emptyList(); return@LaunchedEffect }
        delay(200) // wait until you pause typing
        suggestions = container.youtubeWatch.suggestions(query)
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(if (isLink) Icons.Filled.Link else Icons.Filled.Search, null) },
            trailingIcon = {
                Row {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Clear, "Clear") }
                    }
                    IconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what you want to hear or watch")
                        try {
                            voice.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "Voice search needs the Google app on this phone", Toast.LENGTH_LONG).show()
                        }
                    }) { Icon(Icons.Filled.Mic, "Search by voice") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = if (isLink) ImeAction.Go else ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(query) }, onGo = { submit(query) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .onFocusChanged { focused = it.isFocused },
        )

        val showRecent = focused && query.isBlank() && recent.isNotEmpty()
        val showSuggestions = focused && query.isNotBlank() && suggestions.isNotEmpty()
        when {
            showRecent -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text("Recent searches", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { container.searchHistory.clear(box) }) { Text("Clear") }
                    }
                }
                items(recent, key = { "r-$it" }) { r ->
                    ListItem(
                        headlineContent = { Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Filled.History, null) },
                        trailingContent = {
                            IconButton(onClick = { container.searchHistory.remove(box, r) }) { Icon(Icons.Filled.Clear, "Remove") }
                        },
                        modifier = Modifier.clickable { onQueryChange(r); submit(r) },
                    )
                }
            }
            showSuggestions -> LazyColumn(Modifier.fillMaxSize()) {
                items(suggestions, key = { "s-$it" }) { s ->
                    ListItem(
                        headlineContent = { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Filled.Search, null) },
                        trailingContent = {
                            // Put the suggestion in the box to keep typing, like YouTube's arrow.
                            IconButton(onClick = { onQueryChange("$s ") }) { Icon(Icons.AutoMirrored.Filled.CallMade, "Use") }
                        },
                        modifier = Modifier.clickable { onQueryChange(s); submit(s) },
                    )
                }
            }
            else -> content()
        }
    }
}
