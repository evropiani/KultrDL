package app.kultr.dl.ui.screens

import android.util.Log
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.links.Links
import app.kultr.dl.core.model.LinkResult
import app.kultr.dl.core.model.SearchResults
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.Format
import app.kultr.dl.data.describe
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.LocalBlocks
import app.kultr.dl.ui.chromePadding
import app.kultr.dl.ui.components.ArtworkFill
import app.kultr.dl.ui.components.CollectionCard
import app.kultr.dl.ui.components.EmptyState
import app.kultr.dl.ui.components.Eyebrow
import app.kultr.dl.ui.components.GlassPanel
import app.kultr.dl.ui.components.Loading
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.components.SectionHeader
import app.kultr.dl.ui.components.Segmented
import app.kultr.dl.ui.components.Shelf
import app.kultr.dl.ui.components.TrackRow
import app.kultr.dl.ui.components.trackItems
import app.kultr.dl.ui.theme.Kultr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Search one source at a time, or open a link from any of them. The field
 * itself lives in the floating bar; this page shows what it finds.
 */
@Composable
fun SearchScreen(query: String) {
    val actions = LocalActions.current
    val graph = actions.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val player by graph.player.ui.collectAsStateWithLifecycle()
    val blocks = LocalBlocks.current
    val recent by graph.library.recentSearches.collectAsStateWithLifecycle(emptyList())
    val source = settings.searchSource
    val trimmed = query.trim()
    val isLink = Links.isLink(trimmed)

    var results by remember { mutableStateOf(SearchResults()) }
    var link by remember { mutableStateOf<LinkResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(trimmed, source) {
        error = null
        if (trimmed.length < 2) {
            results = SearchResults()
            link = null
            loading = false
            return@LaunchedEffect
        }
        delay(if (isLink) 50 else 450)
        loading = true
        try {
            if (isLink) {
                results = SearchResults()
                link = graph.catalog.resolve(trimmed)
            } else {
                link = null
                results = graph.catalog.search(source, trimmed)
                graph.library.rememberSearch(trimmed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("KultrDL", if (isLink) "Couldn't open $trimmed" else "Search on ${source.label} failed", e)
            error = describe(e)
            results = SearchResults()
            link = null
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Search", style = MaterialTheme.typography.headlineMedium, color = Kultr.colors.ink, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        if (!isLink) {
            Segmented(
                options = Source.searchSources.map { it to it.label },
                selected = source,
                onSelect = { s -> graph.settings.update { it.copy(searchSource = s) } },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
            )
            if (!source.streams) {
                Text(
                    "${source.label} tracks play and download from the matching recording on YouTube Music.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Kultr.colors.ink3,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 4.dp, bottom = chromePadding())) {
            when {
                trimmed.length < 2 -> {
                    if (recent.isNotEmpty()) {
                        item(key = "recent-head") {
                            SectionHeader("Recent searches", icon = Icons.Rounded.History, action = {
                                TextButton(onClick = { actions.launch { graph.library.clearSearches() } }) { Text("Clear") }
                            })
                        }
                        items(recent, key = { "r:$it" }) { q ->
                            Row(
                                Modifier.fillMaxWidth().clickable { actions.search(q) }.padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = null, tint = Kultr.colors.ink3, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(q, color = Kultr.colors.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { actions.launch { graph.library.forgetSearch(q) } }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Forget", tint = Kultr.colors.ink3)
                                }
                            }
                        }
                    } else {
                        item(key = "hint") {
                            EmptyState(
                                Icons.Rounded.Search,
                                "Find anything",
                                body = "Type a song, artist or album, or paste a link from Spotify, Apple Music, Tidal, Qobuz, Deezer, Amazon Music, YouTube, SoundCloud or Bandcamp.",
                            )
                        }
                    }
                }
                loading -> item(key = "loading") { Loading() }
                error != null -> item(key = "error") { EmptyState(Icons.Rounded.CloudOff, if (isLink) "Couldn't open that link" else "Search failed", body = error) }
                link != null -> linkResult(link!!, player.current?.id) { tracks, i -> actions.play(tracks, i) }
                results.isEmpty -> item(key = "none") { EmptyState(Icons.Rounded.Search, "Nothing matches “$trimmed”", body = "Try another source above.") }
                else -> {
                    // Albums by blocked artists are left out (their songs are hidden row by row).
                    val albums = results.collections.filterNot(blocks::blocks)
                    if (albums.isNotEmpty()) {
                        item(key = "albums") {
                            SectionHeader("Albums")
                            Shelf(albums, key = { it.id }) { _, c, modifier -> CollectionCard(c, { actions.openCollection(c) }, modifier) }
                        }
                    }
                    if (results.tracks.isNotEmpty()) {
                        item(key = "tracks-head") {
                            SectionHeader("Tracks", action = { TextButton(onClick = { actions.play(results.tracks) }) { Text("Play all") } })
                        }
                        trackItems(results.tracks, player.current?.id, onPlay = { actions.play(results.tracks, it) }, keyPrefix = "s")
                    }
                }
            }
        }
    }
}

private fun LazyListScope.linkResult(result: LinkResult, currentId: String?, onPlay: (List<Track>, Int) -> Unit) {
    when (result) {
        is LinkResult.Single -> item(key = "link-single") {
            val actions = LocalActions.current
            val track = result.track
            GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Link, contentDescription = null, tint = Kultr.colors.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Eyebrow("${track.source.label} track")
                    }
                    TrackRow(track, onClick = { actions.play(listOf(track)) }, isCurrent = track.id == currentId)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Play", icon = Icons.Rounded.PlayArrow, accent = true, onClick = { actions.play(listOf(track)) })
                        Pill("Download", icon = Icons.Rounded.Download, onClick = { actions.download(listOf(track)) })
                    }
                }
            }
        }
        is LinkResult.Many -> {
            val c = result.collection
            item(key = "link-many") {
                val actions = LocalActions.current
                GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArtworkFill(c.artworkUrl, Modifier.size(92.dp), label = c.title)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Eyebrow("${c.source.label} ${c.kind.name.lowercase()}")
                            Text(c.title, style = MaterialTheme.typography.titleMedium, color = Kultr.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(c.subtitle, Format.count(c.tracks.size, "track")).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Kultr.colors.ink3,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(4.dp))
                            Pill("Open", onClick = { actions.openCollection(c) }, accent = true)
                        }
                    }
                }
            }
            trackItems(c.tracks, currentId, onPlay = { i -> onPlay(c.tracks, i) }, keyPrefix = "l")
        }
    }
}
