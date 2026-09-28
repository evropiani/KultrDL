package app.kultr.dl.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.data.Library
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QueueEntry(val index: Int, val track: Track) {
    val key: String get() = "$index:${track.id}"
}

data class PlayerUiState(
    val current: Track? = null,
    val index: Int = -1,
    val queue: List<Track> = emptyList(),
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
) {
    val upNext: List<QueueEntry>
        get() = if (index < 0) emptyList() else queue.drop(index + 1).mapIndexed { i, t -> QueueEntry(index + 1 + i, t) }
}

/**
 * The interface's hold on the playback service: a MediaController, and
 * its state as a flow for Compose.
 */
class PlayerConnection(private val context: Context, private val library: Library, private val scope: CoroutineScope) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private val state = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = state

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh()
            pending.toList().forEach { it(c) }
            pending.clear()
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            action(c)
        } else {
            pending += action
            connect()
        }
    }

    private fun trackOf(item: MediaItem): Track {
        val m = item.mediaMetadata
        return Track(
            id = item.mediaId,
            source = runCatching { Source.valueOf(m.extras?.getString("kultrdl.source") ?: "") }.getOrDefault(Source.WEB),
            title = m.title?.toString() ?: "Unknown",
            artist = m.artist?.toString() ?: "",
            album = m.albumTitle?.toString(),
            albumArtist = m.albumArtist?.toString(),
            artworkUrl = m.artworkUri?.toString(),
            trackNumber = m.trackNumber,
        )
    }

    private fun refresh() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).map { trackOf(c.getMediaItemAt(it)) }
        val index = c.currentMediaItemIndex.takeIf { queue.isNotEmpty() } ?: -1
        val current = queue.getOrNull(index)
        state.value = PlayerUiState(
            current = current?.let { t -> state.value.current?.takeIf { it.id == t.id && it.durationMs != null } ?: t },
            index = index,
            queue = queue,
            playWhenReady = c.playWhenReady,
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            ended = c.playbackState == Player.STATE_ENDED,
            durationMs = c.duration.takeIf { it > 0 } ?: current?.durationMs ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
    }

    fun positionMs(): Long = controller?.currentPosition ?: 0

    private fun remembered(tracks: List<Track>, then: (List<MediaItem>) -> Unit) {
        // The controller lives on the main thread; only the database work leaves it.
        scope.launch(Dispatchers.Main.immediate) {
            withContext(Dispatchers.IO) { library.remember(tracks) }
            then(tracks.map(MediaItems::from))
        }
    }

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        remembered(tracks) { items ->
            withController { c ->
                c.shuffleModeEnabled = shuffle
                c.setMediaItems(items, if (shuffle) (items.indices).random() else startIndex.coerceIn(0, items.lastIndex), 0)
                c.prepare()
                c.play()
            }
        }
    }

    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        remembered(tracks) { items ->
            withController { c ->
                if (c.mediaItemCount == 0) {
                    c.setMediaItems(items)
                    c.prepare()
                    c.play()
                } else {
                    c.addMediaItems(c.currentMediaItemIndex + 1, items)
                }
            }
        }
    }

    fun enqueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        remembered(tracks) { items ->
            withController { c ->
                val empty = c.mediaItemCount == 0
                c.addMediaItems(items)
                if (empty) {
                    c.prepare()
                    c.play()
                }
            }
        }
    }

    fun toggle() = withController { c ->
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playWhenReady) c.pause() else c.play()
    }

    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }

    fun previous() = withController { c ->
        if (c.currentPosition > 4000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun jumpTo(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0)
            c.play()
        }
    }

    fun remove(index: Int) = withController { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) }

    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }

    fun clearUpcoming() = withController { c ->
        val start = c.currentMediaItemIndex + 1
        if (start < c.mediaItemCount) c.removeMediaItems(start, c.mediaItemCount)
    }

    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stop() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }
}
