package app.kultr.dl.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.taste.Taste
import app.kultr.dl.data.MessageKind
import app.kultr.dl.data.ReleaseAlerts
import app.kultr.dl.data.Settings
import app.kultr.dl.data.describe
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.Routes
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.components.SettingRow
import app.kultr.dl.ui.theme.Kultr
import kotlinx.coroutines.CancellationException

private val GENRES = listOf(
    "Pop", "Rock", "Rap/Hip-Hop", "Electro", "Dance", "R&B", "Alternative", "Metal", "Jazz", "Classical",
    "Country", "Reggae", "Latin Music", "Folk", "Soul & Funk", "Blues", "Kids", "Films/Games", "Schlager",
)

/** Settings → Recommendations: what suggestions learn from, how adventurous they are, and new-release alerts. */
@Composable
fun RecommendationSettings(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    val actions = LocalActions.current
    val graph = actions.graph
    val navidrome by graph.navidrome.config.collectAsStateWithLifecycle()
    val phoneSongs by graph.recommender.phoneSongs.collectAsStateWithLifecycle(0)
    val taste by graph.taste.data.collectAsStateWithLifecycle()
    fun changed(transform: (Settings) -> Settings) {
        update(transform)
        graph.recommender.schedule()
    }
    val askAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            update { it.copy(usePhoneMusic = true) }
            actions.launch {
                val n = graph.recommender.scanPhone()
                graph.messages.show("Found ${n} songs on this phone", MessageKind.SUCCESS)
                graph.recommender.refreshInBackground()
            }
        } else {
            graph.messages.show("Without that permission KultrDL can't see the music on this phone.")
        }
    }

    Toggle(
        "Suggestions",
        s.suggestions,
        hint = "“For you” on Home: new releases, mixes and albums, worked out on this phone once a day.",
    ) { v -> changed { it.copy(suggestions = v) } }
    if (!s.suggestions) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Familiar or new", style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink)
        Text("How much of each mix is music you know, and how much is new to you.", style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3)
        var level by remember(s.discoverLevel) { mutableFloatStateOf(s.discoverLevel) }
        Slider(
            value = level,
            onValueChange = { level = it },
            onValueChangeFinished = { update { it.copy(discoverLevel = level) } },
            valueRange = 0f..1f,
            steps = 3,
        )
        Row {
            Text("Familiar", style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3, modifier = Modifier.weight(1f))
            Text("Discover", style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3)
        }
    }

    Choice(
        "New releases",
        ReleaseAlerts.entries.map { it to it.label },
        s.releaseAlerts,
        hint = "A notification when artists you play release something new.",
    ) { v -> update { it.copy(releaseAlerts = v) } }
    Choice(
        "Counts as new for",
        listOf(14 to "2 weeks", 30 to "1 month", 90 to "3 months"),
        s.releaseWindowDays,
    ) { v -> update { it.copy(releaseWindowDays = v) } }

    Spacer(Modifier.height(4.dp))
    Text("Learn from", style = MaterialTheme.typography.titleSmall, color = Kultr.colors.ink2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    Text(
        "Always: what you play, skip, heart, save, download and put in playlists here.",
        style = MaterialTheme.typography.bodySmall,
        color = Kultr.colors.ink3,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Toggle(
        "Music on this phone",
        s.usePhoneMusic && graph.phone.hasPermission(),
        hint = if (s.usePhoneMusic && phoneSongs > 0) "$phoneSongs songs; they also play in your mixes, offline." else "Music files you already have, from any app.",
    ) { v ->
        if (!v) {
            update { it.copy(usePhoneMusic = false) }
            actions.launch { graph.recommender.forgetPhone() }
        } else if (graph.phone.hasPermission()) {
            askAudio.launch(graph.phone.permission)
        } else {
            askAudio.launch(graph.phone.permission)
        }
    }
    SettingRow(
        "Navidrome",
        hint = if (navidrome.configured) "${navidrome.username} at ${navidrome.url}${navidrome.lastSync?.let { " · $it" } ?: ""}" else "Your plays, stars, ratings and collection there.",
        onClick = { actions.navigate(Routes.NAVIDROME) },
    ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Kultr.colors.ink3) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Last.fm", style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink)
        Text(
            "Your top artists there, and its similar artists. Needs your username and a free API key from last.fm/api/account/create.",
            style = MaterialTheme.typography.bodySmall,
            color = Kultr.colors.ink3,
        )
        var lfUser by remember { mutableStateOf(s.lastFmUser) }
        var lfKey by remember { mutableStateOf(s.lastFmApiKey) }
        OutlinedTextField(
            value = lfUser,
            onValueChange = { v -> lfUser = v.trim(); update { it.copy(lastFmUser = lfUser) } },
            singleLine = true,
            label = { Text("Last.fm username") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = lfKey,
            onValueChange = { v -> lfKey = v.trim(); update { it.copy(lastFmApiKey = lfKey) } },
            singleLine = true,
            label = { Text("Last.fm API key") },
            visualTransformation = PasswordVisualTransformation(),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text("ListenBrainz", style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink)
        Text(
            "Your top artists there, and the Weekly Exploration and Weekly Jams playlists it makes for you. Just your username; no password.",
            style = MaterialTheme.typography.bodySmall,
            color = Kultr.colors.ink3,
        )
        var lbUser by remember { mutableStateOf(s.listenBrainzUser) }
        OutlinedTextField(
            value = lbUser,
            onValueChange = { v -> lbUser = v.trim(); update { it.copy(listenBrainzUser = lbUser) } },
            singleLine = true,
            label = { Text("ListenBrainz username") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(4.dp))
    Text("Find new music on", style = MaterialTheme.typography.titleSmall, color = Kultr.colors.ink2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    Toggle("Deezer", s.useDeezer, hint = "Discographies with release dates, related artists and top songs. Apple Music is used when this is off.") { v ->
        update { it.copy(useDeezer = v) }
    }
    Toggle("YouTube Music radio", s.useYouTubeRadio, hint = "Songs YouTube Music plays after your favourites.") { v -> update { it.copy(useYouTubeRadio = v) } }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Leave out genres", style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink)
        Text("Never suggest artists whose music is mainly these.", style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3)
        Spacer(Modifier.height(8.dp))
        val excluded = s.excludedGenres.map { Taste.genreName(it).lowercase() }.toSet()
        GENRES.chunked(7).forEach { row ->
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { genre ->
                    val on = Taste.genreName(genre).lowercase() in excluded
                    GenreChip(genre, on) {
                        update { st ->
                            val list = st.excludedGenres.filterNot { Taste.genreName(it).lowercase() == Taste.genreName(genre).lowercase() }
                            st.copy(excludedGenres = if (on) list else list + genre)
                        }
                    }
                }
            }
        }
    }

    SettingRow(
        "Blocked artists",
        hint = if (taste.blocked.isEmpty()) "No one. Block an artist from any song's menu." else taste.blocked.joinToString { it.name },
        onClick = { actions.navigate(Routes.BLOCKED) },
    ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Kultr.colors.ink3) }
    Toggle("Update on Wi-Fi only", s.suggestionsOnWifiOnly) { v -> changed { it.copy(suggestionsOnWifiOnly = v) } }
    Toggle("Update only while charging", s.suggestionsWhileCharging) { v -> changed { it.copy(suggestionsWhileCharging = v) } }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Pill("Update suggestions now", icon = Icons.Rounded.Refresh, onClick = {
            actions.launch {
                try {
                    val feed = graph.recommender.refresh()
                    graph.messages.show("Suggestions updated: ${feed.releases.size} new releases, ${feed.mixes.size} mixes", MessageKind.SUCCESS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graph.messages.error("Couldn't update suggestions: ${describe(e)}")
                }
            }
        })
    }
}

@Composable
private fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Kultr.colors
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = if (selected) colors.onAccent else colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) colors.danger else colors.glass)
            .border(BorderStroke(1.dp, if (selected) Color.Transparent else colors.edge), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
