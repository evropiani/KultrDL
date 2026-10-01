package app.kultr.dl.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.util.Format
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.chromePadding
import app.kultr.dl.ui.components.EmptyState
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.components.TextDialog
import app.kultr.dl.ui.theme.Kultr

/** Artists the user never wants to hear: add one by name, or unblock. */
@Composable
fun BlockedArtistsScreen() {
    val actions = LocalActions.current
    val data by actions.graph.taste.data.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    val blocked = data.blocked.sortedBy { it.name.lowercase() }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = chromePadding(24.dp))) {
        item(key = "head") {
            PageHeader("Blocked artists")
            Text(
                "Their songs, and every song they're on (“feat.”, “with”, shared credits), are hidden in search, albums, " +
                    "playlists, your library and suggestions, and skipped if they come up in the queue. Your downloads stay where they are.",
                style = MaterialTheme.typography.bodyMedium,
                color = Kultr.colors.ink2,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Pill("Block an artist", icon = Icons.Rounded.Add, accent = true, onClick = { adding = true })
            }
        }
        if (blocked.isEmpty()) {
            item(key = "empty") {
                EmptyState(Icons.Rounded.Block, "No one blocked", body = "Choose “Block artist…” in any song's menu, or add a name here.")
            }
        }
        items(blocked, key = { it.name }) { artist ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Block, contentDescription = null, tint = Kultr.colors.danger)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(artist.name, style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Blocked ${Format.ago(artist.at)}", style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3)
                }
                TextButton(onClick = { actions.unblock(artist.name) }) { Text("Unblock") }
            }
        }
    }
    if (adding) {
        TextDialog(
            title = "Block an artist",
            initial = "",
            placeholder = "Artist name, as it's written",
            confirm = "Block",
            onConfirm = { name ->
                adding = false
                actions.block(listOf(name))
            },
            onDismiss = { adding = false },
        )
    }
}
