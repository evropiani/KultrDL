package app.kultr.dl.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.CollectionKind
import app.kultr.dl.core.util.Format
import app.kultr.dl.data.describe
import app.kultr.dl.ui.CollectionCache
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.LocalBlocks
import app.kultr.dl.ui.chromePadding
import app.kultr.dl.ui.components.AccentWash
import app.kultr.dl.ui.components.ArtworkFill
import app.kultr.dl.ui.components.EmptyState
import app.kultr.dl.ui.components.Eyebrow
import app.kultr.dl.ui.components.Loading
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.components.trackItems
import app.kultr.dl.ui.theme.Kultr

private sealed interface Loaded {
    data object Loading : Loaded
    data class Ready(val collection: Collection) : Loaded
    data class Failed(val message: String) : Loaded
}

/** An album or playlist from any source, with its tracks. */
@Composable
fun CollectionScreen(id: String) {
    val actions = LocalActions.current
    val graph = actions.graph
    val player by graph.player.ui.collectAsStateWithLifecycle()
    val state by produceState<Loaded>(Loaded.Loading, id) {
        val shell = CollectionCache.get(id)
        value = if (shell == null) {
            Loaded.Failed("This page was closed by the system. Open it again from search.")
        } else {
            try {
                val full = graph.catalog.load(shell)
                CollectionCache.put(full)
                Loaded.Ready(full)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Loaded.Failed(describe(e))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AccentWash()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chromePadding())) {
            item(key = "back") {
                IconButton(onClick = { actions.back() }, modifier = Modifier.statusBarsPadding().padding(start = 4.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            }
            when (val s = state) {
                Loaded.Loading -> item(key = "loading") {
                    CollectionCache.get(id)?.let { Header(it) }
                    Loading()
                }
                is Loaded.Failed -> item(key = "failed") { EmptyState(Icons.Rounded.CloudOff, "Couldn't open this", body = s.message) }
                is Loaded.Ready -> {
                    val c = s.collection
                    item(key = "header") {
                        Header(c)
                        Actions(c)
                    }
                    trackItems(
                        c.tracks,
                        player.current?.id,
                        onPlay = { actions.play(c.tracks, it) },
                        keyPrefix = "c",
                        numbered = c.kind == CollectionKind.ALBUM,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(c: Collection) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtworkFill(
            c.artworkUrl,
            Modifier.size(220.dp).shadow(20.dp, RoundedCornerShape(Kultr.radii.lg)),
            shape = RoundedCornerShape(Kultr.radii.lg),
            label = c.title,
        )
        Spacer(Modifier.height(16.dp))
        Eyebrow(
            when {
                c.id.startsWith("mix:") -> "Made for you"
                c.recordType == "single" -> "${c.source.label} · Single"
                c.recordType == "ep" -> "${c.source.label} · EP"
                else -> "${c.source.label} · ${if (c.kind == CollectionKind.ALBUM) "Album" else "Playlist"}"
            },
        )
        Spacer(Modifier.height(4.dp))
        Text(c.title, style = MaterialTheme.typography.headlineSmall, color = Kultr.colors.ink, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(
            c.subtitle,
            Format.released(c.releaseDate)?.takeIf { c.releaseDate != null && c.year == java.time.LocalDate.now().year } ?: c.year?.toString(),
            (c.tracks.size.takeIf { it > 0 } ?: c.trackCount)?.let { Format.count(it, "track") },
            c.tracks.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()?.let { Format.duration(it) },
        ).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodyMedium, color = Kultr.colors.ink2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun Actions(c: Collection) {
    val actions = LocalActions.current
    val blocks = LocalBlocks.current
    val feed by actions.graph.recommender.feed.collectAsStateWithLifecycle()
    val mix = if (c.id.startsWith("mix:")) feed?.mixes?.firstOrNull { "mix:" + it.id == c.id } else null
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill("Play", icon = Icons.Rounded.PlayArrow, accent = true, onClick = { actions.play(c.tracks) })
        Pill("Shuffle", icon = Icons.Rounded.Shuffle, onClick = { actions.shuffle(c.tracks) })
        if (mix != null) Pill("Keep updated", icon = Icons.Rounded.Autorenew, onClick = { actions.followMix(mix) })
        Pill("Download all", icon = Icons.Rounded.Download, onClick = { actions.download(c.tracks) })
        if (actions.canDownloadToNavidrome()) {
            Pill("Download to Navidrome", icon = Icons.Rounded.CloudDownload, onClick = { actions.downloadToNavidrome(c.tracks) })
        }
        Pill("Download as…", icon = Icons.Rounded.Tune, onClick = { actions.downloadAs(c.tracks) })
        Pill("Save as playlist", icon = Icons.AutoMirrored.Rounded.PlaylistAdd, onClick = {
            actions.launch {
                actions.graph.library.createPlaylist(c.title, c.tracks, sourceUrl = c.pageUrl, artworkUrl = c.artworkUrl)
                actions.graph.messages.show("Saved “${c.title}” to your playlists")
            }
        })
        Pill("Save tracks", icon = Icons.Rounded.BookmarkAdd, onClick = { actions.setSaved(c.tracks, true) })
    }
    val hidden = c.tracks.count(blocks::blocks)
    if (hidden > 0) {
        Text(
            "${Format.count(hidden, "song")} by blocked artists hidden",
            style = MaterialTheme.typography.bodySmall,
            color = Kultr.colors.ink3,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
}
