package app.kultr.dl.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.discover.Feed
import app.kultr.dl.core.util.Format
import app.kultr.dl.engine.Recommender
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.Routes
import app.kultr.dl.ui.components.GlassPanel
import app.kultr.dl.ui.components.MixCard
import app.kultr.dl.ui.components.PickCard
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.components.SectionHeader
import app.kultr.dl.ui.components.Shelf
import app.kultr.dl.ui.components.SuggestedTrackCard
import app.kultr.dl.ui.components.page
import app.kultr.dl.ui.theme.Kultr

/**
 * "For you" at the top of Home: new releases from the user's artists,
 * mixes made for them, albums to try, albums missing from their
 * collection, and old favourites. Long-press anything to steer it.
 */
@Composable
fun ForYouSection() {
    val actions = LocalActions.current
    val graph = actions.graph
    val feed by graph.recommender.feed.collectAsStateWithLifecycle()
    val status by graph.recommender.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { graph.recommender.refreshIfStale() }

    Column(Modifier.fillMaxWidth()) {
        SectionHeader("For you", icon = Icons.Rounded.AutoAwesome, action = {
            when (status) {
                is Recommender.Status.Working -> CircularProgressIndicator(color = Kultr.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else -> TextButton(onClick = { graph.recommender.refreshInBackground() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh")
                }
            }
        })
        val line = when (val s = status) {
            is Recommender.Status.Working -> "${s.step}…"
            is Recommender.Status.Failed -> "Couldn't update: ${s.message}"
            else -> feed?.let { f ->
                "Updated ${Format.ago(f.builtAt)}" + if (f.offline) " · offline, from what's on hand" else ""
            }
        }
        if (line != null) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp))
        }
        val f = feed
        if (f == null || f.isEmpty) {
            if (status !is Recommender.Status.Working) Intro(f)
        } else {
            Shelves(f)
        }
    }
}

@Composable
private fun Shelves(feed: Feed) {
    val actions = LocalActions.current
    if (feed.releases.isNotEmpty()) {
        SectionHeader("New releases", icon = Icons.Rounded.NewReleases)
        Shelf(feed.releases, key = { it.key }) { _, pick, modifier ->
            PickCard(pick, onClick = { actions.openCollection(pick.page()) }, modifier = modifier, showDate = true)
        }
    }
    if (feed.mixes.isNotEmpty()) {
        SectionHeader("Made for you", icon = Icons.AutoMirrored.Rounded.QueueMusic)
        Shelf(feed.mixes, key = { it.id }, cardWidth = 160.dp) { _, mix, modifier ->
            MixCard(mix, onClick = { actions.openCollection(mix.asCollection()) }, modifier = modifier)
        }
    }
    if (feed.albums.isNotEmpty()) {
        SectionHeader("Albums for you")
        Shelf(feed.albums, key = { it.key }) { _, pick, modifier ->
            PickCard(pick, onClick = { actions.openCollection(pick.page()) }, modifier = modifier)
        }
    }
    if (feed.missing.isNotEmpty()) {
        SectionHeader("Missing from your collection", icon = Icons.Rounded.LibraryAdd)
        Shelf(feed.missing, key = { it.key }) { _, pick, modifier ->
            PickCard(pick, onClick = { actions.openCollection(pick.page()) }, modifier = modifier)
        }
    }
    if (feed.rediscover.isNotEmpty()) {
        SectionHeader("Rediscover", icon = Icons.Rounded.History, action = {
            TextButton(onClick = { actions.play(feed.rediscover) }) { Text("Play all") }
        })
        Shelf(feed.rediscover, key = { it.id }) { index, track, modifier ->
            SuggestedTrackCard(track, onClick = { actions.play(feed.rediscover, index) }, modifier = modifier)
        }
    }
    Text(
        "Long-press a suggestion for “More like this”, “Not interested” or “Never this artist”.",
        style = MaterialTheme.typography.bodySmall,
        color = Kultr.colors.ink3,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** Nothing to suggest yet: say where suggestions come from, and how to give them more to go on. */
@Composable
private fun Intro(feed: Feed?) {
    val actions = LocalActions.current
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (feed == null) "Suggestions are on their way" else "Nothing to suggest yet",
                style = MaterialTheme.typography.titleMedium,
                color = Kultr.colors.ink,
            )
            Text(
                "They grow from what you play, heart, save and download here — and, if you like, from the music files on this phone, " +
                    "your Navidrome, Last.fm or ListenBrainz. New releases from your artists show up here too.",
                style = MaterialTheme.typography.bodyMedium,
                color = Kultr.colors.ink2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Set up", icon = Icons.Rounded.Settings, accent = true, onClick = { actions.navigate(Routes.SETTINGS) })
                Pill("Refresh", icon = Icons.Rounded.Refresh, onClick = { actions.graph.recommender.refreshInBackground() })
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
