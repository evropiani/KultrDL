package app.kultr.dl.engine

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import app.kultr.dl.BuildConfig
import app.kultr.dl.data.describe as reason
import app.kultr.dl.core.links.LinkTarget
import app.kultr.dl.core.links.Links
import app.kultr.dl.core.links.YtDlpJson
import app.kultr.dl.core.model.LinkResult
import app.kultr.dl.core.model.MediaExtractor
import app.kultr.dl.core.model.Track
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

    private val permits = Semaphore(3)
    private val initLock = Mutex()
    @Volatile private var initialised = false
    private val state = MutableStateFlow<Status>(Status.Starting)
    val status: StateFlow<Status> = state

    /** Unpack Python, yt-dlp and ffmpeg in the background (the first launch takes a while). */
    fun start() {
        scope.launch(Dispatchers.IO) {
            runCatching { ensureReady() }
            if (initialised) selfCheck()
        }
    }

    /**
     * Initialise once; after a failure the next call tries again, so a
     * problem that has gone away (storage was full, say) doesn't need a restart.
     */
    private suspend fun ensureReady() {
        if (initialised) return
        initLock.withLock {
            if (initialised) return
            try {
                state.value = Status.Starting
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                initialised = true
                state.value = Status.Ready(version())
                Log.i(TAG, "yt-dlp unpacked, version ${version()}")
            } catch (e: Throwable) {
                Log.e(TAG, "yt-dlp failed to start", e)
                state.value = Status.Failed(reason(e))
                throw IOException("The download engine couldn't start: ${reason(e)}", e)
            }
        }
    }

    /**
     * After an install or update, run yt-dlp once, so a broken engine shows up
     * in Settings (and in the log) straight away rather than at the first song.
     */
    private suspend fun selfCheck() {
        val prefs = context.getSharedPreferences("kultrdl.engine", Context.MODE_PRIVATE)
        val build = BuildConfig.VERSION_CODE
        if (prefs.getInt("checkedBuild", -1) == build) return
        try {
            val out = run(listOf("--version")).out.trim()
            Log.i(TAG, "yt-dlp runs: $out")
            prefs.edit { putInt("checkedBuild", build) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "yt-dlp doesn't run", e)
            state.value = Status.Failed(reason(e))
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
        ensureReady()
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
        // A video opened from inside a playlist or mix is just that video.
        val single = Links.classify(url).kind == LinkTarget.Kind.TRACK
        val mode = if (single) listOf("--no-playlist") else listOf("--flat-playlist", "--playlist-end", "500")
        val out = run(listOf("-J") + mode + url).out
        return YtDlpJson.parse(out)
    }

    /** Fetch the newest yt-dlp release. Returns the version now installed. */
    suspend fun update(): String? = withContext(Dispatchers.IO) {
        ensureReady()
        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
        version().also { state.value = Status.Ready(it) }
    }

    private companion object {
        const val TAG = "KultrDL"
    }
}
