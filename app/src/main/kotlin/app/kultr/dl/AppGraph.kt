package app.kultr.dl

import android.app.Application
import app.kultr.dl.core.Catalog
import app.kultr.dl.core.net.Http
import app.kultr.dl.data.Library
import app.kultr.dl.data.Messages
import app.kultr.dl.data.NavidromeRepository
import app.kultr.dl.data.ServerRepository
import app.kultr.dl.data.TasteStore
import app.kultr.dl.data.SettingsRepository
import app.kultr.dl.data.db.KultrDLDatabase
import app.kultr.dl.engine.Downloads
import app.kultr.dl.engine.MediaSaver
import app.kultr.dl.engine.PhoneMusic
import app.kultr.dl.engine.Recommender
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
        // Navidrome covers and streams are stored without the login; it is added to each request.
        .addInterceptor { chain ->
            val request = chain.request()
            val server = navidrome.client(http)
            val url = request.url.toString()
            if (server != null && server.owns(url) && request.url.queryParameter("t") == null && request.url.queryParameter("p") == null) {
                chain.proceed(request.newBuilder().url(server.authenticate(url)).build())
            } else {
                chain.proceed(request)
            }
        }
        .build()
    val http = Http(okHttp)

    val settings = SettingsRepository(app)
    val servers = ServerRepository(app)
    val taste = TasteStore(app)
    val navidrome = NavidromeRepository(app)
    val messages = Messages()
    private val db = KultrDLDatabase.open(app)
    val library = Library(db, scope)
    val ytDlp = YtDlp(app, scope)

    val catalog = Catalog(
        http = http,
        extractor = ytDlp,
        country = { settings.settings.value.country.ifBlank { Locale.getDefault().country.ifBlank { "US" } } },
        spotifyCredentials = {
            val s = settings.settings.value
            if (s.spotifyClientId.isNotBlank() && s.spotifyClientSecret.isNotBlank()) s.spotifyClientId.trim() to s.spotifyClientSecret.trim() else null
        },
    )

    val resolver = StreamResolver(library, catalog, ytDlp, settings, scope, navidrome = { navidrome.client(http) }, http = okHttp)
    val saver = MediaSaver(app)
    val youtubeCheck = YouTubeCheck(ytDlp, okHttp, settings)
    val downloads = Downloads(app, db.downloads(), library, settings, servers, resolver, ytDlp, saver, okHttp, scope)
    val phone = PhoneMusic(app)
    val recommender = Recommender(app, library, db.owned(), settings, taste, navidrome, catalog, phone, http, scope).also { r ->
        downloads.afterSent = { r.afterUploads(it) }
    }
    val player = PlayerConnection(app, library, settings, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
}
