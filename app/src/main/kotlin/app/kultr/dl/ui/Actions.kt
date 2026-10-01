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
    const val NAVIDROME = "navidrome"
    const val BLOCKED = "blocked"

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
    /** "Block artist…" for this track: who to block among its credits. */
    var block by mutableStateOf<Track?>(null)
}

/** What a download row in the queue looks like to a track list. */
data class DownloadBadge(val state: DownloadState, val progress: Float)

val LocalTrackFlags = staticCompositionLocalOf<Map<String, TrackFlags>> { emptyMap() }
val LocalDownloadBadges = staticCompositionLocalOf<Map<String, DownloadBadge>> { emptyMap() }
/** Blocked artists: their songs, and songs they are on, are left out of every list. */
val LocalBlocks = staticCompositionLocalOf { app.kultr.dl.core.taste.ArtistBlocks.NONE }
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

    /** Songs by blocked artists (or with them on) never reach the player. */
    private fun allowed(tracks: List<Track>): List<Track> {
        val kept = graph.taste.blocks.value.tracks(tracks)
        if (kept.isEmpty() && tracks.isNotEmpty()) messages.show("All of these are by artists you blocked.")
        return kept
    }

    fun play(tracks: List<Track>, index: Int = 0) {
        val start = tracks.getOrNull(index)
        val kept = allowed(tracks)
        if (kept.isEmpty()) return
        graph.player.play(kept, start?.let { s -> kept.indexOfFirst { it.id == s.id }.takeIf { it >= 0 } } ?: 0)
    }

    fun shuffle(tracks: List<Track>) {
        val kept = allowed(tracks)
        if (kept.isNotEmpty()) graph.player.play(kept, 0, shuffle = true)
    }

    fun playNext(tracks: List<Track>) {
        val kept = allowed(tracks)
        if (kept.isEmpty()) return
        graph.player.playNext(kept)
        messages.show(if (tracks.size == 1) "“${tracks[0].title}” plays next" else "${tracks.size} tracks play next")
    }

    fun enqueue(tracks: List<Track>) {
        val kept = allowed(tracks)
        if (kept.isEmpty()) return
        graph.player.enqueue(kept)
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
        val wanted = allowed(tracks).filterNot { it.source == app.kultr.dl.core.model.Source.PHONE }
        if (wanted.isEmpty()) {
            if (tracks.isNotEmpty() && tracks.all { it.source == app.kultr.dl.core.model.Source.PHONE }) messages.show("That's already on this phone.")
            return
        }
        @Suppress("NAME_SHADOWING") val tracks = wanted
        val server = destination?.let { graph.servers.get(it.serverId) }
        val onPhone = destination == null || server == null || destination.keepOnPhone
        if (onPhone && graph.saver.needsPermission(graph.settings.settings.value.saveToMusic)) askStoragePermission()
        launch {
            graph.downloads.enqueue(tracks, preset, destination)
            val what = if (tracks.size == 1) "“${tracks[0].title}”" else "${tracks.size} tracks"
            messages.show("Downloading $what · ${preset.label}" + (server?.let { " → ${it.name}" } ?: ""))
        }
    }

    /** Download straight into Navidrome's music folder (set in Settings → Recommendations → Navidrome). */
    fun downloadToNavidrome(tracks: List<Track>) {
        val target = graph.navidrome.config.value.destination
        if (target == null || graph.servers.get(target.serverId) == null) {
            messages.show("Choose Navidrome's music folder first.")
            navigate(Routes.NAVIDROME)
            return
        }
        download(tracks, graph.settings.settings.value.download, target.copy(keepOnPhone = false))
    }

    fun canDownloadToNavidrome(): Boolean = graph.navidrome.config.value.destination?.let { graph.servers.get(it.serverId) } != null

    // ------------------------------------------------------------ taste --

    fun blockArtist(track: Track) {
        val people = app.kultr.dl.core.taste.Credits.people(track.artist, track.title)
        if (people.size <= 1) block(people.ifEmpty { listOf(track.artist) }) else dialogs.block = track
    }

    fun block(names: List<String>) {
        if (names.isEmpty()) return
        graph.taste.block(names)
        messages.show(
            (if (names.size == 1) "Blocked ${names[0]}" else "Blocked ${names.joinToString()}") +
                ". Their songs, and songs they're on, are hidden and skipped.",
            MessageKind.SUCCESS,
            long = true,
        )
    }

    fun unblock(name: String) {
        graph.taste.unblock(name)
        messages.show("Unblocked $name")
    }

    /** "More like this" on a suggestion. */
    fun like(key: String, artist: String, label: String) {
        graph.taste.like(key, artist, label)
        messages.show("More like $artist from now on", MessageKind.SUCCESS)
    }

    /** "Not interested": hidden now, and a little less of this artist. */
    fun dismiss(key: String, artist: String, label: String) {
        graph.taste.dismiss(key, artist, label)
        messages.show("Got it — you won't see “$label” again")
    }

    /** Save a mix as a playlist that gets the mix's new songs each day. */
    fun followMix(mix: app.kultr.dl.core.discover.Mix) = launch {
        val url = app.kultr.dl.engine.Recommender.MIX_URL + mix.id
        val existing = graph.library.playlistsWithTracks().keys.firstOrNull { it.sourceUrl == url }
        if (existing != null) {
            messages.show("“${existing.name}” is already in your playlists, updated daily")
            return@launch
        }
        graph.library.createPlaylist(mix.title, mix.tracks, sourceUrl = url, artworkUrl = mix.artworkUrls.firstOrNull())
        messages.show("“${mix.title}” saved to your playlists; it gets new songs every day", MessageKind.SUCCESS)
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
