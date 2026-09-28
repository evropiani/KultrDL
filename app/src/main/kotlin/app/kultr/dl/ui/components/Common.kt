package app.kultr.dl.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kultr.dl.ui.theme.EyebrowStyle
import app.kultr.dl.ui.theme.Kultr

/** A translucent panel with an accent-tinted edge — Kultr's "glass". */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Kultr.radii.lg),
    strong: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val colors = Kultr.colors
    Box(
        modifier
            .clip(shape)
            .background(if (strong) colors.glassStrong else colors.glass)
            .border(BorderStroke(1.dp, colors.edge), shape)
            .padding(padding),
    ) { content() }
}

/** Rounded pill button, optionally filled with the accent. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Boolean = false,
    enabled: Boolean = true,
    badge: String? = null,
) {
    val colors = Kultr.colors
    val shape = RoundedCornerShape(50)
    val background = when {
        accent -> colors.accent
        else -> colors.glass
    }
    val content = if (accent) colors.onAccent else colors.ink
    Row(
        modifier
            .clip(shape)
            .background(background.copy(alpha = if (enabled) background.alpha else background.alpha * 0.5f))
            .border(BorderStroke(1.dp, if (accent) Color.Transparent else colors.edge), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Centred, for a pill stretched wider than its label.
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(18.dp))
        Text(text, color = content.copy(alpha = if (enabled) 1f else 0.5f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        if (badge != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (accent) colors.onAccent.copy(alpha = 0.18f) else colors.accentSoft)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) { Text(badge, color = content, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** A small chip used for years, genres, formats. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val colors = Kultr.colors
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(colors.glass)
            .border(BorderStroke(1.dp, colors.edge), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, color = colors.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Kultr.colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Kultr.colors.ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        action?.invoke(this)
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = EyebrowStyle, color = Kultr.colors.ink3, modifier = modifier)
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Kultr.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Kultr.colors.accent, modifier = Modifier.size(28.dp)) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = Kultr.colors.ink, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Kultr.colors.ink2, textAlign = TextAlign.Center)
        }
        action?.invoke()
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Kultr.colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
    }
}

/** A settings-style row: label and hint on the left, a control on the right. */
@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onClick: (() -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3)
        }
        if (control != null) {
            Spacer(Modifier.width(12.dp))
            control()
        }
    }
}

/** A thin horizontal rule in the theme's line colour. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Kultr.colors.line))
}

/** A soft wash of the accent behind a header, fading into the background. */
@Composable
fun AccentWash(modifier: Modifier = Modifier, height: Dp = 320.dp) {
    val colors = Kultr.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Brush.verticalGradient(listOf(colors.accent.copy(alpha = 0.28f), Color.Transparent))),
    )
}

/** The drifting colour field behind screens that have no artwork of their own. */
@Composable
fun ArtworkBackdropPlain(modifier: Modifier = Modifier) {
    val colors = Kultr.colors
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .background(
                Brush.radialGradient(
                    listOf(colors.accent.copy(alpha = if (colors.dark) 0.22f else 0.16f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(0f, 0f),
                    radius = 1400f,
                ),
            ),
    )
}

@Composable
fun FullScreenCenter(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun AccentSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, color = Kultr.colors.accentSoft, shape = RoundedCornerShape(Kultr.radii.md), content = content)
}
