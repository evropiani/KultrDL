package app.kultr.dl.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kultr.dl.playback.PlayerUiState
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.LocalTrackFlags
import app.kultr.dl.ui.components.Artwork
import app.kultr.dl.ui.components.glass
import app.kultr.dl.ui.theme.Kultr
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The glass capsule above the tab bar: what is playing, a heart, play/pause
 * and next. Swipe it left for the next track and right for the previous one.
 */
@Composable
fun MiniPlayer(state: PlayerUiState, modifier: Modifier = Modifier) {
    val track = state.current ?: return
    val actions = LocalActions.current
    val colors = Kultr.colors
    val player = actions.graph.player
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val favorite = LocalTrackFlags.current[track.id]?.favorite == true
    val position by rememberPosition(500)
    val shape = RoundedCornerShape(30.dp)

    Column(
        modifier
            .glass(shape)
            .clip(shape)
            .pointerInput(Unit) {
                val width = size.width.toFloat()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val travelled = swipe.value
                        scope.launch {
                            if (abs(travelled) < threshold) {
                                swipe.animateTo(0f, spring())
                                return@launch
                            }
                            val direction = if (travelled < 0) -1f else 1f
                            swipe.animateTo(direction * width, tween(140))
                            if (direction < 0) player.next() else player.previous()
                            swipe.snapTo(-direction * width * 0.5f)
                            swipe.animateTo(0f, spring(dampingRatio = 0.8f))
                        }
                    },
                    onDragCancel = { scope.launch { swipe.animateTo(0f, spring()) } },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { swipe.snapTo(swipe.value + amount) }
                    },
                )
            }
            .clickable { actions.openPlayer() }
            .testTag("mini-player"),
    ) {
        Row(
            Modifier
                .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 4.dp)
                .graphicsLayer {
                    translationX = swipe.value
                    alpha = 1f - (abs(swipe.value) / size.width).coerceIn(0f, 0.7f)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.artworkUrl, size = 44.dp, shape = RoundedCornerShape(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { actions.toggleFavorite(track) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
                    tint = if (favorite) colors.accent else colors.ink2,
                )
            }
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (state.buffering && state.playWhenReady) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                } else {
                    IconButton(onClick = { player.toggle() }) {
                        Icon(
                            if (state.playWhenReady && !state.ended) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.playWhenReady) "Pause" else "Play",
                            tint = colors.ink,
                        )
                    }
                }
            }
            IconButton(onClick = { player.next() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = colors.ink)
            }
        }
        val fraction = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .padding(start = 64.dp, end = 28.dp, bottom = 5.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.ink4),
        ) {
            Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(colors.accent))
        }
    }
}
