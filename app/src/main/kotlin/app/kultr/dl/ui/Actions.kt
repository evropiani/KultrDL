package app.kultr.dl.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.kultr.dl.AppGraph
import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.Track
import app.kultr.dl.data.Destination
import app.kultr.dl.data.MessageKind
import app.kultr.dl.data.db.DownloadState
import app.kultr.dl.data.db.TrackFlags
import app.kultr.dl.data.describe
import app.kultr.dl.engine.DownloadPreset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library?tab={tab}"
    const val SEARCH = "search"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val COLLECTION = "collection/{id}"
    const val PLAYLIST = "playlist/{id}"
    const val SERVERS = "servers"
    const val SERVER = "server/{id}"
    const val NEW = "new"

    fun library(tab: String? = null) = if (tab == null) "library" else "library?tab=$tab"
    fun server(id: String) = "server/" + Uri.encode(id)
    fun collection(id: String) = "collection/" + Uri.encode(id)
    fun playlist(id: Long) = "playlist/$id"
}

/** Albums and playlists opened from search results, looked up by id when their page opens. */
object CollectionCache {
    private val items = ConcurrentHashMap<String, Collection>()

    fun put(collection: Collection) {
        items[collection.id] = collection
    }

    fun get(id: String): Collection? = items[id]
}

@Stable
class Dialogs {
    var addToPlaylist by mutableStateOf<List<Track>?>(null)
    var downloadAs by mutableStateOf<List<Track>?>(null)
    var sendTo by mutableStateOf<List<Track>?>(null)
}

/** What a download row in the queue looks like to a track list. */
data class DownloadBadge(val state: DownloadState, val progress: Float)

val LocalTrackFlags = staticCompositionLocalOf<Map<String, TrackFlags>> { emptyMap() }
val LocalDownloadBadges = staticCompositionLocalOf<Map<String, DownloadBadge>> { emptyMap() }
val LocalActions = staticCompositionLocalOf<AppActions> { error("No actions") }

/** Everything a screen can ask the app to do. */
class AppActions(
    val graph: AppGraph,
    val scope: CoroutineScope,
    val context: Context,
    val navigate: (String) -> Unit,
    val back: () -> Unit,
    val openPlayer: () -> Unit,
    val search: (String) -> Unit,
    val dialogs: Dialogs,
    private val askStoragePermission: () -> Unit,
) {
    private val messages get() = graph.messages

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messages.error(describe(e))
        }
    }

    fun play(tracks: List<Track>, index: Int = 0) = graph.player.play(tracks, index)

    fun shuffle(tracks: List<Track>) = graph.player.play(tracks, 0, shuffle = true)

    fun playNext(tracks: List<Track>) {
        graph.player.playNext(tracks)
        messages.show(if (tracks.size == 1) "“${tracks[0].title}” plays next" else "${tracks.size} tracks play next")
    }

    fun enqueue(tracks: List<Track>) {
        graph.player.enqueue(tracks)
        messages.show(if (tracks.size == 1) "Added “${tracks[0].title}” to the queue" else "Added ${tracks.size} tracks to the queue")
    }

    fun isFavorite(track: Track): Boolean = graph.library.flags.value[track.id]?.favorite == true

    fun isSaved(track: Track): Boolean = graph.library.flags.value[track.id]?.saved == true

    fun toggleFavorite(track: Track) = setFavorite(listOf(track), !isFavorite(track))

    fun setFavorite(tracks: List<Track>, on: Boolean) = launch { graph.library.setFavorite(tracks, on) }

    fun toggleSaved(track: Track) {
        val on = !isSaved(track)
        setSaved(listOf(track), on)
    }

    fun setSaved(tracks: List<Track>, on: Boolean) = launch {
        graph.library.setSaved(tracks, on)
        messages.show(
            when {
                !on -> "Removed from your library"
                tracks.size == 1 -> "Saved to your library"
                else -> "Saved ${tracks.size} tracks to your library"
            },
            MessageKind.SUCCESS,
        )
    }

    fun addToPlaylist(tracks: List<Track>) {
        if (tracks.isNotEmpty()) dialogs.addToPlaylist = tracks
    }

    /** Download with the default format, or ask when that is the setting. */
    fun download(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (graph.settings.settings.value.askEachTime) dialogs.downloadAs = tracks else download(tracks, graph.settings.settings.value.download)
    }

    fun downloadAs(tracks: List<Track>) {
        if (tracks.isNotEmpty()) dialogs.downloadAs = tracks
    }

    fun download(tracks: List<Track>, preset: DownloadPreset, destination: Destination? = graph.settings.settings.value.destination) {
        val server = destination?.let { graph.servers.get(it.serverId) }
        val onPhone = destination == null || server == null || destination.keepOnPhone
        if (onPhone && graph.saver.needsPermission(graph.settings.settings.value.saveToMusic)) askStoragePermission()
        launch {
            graph.downloads.enqueue(tracks, preset, destination)
            val what = if (tracks.size == 1) "“${tracks[0].title}”" else "${tracks.size} tracks"
            messages.show("Downloading $what · ${preset.label}" + (server?.let { " → ${it.name}" } ?: ""))
        }
    }

    /** Asks which server folder, then sends tracks that are on the phone there. */
    fun sendToServer(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (graph.servers.servers.value.isEmpty()) navigate(Routes.server(Routes.NEW)) else dialogs.sendTo = tracks
    }

    fun send(tracks: List<Track>, destination: Destination) = launch {
        val count = graph.downloads.send(tracks, destination)
        val name = graph.servers.get(destination.serverId)?.name ?: "the server"
        messages.show(
            when {
                count == 0 -> "Nothing to send: download it to the phone first"
                count == 1 && tracks.size == 1 -> "Sending “${tracks.first().title}” to $name"
                else -> "Sending $count ${if (count == 1) "track" else "tracks"} to $name"
            },
        )
    }

    fun removeDownload(track: Track) = launch {
        graph.downloads.deleteFile(track.id)
        messages.show("Removed the download of “${track.title}”")
    }

    fun rematch(track: Track) = launch {
        graph.resolver.rematch(track.id)
        messages.show("“${track.title}” will be matched again next time it plays")
    }

    fun openCollection(collection: Collection) {
        CollectionCache.put(collection)
        navigate(Routes.collection(collection.id))
    }

    fun openPlaylist(id: Long) = navigate(Routes.playlist(id))

    fun openDownloads() = navigate(Routes.DOWNLOADS)

    fun share(track: Track) {
        val url = track.pageUrl ?: track.streamUrl ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "${track.artist} – ${track.title}\n$url")
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openInBrowser(url: String?) {
        url ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            messages.show("No app can open that link.")
        }
    }
}
