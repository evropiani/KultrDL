package app.kultr.dl.data

import android.content.Context
import androidx.core.content.edit
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.util.LenientJson
import app.kultr.dl.engine.DownloadPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }

@Serializable
enum class StreamQuality(val label: String) { HIGH("Best"), SAVER("Data saver") }

@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accentFromArtwork: Boolean = true,
    val accent: String = "#7c8cff",
    val reduceMotion: Boolean = false,
    val backdropArtwork: Boolean = true,
    val searchSource: Source = Source.YOUTUBE_MUSIC,
    val country: String = "",
    val spotifyClientId: String = "",
    val spotifyClientSecret: String = "",
    val download: DownloadPreset = DownloadPreset(),
    val askEachTime: Boolean = false,
    val saveToMusic: Boolean = true,
    val wifiOnly: Boolean = false,
    val embedArtwork: Boolean = true,
    val streamQuality: StreamQuality = StreamQuality.HIGH,
    val autoUpdateEngine: Boolean = true,
    val lastEngineCheck: Long = 0,
)

/** Settings in one JSON document, so they back up and restore as a whole. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("kultrdl.settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val settings: StateFlow<Settings> = state.asStateFlow()

    private fun load(): Settings = prefs.getString(KEY, null)
        ?.let { runCatching { LenientJson.decodeFromString(Settings.serializer(), it) }.getOrNull() }
        ?: Settings()

    fun update(transform: (Settings) -> Settings) {
        state.update(transform)
        prefs.edit { putString(KEY, LenientJson.encodeToString(Settings.serializer(), state.value)) }
    }

    fun replace(settings: Settings) = update { settings }

    private companion object {
        const val KEY = "settings"
    }
}
