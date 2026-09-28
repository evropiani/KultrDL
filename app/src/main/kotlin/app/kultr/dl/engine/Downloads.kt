package app.kultr.dl.engine

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kultr.dl.KultrDLApp
import app.kultr.dl.MainActivity
import app.kultr.dl.R
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.Text
import app.kultr.dl.data.Library
import app.kultr.dl.data.SettingsRepository
import app.kultr.dl.data.db.DownloadDao
import app.kultr.dl.data.db.DownloadEntity
import app.kultr.dl.data.db.DownloadState
import app.kultr.dl.data.db.DownloadWithTrack
import app.kultr.dl.data.describe
import com.yausername.youtubedl_android.YoutubeDLException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Live progress of the download that is running. */
data class DownloadProgress(val trackId: String, val fraction: Float, val stage: String)

/**
 * The download queue: rows in the database, worked through one at a time
 * by [DownloadWorker]. Each download fetches the best audio with yt-dlp,
 * converts it with ffmpeg to the chosen format and quality, writes the
 * track's tags and cover, and saves it to Music/KultrDL.
 */
class Downloads(
    private val context: Context,
    private val dao: DownloadDao,
    private val library: Library,
    private val settings: SettingsRepository,
    private val resolver: StreamResolver,
    private val ytDlp: YtDlp,
    private val saver: MediaSaver,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    val all: Flow<List<DownloadWithTrack>> = dao.all()
    val activeCount: Flow<Int> = dao.activeCount()

    private val live = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = live

    suspend fun enqueue(tracks: List<Track>, preset: DownloadPreset = settings.settings.value.download) {
        if (tracks.isEmpty()) return
        library.remember(tracks)
        val now = System.currentTimeMillis()
        for (t in tracks.distinctBy { it.id }) {
            val existing = dao.get(t.id)
            if (existing != null && existing.downloadState in setOf(DownloadState.QUEUED, DownloadState.RUNNING)) continue
            dao.upsert(
                DownloadEntity(
                    trackId = t.id,
                    state = DownloadState.QUEUED.name,
                    format = preset.format.name,
                    quality = preset.format.normalise(preset.quality).name,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        start()
    }

    /** Make sure a worker is running the queue. */
    fun start() {
        val network = if (settings.settings.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel(trackId: String) = scope.launch {
        dao.setState(trackId, DownloadState.CANCELLED.name, 0f, null)
        ytDlp.cancel(processId(trackId))
    }

    fun retry(trackId: String) = scope.launch {
        dao.setState(trackId, DownloadState.QUEUED.name, 0f, null)
        start()
    }

    fun retryFailed() = scope.launch {
        dao.retryFailed()
        start()
    }

    fun remove(trackId: String) = scope.launch {
        ytDlp.cancel(processId(trackId))
        dao.delete(trackId)
    }

    fun clearFinished() = scope.launch { dao.clearFinished() }

    /** Delete the downloaded file (the track stays in the library). */
    suspend fun deleteFile(trackId: String) {
        val uri = library.entity(trackId)?.localUri ?: return
        saver.delete(uri)
        library.setLocal(trackId, null, null, null)
        dao.delete(trackId)
    }

    /** Called when the app starts: a download cut off by the system goes back in the queue. */
    fun recover() = scope.launch {
        dao.requeueInterrupted()
        if (dao.nextQueued() != null) start()
    }

    private fun processId(trackId: String) = "dl:$trackId"

    // ------------------------------------------------------------ one job --

    internal suspend fun runQueue(worker: DownloadWorker) {
        var done = 0
        while (true) {
            val row = dao.nextQueued() ?: break
            dao.setState(row.trackId, DownloadState.RUNNING.name, 0f, "Starting…")
            val title = library.track(row.trackId)?.title ?: "Track"
            worker.notify(title, 0f, done)
            val finished = done
            try {
                process(row) { fraction, stage ->
                    live.value = DownloadProgress(row.trackId, fraction, stage)
                    scope.launch { worker.notify(title, fraction, finished) }
                }
                dao.setState(row.trackId, DownloadState.DONE.name, 1f, null)
                done++
            } catch (e: CancellationException) {
                val current = dao.get(row.trackId)
                if (current?.downloadState == DownloadState.CANCELLED) continue
                dao.setState(row.trackId, DownloadState.QUEUED.name, 0f, null)
                throw e
            } catch (e: Throwable) {
                Log.w("KultrDL", "Download of ${row.trackId} failed", e)
                val current = dao.get(row.trackId)
                if (current?.downloadState != DownloadState.CANCELLED && current != null) {
                    dao.setState(row.trackId, DownloadState.FAILED.name, 0f, describe(e))
                }
            } finally {
                live.update { if (it?.trackId == row.trackId) null else it }
            }
        }
    }

    private suspend fun process(row: DownloadEntity, onProgress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        val track = library.track(row.trackId) ?: throw IOException("The track is no longer in the library.")
        val preset = DownloadPreset(
            runCatching { AudioFormat.valueOf(row.format) }.getOrDefault(AudioFormat.MP3),
            runCatching { Quality.valueOf(row.quality) }.getOrDefault(Quality.K320),
        )
        val options = settings.settings.value
        onProgress(0f, "Finding the recording…")
        val source = resolver.sourceUrl(track)

        val dir = File(context.cacheDir, "downloads/${Text.fileName(row.trackId, 60)}")
        dir.deleteRecursively()
        dir.mkdirs()
        try {
            val common = preset.ytDlpOptions() + listOf(
                "--no-playlist",
                "--newline",
                "--no-mtime",
                "--retries", "5",
                "--fragment-retries", "10",
                "--embed-metadata",
                "--no-embed-chapters",
                "--no-embed-info-json",
                "--postprocessor-args", "Metadata+ffmpeg_o:" + metadataArgs(track),
                "-o", File(dir, "audio.%(ext)s").absolutePath,
            )
            // YouTube may refuse one way of asking for the stream (HTTP 403) and accept another.
            val profiles: List<YouTubeProfile?> =
                if (YouTubeProfile.isYouTube(source)) options.youtubeProfile.order() else listOf(null)
            for ((attempt, profile) in profiles.withIndex()) {
                try {
                    ytDlp.run(common + profile?.args.orEmpty() + source, processId(row.trackId), progress = { percent, _, line ->
                        val fraction = (percent / 100f).coerceIn(0f, 1f)
                        val stage = if (line.contains("[ExtractAudio]") || line.contains("[Metadata]")) "Converting…" else "Downloading…"
                        onProgress(fraction * 0.9f, stage)
                        scope.launch { dao.setProgress(row.trackId, fraction * 0.9f, stage) }
                    })
                    if (profile != null) resolver.remember(profile)
                    break
                } catch (e: YoutubeDLException) {
                    if (profile == null || attempt == profiles.lastIndex || !YouTubeProfile.isForbidden(e)) throw e
                    Log.i("KultrDL", "YouTube refused ${profile.label} for ${row.trackId}; trying ${profiles[attempt + 1]?.label}")
                    dir.listFiles()?.forEach { it.delete() }
                    onProgress(0f, "Trying another way…")
                }
            }
            val file = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("audio.") && it.extension.lowercase() !in SIDE_FILES }
                ?.maxByOrNull { it.length() }
                ?: throw IOException("yt-dlp finished without an audio file.")

            onProgress(0.93f, "Writing tags…")
            val cover = if (options.embedArtwork) track.artworkUrl?.let { fetchCover(it) } else null
            runCatching { Tagger.tag(file, track, cover) }

            onProgress(0.97f, "Saving…")
            val ext = file.extension.lowercase()
            val name = Text.fileName("${track.artist} - ${track.title}") + "." + ext
            val previous = library.entity(track.id)?.localUri
            val saved = saver.save(file, name, AudioFormat.mimeFor(ext), options.saveToMusic, track.title, track.artist, track.album)
            if (previous != null && previous != saved.uri.toString()) saver.delete(previous)
            library.setLocal(track.id, saved.uri.toString(), preset.label, saved.size)
            Log.i("KultrDL", "Downloaded ${track.id} as ${preset.label}: ${saved.uri} (${saved.size} bytes)")
            dao.setProgress(row.trackId, 1f, null)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun fetchCover(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            Tagger.squareJpeg(r.body.bytes())
        }
    }.getOrNull()

    companion object {
        const val WORK_NAME = "kultrdl-downloads"
        private val SIDE_FILES = setOf("part", "ytdl", "json", "jpg", "jpeg", "png", "webp", "temp", "tmp")

        private fun quote(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"

        /** ffmpeg options that set the tags from the track (catalogue details win over YouTube's). */
        fun metadataArgs(track: Track): String {
            val fields = listOf(
                "title" to track.title,
                "artist" to track.artist,
                "album" to track.album.orEmpty(),
                "album_artist" to track.albumArtist.orEmpty(),
                "date" to track.year?.toString().orEmpty(),
                "track" to track.trackNumber?.toString().orEmpty(),
                "disc" to track.discNumber?.toString().orEmpty(),
                "genre" to track.genre.orEmpty(),
                "comment" to "",
                "description" to "",
                "synopsis" to "",
                "purl" to "",
            )
            return fields.joinToString(" ") { (key, value) -> "-metadata " + quote("$key=$value") }
        }
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var lastNotified = 0L

    override suspend fun doWork(): Result {
        val downloads = KultrDLApp.graph.downloads
        runCatching { setForeground(foregroundInfo("Preparing downloads…", null, 0)) }
        return try {
            downloads.runQueue(this)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing downloads…", null, 0)

    internal suspend fun notify(title: String, fraction: Float, done: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 800 && fraction > 0f && fraction < 1f) return
        lastNotified = now
        runCatching { setForeground(foregroundInfo(title, fraction, done)) }
    }

    private fun foregroundInfo(title: String, fraction: Float?, done: Int): ForegroundInfo {
        val notification = notification(applicationContext, title, fraction, done)
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL = "downloads"

        fun notification(context: Context, title: String, fraction: Float?, done: Int): Notification {
            val open = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_kultrdl)
                .setContentTitle(title)
                .setContentText(if (done > 0) "$done finished" else "Downloading")
                .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
        }
    }
}
