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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.kultr.dl.KultrDLApp
import app.kultr.dl.MainActivity
import app.kultr.dl.data.db.PlayEntity
import app.kultr.dl.data.describe
import app.kultr.dl.engine.HlsConcatDataSource
import app.kultr.dl.engine.RoutingDataSource
import app.kultr.dl.engine.StreamResolver
import app.kultr.dl.engine.YouTubeProfile
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Background playback with a media notification, lock-screen and headset
 * controls. Queue entries are resolved as the player reaches them: a
 * downloaded file if there is one, otherwise the stream yt-dlp finds.
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
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val graph = KultrDLApp.graph
            endListen(reason)
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
            // Have the next track's stream ready before it is needed.
            val next = player.nextMediaItemIndex
            if (next != C.INDEX_UNSET) graph.resolver.prefetch(player.getMediaItemAt(next).mediaId)
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
            if (playbackState == Player.STATE_READY) player.duration.takeIf { it > 0 }?.let { listen?.durationMs = it }
            if (playbackState == Player.STATE_ENDED) endListen(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
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
