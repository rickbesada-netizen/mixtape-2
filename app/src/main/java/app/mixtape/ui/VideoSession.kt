package app.mixtape.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The video being watched. It sits above the whole app, so swiping it down shrinks it into a small
 * window in the corner (like YouTube's) and it keeps playing while you browse.
 */
object VideoSession {
    var videoId by mutableStateOf<String?>(null)
        private set
    var minimized by mutableStateOf(false)
        private set

    fun open(id: String) {
        videoId = id
        minimized = false
    }

    fun minimize() { if (videoId != null) minimized = true }

    fun expand() { minimized = false }

    fun close() {
        videoId = null
        minimized = false
    }
}
