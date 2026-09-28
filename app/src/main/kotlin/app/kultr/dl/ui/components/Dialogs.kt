package app.kultr.dl.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.Format
import app.kultr.dl.data.Destination
import app.kultr.dl.data.folderLabel
import app.kultr.dl.engine.AudioFormat
import app.kultr.dl.engine.DownloadPreset
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.Routes
import app.kultr.dl.ui.theme.Kultr

@Composable
fun AddToPlaylistDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val library = actions.graph.library
    val playlists by library.playlistSummaries.collectAsStateWithLifecycle(emptyList())
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        TextDialog(
            title = "New playlist",
            initial = "",
            placeholder = "Name",
            confirm = "Create",
            onConfirm = { name ->
                actions.launch {
                    library.createPlaylist(name, tracks)
                    actions.graph.messages.show("Created “$name”")
                }
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Kultr.colors.elevated,
        title = { Text(if (tracks.size == 1) "Add “${tracks[0].title}” to…" else "Add ${tracks.size} tracks to…") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    SettingRow("New playlist…", onClick = { creating = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = Kultr.colors.accent)
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    SettingRow(p.name, hint = Format.count(p.count, "track"), onClick = {
                        actions.launch {
                            library.addToPlaylist(p.id, tracks)
                            actions.graph.messages.show("Added to “${p.name}”")
                        }
                        onDismiss()
                    }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Kultr.colors.ink3) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Choose a format and quality for a download: FLAC, MP3, AAC, Opus, ALAC,
 * WAV or Ogg Vorbis, each with its own bitrates or bit depths — and, once a
 * server is saved, where it goes.
 */
@Composable
fun DownloadAsDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val settings by actions.graph.settings.settings.collectAsStateWithLifecycle()
    val servers by actions.graph.servers.servers.collectAsStateWithLifecycle()
    var preset by remember { mutableStateOf(settings.download) }
    var destination by remember { mutableStateOf(settings.destination?.takeIf { d -> servers.any { it.id == d.serverId } }) }
    var remember by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Kultr.colors.elevated,
        title = { Text(if (tracks.size == 1) "Download “${tracks[0].title}”" else "Download ${tracks.size} tracks") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FormatPicker(preset) { preset = it }
                if (servers.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    DestinationPicker(destination) { destination = it }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { remember = !remember },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("Use for every download", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (remember) actions.graph.settings.update { it.copy(download = preset, destination = destination, askEachTime = false) }
                actions.download(tracks, preset, destination)
                onDismiss()
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * This phone, or one of the folders saved on a server; with a server, whether
 * to keep a copy on the phone too.
 */
@Composable
fun DestinationPicker(selected: Destination?, allowPhone: Boolean = true, onSelect: (Destination?) -> Unit) {
    val actions = LocalActions.current
    val servers by actions.graph.servers.servers.collectAsStateWithLifecycle()
    Column {
        Text(if (allowPhone) "Save to" else "Send to", style = MaterialTheme.typography.labelLarge, color = Kultr.colors.ink2)
        if (allowPhone) DestinationRow("This phone", "Music/KultrDL", selected == null) { onSelect(null) }
        for (server in servers) {
            for (folder in server.folders.ifEmpty { listOf("") }) {
                val on = selected != null && selected.serverId == server.id && selected.folder == folder
                DestinationRow(server.name, "${server.protocol.label} · ${folderLabel(folder)}", on) {
                    onSelect(Destination(server.id, folder, selected?.keepOnPhone ?: false))
                }
            }
        }
        if (servers.isEmpty()) {
            TextButton(onClick = { actions.navigate(Routes.server(Routes.NEW)) }) { Text("Add an FTP or SFTP server…") }
        }
        if (allowPhone && selected != null) {
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(selected.copy(keepOnPhone = !selected.keepOnPhone)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = selected.keepOnPhone, onCheckedChange = { onSelect(selected.copy(keepOnPhone = it)) })
                Text("Keep a copy on this phone too", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DestinationRow(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink, maxLines = 1)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3, maxLines = 1)
        }
    }
}

/** Sends tracks that are on the phone to a server folder. */
@Composable
fun SendToServerDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val servers = actions.graph.servers.servers.value
    val saved = actions.graph.settings.settings.value.destination
    var destination by remember {
        mutableStateOf(
            saved?.takeIf { d -> servers.any { it.id == d.serverId } }
                ?: servers.firstOrNull()?.let { Destination(it.id, it.folders.firstOrNull().orEmpty()) },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Kultr.colors.elevated,
        title = { Text(if (tracks.size == 1) "Send “${tracks[0].title}”" else "Send ${tracks.size} tracks") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DestinationPicker(destination, allowPhone = false) { destination = it }
            }
        },
        confirmButton = {
            TextButton(enabled = destination != null, onClick = {
                destination?.let { actions.send(tracks, it) }
                onDismiss()
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Format chips, then the qualities that format offers. */
@Composable
fun FormatPicker(preset: DownloadPreset, onChange: (DownloadPreset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Format", style = MaterialTheme.typography.labelLarge, color = Kultr.colors.ink2)
        Segmented(
            options = AudioFormat.entries.map { it to it.label.substringBefore(" (") },
            selected = preset.format,
            onSelect = { f -> onChange(DownloadPreset(f, f.normalise(preset.quality))) },
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
        if (preset.format.qualities.size > 1) {
            Text(if (preset.format.lossless) "Bit depth" else "Quality", style = MaterialTheme.typography.labelLarge, color = Kultr.colors.ink2)
            Segmented(
                options = preset.format.qualities.map { it to it.label },
                selected = preset.format.normalise(preset.quality),
                onSelect = { q -> onChange(preset.copy(quality = q)) },
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        Text(formatHint(preset.format), style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3)
    }
}

fun formatHint(format: AudioFormat): String = when (format) {
    AudioFormat.FLAC -> "Lossless and widely supported. Keeps the source audio exactly; it can't add detail the source never had."
    AudioFormat.MP3 -> "Plays everywhere. 320 kbps is the highest MP3 quality."
    AudioFormat.AAC -> "Small and good quality. “Original” keeps YouTube's AAC stream without re-encoding."
    AudioFormat.OPUS -> "The most efficient codec. “Original” keeps the source stream as it is. No embedded cover."
    AudioFormat.ALAC -> "Apple's lossless format, for iTunes and Apple devices."
    AudioFormat.WAV -> "Uncompressed. Largest files."
    AudioFormat.VORBIS -> "Open format, good for older Ogg players."
    AudioFormat.ORIGINAL -> "The file exactly as the source serves it, no conversion."
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TextDialog(
    title: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String = "",
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Kultr.colors.elevated,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                shape = MaterialTheme.shapes.medium,
                // A dialog is its own window; its test tags need their own switch.
                modifier = Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("text-dialog-field"),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Kultr.colors.elevated,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirm, color = Kultr.colors.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
