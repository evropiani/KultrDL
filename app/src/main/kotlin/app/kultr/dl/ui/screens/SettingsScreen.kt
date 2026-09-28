package app.kultr.dl.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.BuildConfig
import app.kultr.dl.R
import app.kultr.dl.core.model.Source
import app.kultr.dl.data.BackupFile
import app.kultr.dl.data.MessageKind
import app.kultr.dl.data.Settings
import app.kultr.dl.data.StreamQuality
import app.kultr.dl.data.ThemeMode
import app.kultr.dl.data.describe
import app.kultr.dl.engine.YouTubeProfile
import app.kultr.dl.engine.YtDlp
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.chromePadding
import app.kultr.dl.ui.components.ConfirmDialog
import app.kultr.dl.ui.components.FormatPicker
import app.kultr.dl.ui.components.GlassPanel
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.components.Segmented
import app.kultr.dl.ui.components.SettingRow
import app.kultr.dl.ui.theme.Kultr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val actions = LocalActions.current
    val graph = actions.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(setOf("Downloads")) }
    fun update(transform: (Settings) -> Settings) = graph.settings.update(transform)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = chromePadding(32.dp))) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Kultr.colors.ink, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
        }
        fun section(title: String, icon: ImageVector, content: @Composable () -> Unit) {
            item(key = title) {
                Section(title, icon, expanded = title in open, onToggle = { open = if (title in open) open - title else open + title }, content = content)
            }
        }
        section("Downloads", Icons.Rounded.Download) { DownloadSettings(settings, ::update) }
        section("Search and sources", Icons.Rounded.Search) { SourceSettings(settings, ::update) }
        section("Playback", Icons.Rounded.PlayCircle) { PlaybackSettings(settings, ::update) }
        section("Appearance", Icons.Rounded.Palette) { AppearanceSettings(settings, ::update) }
        section("Engine", Icons.Rounded.Memory) { EngineSettings(settings, ::update) }
        section("Backup and reset", Icons.Rounded.Backup) { BackupSettings(settings) }
        section("About", Icons.Rounded.Info) { AboutSection() }
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), padding = PaddingValues(0.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Kultr.colors.accent)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = Kultr.colors.ink, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = Kultr.colors.ink3)
            }
            AnimatedVisibility(expanded) { Column(Modifier.padding(bottom = 8.dp)) { content() } }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    SettingRow(label, hint = hint, onClick = { onChange(!checked) }) { Switch(checked = checked, onCheckedChange = onChange) }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, hint: String? = null, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3)
        Spacer(Modifier.height(8.dp))
        Segmented(options, selected, onSelect, Modifier.horizontalScroll(rememberScrollState()))
    }
}

@Composable
private fun DownloadSettings(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        FormatPicker(s.download) { preset -> update { it.copy(download = preset) } }
    }
    Toggle("Ask every time", s.askEachTime, hint = "Choose the format and quality for each download.") { v -> update { it.copy(askEachTime = v) } }
    Toggle(
        "Save to the Music folder",
        s.saveToMusic,
        hint = if (s.saveToMusic) "Music/KultrDL, where other music apps find them." else "KultrDL's own folder, hidden from other apps.",
    ) { v -> update { it.copy(saveToMusic = v) } }
    Toggle("Embed cover art", s.embedArtwork, hint = "Put the album artwork inside each file.") { v -> update { it.copy(embedArtwork = v) } }
    Toggle("Download on Wi-Fi only", s.wifiOnly) { v -> update { it.copy(wifiOnly = v) } }
}

