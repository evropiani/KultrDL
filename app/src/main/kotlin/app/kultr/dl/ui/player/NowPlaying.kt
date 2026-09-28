package app.kultr.dl.ui.player

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.Format
import app.kultr.dl.playback.PlayerUiState
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.LocalTrackFlags
import app.kultr.dl.ui.components.Artwork
import app.kultr.dl.ui.components.ArtworkFill
import app.kultr.dl.ui.components.Eyebrow
import app.kultr.dl.ui.components.GlassPanel
import app.kultr.dl.ui.components.Segmented
import app.kultr.dl.ui.components.Tag
import app.kultr.dl.ui.theme.Kultr
import coil3.compose.AsyncImage

private enum class PlayerTab(val label: String) { QUEUE("Up next"), DETAILS("Details") }

/** Blurred artwork behind everything, darkened so text stays readable. */
@Composable
fun ArtworkBackdrop(url: String?, modifier: Modifier = Modifier) {
    val colors = Kultr.colors
    Box(modifier.fillMaxSize().background(colors.background)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (colors.dark) 0.55f else 0.45f }
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(70.dp) else Modifier),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        colors.background.copy(alpha = 0.35f),
                        colors.accent.copy(alpha = 0.12f),
                        colors.background.copy(alpha = 0.92f),
                    ),
                ),
            ),
        )
    }
}

@Composable
fun NowPlayingScreen(state: PlayerUiState, onClose: () -> Unit) {
    val colors = Kultr.colors
    val actions = LocalActions.current
    val track = state.current
    var tab by rememberSaveable { mutableStateOf(PlayerTab.QUEUE) }
    val listState = rememberLazyListState()
    val pull = rememberPullToDismiss(onClose)

    Box(
        Modifier
            .fillMaxSize()
            // Pulled down, the player is one card over the app, which dims behind it.
            .drawBehind {
                val shown = (pull.offset / 48.dp.toPx()).coerceIn(0f, 1f) * (1f - (pull.offset / size.height).coerceIn(0f, 1f))
                if (shown > 0f) drawRect(Color.Black.copy(alpha = 0.5f * shown))
            }
            .graphicsLayer {
                translationY = pull.offset
                val shrink = (pull.offset / size.height).coerceIn(0f, 1f) * 0.08f
                scaleX = 1f - shrink
                scaleY = 1f - shrink
                val lifted = (pull.offset / 24.dp.toPx()).coerceIn(0f, 1f)
                if (lifted > 0f) {
                    shape = RoundedCornerShape((34 * lifted).dp)
                    clip = true
                    shadowElevation = 24.dp.toPx() * lifted
                } else {
                    clip = false
                    shadowElevation = 0f
                }
            }
            .nestedScroll(pull.connection),
    ) {
        ArtworkBackdrop(track?.artworkUrl)
        if (track == null) {
            Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.Start)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player")
                }
                Spacer(Modifier.weight(1f))
                Text("Nothing is playing.", color = colors.ink2)
                Spacer(Modifier.weight(1f))
            }
            return@Box
        }
        val position by rememberPosition()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "header") {
                Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp)) {
                    PlayerTopBar(track, onClose)
                    Spacer(Modifier.height(8.dp))
                    SwipeArtwork(track)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(track.artist, track.album).joinToString(" — "),
                        color = colors.ink2,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    TrackTags(track)
                    Spacer(Modifier.height(16.dp))
                    Scrubber(positionMs = position, durationMs = state.durationMs, onSeek = { actions.graph.player.seekTo(it) })
                    Transport(state, track)
                    Spacer(Modifier.height(12.dp))
                    Segmented(options = PlayerTab.entries.map { it to it.label }, selected = tab, onSelect = { tab = it })
                    Spacer(Modifier.height(12.dp))
                }
            }
            when (tab) {
                PlayerTab.QUEUE -> queueItems(state)
                PlayerTab.DETAILS -> item(key = "details") { DetailsPanel(track) }
            }
        }
    }
}

@Composable
private fun TrackTags(track: Track) {
    val library = LocalActions.current.graph.library
    val entity by remember(track.id) { library.observe(track.id) }.collectAsStateWithLifecycle(null)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        Tag(track.source.label)
        (entity?.year ?: track.year)?.let { Tag("$it") }
        entity?.localFormat?.let { Tag(it) }
        if (entity?.localUri == null) Tag("Streaming")
    }
}

@Composable
private fun PlayerTopBar(track: Track, onClose: () -> Unit) {
    val actions = LocalActions.current
    val flags = LocalTrackFlags.current[track.id]
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player") }
        Eyebrow(track.album ?: "Now playing", Modifier.weight(1f))
        IconButton(onClick = { actions.download(listOf(track)) }) {
            Icon(
                if (flags?.localUri != null) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                contentDescription = "Download",
                tint = if (flags?.localUri != null) Kultr.colors.accent else Kultr.colors.ink,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Download as…") },
                    leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                    onClick = { menu = false; actions.downloadAs(listOf(track)) },
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist…") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                    onClick = { menu = false; actions.addToPlaylist(listOf(track)) },
                )
                DropdownMenuItem(
                    text = { Text(if (flags?.saved == true) "Remove from library" else "Save to library") },
                    leadingIcon = { Icon(if (flags?.saved == true) Icons.Rounded.BookmarkRemove else Icons.Rounded.BookmarkAdd, null) },
                    onClick = { menu = false; actions.toggleSaved(track) },
                )
                if (track.pageUrl != null) {
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = { menu = false; actions.share(track) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Stop and clear queue") },
                    leadingIcon = { Icon(Icons.Rounded.Close, null) },
                    onClick = { menu = false; actions.graph.player.stop(); onClose() },
                )
            }
        }
    }
}

