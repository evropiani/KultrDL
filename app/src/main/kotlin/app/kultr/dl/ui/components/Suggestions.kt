package app.kultr.dl.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kultr.dl.core.discover.Keys
import app.kultr.dl.core.discover.Mix
import app.kultr.dl.core.discover.Pick
import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.taste.Credits
import app.kultr.dl.core.util.Format
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.theme.Kultr

/**
 * What can be said about a suggestion: more like this, not interested,
 * never this artist; and download it, to the phone or to Navidrome.
 */
@Composable
fun SuggestionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    key: String,
    artist: String,
    label: String,
    tracks: (suspend () -> List<Track>)? = null,
) {
    val actions = LocalActions.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Item("More like this", Icons.Rounded.ThumbUp, onDismiss) { actions.like(key, artist, label) }
        Item("Not interested", Icons.Rounded.ThumbDown, onDismiss) { actions.dismiss(key, artist, label) }
        Item("Never this artist…", Icons.Rounded.Block, onDismiss) {
            actions.blockArtist(Track(id = "", source = Source.WEB, title = "", artist = artist))
        }
        if (tracks != null) {
            HorizontalDivider()
            Item("Download", Icons.Rounded.Download, onDismiss) { actions.launch { actions.download(tracks()) } }
            if (actions.canDownloadToNavidrome()) {
                Item("Download to Navidrome", Icons.Rounded.CloudDownload, onDismiss) { actions.launch { actions.downloadToNavidrome(tracks()) } }
            }
        }
    }
}

@Composable
private fun Item(label: String, icon: ImageVector, onDismiss: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = {
            onDismiss()
            action()
        },
    )
}

/** An album or single suggested: cover, title, artist and why (or when it came out). Long-press for more. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PickCard(pick: Pick, onClick: () -> Unit, modifier: Modifier = Modifier, showDate: Boolean = false) {
    val actions = LocalActions.current
    var menu by remember { mutableStateOf(false) }
    val c = pick.collection
    Box(modifier) {
        Column(Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(6.dp)) {
            ArtworkFill(c.artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f), label = c.title)
            Spacer(Modifier.height(8.dp))
            Text(c.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Kultr.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pick.artist, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = if (showDate) listOfNotNull(pick.reason, Format.released(c.releaseDate)).joinToString(" · ") else pick.reason
            Text(line, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SuggestionMenu(menu, { menu = false }, pick.key, pick.artist, c.title, tracks = { actions.graph.catalog.load(c).tracks })
    }
}

/** A mix: a 2×2 mosaic of its covers, its name and who is in it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MixCard(mix: Mix, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick).padding(6.dp)) {
        Mosaic(mix.artworkUrls, mix.title, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(mix.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Kultr.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(mix.subtitle, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Mosaic(urls: List<String>, label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Kultr.radii.md)
    if (urls.size < 4) {
        ArtworkFill(urls.firstOrNull(), modifier, shape = shape, label = label)
        return
    }
    Column(modifier.clip(shape)) {
        for (row in 0..1) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0..1) {
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        ArtworkFill(urls[row * 2 + col], Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp))
                    }
                }
            }
        }
    }
}

/** A suggested track (Rediscover): long-press for the same choices as any suggestion. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SuggestedTrackCard(track: Track, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(6.dp)) {
            ArtworkFill(track.artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f), label = track.title)
            Spacer(Modifier.height(8.dp))
            Text(track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Kultr.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SuggestionMenu(menu, { menu = false }, Keys.track(track.artist, track.title), Keys.primary(track.artist), track.title, tracks = { listOf(track) })
    }
}

/** "Block artist…": everyone on the song, to tick the ones never to hear again. */
@Composable
fun BlockArtistDialog(track: Track, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val people = remember(track) { Credits.people(track.artist, track.title) }
    val chosen = remember(track) { mutableStateListOf(people.firstOrNull() ?: track.artist) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Kultr.colors.elevated,
        title = { Text("Block which artist?") },
        text = {
            Column {
                Text(
                    "Their songs, and every song they're on, are hidden everywhere and skipped. Undo it in Settings → Recommendations → Blocked artists.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Kultr.colors.ink3,
                )
                Spacer(Modifier.height(8.dp))
                people.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (name in chosen) chosen.remove(name) else chosen.add(name) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = name in chosen, onCheckedChange = { on -> if (on) chosen.add(name) else chosen.remove(name) })
                        Text(name, style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                actions.block(chosen.toList())
                onDismiss()
            }) { Text("Block", color = Kultr.colors.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** An album or release page opened from a suggestion. */
fun Pick.page(): Collection = collection.copy(subtitle = collection.subtitle ?: artist)
