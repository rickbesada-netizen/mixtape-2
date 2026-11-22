package app.mixtape.ui.components

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.core.model.Track
import app.mixtape.core.model.display
import app.mixtape.data.db.PlayStat
import java.text.Collator
import java.util.Locale

/** Ways to order songs in a list. */
enum class SongSort(val label: String) {
    DEFAULT("Original order"),
    RECENTLY_ADDED("Recently added"),
    OLDEST_ADDED("Oldest added"),
    TITLE("Title A-Z"),
    TITLE_DESC("Title Z-A"),
    ARTIST("Artist A-Z"),
    RECENTLY_PLAYED("Recently played"),
    MOST_PLAYED("Most played"),
    LONGEST("Longest first"),
    SHORTEST("Shortest first"),
}

/**
 * Sorts songs. [addedAt] gives when each song was added (or its position, bigger = newer);
 * [stats] is your play history. Names sort the way people expect (ignoring case and accents,
 * and Arabic and other alphabets sort properly).
 */
fun sortSongs(
    list: List<Track>,
    sort: SongSort,
    stats: Map<String, PlayStat>,
    addedAt: (Track, Int) -> Long,
): List<Track> {
    if (sort == SongSort.DEFAULT || list.size < 2) return list
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    val titled = list.mapIndexed { i, t -> Triple(t, i, t.display()) }
    val byTitle = Comparator<Triple<Track, Int, app.mixtape.core.model.DisplayInfo>> { a, b -> collator.compare(a.third.title, b.third.title) }
    val byArtist = Comparator<Triple<Track, Int, app.mixtape.core.model.DisplayInfo>> { a, b -> collator.compare(a.third.artist, b.third.artist) }
    val sorted = when (sort) {
        SongSort.DEFAULT -> titled
        SongSort.RECENTLY_ADDED -> titled.sortedByDescending { addedAt(it.first, it.second) }
        SongSort.OLDEST_ADDED -> titled.sortedBy { addedAt(it.first, it.second) }
        SongSort.TITLE -> titled.sortedWith(byTitle)
        SongSort.TITLE_DESC -> titled.sortedWith(byTitle.reversed())
        SongSort.ARTIST -> titled.sortedWith(byArtist.then(byTitle))
        // Never-played songs go to the end, A-Z.
        SongSort.RECENTLY_PLAYED -> titled.sortedWith(
            compareByDescending<Triple<Track, Int, app.mixtape.core.model.DisplayInfo>> { stats[it.first.key]?.lastPlayed ?: 0L }.then(byTitle)
        )
        SongSort.MOST_PLAYED -> titled.sortedWith(
            compareByDescending<Triple<Track, Int, app.mixtape.core.model.DisplayInfo>> { stats[it.first.key]?.plays ?: 0 }.then(byTitle)
        )
        SongSort.LONGEST -> titled.sortedByDescending { it.first.durationSec }
        SongSort.SHORTEST -> titled.sortedBy { if (it.first.durationSec > 0) it.first.durationSec else Long.MAX_VALUE }
    }
    return sorted.map { it.first }
}

/** The chosen sort for one screen, remembered between visits. */
@Composable
fun rememberSongSort(screen: String, default: SongSort = SongSort.DEFAULT): MutableState<SongSort> {
    val prefs = LocalContext.current.getSharedPreferences("song_sort", Context.MODE_PRIVATE)
    val state = remember(screen) {
        val saved = prefs.getString(screen, null)?.let { runCatching { SongSort.valueOf(it) }.getOrNull() }
        object : MutableState<SongSort> {
            private val inner = mutableStateOf(saved ?: default)
            override var value: SongSort
                get() = inner.value
                set(v) { inner.value = v; prefs.edit().putString(screen, v.name).apply() }
            override fun component1() = value
            override fun component2(): (SongSort) -> Unit = { value = it }
        }
    }
    return state
}

/** Your play history as a lookup, for sorting by recently / most played. */
@Composable
fun rememberPlayStats(): Map<String, PlayStat> {
    val container = LocalContainer.current
    val flow = remember { container.db.history().playStats() }
    val list by flow.collectAsStateWithLifecycle(emptyList())
    return remember(list) { list.associateBy { it.trackKey } }
}

/** "Sort" button with the list of options. */
@Composable
fun SongSortButton(sort: SongSort, onSort: (SongSort) -> Unit, defaultLabel: String = SongSort.DEFAULT.label) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (sort == SongSort.DEFAULT) defaultLabel else sort.label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SongSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == SongSort.DEFAULT) defaultLabel else option.label) },
                    trailingIcon = { if (option == sort) Icon(Icons.Filled.Check, null) },
                    onClick = { onSort(option); open = false },
                )
            }
        }
    }
}
