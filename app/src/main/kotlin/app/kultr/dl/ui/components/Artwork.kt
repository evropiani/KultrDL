package app.kultr.dl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kultr.dl.core.util.Format
import app.kultr.dl.ui.theme.Kultr
import coil3.compose.AsyncImage

/**
 * Cover art with a graceful placeholder: a gradient in the accent with the
 * initials of whatever it is, so a track without artwork still looks placed.
 */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: Shape = RoundedCornerShape(Kultr.radii.sm),
    label: String? = null,
    icon: ImageVector = Icons.Rounded.MusicNote,
) {
    val colors = Kultr.colors
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors.accent.copy(alpha = 0.55f), colors.accent.copy(alpha = 0.15f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                Format.initials(label),
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.3f).coerceIn(10f, 48f).sp,
            )
        } else {
            Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(size * 0.4f))
        }
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Artwork that fills its parent (square), for cards and headers. */
@Composable
fun ArtworkFill(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Kultr.radii.md),
    label: String? = null,
) {
    val colors = Kultr.colors
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors.accent.copy(alpha = 0.55f), colors.accent.copy(alpha = 0.12f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(Format.initials(label), color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 28.sp)
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(36.dp))
        }
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
