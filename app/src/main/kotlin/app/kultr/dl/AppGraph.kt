package app.kultr.dl

import android.app.Application
import app.kultr.dl.core.Catalog
import app.kultr.dl.core.net.Http
import app.kultr.dl.data.Library
import app.kultr.dl.data.Messages
import app.kultr.dl.data.SettingsRepository
import app.kultr.dl.data.db.KultrDLDatabase
import app.kultr.dl.engine.Downloads
import app.kultr.dl.engine.MediaSaver
import app.kultr.dl.engine.StreamDns
import app.kultr.dl.engine.StreamResolver
import app.kultr.dl.engine.YouTubeCheck
import app.kultr.dl.engine.YtDlp
import app.kultr.dl.playback.PlayerConnection
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** The app's long-lived parts, made once in [KultrDLApp]. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .dns(StreamDns)
        .build()

    val settings = SettingsRepository(app)
    val messages = Messages()
    private val db = KultrDLDatabase.open(app)
    val library = Library(db, scope)
    val ytDlp = YtDlp(app, scope)

    val catalog = Catalog(
        http = Http(okHttp),
        extractor = ytDlp,
        country = { settings.settings.value.country.ifBlank { Locale.getDefault().country.ifBlank { "US" } } },
        spotifyCredentials = {
            val s = settings.settings.value
            if (s.spotifyClientId.isNotBlank() && s.spotifyClientSecret.isNotBlank()) s.spotifyClientId.trim() to s.spotifyClientSecret.trim() else null
        },
    )

    val resolver = StreamResolver(library, catalog, ytDlp, settings, scope)
    val saver = MediaSaver(app)
    val youtubeCheck = YouTubeCheck(ytDlp, okHttp, settings)
    val downloads = Downloads(app, db.downloads(), library, settings, resolver, ytDlp, saver, okHttp, scope)
    val player = PlayerConnection(app, library, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
}
