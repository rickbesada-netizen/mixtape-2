package app.mixtape.source

/** Videos lined up to play one after another (a YouTube playlist watched as videos). */
object VideoQueue {
    @Volatile var title: String = ""
    @Volatile var items: List<VideoItem> = emptyList()

    fun set(title: String, videos: List<VideoItem>) {
        this.title = title
        items = videos
    }

    fun clear() { items = emptyList(); title = "" }

    /** The videos after [videoId] in the queue (empty if it isn't in it). */
    fun after(videoId: String): List<VideoItem> {
        val i = items.indexOfFirst { it.id == videoId }
        return if (i < 0) emptyList() else items.drop(i + 1)
    }
}
