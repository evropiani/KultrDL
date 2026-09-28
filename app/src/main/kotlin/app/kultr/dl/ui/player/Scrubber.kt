package app.kultr.dl.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kultr.dl.KultrDLApp
import app.kultr.dl.core.util.Format
import app.kultr.dl.ui.theme.Kultr
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** The playback position, polled while on screen. */
@Composable
fun rememberPosition(intervalMs: Long = 250): State<Long> {
    val connection = KultrDLApp.graph.player
    return produceState(connection.positionMs()) {
        while (isActive) {
            value = connection.positionMs()
            delay(intervalMs)
        }
    }
}

/** The progress bar. Drag or tap to seek. */
@Composable
fun Scrubber(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = Kultr.colors
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { offset -> if (durationMs > 0) onSeek((offset.x / size.width * durationMs).toLong()) }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { if (durationMs > 0) onSeek((it * durationMs).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                        onHorizontalDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val h = 4.dp.toPx()
                val y = size.height / 2
                val radius = CornerRadius(h / 2, h / 2)
                drawRoundRect(colors.ink4, Offset(0f, y - h / 2), Size(size.width, h), radius)
                val played = fraction * size.width
                drawRoundRect(colors.accent, Offset(0f, y - h / 2), Size(played, h), radius)
                val thumb = if (dragFraction != null) 9.dp.toPx() else 6.dp.toPx()
                drawCircle(colors.accent, thumb, Offset(played, y))
                drawCircle(Color.White.copy(alpha = 0.9f), thumb * 0.35f, Offset(played, y))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            val shown = dragFraction?.let { (it * durationMs).toLong() } ?: positionMs
            Text(Format.duration(shown.coerceAtLeast(0)).ifEmpty { "0:00" }, color = colors.ink3, fontSize = 12.sp, style = Tabular)
            Spacer(Modifier.weight(1f))
            Text(if (durationMs > 0) Format.duration(durationMs) else "--:--", color = colors.ink3, fontSize = 12.sp, style = Tabular)
        }
    }
}

private val Tabular = TextStyle(fontFeatureSettings = "tnum")
