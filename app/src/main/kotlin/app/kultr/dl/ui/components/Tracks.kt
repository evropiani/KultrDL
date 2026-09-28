package app.kultr.dl.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.Format
import app.kultr.dl.data.db.DownloadState
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.LocalDownloadBadges
import app.kultr.dl.ui.LocalTrackFlags
import app.kultr.dl.ui.theme.Kultr

/** An extra entry for a track's menu, for screen-specific actions. */
data class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    isCurrent: Boolean = false,
    showSource: Boolean = false,
    extraActions: List<MenuAction> = emptyList(),
) {
    val colors = Kultr.colors
    val flags = LocalTrackFlags.current[track.id]
    val badge = LocalDownloadBadges.current[track.id]
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = colors.accent, modifier = Modifier.size(18.dp))
                } else {
                    Text("$number", color = colors.ink3, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.width(8.dp))
        } else {
            Box {
                Artwork(track.artworkUrl, size = 48.dp)
                if (isCurrent) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = colors.accent)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (isCurrent) colors.accent else colors.ink,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.explicit) {
                    Text(
                        "E",
                        color = colors.background,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(colors.ink3, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                val sub = listOfNotNull(track.artist.ifEmpty { null }, track.album, if (showSource) track.source.label else null).joinToString(" · ")
                Text(sub, color = colors.ink3, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            badge?.state == DownloadState.RUNNING -> {
                CircularProgressIndicator(
                    progress = { badge.progress.coerceIn(0.02f, 1f) },
                    color = colors.accent,
                    trackColor = colors.ink4,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            badge?.state == DownloadState.QUEUED -> {
                Icon(Icons.Rounded.Download, contentDescription = "Queued", tint = colors.ink3, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            flags?.localUri != null -> {
                Icon(Icons.Rounded.DownloadDone, contentDescription = "Downloaded", tint = colors.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
        }
        if (flags?.favorite == true) {
            Icon(Icons.Rounded.Favorite, contentDescription = "Favourite", tint = colors.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        track.durationMs?.let { Text(Format.duration(it), color = colors.ink3, fontSize = 12.sp) }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${track.title}", tint = colors.ink3)
            }
            TrackMenu(track, menu, onDismiss = { menu = false }, extraActions = extraActions)
        }
    }
}

@Composable
fun TrackMenu(track: Track, expanded: Boolean, onDismiss: () -> Unit, extraActions: List<MenuAction> = emptyList()) {
    val actions = LocalActions.current
    val flags = LocalTrackFlags.current[track.id]
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem("Play", Icons.Rounded.PlayArrow, onDismiss) { actions.play(listOf(track)) }
        MenuItem("Play next", Icons.Rounded.SkipNext, onDismiss) { actions.playNext(listOf(track)) }
        MenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic, onDismiss) { actions.enqueue(listOf(track)) }
        HorizontalDivider()
        if (flags?.favorite == true) {
            MenuItem("Remove from favourites", Icons.Rounded.FavoriteBorder, onDismiss) { actions.setFavorite(listOf(track), false) }
        } else {
            MenuItem("Add to favourites", Icons.Rounded.Favorite, onDismiss) { actions.setFavorite(listOf(track), true) }
        }
        if (flags?.saved == true) {
            MenuItem("Remove from library", Icons.Rounded.BookmarkRemove, onDismiss) { actions.setSaved(listOf(track), false) }
        } else {
            MenuItem("Save to library", Icons.Rounded.BookmarkAdd, onDismiss) { actions.setSaved(listOf(track), true) }
        }
        MenuItem("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd, onDismiss) { actions.addToPlaylist(listOf(track)) }
        HorizontalDivider()
        MenuItem("Download", Icons.Rounded.Download, onDismiss) { actions.download(listOf(track)) }
        MenuItem("Download as…", Icons.Rounded.Tune, onDismiss) { actions.downloadAs(listOf(track)) }
        if (flags?.localUri != null) {
            if (actions.graph.servers.servers.value.isNotEmpty()) {
                MenuItem("Send to server…", Icons.Rounded.CloudUpload, onDismiss) { actions.sendToServer(listOf(track)) }
            }
            MenuItem("Remove download", Icons.Rounded.Delete, onDismiss) { actions.removeDownload(track) }
        }
        if (track.needsMatch) {
            MenuItem("Find another recording", Icons.Rounded.Refresh, onDismiss) { actions.rematch(track) }
        }
        HorizontalDivider()
        if (track.pageUrl != null) {
            MenuItem("Open on ${track.source.label}", Icons.AutoMirrored.Rounded.OpenInNew, onDismiss) { actions.openInBrowser(track.pageUrl) }
            MenuItem("Share", Icons.Rounded.Share, onDismiss) { actions.share(track) }
        }
        if (extraActions.isNotEmpty()) {
            HorizontalDivider()
            extraActions.forEach { extra -> MenuItem(extra.label, extra.icon, onDismiss, extra.onClick) }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onDismiss: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = {
            onDismiss()
            action()
        },
    )
}

/** Track rows for a LazyColumn; tapping plays the list from that track. */
fun LazyListScope.trackItems(
    tracks: List<Track>,
    currentId: String?,
    onPlay: (Int) -> Unit,
    keyPrefix: String = "track",
    numbered: Boolean = false,
    showSource: Boolean = false,
    extraActions: (Int, Track) -> List<MenuAction> = { _, _ -> emptyList() },
) {
    itemsIndexed(tracks, key = { index, t -> "$keyPrefix:$index:${t.id}" }) { index, track ->
        TrackRow(
            track = track,
            onClick = { onPlay(index) },
            number = if (numbered) track.trackNumber ?: (index + 1) else null,
            isCurrent = track.id == currentId,
            showSource = showSource,
            extraActions = extraActions(index, track),
        )
    }
}
