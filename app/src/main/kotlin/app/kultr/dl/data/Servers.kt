package app.kultr.dl.data

import android.content.Context
import androidx.core.content.edit
import app.kultr.dl.core.remote.Connection
import app.kultr.dl.core.remote.FolderLayout
import app.kultr.dl.core.remote.Protocol
import app.kultr.dl.core.remote.RemoteException
import app.kultr.dl.core.util.LenientJson
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * An FTP, FTPS or SFTP server, with the folders on it that downloads can
 * go to. [password], [privateKey] and [passphrase] are sealed by
 * [SecretBox]; [pin] is the SSH host key or pinned certificate it trusts.
 */
@Serializable
data class SavedServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol = Protocol.SFTP,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val keyName: String? = null,
    val pin: String? = null,
    val folders: List<String> = emptyList(),
    val layout: FolderLayout = FolderLayout.FLAT,
    val passive: Boolean = true,
) {
    val address: String
        get() = (if (username.isNotEmpty()) "$username@" else "") + host + (if (port != protocol.defaultPort) ":$port" else "")

    fun withoutSecrets() = copy(password = "", privateKey = "", passphrase = "")
}

/** A folder on a saved server that a download is sent to. */
@Serializable
data class Destination(val serverId: String, val folder: String, val keepOnPhone: Boolean = false)

/** Saved servers, in their own preferences file (not part of the settings JSON). */
class ServerRepository(context: Context) {
    private val prefs = context.getSharedPreferences("kultrdl.servers", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(SavedServer.serializer())
    private val state = MutableStateFlow(load())
    val servers: StateFlow<List<SavedServer>> = state.asStateFlow()

    private fun load(): List<SavedServer> = prefs.getString(KEY, null)
        ?.let { runCatching { LenientJson.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()

    private fun write(transform: (List<SavedServer>) -> List<SavedServer>) {
        state.update(transform)
        prefs.edit { putString(KEY, LenientJson.encodeToString(serializer, state.value)) }
    }

    fun get(id: String): SavedServer? = state.value.firstOrNull { it.id == id }

    fun save(server: SavedServer) = write { list ->
        if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server
    }

    fun remove(id: String) = write { list -> list.filterNot { it.id == id } }

    /** The first key an SFTP server showed, trusted from then on. */
    fun setPin(id: String, pin: String) = write { list -> list.map { if (it.id == id && it.pin == null) it.copy(pin = pin) else it } }

    /** Servers from a backup that aren't here yet; they come without passwords. */
    fun restore(servers: List<SavedServer>): Int {
        val known = state.value.map { it.id }.toSet()
        val added = servers.filterNot { it.id in known }.map { it.withoutSecrets() }
        if (added.isNotEmpty()) write { it + added }
        return added.size
    }

    /** How to sign in, with the secrets opened. */
    fun connection(server: SavedServer): Connection {
        fun open(sealed: String, what: String): String = SecretBox.decrypt(sealed)
            ?: throw RemoteException("The $what for “${server.name}” can't be read any more. Enter it again in Settings → Servers.")
        return Connection(
            protocol = server.protocol,
            host = server.host,
            port = server.port,
            username = server.username,
            password = open(server.password, "password"),
            privateKey = open(server.privateKey, "key"),
            passphrase = open(server.passphrase, "key passphrase"),
            pin = server.pin,
            passive = server.passive,
        )
    }

    fun label(destination: Destination?): String {
        destination ?: return "This phone"
        val server = get(destination.serverId) ?: return "A removed server"
        return "${server.name} · ${folderLabel(destination.folder)}"
    }

    private companion object {
        const val KEY = "servers"
    }
}

/** "" is the folder the server starts in. */
fun folderLabel(folder: String): String = folder.ifEmpty { "Start folder" }
