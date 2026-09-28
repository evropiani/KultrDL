package app.kultr.dl.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import app.kultr.dl.ui.theme.Kultr
import app.kultr.dl.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch

/**
 * A switcher: choices in a capsule, with the highlight sliding from the old
 * choice to the new one instead of jumping. The slide starts on the tap,
 * before whatever the choice changes has drawn.
 */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Kultr.colors
    val reduce = LocalReduceMotion.current
    val shape = RoundedCornerShape(50)
    // Each option's left edge and width, in the row.
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val left = remember { Animatable(0f) }
    val width = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val index = options.indexOfFirst { it.first == selected }
    val target = bounds[index]

    LaunchedEffect(target, reduce) {
        val (x, w) = target ?: return@LaunchedEffect
        if (!placed || reduce) {
            left.snapTo(x)
            width.snapTo(w)
            placed = true
        } else {
            val motion = spring<Float>(dampingRatio = 0.78f, stiffness = 520f)
            launch { width.animateTo(w, motion) }
            left.animateTo(x, motion)
        }
    }

    Row(
        modifier
            .clip(shape)
            .background(colors.glass)
            .border(1.dp, colors.edge, shape)
            .drawBehind {
                if (!placed || index < 0) return@drawBehind
                // Option positions are inside the row's padding; the highlight is drawn outside it.
                val inset = 3.dp.toPx()
                drawRoundRect(
                    colors.accent,
                    topLeft = Offset(left.value + inset, inset),
                    size = Size(width.value, size.height - 2 * inset),
                    cornerRadius = CornerRadius((size.height - 2 * inset) / 2f),
                )
            }
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, (value, label) ->
            val on = i == index
            val ink by animateColorAsState(if (on) colors.onAccent else colors.ink, label = "segment")
            Box(
                Modifier
                    .onGloballyPositioned { c -> bounds[i] = c.positionInParent().x to c.size.width.toFloat() }
                    .clip(shape)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/**
 * Slide into a new place in its parent instead of jumping there, when a list
 * is reordered. Give each moving row a stable key.
 */
fun Modifier.animatePlacement(): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(IntOffset.Zero) }
    var animation by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    this
        .onPlaced { target = it.positionInParent().round() }
        .offset {
            val anim = animation ?: Animatable(target, IntOffset.VectorConverter).also { animation = it }
            if (anim.targetValue != target) {
                scope.launch {
                    if (reduce) anim.snapTo(target) else anim.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
            anim.value - target
        }
}
