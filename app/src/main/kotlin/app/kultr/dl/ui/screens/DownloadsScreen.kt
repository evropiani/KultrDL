package app.kultr.dl.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.util.LenientJson
import app.kultr.dl.data.Destination
import app.kultr.dl.data.ServerRepository
import app.kultr.dl.data.db.DownloadState
import app.kultr.dl.data.db.DownloadWithTrack
import app.kultr.dl.data.db.TrackEntity
import app.kultr.dl.engine.AudioFormat
import app.kultr.dl.engine.DownloadPreset
import app.kultr.dl.engine.Quality
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.chromePadding
import app.kultr.dl.ui.components.Artwork
import app.kultr.dl.ui.components.EmptyState
import app.kultr.dl.ui.components.FormatPicker
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.components.SectionHeader
import app.kultr.dl.ui.components.glass
import app.kultr.dl.ui.theme.Kultr

/** Everything downloading, waiting, failed and done, and the format new downloads use. */
@Composable
fun DownloadsScreen() {
    val actions = LocalActions.current
    val graph = actions.graph
    val rows by graph.downloads.all.collectAsStateWithLifecycle(emptyList())
    val live by graph.downloads.progress.collectAsStateWithLifecycle()
    val done by graph.library.downloaded.collectAsStateWithLifecycle(emptyList())
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val servers by graph.servers.servers.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    var choosingDestination by rememberSaveable { mutableStateOf(false) }

    val active = rows.filter { it.state == DownloadState.RUNNING.name || it.state == DownloadState.QUEUED.name }
    val failed = rows.filter { it.state == DownloadState.FAILED.name }
    val sent = rows.filter { it.state == DownloadState.DONE.name && it.destination != null && it.message != null }
    val destination = settings.destination?.takeIf { d -> servers.any { it.id == d.serverId } }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = chromePadding())) {
        item(key = "head") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                Text("Downloads", style = MaterialTheme.typography.headlineMedium, color = Kultr.colors.ink)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(settings.download.label, icon = Icons.Rounded.Tune, onClick = { picking = true })
                    if (servers.isNotEmpty()) {
                        Pill(
                            destination?.let { graph.servers.label(it) } ?: "This phone",
                            icon = if (destination == null) Icons.Rounded.PhoneAndroid else Icons.Rounded.Dns,
                            onClick = { choosingDestination = true },
                        )
                    }
                    if (failed.isNotEmpty()) Pill("Retry failed", icon = Icons.Rounded.Refresh, onClick = { graph.downloads.retryFailed() })
                    if (rows.any { it.state == DownloadState.DONE.name || it.state == DownloadState.CANCELLED.name }) {
                        Pill("Clear finished", icon = Icons.Rounded.Close, onClick = { graph.downloads.clearFinished() })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        destination != null && destination.keepOnPhone -> "Sent to ${graph.servers.label(destination)}, with a copy on this phone."
                        destination != null -> "Sent to ${graph.servers.label(destination)}."
                        settings.saveToMusic -> "Saved to Music/KultrDL, where other music apps find them."
                        else -> "Saved in KultrDL's own folder."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Kultr.colors.ink3,
                )
            }
        }

        if (active.isNotEmpty()) {
            item(key = "active-head") { SectionHeader("In progress", icon = Icons.Rounded.Download) }
            items(active, key = { "a:" + it.trackId }) { row ->
                val progress = live?.takeIf { it.trackId == row.trackId }
                QueueRow(row, progress?.fraction ?: row.progress, progress?.stage ?: row.message)
            }
        }
        if (failed.isNotEmpty()) {
            item(key = "failed-head") { SectionHeader("Failed", icon = Icons.Rounded.ErrorOutline) }
            items(failed, key = { "f:" + it.trackId }) { row -> QueueRow(row, 0f, row.message) }
        }
        if (sent.isNotEmpty()) {
            item(key = "sent-head") { SectionHeader("Sent to servers", icon = Icons.Rounded.CloudDone) }
            items(sent, key = { "s:" + it.trackId }) { row -> SentRow(row) }
        }
        if (done.isNotEmpty()) {
            item(key = "done-head") {
                SectionHeader("On this phone", icon = Icons.Rounded.DownloadDone, action = {
                    if (servers.isNotEmpty()) TextButton(onClick = { actions.sendToServer(done.map(TrackEntity::toTrack)) }) { Text("Send all…") }
                    TextButton(onClick = { actions.play(done.map(TrackEntity::toTrack)) }) { Text("Play all") }
                })
            }
            items(done, key = { "d:" + it.id }) { entity ->
                DoneRow(entity, canSend = servers.isNotEmpty(), onPlay = { actions.play(done.map(TrackEntity::toTrack), done.indexOf(entity)) })
            }
        }
        if (active.isEmpty() && failed.isEmpty() && done.isEmpty() && sent.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    Icons.Rounded.Download,
                    "Nothing downloaded yet",
                    body = "Find a track, album or playlist and tap Download. Choose FLAC, MP3, AAC, Opus, ALAC, WAV or Ogg Vorbis above, " +
                        "and add an FTP or SFTP server in Settings to send music straight to it.",
                )
            }
        }
    }

    if (picking) {
        var preset by remember { mutableStateOf(settings.download) }
        AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = Kultr.colors.elevated,
            title = { Text("Download format") },
            text = { FormatPicker(preset) { preset = it } },
            confirmButton = {
                TextButton(onClick = {
                    graph.settings.update { it.copy(download = preset) }
                    picking = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
    if (choosingDestination) {
        DestinationDialog(settings.destination, onSave = { d -> graph.settings.update { it.copy(destination = d) } }, onDismiss = { choosingDestination = false })
    }
}

private fun destinationOf(row: DownloadWithTrack): Destination? =
    row.destination?.let { runCatching { LenientJson.decodeFromString(Destination.serializer(), it) }.getOrNull() }

private fun serverName(servers: ServerRepository, row: DownloadWithTrack): String? =
    destinationOf(row)?.let { d -> servers.get(d.serverId)?.name ?: "a removed server" }

/** A track that went to a server, and where it is there. */
@Composable
private fun SentRow(row: DownloadWithTrack) {
    val colors = Kultr.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(row.artworkUrl, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, color = colors.ink, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.message.orEmpty(), color = colors.ink3, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun presetLabel(row: DownloadWithTrack): String {
    val format = runCatching { AudioFormat.valueOf(row.format) }.getOrNull() ?: return ""
    val quality = runCatching { Quality.valueOf(row.quality) }.getOrDefault(format.qualities.first())
    return DownloadPreset(format, quality).label
}

@Composable
private fun QueueRow(row: DownloadWithTrack, fraction: Float, stage: String?) {
    val graph = LocalActions.current.graph
    val colors = Kultr.colors
    val state = runCatching { DownloadState.valueOf(row.state) }.getOrDefault(DownloadState.FAILED)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(row.artworkUrl, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, color = colors.ink, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val server = serverName(graph.servers, row)
            val line = when (state) {
                DownloadState.QUEUED -> when {
                    row.upload -> "Waiting to send to $server"
                    server != null -> "Waiting · ${presetLabel(row)} → $server"
                    else -> "Waiting · ${presetLabel(row)}"
                }
                DownloadState.RUNNING -> listOfNotNull(stage, "${(fraction * 100).toInt()}%").joinToString(" · ")
                DownloadState.FAILED -> stage ?: "Failed"
                else -> presetLabel(row)
            }
            Text(
                line,
                color = if (state == DownloadState.FAILED) colors.danger else colors.ink3,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state == DownloadState.RUNNING) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = colors.accent,
                    trackColor = colors.ink4,
                )
            }
        }
        if (state == DownloadState.FAILED) {
            IconButton(onClick = { graph.downloads.retry(row.trackId) }) { Icon(Icons.Rounded.Refresh, contentDescription = "Retry", tint = colors.ink2) }
            IconButton(onClick = { graph.downloads.remove(row.trackId) }) { Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = colors.ink3) }
        } else {
            IconButton(onClick = { graph.downloads.cancel(row.trackId) }) { Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = colors.ink3) }
        }
    }
}

