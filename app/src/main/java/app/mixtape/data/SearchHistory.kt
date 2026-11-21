package app.mixtape.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Your recent searches (one list per search box: music, videos, playlists), newest first. */
class SearchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val flows = HashMap<String, MutableStateFlow<List<String>>>()

    fun recent(box: String): StateFlow<List<String>> = flow(box).asStateFlow()

    fun add(box: String, query: String) {
        val q = query.trim()
        if (q.isEmpty() || q.startsWith("http")) return
        save(box, (listOf(q) + flow(box).value.filter { !it.equals(q, ignoreCase = true) }).take(MAX))
    }

    fun remove(box: String, query: String) = save(box, flow(box).value - query)

    fun clear(box: String) = save(box, emptyList())

    private fun flow(box: String) = flows.getOrPut(box) {
        val arr = runCatching { JSONArray(prefs.getString(box, "[]")) }.getOrDefault(JSONArray())
        MutableStateFlow((0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() })
    }

    private fun save(box: String, list: List<String>) {
        flow(box).value = list
        prefs.edit().putString(box, JSONArray(list).toString()).apply()
    }

    private companion object {
        const val MAX = 20
    }
}
