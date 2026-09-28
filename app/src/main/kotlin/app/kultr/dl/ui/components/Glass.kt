package app.kultr.dl.ui.components

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kultr.dl.ui.theme.Kultr

/**
 * Liquid glass: floating surfaces that show the page scrolling behind them,
 * blurred, a little more vivid, and bent at the rim like the edge of a lens.
 *
 * The page records itself into a graphics layer as it draws ([glassSource]),
 * and each glass surface ([glass]) draws the part of that layer beneath it
 * through a blur. The layer refers to the page's own render nodes rather than
 * copying them, so the glass follows scrolling as it happens. Blur needs
 * Android 12 and the bent rim Android 13; before that, glass is a frosted
 * tint.
 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer?) {
    /** Where the recorded page sits, in root coordinates. */
    internal var origin by mutableStateOf(Offset.Zero)
}

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = if (Build.VERSION.SDK_INT >= 31) rememberGraphicsLayer() else null
    return remember(layer) { GlassBackdrop(layer) }
}

/** Record this content as what glass surfaces above it show through. */
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier {
    val layer = backdrop.layer ?: return this
    return this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

/**
 * A liquid glass surface in [shape]: the page behind, blurred by [blur], under
 * a light [tint] and a rim that catches the light. Without a backdrop (or
 * before Android 12) it falls back to a nearly solid frosted fill.
 */
fun Modifier.glass(shape: Shape, tint: Color? = null, blur: Dp = 18.dp): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    val source = backdrop?.layer
    val layer = if (source != null) rememberGraphicsLayer() else null
    val colors = Kultr.colors
    val density = LocalDensity.current
    val effects = remember(density) { GlassEffects(with(density) { blur.toPx() }, with(density) { 8.dp.toPx() }) }
    var position by remember { mutableStateOf(Offset.Zero) }

    // A thin tint, so the colours behind show through; without a blur it has
    // to carry the legibility on its own, so it is nearly solid.
    val fill = tint ?: when {
        layer == null -> colors.elevated.copy(alpha = 0.92f)
        colors.dark -> Color(0xFF15151E).copy(alpha = 0.4f)
        else -> Color.White.copy(alpha = 0.42f)
    }
    val light = if (colors.dark) Color.White.copy(alpha = 0.34f) else Color.White
    val shade = if (colors.dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.07f)

    this
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            if (layer != null && source != null && backdrop != null) {
                val offset = backdrop.origin - position
                layer.clip = true
                layer.renderEffect = effects.effectFor(size, cornerRadius(outline, size))
                layer.colorFilter = VIVID
                layer.record { translate(offset.x, offset.y) { drawLayer(source) } }
                clipPath(path) { drawLayer(layer) }
            }
            drawPath(path, fill)
            // Light from above: bright along the top edge, fading round the sides.
            drawPath(
                path,
                Brush.verticalGradient(listOf(light, shade, light.copy(alpha = light.alpha * 0.45f))),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
}

/** The glass sees the page slightly more saturated, the way thick glass does. */
private val VIVID = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.45f) })

private fun cornerRadius(outline: Outline, size: Size): Float = when (outline) {
    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
    is Outline.Rectangle -> 0f
    is Outline.Generic -> minOf(size.width, size.height) / 2f
}

/**
 * The blur (and, on Android 13+, the lens at the rim) for one surface,
 * rebuilt only when its size changes. The shader is compiled once and its
 * uniforms are captured each time an effect is made from it.
 */
private class GlassEffects(private val blurPx: Float, private val depthPx: Float) {
    private var size = Size.Unspecified
    private var radius = -1f
    private var effect: RenderEffect? = null
    private var shader: Any? = null

    fun effectFor(size: Size, radius: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < 31) return null
        if (size != this.size || radius != this.radius || effect == null) {
            this.size = size
            this.radius = radius
            effect = build(size, radius)
        }
        return effect
    }

    @RequiresApi(31)
    private fun build(size: Size, radius: Float): RenderEffect {
        val blur = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        if (Build.VERSION.SDK_INT >= 33) {
            val lens = runCatching { lens(size, radius) }.getOrNull()
            if (lens != null) return android.graphics.RenderEffect.createChainEffect(lens, blur).asComposeRenderEffect()
        }
        return blur.asComposeRenderEffect()
    }

    @RequiresApi(33)
    private fun lens(size: Size, radius: Float): android.graphics.RenderEffect {
        val runtime = (shader as? RuntimeShader) ?: RuntimeShader(LENS_SHADER).also { shader = it }
        runtime.setFloatUniform("size", size.width, size.height)
        runtime.setFloatUniform("radius", radius.coerceAtMost(minOf(size.width, size.height) / 2f))
        runtime.setFloatUniform("depth", depthPx)
        runtime.setFloatUniform("band", minOf(depthPx * 3f, minOf(size.width, size.height) / 2f))
        return android.graphics.RenderEffect.createRuntimeShaderEffect(runtime, "content")
    }
}

/**
 * Near the rim of a rounded rectangle, sample from further inside, so the
 * edge shows what is just within it, drawn out towards the border: the look
 * of light bending through a thick edge. The three colour channels bend by
 * slightly different amounts, as they do through real glass.
 */
private const val LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float depth;
uniform float band;

float box(float2 p, float2 extent, float r) {
    float2 q = abs(p) - extent + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 extent = size * 0.5;
    float2 p = coord - extent;
    float d = box(p, extent, radius);
    float t = clamp(1.0 + d / band, 0.0, 1.0);
    float bend = t * t * t;
    float2 n = float2(
        box(p + float2(1.0, 0.0), extent, radius) - box(p - float2(1.0, 0.0), extent, radius),
        box(p + float2(0.0, 1.0), extent, radius) - box(p - float2(0.0, 1.0), extent, radius));
    float len = length(n);
    n = len > 0.0001 ? n / len : float2(0.0, 0.0);
    float2 offset = n * depth * bend;
    half4 r = content.eval(coord - offset * 1.12);
    half4 g = content.eval(coord - offset);
    half4 b = content.eval(coord - offset * 0.88);
    return half4(r.r, g.g, b.b, g.a);
}
"""
