package app.kultr.dl.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * Pull a scrolling screen down to close it, like a bottom sheet.
 *
 * Once the list inside is scrolled to its top, dragging further down moves
 * the whole screen by [offset] instead; letting go far enough (or flicking
 * down) calls the dismiss callback, otherwise the screen springs back.
 * Attach [connection] with `Modifier.nestedScroll` around the list.
 */
@Stable
class PullToDismissState internal constructor(
    private val threshold: Float,
    private val onDismiss: () -> Unit,
) {
    var offset by mutableFloatStateOf(0f)
        private set

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // While pulled down, moving back up first undoes the pull.
            if (offset > 0f && available.y < 0f && source == NestedScrollSource.UserInput) {
                val used = maxOf(available.y, -offset)
                offset += used
                return Offset(0f, used)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // The list is at its top and the finger keeps going down.
            if (available.y > 0f && source == NestedScrollSource.UserInput) {
                offset += available.y
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset <= 0f) return Velocity.Zero
            if (offset > threshold || available.y > FLING_TO_DISMISS) {
                onDismiss()
            } else {
                animate(offset, 0f, animationSpec = spring()) { value, _ -> offset = value }
            }
            return available
        }
    }

    private companion object {
        const val FLING_TO_DISMISS = 2_000f
    }
}

@Composable
fun rememberPullToDismiss(onDismiss: () -> Unit): PullToDismissState {
    val threshold = with(LocalDensity.current) { 140.dp.toPx() }
    val latest by rememberUpdatedState(onDismiss)
    return remember(threshold) { PullToDismissState(threshold) { latest() } }
}