@Composable
private fun DoneRow(entity: TrackEntity, canSend: Boolean, onPlay: () -> Unit) {
    val actions = LocalActions.current
    val colors = Kultr.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(entity.artworkUrl, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entity.title, color = colors.ink, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val size = entity.localSize?.let { app.kultr.dl.core.util.Format.bytes(it) }
            Text(
                listOfNotNull(entity.artist, entity.localFormat, size).joinToString(" · "),
                color = colors.ink3,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canSend) {
            IconButton(onClick = { actions.sendToServer(listOf(entity.toTrack())) }) {
                Icon(Icons.Rounded.CloudUpload, contentDescription = "Send to server", tint = colors.ink2)
            }
        } else {
            IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = colors.ink2) }
        }
        IconButton(onClick = { actions.removeDownload(entity.toTrack()) }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete download", tint = colors.ink3) }
    }
}

/** A glass strip over the page while anything is downloading; tap it for the Downloads page. */
@Composable
fun DownloadIndicator(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalActions.current.graph
    val colors = Kultr.colors
    val count by graph.downloads.activeCount.collectAsStateWithLifecycle(0)
    val live by graph.downloads.progress.collectAsStateWithLifecycle()
    val rows by graph.downloads.all.collectAsStateWithLifecycle(emptyList())
    AnimatedVisibility(count > 0, modifier = modifier, enter = expandVertically(), exit = shrinkVertically()) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .glass(shape)
                .clip(shape)
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                val current = live?.let { p -> rows.firstOrNull { it.trackId == p.trackId } }
                Text(
                    if (current != null) "${current.title} · ${live?.stage.orEmpty()}" else "$count waiting to download",
                    color = colors.ink,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (count > 1) Text("$count", color = colors.ink3, style = MaterialTheme.typography.bodySmall)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Open downloads", tint = colors.ink3, modifier = Modifier.size(18.dp))
            }
            val fraction = live?.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(colors.ink4),
                    color = colors.accent,
                    trackColor = colors.ink4,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)), color = colors.accent, trackColor = colors.ink4)
            }
        }
    }
}
