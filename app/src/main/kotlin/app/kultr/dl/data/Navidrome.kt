package app.kultr.dl.data

import android.content.Context
import androidx.core.content.edit
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.sources.Subsonic
import app.kultr.dl.core.util.LenientJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The user's Navidrome server: how to sign in (passwords sealed by
 * [SecretBox]), whether its history feeds the recommendations, and which
 * saved FTP/SFTP folder is its music folder, for "Download to Navidrome".
 *
 * [username] is the account the user listens with: Navidrome keeps plays,
 * stars and ratings per account. Only admins may start a scan, so an admin
 * login can be kept beside it, used for nothing else.
 */
@Serializable
data class NavidromeConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    /** Whether [username] is an admin, as the server said at the last sync (null: not known). */
    val isAdmin: Boolean? = null,
    /** An admin login for starting scans, when [username] isn't one. */
    val adminUsername: String = "",
    val adminPassword: String = "",
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

    val hasAdmin: Boolean get() = adminUsername.isNotBlank() && adminPassword.isNotBlank()

    fun withoutSecrets() = copy(password = "", adminPassword = "")
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

    /**
     * Saves the login the user listens with. Moving from an admin to another
     * account on the same server keeps the admin login for rescans, so
     * switching to one's own account doesn't stop them. Returns that admin's
     * name when it was kept.
     */
    suspend fun signIn(http: Http, url: String, username: String, password: String): String? {
        val before = state.value
        val base = Subsonic.baseUrl(url)
        val user = username.trim()
        val another = before.configured && before.url == base && !before.username.equals(user, ignoreCase = true)
        val keep = another && !before.hasAdmin && before.isAdmin != false && (before.isAdmin == true || isAdmin(http, before))
        update {
            it.copy(
                url = base,
                username = user,
                password = SecretBox.encrypt(password),
                isAdmin = null,
                adminUsername = if (keep) before.username else it.adminUsername,
                adminPassword = if (keep) before.password else it.adminPassword,
                lastSyncAt = 0,
            )
        }
        return if (keep) before.username else null
    }

    /** Keeps [username] as the login for rescans, if the server says it's an admin. */
    suspend fun keepAdmin(http: Http, username: String, password: String): Boolean {
        val c = state.value
        val admin = withContext(Dispatchers.IO) { clientFor(http, c.url, username.trim(), password).isAdmin() }
        if (admin == false) return false
        update { it.copy(adminUsername = username.trim(), adminPassword = SecretBox.encrypt(password)) }
        return true
    }

    fun forgetAdmin() = update { it.copy(adminUsername = "", adminPassword = "") }

    private suspend fun isAdmin(http: Http, c: NavidromeConfig): Boolean {
        val password = SecretBox.decrypt(c.password)?.takeIf { it.isNotEmpty() } ?: return false
        return try {
            withContext(Dispatchers.IO) { clientFor(http, c.url, c.username, password).isAdmin() } == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** A client for the saved server, or null when there is none (or its password can't be opened). */
    fun client(http: Http): Subsonic? {
        val c = state.value
        cached?.let { (config, client) -> if (config == c) return client }
        val client = if (!c.configured) null else SecretBox.decrypt(c.password)?.let { Subsonic(http, Subsonic.Server(c.url, c.username, it)) }
        cached = c to client
        return client
    }

    /** The client for starting scans: the admin login when one is kept, otherwise [client]. */
    fun scanClient(http: Http): Subsonic? {
        val c = state.value
        if (!c.configured || !c.hasAdmin) return client(http)
        val password = SecretBox.decrypt(c.adminPassword)?.takeIf { it.isNotEmpty() } ?: return client(http)
        return Subsonic(http, Subsonic.Server(c.url, c.adminUsername, password))
    }

    companion object {
        private const val KEY = "navidrome"

        fun clientFor(http: Http, url: String, username: String, password: String) = Subsonic(http, Subsonic.Server(url, username, password))
    }
}
