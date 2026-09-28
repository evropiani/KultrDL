package app.kultr.dl.engine

import android.content.Context
import app.kultr.dl.core.links.YtDlpJson
import app.kultr.dl.core.model.LinkResult
import app.kultr.dl.core.model.MediaExtractor
import app.kultr.dl.core.model.Track
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * yt-dlp with its own Python, QuickJS and ffmpeg (youtubedl-android).
 * Everything that reads a streaming site goes through here: finding the
 * audio stream to play, downloading and converting, and reading links.
 * yt-dlp can update itself, so a site change is fixed without a new app.
 */
class YtDlp(private val context: Context, private val scope: CoroutineScope) : MediaExtractor {
    sealed interface Status {
        data object Starting : Status
        data class Ready(val version: String?) : Status
        data class Failed(val message: String) : Status
    }

    private val ready = CompletableDeferred<Unit>()
    private val permits = Semaphore(3)
    private val state = MutableStateFlow<Status>(Status.Starting)
    val status: StateFlow<Status> = state

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                state.value = Status.Ready(version())
                ready.complete(Unit)
            } catch (e: Throwable) {
                state.value = Status.Failed(e.message ?: e.toString())
                ready.completeExceptionally(e)
            }
        }
    }

    fun version(): String? = runCatching {
        YoutubeDL.getInstance().versionName(context) ?: YoutubeDL.getInstance().version(context)
    }.getOrNull()

    private val cacheDir: File get() = File(context.cacheDir, "yt-dlp").apply { mkdirs() }

    /**
     * Run yt-dlp with [args] (options and URLs, as on a command line).
     * Cancelling the coroutine stops the process.
     */
    suspend fun run(
        args: List<String>,
        processId: String = UUID.randomUUID().toString(),
        progress: ((Float, Long, String) -> Unit)? = null,
    ): YoutubeDLResponse = withContext(Dispatchers.IO) {
        ready.await()
        permits.withPermit {
            val request = YoutubeDLRequest(emptyList<String>())
            request.addCommands(listOf("--cache-dir", cacheDir.absolutePath, "--no-warnings", "--socket-timeout", "20") + args)
            try {
                runInterruptible { YoutubeDL.getInstance().execute(request, processId, progress) }
            } catch (e: CancellationException) {
                cancel(processId)
                throw e
            } catch (e: InterruptedException) {
                cancel(processId)
                throw CancellationException("Stopped")
            }
        }
    }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    /** yt-dlp's JSON description of one link. */
    suspend fun describe(url: String, vararg options: String): String =
        run(listOf("-J", "--no-playlist", *options, url)).out

    override suspend fun search(prefix: String, query: String, limit: Int): List<Track> {
        val out = run(listOf("-J", "--flat-playlist", "$prefix$limit:$query")).out
        return YtDlpJson.parseSearch(out)
    }

    override suspend fun extract(url: String): LinkResult? {
        val out = run(listOf("-J", "--flat-playlist", "--playlist-end", "500", url)).out
        return YtDlpJson.parse(out)
    }

    /** Fetch the newest yt-dlp release. Returns the version now installed. */
    suspend fun update(): String? = withContext(Dispatchers.IO) {
        ready.await()
        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
        version().also { state.value = Status.Ready(it) }
    }
}
