package app.kultr.dl.data

import android.content.Context
import androidx.core.content.edit
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.sources.Subsonic
import app.kultr.dl.core.util.LenientJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * The user's Navidrome server: how to sign in (the password sealed by
 * [SecretBox]), whether its history feeds the recommendations, and which
 * saved FTP/SFTP folder is its music folder, for "Download to Navidrome".
 */
@Serializable
data class NavidromeConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val useHistory: Boolean = true,
    /** The SFTP/FTP folder Navidrome reads its music from. */
    val destination: Destination? = null,
    /** Ask Navidrome to rescan after downloads reach that folder. */
    val rescan: Boolean = true,
    val lastSyncAt: Long = 0,
    val lastSync: String? = null,
    val songCount: Int = 0,
    val lastScan: String? = null,
) {
    val configured: Boolean get() = url.isNotBlank() && username.isNotBlank()

    fun withoutSecrets() = copy(password = "")
}

class NavidromeRepository(context: Context) {
    private val prefs = context.getSharedPreferences("kultrdl.navidrome", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val config: StateFlow<NavidromeConfig> = state.asStateFlow()

    /** The client for the saved login, rebuilt when it changes. */
    @Volatile private var cached: Pair<NavidromeConfig, Subsonic?>? = null

    private fun load(): NavidromeConfig = prefs.getString(KEY, null)
        ?.let { runCatching { LenientJson.decodeFromString(NavidromeConfig.serializer(), it) }.getOrNull() }
        ?: NavidromeConfig()

    fun update(transform: (NavidromeConfig) -> NavidromeConfig) {
        state.update(transform)
        prefs.edit { putString(KEY, LenientJson.encodeToString(NavidromeConfig.serializer(), state.value)) }
    }

    fun clear() = update { NavidromeConfig() }

    /** A client for the saved server, or null when there is none (or its password can't be opened). */
    fun client(http: Http): Subsonic? {
        val c = state.value
        cached?.let { (config, client) -> if (config == c) return client }
        val client = if (!c.configured) null else SecretBox.decrypt(c.password)?.let { Subsonic(http, Subsonic.Server(c.url, c.username, it)) }
        cached = c to client
        return client
    }

    companion object {
        private const val KEY = "navidrome"

        fun clientFor(http: Http, url: String, username: String, password: String) = Subsonic(http, Subsonic.Server(url, username, password))
    }
}
