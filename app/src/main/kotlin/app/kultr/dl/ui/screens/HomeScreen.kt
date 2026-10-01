package app.kultr.dl.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.R
import app.kultr.dl.core.links.Links
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.Format
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.LocalBlocks
import app.kultr.dl.ui.Routes
import app.kultr.dl.ui.chromePadding
import app.kultr.dl.ui.components.CollectionCard
import app.kultr.dl.ui.components.GlassPanel
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.components.SectionHeader
import app.kultr.dl.ui.components.Shelf
import app.kultr.dl.ui.components.Tag
import app.kultr.dl.ui.components.TrackCard
import app.kultr.dl.ui.components.TrackRow
import app.kultr.dl.ui.theme.Kultr
import app.kultr.dl.core.model.Collection as TrackCollection
import app.kultr.dl.core.model.CollectionKind
import java.util.Calendar

@Composable
fun HomeScreen() {
    val actions = LocalActions.current
    val graph = actions.graph
    val context = LocalContext.current
    val blocks = LocalBlocks.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val allHistory by remember { graph.library.history(20) }.collectAsStateWithLifecycle(emptyList())
    val allFavorites by graph.library.favorites.collectAsStateWithLifecycle(emptyList())
    val history = remember(allHistory, blocks) { blocks.tracks(allHistory) }
    val favorites = remember(allFavorites, blocks) { blocks.tracks(allFavorites) }
    val playlists by graph.library.playlistSummaries.collectAsStateWithLifecycle(emptyList())
    val player by graph.player.ui.collectAsStateWithLifecycle()
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val allCharts by produceState<List<Track>>(emptyList()) {
        value = runCatching { graph.catalog.apple.topSongs(25) }.getOrElse { runCatching { graph.catalog.deezer.chart() }.getOrDefault(emptyList()) }
    }
    val charts = remember(allCharts, blocks) { blocks.tracks(allCharts) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chromePadding())) {
        item(key = "head") {
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(R.drawable.kultrdl_logo),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(Format.greeting(hour), style = MaterialTheme.typography.headlineMedium, color = Kultr.colors.ink)
                }
                Spacer(Modifier.height(14.dp))
                LinkCard(onPaste = {
                    val text = clipboardText(context)
                    val link = text?.let(Links::find)
                    if (link != null) actions.search(link) else graph.messages.show("There's no link on the clipboard.")
                })
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Source.searchSources.forEach { source ->
                        Tag(source.label, onClick = {
                            graph.settings.update { it.copy(searchSource = source) }
                            actions.search("")
                        })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (settings.suggestions) {
            item(key = "for-you") { ForYouSection() }
        }
        if (history.isNotEmpty()) {
            item(key = "history") {
                SectionHeader("Jump back in", icon = Icons.Rounded.History)
                Shelf(history, key = { it.id }) { index, track, modifier -> TrackCard(track, { actions.play(history, index) }, modifier) }
            }
        }
        if (favorites.isNotEmpty()) {
            item(key = "favorites") {
                SectionHeader("Favourites", icon = Icons.Rounded.Favorite, action = {
                    TextButton(onClick = { actions.shuffle(favorites) }) { Text("Shuffle") }
                })
                Shelf(favorites.take(20), key = { it.id }) { index, track, modifier -> TrackCard(track, { actions.play(favorites, index) }, modifier) }
            }
        }
        if (playlists.isNotEmpty()) {
            item(key = "playlists") {
                SectionHeader("Your playlists", action = { TextButton(onClick = { actions.navigate(Routes.library("PLAYLISTS")) }) { Text("See all") } })
                Shelf(playlists, key = { it.id.toString() }) { _, p, modifier ->
                    CollectionCard(
                        TrackCollection(id = "local:${p.id}", source = Source.WEB, kind = CollectionKind.PLAYLIST, title = p.name, subtitle = Format.count(p.count, "track"), artworkUrl = p.artworkUrl),
                        onClick = { actions.openPlaylist(p.id) },
                        modifier = modifier,
                    )
                }
            }
        }
        if (charts.isNotEmpty()) {
            item(key = "charts-head") {
                SectionHeader("Top songs right now", icon = Icons.AutoMirrored.Rounded.TrendingUp, action = {
                    TextButton(onClick = { actions.play(charts) }) { Text("Play all") }
                })
            }
            charts.take(15).forEachIndexed { index, track ->
                item(key = "chart:$index:${track.id}") {
                    TrackRow(track, onClick = { actions.play(charts, index) }, number = index + 1, isCurrent = player.current?.id == track.id)
                }
            }
        }
        if (history.isEmpty() && favorites.isEmpty()) {
            item(key = "welcome") { Welcome() }
        }
    }
}

private fun clipboardText(context: Context): String? = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

@Composable
private fun LinkCard(onPaste: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Got a link?", style = MaterialTheme.typography.titleMedium, color = Kultr.colors.ink)
            Text(
                "Spotify, Apple Music, Tidal, Qobuz, Deezer, Amazon Music, YouTube, SoundCloud, Bandcamp and more. " +
                    "Paste it, or share it to KultrDL from any app.",
                style = MaterialTheme.typography.bodyMedium,
                color = Kultr.colors.ink2,
            )
            Pill("Paste link", onClick = onPaste, icon = Icons.Rounded.ContentPaste, accent = true)
        }
    }
}

@Composable
private fun Welcome() {
    val actions = LocalActions.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(painterResource(R.drawable.kultrdl_logo), contentDescription = null, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)))
        Text("Find something to play", style = MaterialTheme.typography.titleLarge, color = Kultr.colors.ink)
        Text(
            "Search YouTube Music, Spotify, Apple Music, Deezer, SoundCloud or Bandcamp with the button at the bottom right. " +
                "Heart what you love, save it to your library and download it as FLAC, MP3 and more.",
            style = MaterialTheme.typography.bodyMedium,
            color = Kultr.colors.ink2,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Search", icon = Icons.Rounded.Search, accent = true, onClick = { actions.search("") })
            Pill("Downloads", icon = Icons.Rounded.Download, onClick = { actions.openDownloads() })
        }
    }
}
