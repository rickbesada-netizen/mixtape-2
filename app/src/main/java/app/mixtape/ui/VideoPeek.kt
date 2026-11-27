package app.mixtape.ui

import app.mixtape.source.VideoItem

/**
 * Videos seen in lists (title, channel, thumbnail), so a video you tap shows its picture and name
 * straight away while the rest loads.
 */
object VideoPeek {
    private val seen = object : LinkedHashMap<String, VideoItem>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoItem>?) = size > 400
    }

    fun remember(v: VideoItem) = synchronized(seen) { seen[v.id] = v }

    fun get(id: String): VideoItem? = synchronized(seen) { seen[id] }
}