/** The big artwork; swipe it sideways to skip. */
@Composable
private fun SwipeArtwork(track: Track) {
    val actions = LocalActions.current
    var drag by remember(track.id) { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(drag, label = "swipe")
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .aspectRatio(1f)
            .graphicsLayer {
                translationX = offset
                rotationZ = offset / 60f
            }
            .pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            drag > threshold -> actions.graph.player.previous()
                            drag < -threshold -> actions.graph.player.next()
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                    onHorizontalDrag = { _, amount -> drag += amount },
                )
            },
    ) {
        ArtworkFill(
            track.artworkUrl,
            Modifier.fillMaxSize().shadow(24.dp, RoundedCornerShape(Kultr.radii.xl)),
            shape = RoundedCornerShape(Kultr.radii.xl),
        )
    }
}

@Composable
private fun Transport(state: PlayerUiState, track: Track) {
    val actions = LocalActions.current
    val player = actions.graph.player
    val colors = Kultr.colors
    val favorite = LocalTrackFlags.current[track.id]?.favorite == true
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { player.setShuffle(!state.shuffle) }) {
            Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle", tint = if (state.shuffle) colors.accent else colors.ink2)
        }
        IconButton(onClick = { player.previous() }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = colors.ink, modifier = Modifier.size(34.dp))
        }
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(colors.ink)
                .clickable { player.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (state.buffering && state.playWhenReady) {
                CircularProgressIndicator(color = colors.background, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
            } else {
                Icon(
                    if (state.playWhenReady && !state.ended) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.playWhenReady) "Pause" else "Play",
                    tint = colors.background,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        IconButton(onClick = { player.next() }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = colors.ink, modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = { player.cycleRepeat() }) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "Repeat",
                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) colors.accent else colors.ink2,
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = { actions.toggleFavorite(track) }) {
            Icon(
                if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
                tint = if (favorite) colors.accent else colors.ink2,
            )
        }
        IconButton(onClick = { actions.addToPlaylist(listOf(track)) }) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = "Add to playlist", tint = colors.ink2)
        }
    }
}

@Composable
private fun DetailsPanel(track: Track) {
    val actions = LocalActions.current
    val colors = Kultr.colors
    val entity by remember(track.id) { actions.graph.library.observe(track.id) }.collectAsStateWithLifecycle(null)
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Detail("Source", track.source.label)
            val played = entity?.matchedUrl ?: track.streamUrl
            if (track.needsMatch) {
                Detail("Plays from", if (played != null) "The matching recording on YouTube Music" else "Matched when it first plays")
            }
            entity?.localFormat?.let { Detail("Downloaded", it + (entity?.localSize?.let { s -> " · " + Format.bytes(s) } ?: "")) }
            track.isrc?.let { Detail("ISRC", it) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (track.pageUrl != null) {
                    IconButton(onClick = { actions.openInBrowser(track.pageUrl) }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open on ${track.source.label}", tint = colors.ink2)
                    }
                }
                if (played != null && played != track.pageUrl) {
                    IconButton(onClick = { actions.openInBrowser(played) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Open the recording", tint = colors.ink2)
                    }
                }
                if (track.needsMatch) {
                    IconButton(onClick = { actions.rematch(track) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Find another recording", tint = colors.ink2)
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row {
        Text(label, color = Kultr.colors.ink3, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Text(value, color = Kultr.colors.ink, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun LazyListScope.queueItems(state: PlayerUiState) {
    val upNext = state.upNext
    item(key = "queue-head") {
        val actions = LocalActions.current
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (upNext.isEmpty()) "Nothing queued after this track." else "${upNext.size} up next",
                color = Kultr.colors.ink3,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (upNext.isNotEmpty()) {
                IconButton(onClick = { actions.graph.player.clearUpcoming() }) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear up next", tint = Kultr.colors.ink2)
                }
            }
        }
    }
    itemsIndexed(upNext, key = { _, entry -> entry.key }) { _, entry -> QueueRow(entry.index, entry.track) }
}

@Composable
private fun QueueRow(index: Int, track: Track) {
    val actions = LocalActions.current
    val colors = Kultr.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable { actions.graph.player.jumpTo(index) }
            .padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artworkUrl, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(track.artist, color = colors.ink3, style = MaterialTheme.typography.bodySmall, maxLines = 1, fontSize = 13.sp)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = colors.ink3) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play now") }, onClick = { menu = false; actions.graph.player.jumpTo(index) })
                DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { menu = false; actions.graph.player.remove(index) })
                DropdownMenuItem(text = { Text("Move to top") }, onClick = {
                    menu = false
                    val target = actions.graph.player.ui.value.index + 1
                    if (target in 0 until actions.graph.player.ui.value.queue.size) actions.graph.player.move(index, target)
                })
            }
        }
    }
}
