package app.kultr.dl.core.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The colour the interface takes from whatever is playing.
 *
 * Works on packed ARGB pixels (Android's `Bitmap.getPixels` layout) from a
 * small downscaled copy of the artwork: bucket colours coarsely and pick the
 * most common one that is neither near-black, near-white nor fully
 * desaturated, then lift it into a range that reads as an accent.
 */
object ArtworkColor {
    fun dominant(pixels: IntArray): Int? {
        val counts = HashMap<Int, IntArray>()
        for (argb in pixels) {
            val alpha = argb ushr 24 and 0xff
            if (alpha < 200) continue
            val r = argb shr 16 and 0xff
            val g = argb shr 8 and 0xff
            val b = argb and 0xff
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if (luma < 28 || luma > 235) continue
            val saturation = if (hi == 0) 0.0 else (hi - lo).toDouble() / hi
            if (saturation < 0.12) continue
            val key = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
            val bucket = counts.getOrPut(key) { IntArray(4) }
            bucket[0]++
            bucket[1] += r
            bucket[2] += g
            bucket[3] += b
        }
        val best = counts.values.maxByOrNull { it[0] } ?: return null
        val n = best[0]
        return lift(best[1] / n, best[2] / n, best[3] / n)
    }

    /** Nudge a sampled colour into a range that still reads as an accent. */
    fun lift(r: Int, g: Int, b: Int): Int {
        val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
        var scale = 1.0
        if (luma < 90) scale = 90 / max(luma, 1.0)
        if (luma > 200) scale = 200 / luma
        return rgb(
            min(255, (r * scale).roundToInt()),
            min(255, (g * scale).roundToInt()),
            min(255, (b * scale).roundToInt()),
        )
    }

    fun rgb(r: Int, g: Int, b: Int): Int = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    /** "#7c8cff" → ARGB, or null. */
    fun parseHex(hex: String): Int? {
        val match = Regex("^#?([0-9a-fA-F]{6})$").matchEntire(hex.trim()) ?: return null
        return (0xff shl 24) or match.groupValues[1].toInt(16)
    }

    fun toHex(argb: Int): String = "#%06x".format(argb and 0xffffff)

    /** Relative luminance (as WCAG defines it): 0 for black, 1 for white. */
    fun luminance(argb: Int): Double {
        fun linear(c: Int): Double {
            val v = (c and 0xff) / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(argb shr 16) + 0.7152 * linear(argb shr 8) + 0.0722 * linear(argb)
    }

    /**
     * [argb] darkened until it reads as text on a light background (luminance
     * at most 0.22). The hue stays, and pale colours gain a little saturation
     * so they turn deeper rather than grey. Dark enough colours are unchanged.
     */
    fun readableOnLight(argb: Int): Int {
        val target = 0.22
        if (luminance(argb) <= target) return argb
        val r = (argb shr 16 and 0xff) / 255.0
        val g = (argb shr 8 and 0xff) / 255.0
        val b = (argb and 0xff) / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val hue = when {
            hi == lo -> 0.0
            hi == r -> 60 * (((g - b) / (hi - lo)).mod(6.0))
            hi == g -> 60 * ((b - r) / (hi - lo) + 2)
            else -> 60 * ((r - g) / (hi - lo) + 4)
        }
        var saturation = if (hi == 0.0) 0.0 else (hi - lo) / hi
        if (saturation > 0.05) saturation = min(1.0, max(saturation, 0.45) * 1.1)
        var value = hi
        var color = argb
        while (value > 0.3) {
            color = fromHsv(hue, saturation, value)
            if (luminance(color) <= target) break
            value -= 0.02
        }
        return color
    }

    private fun fromHsv(hue: Double, saturation: Double, value: Double): Int {
        val c = value * saturation
        val x = c * (1 - abs((hue / 60).mod(2.0) - 1))
        val m = value - c
        val (r, g, b) = when {
            hue < 60 -> Triple(c, x, 0.0)
            hue < 120 -> Triple(x, c, 0.0)
            hue < 180 -> Triple(0.0, c, x)
            hue < 240 -> Triple(0.0, x, c)
            hue < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return rgb(ch(r), ch(g), ch(b))
    }

    /** Mix [b] into [a] by [amount] (0..1). */
    fun mix(a: Int, b: Int, amount: Double): Int {
        val t = amount.coerceIn(0.0, 1.0)
        fun ch(shift: Int) = (((a shr shift) and 0xff) * (1 - t) + ((b shr shift) and 0xff) * t).roundToInt()
        return rgb(ch(16), ch(8), ch(0))
    }
}
