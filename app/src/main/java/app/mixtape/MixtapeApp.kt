package app.mixtape

import android.app.Application
import android.content.Context
import app.mixtape.core.net.NewPipeDownloader
import app.mixtape.core.net.buildHttpClient
import app.mixtape.data.LibraryRepository
import app.mixtape.data.Settings
import app.mixtape.data.db.AppDatabase
import app.mixtape.download.CryptoManager
import app.mixtape.download.DownloadController
import app.mixtape.matcher.TrackMatcher
import app.mixtape.player.PlayerConnection
import app.mixtape.player.StreamResolver
import app.mixtape.source.SoundCloudSource
import app.mixtape.source.SourceRegistry
import app.mixtape.source.YouTubeSource
import app.mixtape.spotify.SpotifyApi
import app.mixtape.spotify.SpotifyAuth
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

    val player = PlayerConnection(context)
}
