package app.kultr.dl.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.CollectionKind
import app.kultr.dl.core.model.Track
import app.kultr.dl.ui.theme.Kultr

@Composable
fun CollectionCard(collection: Collection, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick).padding(6.dp)) {
        ArtworkFill(collection.artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f), label = collection.title)
        Spacer(Modifier.height(8.dp))
        Text(
            collection.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Kultr.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val kind = if (collection.kind == CollectionKind.ALBUM) "Album" else "Playlist"
        val sub = listOfNotNull(collection.subtitle, collection.year?.toString() ?: kind).joinToString(" · ")
        Text(sub, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TrackCard(track: Track, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick).padding(6.dp)) {
        ArtworkFill(track.artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f), label = track.title)
        Spacer(Modifier.height(8.dp))
        Text(
            track.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Kultr.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A horizontally scrolling row of cards. */
@Composable
fun <T> Shelf(
    items: List<T>,
    key: (T) -> String,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 150.dp,
    card: @Composable (Int, T, Modifier) -> Unit,
) {
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(items, key = { i, item -> "$i:${key(item)}" }) { index, item -> card(index, item, Modifier.width(cardWidth)) }
    }
}
