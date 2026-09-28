package app.kultr.dl.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.Format
import app.kultr.dl.data.db.TrackEntity
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.chromePadding
import app.kultr.dl.ui.components.Artwork
import app.kultr.dl.ui.components.EmptyState
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.components.TextDialog
import app.kultr.dl.ui.components.trackItems
import app.kultr.dl.ui.theme.Kultr
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String) {
    FAVOURITES("Favourites"),
    SAVED("Saved"),
    PLAYLISTS("Playlists"),
    DOWNLOADED("Downloaded"),
    HISTORY("History"),
}

/** The user's library, one page per tab; swipe between them. */
@Composable
fun LibraryScreen(initialTab: LibraryTab) {
    val tabs = LibraryTab.entries
    val pager = rememberPagerState(initialPage = initialTab.ordinal) { tabs.size }
    val scope = rememberCoroutineScope()
    var opened by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab) {
        if (initialTab != opened) {
            opened = initialTab
            pager.scrollToPage(initialTab.ordinal)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Library", style = MaterialTheme.typography.headlineMedium, color = Kultr.colors.ink, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
        PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp, containerColor = Color.Transparent) {
            tabs.forEachIndexed { index, entry ->
                Tab(selected = pager.currentPage == index, onClick = { scope.launch { pager.animateScrollToPage(index) } }, text = { Text(entry.label) })
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), key = { tabs[it].name }, verticalAlignment = Alignment.Top) { page ->
            when (tabs[page]) {
                LibraryTab.FAVOURITES -> FavouritesTab()
                LibraryTab.SAVED -> SavedTab()
                LibraryTab.PLAYLISTS -> PlaylistsTab()
                LibraryTab.DOWNLOADED -> DownloadedTab()
                LibraryTab.HISTORY -> HistoryTab()
            }
        }
    }
}

@Composable
private fun TrackList(tracks: List<Track>, emptyIcon: ImageVector, emptyTitle: String, emptyBody: String, keyPrefix: String, extra: @Composable () -> Unit = {}) {
    val actions = LocalActions.current
    val player by actions.graph.player.ui.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chromePadding())) {
        if (tracks.isEmpty()) {
            item { EmptyState(emptyIcon, emptyTitle, body = emptyBody) }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill("Play", icon = Icons.Rounded.PlayArrow, accent = true, onClick = { actions.play(tracks) })
                    Pill("Shuffle", icon = Icons.Rounded.Shuffle, onClick = { actions.shuffle(tracks) })
                    extra()
                    Text(Format.count(tracks.size, "track"), color = Kultr.colors.ink3, style = MaterialTheme.typography.bodySmall)
                }
            }
            trackItems(tracks, player.current?.id, onPlay = { actions.play(tracks, it) }, keyPrefix = keyPrefix, showSource = true)
        }
    }
}

@Composable
private fun FavouritesTab() {
    val actions = LocalActions.current
    val tracks by actions.graph.library.favorites.collectAsStateWithLifecycle(emptyList())
    TrackList(tracks, Icons.Rounded.Favorite, "No favourites yet", "Tap the heart on anything you love.", "fav") {
        Pill("Download all", icon = Icons.Rounded.Download, onClick = { actions.download(tracks) })
    }
}

@Composable
private fun SavedTab() {
    val actions = LocalActions.current
    val tracks by actions.graph.library.saved.collectAsStateWithLifecycle(emptyList())
    TrackList(tracks, Icons.Rounded.Bookmark, "Nothing saved yet", "Choose “Save to library” from a track's menu to keep it here.", "saved") {
        Pill("Download all", icon = Icons.Rounded.Download, onClick = { actions.download(tracks) })
    }
}

@Composable
private fun DownloadedTab() {
    val actions = LocalActions.current
    val rows by actions.graph.library.downloaded.collectAsStateWithLifecycle(emptyList())
    val tracks = remember(rows) { rows.map(TrackEntity::toTrack) }
    TrackList(tracks, Icons.Rounded.DownloadDone, "No downloads yet", "Downloaded tracks play without a connection.", "dl")
}

@Composable
private fun HistoryTab() {
    val actions = LocalActions.current
    val tracks by remember { actions.graph.library.history(200) }.collectAsStateWithLifecycle(emptyList())
    TrackList(tracks, Icons.Rounded.History, "Nothing played yet", "What you play shows up here.", "hist") {
        Pill("Clear", onClick = { actions.launch { actions.graph.library.clearHistory() } })
    }
}

@Composable
private fun PlaylistsTab() {
    val actions = LocalActions.current
    val playlists by actions.graph.library.playlistSummaries.collectAsStateWithLifecycle(emptyList())
    var creating by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chromePadding())) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Pill("New playlist", icon = Icons.Rounded.Add, accent = true, onClick = { creating = true })
            }
        }
        if (playlists.isEmpty()) {
            item { EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "No playlists yet", body = "Make one here, or save an album or playlist from any source with “Save as playlist”.") }
        }
        items(playlists, key = { it.id }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable { actions.openPlaylist(p.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(p.artworkUrl, size = 52.dp, label = p.name)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Kultr.colors.ink, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Format.count(p.count, "track"), color = Kultr.colors.ink3, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (creating) {
        TextDialog(
            title = "New playlist",
            initial = "",
            placeholder = "Name",
            confirm = "Create",
            onConfirm = { name ->
                creating = false
                actions.launch { actions.openPlaylistAfter(actions.graph.library.createPlaylist(name)) }
            },
            onDismiss = { creating = false },
        )
    }
}

private fun app.kultr.dl.ui.AppActions.openPlaylistAfter(id: Long) {
    scope.launch(kotlinx.coroutines.Dispatchers.Main) { openPlaylist(id) }
}
