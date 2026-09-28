package app.kultr.dl

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import app.kultr.dl.engine.DownloadWorker
import app.kultr.dl.engine.YtDlp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class KultrDLApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        createChannels()
        graph.ytDlp.start()
        graph.downloads.recover()
        keepEngineFresh()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.okHttp })) }
            .crossfade(true)
            .build()

    private fun createChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(DownloadWorker.CHANNEL, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** YouTube changes often; a day-old yt-dlp is checked against the newest release. */
    private fun keepEngineFresh() {
        graph.scope.launch {
            graph.ytDlp.status.first { it !is YtDlp.Status.Starting }
            val settings = graph.settings.settings.value
            val now = System.currentTimeMillis()
            if (!settings.autoUpdateEngine || now - settings.lastEngineCheck < 24 * 3600_000L) return@launch
            delay(8_000)
            runCatching { graph.ytDlp.update() }
                .onSuccess { Log.i("KultrDL", "yt-dlp is now $it") }
                .onFailure { Log.w("KultrDL", "yt-dlp update failed", it) }
            graph.settings.update { it.copy(lastEngineCheck = now) }
        }
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
