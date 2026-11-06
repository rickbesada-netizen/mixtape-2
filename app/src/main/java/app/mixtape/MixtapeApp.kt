package app.mixtape

import android.app.Application
import android.content.Context
import app.mixtape.cloud.CloudSync
import app.mixtape.core.net.NewPipeDownloader
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import app.mixtape.core.net.buildHttpClient
import app.mixtape.data.LibraryRepository
import app.mixtape.data.Settings
import app.mixtape.data.db.AppDatabase
import app.mixtape.download.CryptoManager
import app.mixtape.download.DownloadController
import app.mixtape.matcher.TrackMatcher
import app.mixtape.player.PlayerConnection
import app.mixtape.player.StreamResolver
import app.mixtape.source.HomeFeedRepository
import app.mixtape.source.LinkResolver
import app.mixtape.source.SoundCloudSource
import app.mixtape.source.SourceRegistry
import app.mixtape.source.YouTubeSource
import app.mixtape.spotify.SpotifyApi
import app.mixtape.spotify.SpotifyAuth
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class MixtapeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val locale = Locale.getDefault()
        NewPipe.init(
            NewPipeDownloader(container.http),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifBlank { "US" }),
        )
        DownloadController.createChannel(this)

        val c = container
        c.library.onPlaylistDeleted = { syncId -> c.cloud.onPlaylistDeleted(syncId) }
        c.downloads.onDeleted = { key -> c.cloud.onDownloadDeleted(key) }
        c.cloud.start(
            merge(
                c.library.likedKeys().map { it as Any },
                c.library.playlists().map { it as Any },
                c.downloads.doneKeys().map { it as Any },
            )
        )
    }
}

/** Manual dependency container: one instance of each service for the whole process. */
class AppContainer(context: Context) {
    val http: OkHttpClient = buildHttpClient()
    val db: AppDatabase = AppDatabase.build(context)
    val settings = Settings(context)
    val library = LibraryRepository(db)
    val crypto = CryptoManager(context)

    val sources = SourceRegistry(
        youtube = YouTubeSource(quality = { settings.quality.value }),
        soundcloud = SoundCloudSource(),
    )
    val matcher = TrackMatcher(sources.youtube, db.matches(), library)
    val downloads = DownloadController(context, db.downloads(), library, settings)
    val resolver = StreamResolver(sources, matcher, downloads)

    val spotifyAuth = SpotifyAuth(context, http)
    val spotify = SpotifyApi(spotifyAuth, http)

    val links = LinkResolver(http, spotify, spotifyAuth)

    val cloud = CloudSync(context, http, db, library)

    val home = HomeFeedRepository(library, db.matches(), spotify, spotifyAuth)

    /** A link shared into the app from another app, waiting to be opened by the UI. */
    val pendingLink = MutableStateFlow<String?>(null)

    val player = PlayerConnection(context, settings)
}
