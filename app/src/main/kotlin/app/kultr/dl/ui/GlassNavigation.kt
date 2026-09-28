package app.kultr.dl.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import app.kultr.dl.ui.components.glass
import app.kultr.dl.ui.theme.Kultr
import app.kultr.dl.ui.theme.LocalReduceMotion
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Height of the floating tab bar and of the round button beside it. */
val GlassBarHeight = 64.dp

/**
 * How much of the bottom of the screen the floating controls cover, so pages
 * can pad their lists and scroll their last rows clear of them.
 */
val LocalChromeInset = staticCompositionLocalOf { 0.dp }

/** Bottom padding for a page's list: [extra] below its last row, clear of the floating controls. */
@Composable
fun chromePadding(extra: Dp = 24.dp): Dp = LocalChromeInset.current + extra

class GlassTab(val label: String, val icon: ImageVector)

/**
 * The floating tab bar: a glass capsule whose selection is a lens.
 *
 * At rest the selected tab sits on a soft pill. Touch the bar and the pill
 * lifts into a clear lens, a little larger than the bar, that follows the
 * finger; the tabs under it are magnified and lit in the accent. Let go and
 * it settles on the nearest tab with a small wobble, stretching as it moves.
 * A plain tap does the same in one short movement. [selected] is -1 when
 * none of these tabs is showing, which hides the pill.
 */
@Composable
fun GlassTabBar(tabs: List<GlassTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Kultr.colors
    val scope = rememberCoroutineScope()
    val latestSelect by rememberUpdatedState(onSelect)
    val shape = RoundedCornerShape(50)

    // Where the lens is, in tabs: 0 is the middle of the first tab.
    val position = remember { Animatable(selected.coerceAtLeast(0).toFloat()) }
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 520f), label = "lens")
    val rest by animateFloatAsState(if (selected >= 0) 1f else 0f, label = "rest")
    LaunchedEffect(selected) {
        if (!pressed && selected >= 0) position.animateTo(selected.toFloat(), spring(dampingRatio = 0.6f, stiffness = 360f))
    }

    val restFill = if (colors.dark) Color.White.copy(alpha = 0.13f) else Color.Black.copy(alpha = 0.07f)
    val rimLight = if (colors.dark) Color.White.copy(alpha = 0.55f) else Color.White
    val rimShade = if (colors.dark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.12f)

    /** The lens (or the pill at rest) for the current frame. */
    fun DrawScope.lens(): RoundRect {
        val count = tabs.size
        val itemWidth = size.width / count
        val inset = 5.dp.toPx()
        // Stretch along the way it moves, and thin a little, like a drop.
        val stretch = (abs(position.velocity) * 0.07f).coerceAtMost(0.4f)
        val width = (itemWidth - 2 * inset) * (1f + 0.2f * press) * (1f + stretch)
        val height = (size.height - 2 * inset) * (1f + 0.36f * press) * (1f - 0.3f * stretch)
        val centre = Offset((position.value + 0.5f) * itemWidth, size.height / 2f)
        val rect = Rect(centre.x - width / 2f, centre.y - height / 2f, centre.x + width / 2f, centre.y + height / 2f)
        return RoundRect(rect, CornerRadius(height / 2f))
    }

    Box(
        modifier
            .height(GlassBarHeight)
            .glass(shape)
            .pointerInput(tabs.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val itemWidth = size.width / tabs.size.toFloat()
                    fun at(x: Float) = (x / itemWidth - 0.5f).coerceIn(0f, tabs.size - 1f)
                    var x = down.position.x
                    pressed = true
                    scope.launch { position.animateTo(at(x), spring(dampingRatio = 0.75f, stiffness = 700f)) }
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            x = change.position.x
                            scope.launch { position.animateTo(at(x), spring(dampingRatio = 0.85f, stiffness = 900f)) }
                        }
                        val target = at(x).roundToInt()
                        latestSelect(target)
                        scope.launch { position.animateTo(target.toFloat(), spring(dampingRatio = 0.55f, stiffness = 380f)) }
                    } finally {
                        pressed = false
                    }
                }
            }
            .drawBehind {
                // The pill under the selected tab, which lifts away as the lens appears.
                val pillAlpha = rest * (1f - press)
                if (pillAlpha > 0.01f) drawPath(Path().apply { addRoundRect(lens()) }, restFill.copy(alpha = restFill.alpha * pillAlpha))
            },
    ) {
        // The tabs as they are, except where the lens is.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (press > 0.01f) {
                        clipPath(Path().apply { addRoundRect(lens()) }, ClipOp.Difference) { this@drawWithContent.drawContent() }
                    } else {
                        drawContent()
                    }
                },
        ) {
            TabRow(tabs, selected, lit = false, onSelect = onSelect)
        }

        // The same tabs again in the accent, magnified and seen only through the lens.
        Box(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .drawWithContent {
                    if (press <= 0.01f) return@drawWithContent
                    val lens = lens()
                    val lensPath = Path().apply { addRoundRect(lens) }
                    clipPath(lensPath) {
                        scale(1f + 0.18f * press, pivot = lens.center) { this@drawWithContent.drawContent() }
                    }
                    // The glass itself: nearly clear, with a bright rim and a faint sheen.
                    drawPath(lensPath, Color.White.copy(alpha = 0.07f * press))
                    drawPath(
                        lensPath,
                        Brush.verticalGradient(
                            listOf(rimLight.copy(alpha = rimLight.alpha * press), rimShade.copy(alpha = rimShade.alpha * press)),
                            startY = lens.top,
                            endY = lens.bottom,
                        ),
                        style = Stroke(width = 1.4.dp.toPx()),
                    )
                },
        ) {
            TabRow(tabs, selected, lit = true, onSelect = null)
        }
    }
}

