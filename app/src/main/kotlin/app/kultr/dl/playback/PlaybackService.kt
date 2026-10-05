package app.kultr.dl.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.kultr.dl.KultrDLApp
import app.kultr.dl.MainActivity
import app.kultr.dl.core.discover.Keys
import app.kultr.dl.data.db.PlayEntity
import app.kultr.dl.data.describe
import app.kultr.dl.engine.HlsConcatDataSource
import app.kultr.dl.engine.RoutingDataSource
import app.kultr.dl.engine.StreamResolver
import app.kultr.dl.engine.YouTubeProfile
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Background playback with a media notification, lock-screen and headset
 * controls. Queue entries are resolved as the player reaches them: a
 * downloaded file if there is one, otherwise the stream yt-dlp finds.
 * With Karousel on, similar music is added as the queue runs out.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val retried = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        val graph = KultrDLApp.graph
        val http = OkHttpDataSource.Factory(graph.okHttp)
        val routed = RoutingDataSource.Factory(DefaultDataSource.Factory(this, http), graph.okHttp)
        val resolving = ResolvingDataSource.Factory(routed) { spec -> resolve(spec, graph.resolver) }
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Buffer well ahead: once a song is loaded, the next one starts loading up to two and a half
            // minutes before it's due, so songs follow each other without a pause.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(90_000, 150_000, 1_000, 2_500)
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .build()
        player.addListener(Listener(player))

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(mediaItems.map(MediaItems::restore).toMutableList())
            })
            .build()

        // Karousel on: top the queue up now if it is about to end. Off: take its songs out again.
        scope.launch {
            graph.settings.settings.map { it.karousel }.distinctUntilChanged().drop(1).collect { on ->
                if (on) topUp(player) else dropKarousel(player)
            }
        }
    }

    private fun resolve(spec: DataSpec, resolver: StreamResolver): DataSpec {
        val trackId = MediaItems.trackId(spec.uri) ?: return spec
        // Anything thrown here reaches the player as a load error, never as a crash:
        // an Error escaping the loader thread would take the whole app down.
        val resolved = try {
            runBlocking { resolver.resolve(trackId) }
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't resolve $trackId", e)
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Couldn't resolve $trackId", e)
            throw IOException(describe(e), e)
        }
        Log.i(TAG, "Resolved $trackId: ${if (resolved is StreamResolver.Resolved.Local) "downloaded file" else "stream"}")
        return when (resolved) {
            is StreamResolver.Resolved.Local -> spec.withUri(resolved.uri)
            is StreamResolver.Resolved.Remote -> spec.buildUpon()
                .setUri(if (resolved.hls) HlsConcatDataSource.uriFor(resolved.url) else android.net.Uri.parse(resolved.url))
                .setHttpRequestHeaders(spec.httpRequestHeaders + resolved.headers)
                .build()
        }
    }

    /** How the song now loaded is being listened to, for the listening log. */
    private class Listen(val id: String, val artist: String, val title: String, val startedAt: Long) {
        var listenedMs = 0L
        var playingSince = 0L
        var durationMs: Long? = null
    }

    private var listen: Listen? = null

    /** Log the song that was loaded: how long it played, and whether it was finished or skipped. */
    private fun endListen(reason: Int?) {
        val l = listen ?: return
        listen = null
        val now = System.currentTimeMillis()
        if (l.playingSince > 0) l.listenedMs += now - l.playingSince
        val duration = l.durationMs
        val completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
            (duration != null && l.listenedMs >= duration * 0.8)
        val skipped = !completed && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
            l.listenedMs < minOf(30_000L, (duration ?: 60_000L) / 2)
        if (!skipped && l.listenedMs < 3_000) return
        val play = PlayEntity(
            trackId = l.id,
            artist = l.artist,
            title = l.title,
            startedAt = l.startedAt,
            listenedMs = l.listenedMs,
            durationMs = duration,
            completed = completed,
            skipped = skipped,
        )
        scope.launch { runCatching { KultrDLApp.graph.library.recordPlay(play) } }
    }

    private inner class Listener(private val player: ExoPlayer) : Player.Listener {
        /** When the player last moved on to the next song by itself, while it is still loading. */
        private var movedOnAt = 0L

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val graph = KultrDLApp.graph
            endListen(reason)
            movedOnAt = 0
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null) {
                if (player.playbackState == Player.STATE_READY) Log.i(TAG, "Moved on to ${mediaItem.mediaId} without a pause")
                else movedOnAt = System.currentTimeMillis()
            }
            val meta = mediaItem?.mediaMetadata
            // Songs by blocked artists (or with them on) are passed over, wherever they came from.
            if (mediaItem != null && graph.taste.blocks.value.blocks(meta?.artist?.toString().orEmpty(), meta?.title?.toString().orEmpty())) {
                Log.i(TAG, "Skipping ${mediaItem.mediaId}: blocked artist")
                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
                return
            }
            mediaItem?.let {
                listen = Listen(it.mediaId, meta?.artist?.toString().orEmpty(), meta?.title?.toString().orEmpty(), System.currentTimeMillis())
                    .apply { if (player.isPlaying) playingSince = System.currentTimeMillis() }
            }
            mediaItem?.mediaId?.let { id -> scope.launch { runCatching { graph.library.markPlayed(id) } } }
            readyAhead(player)
            topUp(player)
        }

        // Whatever changes what comes next (songs added, moved or removed, shuffle, repeat) gets it ready again.
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                readyAhead(player)
                topUp(player)
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = readyAhead(player)

        override fun onRepeatModeChanged(repeatMode: Int) {
            readyAhead(player)
            topUp(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error", error)
            val graph = KultrDLApp.graph
            val item = player.currentMediaItem ?: return
            val id = item.mediaId
            val position = player.currentPosition
            fun again() {
                player.seekTo(player.currentMediaItemIndex, position)
                player.prepare()
                player.play()
            }
            // YouTube refused this way of asking for the stream: try the next one.
            if (YouTubeProfile.isForbidden(error) && graph.resolver.tryNextProfile(id)) {
                Log.i(TAG, "YouTube refused the stream of $id; trying another client")
                again()
                return
            }
            // A stream link can expire or drop: look it up again once, from where it stopped.
            if (id !in retried) {
                retried += id
                graph.resolver.invalidate(id)
                again()
                return
            }
            val title = item.mediaMetadata.title ?: "this track"
            val reason = error.cause?.let(::describe) ?: error.errorCodeName
            graph.messages.error("Couldn't play $title: $reason")
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && movedOnAt > 0) {
                Log.i(TAG, "Moved on to ${player.currentMediaItem?.mediaId} after ${System.currentTimeMillis() - movedOnAt} ms loading")
                movedOnAt = 0
            }
            if (playbackState == Player.STATE_READY) player.duration.takeIf { it > 0 }?.let { listen?.durationMs = it }
            if (playbackState == Player.STATE_ENDED) {
                endListen(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                topUp(player)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listen?.let { l ->
                val now = System.currentTimeMillis()
                if (isPlaying) {
                    if (l.playingSince == 0L) l.playingSince = now
                } else if (l.playingSince > 0) {
                    l.listenedMs += now - l.playingSince
                    l.playingSince = 0
                }
            }
            if (isPlaying) {
                player.currentMediaItem?.let { item ->
                    retried.remove(item.mediaId)
                    KultrDLApp.graph.resolver.onPlaying(item.mediaId)
                    Log.i(TAG, "Playing ${item.mediaId}")
                }
            }
        }
    }

    /** The next two songs, in the order they'll play, made ready to start at once (see [StreamResolver.prefetch]). */
    private fun readyAhead(player: Player) {
        if (player.mediaItemCount < 2 || player.repeatMode == Player.REPEAT_MODE_ONE) return
        val next = player.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return
        val after = player.currentTimeline.getNextWindowIndex(next, player.repeatMode, player.shuffleModeEnabled)
        listOf(next, after)
            .filter { it != C.INDEX_UNSET && it != player.currentMediaItemIndex }
            .distinct()
            .forEach { KultrDLApp.graph.resolver.prefetch(player.getMediaItemAt(it).mediaId) }
    }

    // ------------------------------------------------------------- Karousel --

    private var topping: Job? = null

    /** The song Karousel last found nothing for, so it doesn't ask again for the same one. */
    private var nothingFor: String? = null

    private fun karouselOn() = KultrDLApp.graph.settings.settings.value.karousel

    /** Queue positions in the order they play from the current one: after it ([forward]) or before it, nearest first. */
    private fun around(player: Player, forward: Boolean): List<Int> {
        val t = player.currentTimeline
        if (t.isEmpty || player.currentMediaItemIndex == C.INDEX_UNSET) return emptyList()
        val out = ArrayList<Int>()
        fun step(i: Int) = if (forward) t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        else t.getPreviousWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        var i = step(player.currentMediaItemIndex)
        while (i != C.INDEX_UNSET && out.size < t.windowCount) {
            out += i
            i = step(i)
        }
        return out
    }

    /**
     * With Karousel on, when the song playing is the last or next to last (or the
     * queue has ended), adds music like what has been playing, and carries on
     * playing if it had stopped. Repeat keeps the queue going by itself, so
     * Karousel waits while it's on.
     */
    private fun topUp(player: ExoPlayer) {
        if (!karouselOn() || player.repeatMode != Player.REPEAT_MODE_OFF || player.mediaItemCount == 0) return
        if (topping?.isActive == true) return
        val ended = player.playbackState == Player.STATE_ENDED
        if (!ended && around(player, forward = true).size > 1) return
        val current = player.currentMediaItem ?: return
        if (nothingFor == current.mediaId) return
        val graph = KultrDLApp.graph
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        // The song playing and the ones before it, and a couple the user queued themselves, to stay close to where the music started.
        val recent = listOf(current.mediaId) + around(player, forward = false).take(4).map { player.getMediaItemAt(it).mediaId }
        val chosen = items.filterNot(MediaItems::isKarousel).map { it.mediaId }.shuffled().take(2)
        val queued = items.map { Keys.track(it.mediaMetadata.artist?.toString().orEmpty(), it.mediaMetadata.title?.toString().orEmpty()) }.toSet()
        topping = scope.launch {
            val more = try {
                val seeds = (recent + chosen).distinct().mapNotNull { graph.library.track(it) }
                graph.recommender.karousel(seeds, queued)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Karousel: ${describe(e)}", e)
                emptyList()
            }
            if (!karouselOn() || player.mediaItemCount == 0) return@launch
            if (more.isEmpty()) {
                nothingFor = current.mediaId
                Log.i(TAG, "Karousel: found nothing to add")
                return@launch
            }
            nothingFor = null
            withContext(Dispatchers.IO) { graph.library.remember(more) }
            val start = player.mediaItemCount
            val stopped = player.playbackState == Player.STATE_ENDED
            player.addMediaItems(more.map { MediaItems.from(it, karousel = true) })
            if (player.shuffleModeEnabled) playLast(player, start, more.size)
            Log.i(TAG, "Karousel: queued ${more.size} songs: ${more.take(3).joinToString { "${it.artist} – ${it.title}" }}…")
            if (stopped) {
                player.seekTo(start, 0)
                player.prepare()
                player.play()
            }
        }
    }

    /**
     * Shuffled play gives added songs random places in the order, some among the
     * songs already played; Karousel's songs go after everything else instead.
     */
    private fun playLast(player: ExoPlayer, start: Int, count: Int) {
        val t = player.currentTimeline
        val order = ArrayList<Int>(t.windowCount)
        var i = t.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && order.size < t.windowCount) {
            if (i < start) order += i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        order += start until start + count
        if (order.size == player.mediaItemCount) player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
    }

    /** Karousel off: its songs that haven't played yet leave the queue. */
    private fun dropKarousel(player: ExoPlayer) {
        topping?.cancel()
        nothingFor = null
        val upcoming = around(player, forward = true).toSet()
        (player.mediaItemCount - 1 downTo 0)
            .filter { it in upcoming && MediaItems.isKarousel(player.getMediaItemAt(it)) }
            .forEach(player::removeMediaItem)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    companion object {
        private const val TAG = "KultrDL"
    }

    override fun onDestroy() {
        endListen(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