@Composable
private fun SourceSettings(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    Choice("Search first on", Source.searchSources.map { it to it.label }, s.searchSource) { v -> update { it.copy(searchSource = v) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Country", style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink)
        Text("Two letters, like US or DE: the Apple Music store searched and its charts. Empty uses the phone's region.", style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3)
        var country by remember { mutableStateOf(s.country) }
        OutlinedTextField(
            value = country,
            onValueChange = { v ->
                country = v.filter(Char::isLetter).take(2).uppercase()
                update { it.copy(country = country) }
            },
            singleLine = true,
            placeholder = { Text("Automatic") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.width(140.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text("Spotify search", style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink)
        Text(
            "Spotify links work as they are. To search Spotify too, create a free app at developer.spotify.com and enter its Client ID and secret.",
            style = MaterialTheme.typography.bodySmall,
            color = Kultr.colors.ink3,
        )
        var id by remember { mutableStateOf(s.spotifyClientId) }
        var secret by remember { mutableStateOf(s.spotifyClientSecret) }
        OutlinedTextField(
            value = id,
            onValueChange = { v -> id = v.trim(); update { it.copy(spotifyClientId = id) } },
            singleLine = true,
            label = { Text("Client ID") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { v -> secret = v.trim(); update { it.copy(spotifyClientSecret = secret) } },
            singleLine = true,
            label = { Text("Client secret") },
            visualTransformation = PasswordVisualTransformation(),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PlaybackSettings(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    Choice(
        "Streaming quality",
        StreamQuality.entries.map { it to it.label },
        s.streamQuality,
        hint = "Downloads always fetch the best audio and convert it to your format.",
    ) { v -> update { it.copy(streamQuality = v) } }
}

@Composable
private fun AppearanceSettings(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    Choice("Theme", ThemeMode.entries.map { it to it.label }, s.theme) { v -> update { it.copy(theme = v) } }
    Toggle("Colour from the artwork", s.accentFromArtwork, hint = "Tint the app with the colour of what's playing.") { v -> update { it.copy(accentFromArtwork = v) } }
    Toggle("Artwork behind pages", s.backdropArtwork, hint = "Blurred cover art of what's playing behind every page.") { v -> update { it.copy(backdropArtwork = v) } }
    Toggle("Reduce motion", s.reduceMotion) { v -> update { it.copy(reduceMotion = v) } }
}

@Composable
private fun EngineSettings(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    val actions = LocalActions.current
    val status by actions.graph.ytDlp.status.collectAsStateWithLifecycle()
    var updating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SettingRow(
        "yt-dlp",
        hint = when (val st = status) {
            YtDlp.Status.Starting -> "Starting…"
            is YtDlp.Status.Ready -> "Version ${st.version ?: "unknown"}. It finds and fetches the audio; YouTube changes often, so keep it current."
            is YtDlp.Status.Failed -> "Couldn't start: ${st.message}"
        },
    )
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Pill(if (updating) "Updating…" else "Update now", enabled = !updating && status is YtDlp.Status.Ready, onClick = {
            updating = true
            scope.launch {
                try {
                    val v = actions.graph.ytDlp.update()
                    actions.graph.messages.show("yt-dlp is up to date${v?.let { " ($it)" } ?: ""}", MessageKind.SUCCESS)
                    update { it.copy(lastEngineCheck = System.currentTimeMillis()) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    actions.graph.messages.error("Update failed: ${describe(e)}")
                } finally {
                    updating = false
                }
            }
        })
    }
    Toggle("Update automatically", s.autoUpdateEngine, hint = "Check for a new yt-dlp once a day.") { v -> update { it.copy(autoUpdateEngine = v) } }
    YouTubeSettings(s, update)
}

/**
 * YouTube can refuse one way of asking for a stream (HTTP 403) on some
 * networks. KultrDL switches by itself; this shows which one is in use,
 * lets it be picked, and tests them all with a copyable report.
 */
@Composable
private fun YouTubeSettings(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    val actions = LocalActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    Choice(
        "YouTube connection",
        YouTubeProfile.entries.map { it to it.label },
        s.youtubeProfile,
        hint = "If YouTube refuses a song (error 403), KultrDL tries the others and keeps the one that works.",
    ) { v -> update { it.copy(youtubeProfile = v) } }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Pill(testing ?: "Test YouTube", enabled = testing == null, onClick = {
            testing = "Starting…"
            scope.launch {
                try {
                    val outcome = actions.graph.youtubeCheck.run { step -> testing = step }
                    report = outcome.report
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    report = "The test couldn't run: ${describe(e)}"
                } finally {
                    testing = null
                }
            }
        })
    }
    report?.let { text ->
        AlertDialog(
            onDismissRequest = { report = null },
            containerColor = Kultr.colors.elevated,
            title = { Text("YouTube test") },
            text = {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = Kultr.colors.ink2,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("KultrDL YouTube test", text))
                    actions.graph.messages.show("Report copied")
                }) { Text("Copy report") }
            },
            dismissButton = { TextButton(onClick = { report = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun BackupSettings(s: Settings) {
    val actions = LocalActions.current
    val graph = actions.graph
    val context = LocalContext.current
    var confirmHistory by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        actions.launch {
            val file = graph.library.snapshot(s.copy(spotifyClientSecret = ""))
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(file.encode().toByteArray()) }
            }
            graph.messages.show("Backed up ${file.tracks.size} tracks and ${file.playlists.size} playlists", MessageKind.SUCCESS)
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        actions.launch {
            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
                ?: throw IllegalStateException("Couldn't read that file.")
            val file = BackupFile.decode(text)
            val count = graph.library.restore(file)
            file.settings?.let { restored -> graph.settings.update { restored.copy(spotifyClientSecret = it.spotifyClientSecret) } }
            graph.messages.show("Restored $count tracks and ${file.playlists.size} playlists", MessageKind.SUCCESS)
        }
    }
    SettingRow("Back up library", hint = "Favourites, saved tracks, playlists, history and settings, as one file.", onClick = { export.launch("KultrDL-backup.json") })
    SettingRow("Restore a backup", hint = "Adds to what's here; nothing is removed.", onClick = { restore.launch(arrayOf("application/json", "text/plain", "*/*")) })
    SettingRow("Clear listening history", onClick = { confirmHistory = true })
    SettingRow("Clear recent searches", onClick = { actions.launch { graph.library.clearSearches() } })
    if (confirmHistory) {
        ConfirmDialog(
            title = "Clear listening history?",
            body = "Play counts and “Jump back in” start again. Favourites, playlists and downloads stay.",
            confirm = "Clear",
            onConfirm = { actions.launch { graph.library.clearHistory() } },
            onDismiss = { confirmHistory = false },
        )
    }
}

@Composable
private fun AboutSection() {
    val actions = LocalActions.current
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.kultrdl_logo), contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.width(14.dp))
        Column {
            Text("KultrDL ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, color = Kultr.colors.ink)
            Text("Search, play, save and download music.", style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3)
        }
    }
    Text(
        "Audio comes from YouTube Music, YouTube, SoundCloud, Bandcamp and the other sites yt-dlp supports. " +
            "Spotify, Apple Music, Deezer, Tidal, Qobuz and Amazon Music are used for search, links and track details; " +
            "their tracks play and download from the matching recording on YouTube Music, with the catalogue's tags and cover. " +
            "Only download music you have the right to keep.",
        style = MaterialTheme.typography.bodySmall,
        color = Kultr.colors.ink2,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        TextButton(onClick = { actions.openInBrowser("https://github.com/evropiani/KultrDL") }) { Text("Source code") }
        TextButton(onClick = { actions.openInBrowser("https://github.com/evropiani/KultrDL/releases/latest") }) { Text("Latest release") }
    }
    Text(
        "Free software under the GNU GPL v3. Built with yt-dlp, youtubedl-android, ffmpeg, Media3, jaudiotagger and the Kultr design.",
        style = MaterialTheme.typography.bodySmall,
        color = Kultr.colors.ink3,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