@Composable
private fun TabRow(tabs: List<GlassTab>, selected: Int, lit: Boolean, onSelect: ((Int) -> Unit)?) {
    val colors = Kultr.colors
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { index, tab ->
            val on = lit || index == selected
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (onSelect == null) {
                            Modifier
                        } else {
                            // The bar handles touch as a whole; this is for accessibility services.
                            Modifier.semantics(mergeDescendants = true) {
                                role = Role.Tab
                                this.selected = index == selected
                                contentDescription = tab.label
                                onClick(tab.label) { onSelect(index); true }
                            }
                        },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, contentDescription = null, tint = if (on) colors.accent else colors.ink2, modifier = Modifier.size(24.dp))
                Text(
                    tab.label,
                    color = if (on) colors.accent else colors.ink2,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A round glass button beside the tab bar (Search), with the same pill when
 * it is the page showing, and a little swell under the finger.
 */
@Composable
fun GlassRoundButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = GlassBarHeight,
) {
    val colors = Kultr.colors
    val interaction = remember { MutableInteractionSource() }
    val down by interaction.collectIsPressedAsState()
    val swell by animateFloatAsState(if (down) 1.1f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "swell")
    val pill by animateFloatAsState(if (selected) 1f else 0f, label = "pill")
    val restFill = if (colors.dark) Color.White.copy(alpha = 0.13f) else Color.Black.copy(alpha = 0.07f)
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = swell
                scaleY = swell
            }
            .glass(CircleShape)
            .drawWithContent {
                if (pill > 0.01f) {
                    val inset = 5.dp.toPx()
                    drawCircle(restFill.copy(alpha = restFill.alpha * pill), radius = this.size.minDimension / 2f - inset)
                }
                drawContent()
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClickLabel = label, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) colors.accent else colors.ink2, modifier = Modifier.size(26.dp))
    }
}

/**
 * The floating bar, with search in it. Choosing Search folds the tabs into a
 * round button back to where you came from, and the search button grows to
 * the left into the search field, which takes the keyboard straight away.
 */
@Composable
fun GlassNavigationBar(
    tabs: List<GlassTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    back: GlassTab,
    onBack: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    val progress by animateFloatAsState(
        if (searching) 1f else 0f,
        if (reduce) snap<Float>() else spring<Float>(dampingRatio = 0.84f, stiffness = 420f),
        label = "search",
    )
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (searching) runCatching { focus.requestFocus() }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 10.dp
        val wide = maxWidth - gap - GlassBarHeight
        val left = lerp(wide, GlassBarHeight, progress)
        val right = lerp(GlassBarHeight, wide, progress)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(left).height(GlassBarHeight), contentAlignment = Alignment.CenterStart) {
                if (progress < 0.999f) {
                    GlassTabBar(
                        tabs,
                        selected,
                        onSelect,
                        Modifier.fillMaxWidth().graphicsLayer { alpha = (1f - progress * 1.6f).coerceIn(0f, 1f) },
                    )
                }
                if (progress > 0.001f) {
                    GlassRoundButton(
                        icon = back.icon,
                        label = "Back to ${back.label}",
                        selected = false,
                        onClick = onBack,
                        modifier = Modifier.graphicsLayer { alpha = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f) },
                    )
                }
            }
            Spacer(Modifier.width(gap))
            SearchCapsule(
                progress = progress,
                searching = searching,
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                focus = focus,
                modifier = Modifier.width(right),
            )
        }
    }
}

@Composable
private fun SearchCapsule(
    progress: Float,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = Kultr.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val down by interaction.collectIsPressedAsState()
    val swell by animateFloatAsState(if (down && !searching) 1.1f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "swell")
    Row(
        modifier
            .height(GlassBarHeight)
            .graphicsLayer {
                scaleX = swell
                scaleY = swell
            }
            .glass(RoundedCornerShape(50))
            .then(
                if (searching) {
                    Modifier
                } else {
                    Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClickLabel = "Search", onClick = onSearch)
                },
            )
            // The icon sits where it does in the round button, and the field grows beside it.
            .padding(start = 19.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = if (searching) null else "Search",
            tint = if (searching) colors.accent else colors.ink2,
            modifier = Modifier.size(26.dp),
        )
        // Composed as soon as search is chosen, so it can take the keyboard at once.
        if (searching || progress > 0.05f) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).graphicsLayer { alpha = ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f) }) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    enabled = searching,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { field ->
                        if (query.isEmpty()) {
                            Text("Search or paste a link", style = MaterialTheme.typography.bodyLarge, color = colors.ink3, maxLines = 1)
                        }
                        field()
                    },
                )
            }
            if (searching && query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Clear, contentDescription = "Clear", tint = colors.ink3)
                }
            }
        }
    }
}
