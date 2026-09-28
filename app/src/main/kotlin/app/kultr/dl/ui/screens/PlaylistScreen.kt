package app.kultr.dl.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.util.Format
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.chromePadding
import app.kultr.dl.ui.components.AccentWash
import app.kultr.dl.ui.components.ArtworkFill
import app.kultr.dl.ui.components.ConfirmDialog
import app.kultr.dl.ui.components.EmptyState
import app.kultr.dl.ui.components.Eyebrow
import app.kultr.dl.ui.components.MenuAction
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.components.TextDialog
import app.kultr.dl.ui.components.trackItems
import app.kultr.dl.ui.theme.Kultr

/** One of the user's own playlists. */
@Composable
fun PlaylistScreen(id: Long) {
    val actions = LocalActions.current
    val library = actions.graph.library
    val playlist by remember(id) { library.playlist(id) }.collectAsStateWithLifecycle(null)
    val tracks by remember(id) { library.playlistTracks(id) }.collectAsStateWithLifecycle(emptyList())
    val player by actions.graph.player.ui.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val name = playlist?.name ?: "Playlist"

    Box(Modifier.fillMaxSize()) {
        AccentWash()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chromePadding())) {
            item(key = "head") {
                Column {
                    IconButton(onClick = { actions.back() }, modifier = Modifier.statusBarsPadding().padding(start = 4.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ArtworkFill(
                            playlist?.artworkUrl ?: tracks.firstOrNull()?.artworkUrl,
                            Modifier.size(200.dp).shadow(20.dp, RoundedCornerShape(Kultr.radii.lg)),
                            shape = RoundedCornerShape(Kultr.radii.lg),
                            label = name,
                        )
                        Spacer(Modifier.height(16.dp))
                        Eyebrow("Playlist")
                        Text(name, style = MaterialTheme.typography.headlineSmall, color = Kultr.colors.ink, textAlign = TextAlign.Center)
                        val total = tracks.mapNotNull { it.durationMs }.sum()
                        Text(
                            listOfNotNull(Format.count(tracks.size, "track"), Format.duration(total).ifEmpty { null }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Kultr.colors.ink2,
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Pill("Play", icon = Icons.Rounded.PlayArrow, accent = true, enabled = tracks.isNotEmpty(), onClick = { actions.play(tracks) })
                        Pill("Shuffle", icon = Icons.Rounded.Shuffle, enabled = tracks.isNotEmpty(), onClick = { actions.shuffle(tracks) })
                        Pill("Download all", icon = Icons.Rounded.Download, enabled = tracks.isNotEmpty(), onClick = { actions.download(tracks) })
                        Pill("Rename", icon = Icons.Rounded.Edit, onClick = { renaming = true })
                        Pill("Delete", icon = Icons.Rounded.Delete, onClick = { deleting = true })
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (tracks.isEmpty()) {
                item(key = "empty") {
                    EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "Nothing in here yet", body = "Add tracks with “Add to playlist…” from any track's menu.")
                }
            }
            trackItems(
                tracks,
                player.current?.id,
                onPlay = { actions.play(tracks, it) },
                keyPrefix = "p",
                extraActions = { index, _ ->
                    listOfNotNull(
                        MenuAction("Remove from playlist", Icons.Rounded.RemoveCircleOutline) { actions.launch { library.removeFromPlaylist(id, index) } },
                        if (index > 0) MenuAction("Move up", Icons.Rounded.ArrowUpward) { actions.launch { library.movePlaylistTrack(id, index, index - 1) } } else null,
                        if (index < tracks.lastIndex) MenuAction("Move down", Icons.Rounded.ArrowDownward) { actions.launch { library.movePlaylistTrack(id, index, index + 1) } } else null,
                    )
                },
            )
        }
    }

    if (renaming) {
        TextDialog(
            title = "Rename playlist",
            initial = name,
            confirm = "Rename",
            onConfirm = { newName ->
                renaming = false
                actions.launch { library.renamePlaylist(id, newName) }
            },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = "Delete “$name”?",
            body = "The playlist goes; its tracks stay in your library and downloads.",
            confirm = "Delete",
            onConfirm = {
                actions.launch { library.deletePlaylist(id) }
                actions.back()
            },
            onDismiss = { deleting = false },
        )
    }
}
