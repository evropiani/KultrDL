package app.kultr.dl.core.remote

import app.kultr.dl.core.util.Text
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable

/** The ways KultrDL can reach a server. */
@Serializable
enum class Protocol(val label: String, val defaultPort: Int, val hint: String) {
    SFTP("SFTP", 22, "Files over SSH. Most NAS boxes and Linux servers have it."),
    FTPS("FTPS", 21, "FTP with encryption (explicit TLS)."),
    FTP("FTP", 21, "Unencrypted: the password and music cross the network in the clear."),
    FTPS_IMPLICIT("FTPS implicit", 990, "Older FTP encryption on its own port, usually 990."),
}

/** Where on the server a track goes inside the chosen folder. */
@Serializable
enum class FolderLayout(val label: String) {
    FLAT("Files only"),
    ARTIST("Artist"),
    ARTIST_ALBUM("Artist / Album"),
    ;

    /** The folders under the chosen one, e.g. ["Björk", "Homogenic"]. */
    fun folders(artist: String, albumArtist: String?, album: String?): List<String> {
        val who = Text.fileName(albumArtist?.takeIf { it.isNotBlank() } ?: artist.ifBlank { "Unknown artist" }, 80)
        return when (this) {
            FLAT -> emptyList()
            ARTIST -> listOf(who)
            ARTIST_ALBUM -> listOfNotNull(who, album?.takeIf { it.isNotBlank() }?.let { Text.fileName(it, 80) })
        }
    }
}

/**
 * How to reach one server and sign in. Secrets are in the clear here and
 * only live as long as a connection is being made.
 *
 * [pin] is the fingerprint the user trusts: the SSH host key for SFTP, or a
 * self-signed certificate for FTPS. Without one, SFTP trusts the first key
 * it sees (and reports it in [RemoteSession.newPin]) and FTPS needs a
 * certificate the phone already trusts.
 */
data class Connection(
    val protocol: Protocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val pin: String? = null,
    val passive: Boolean = true,
    val timeoutMs: Int = 20_000,
) {
    override fun toString(): String = "${protocol.label} $username@$host:$port"
}

data class RemoteEntry(val name: String, val isDirectory: Boolean, val size: Long = 0)

/** One signed-in connection. Not thread safe: use it from one thread at a time. */
interface RemoteSession : Closeable {
    /** The folder the server starts in, as an absolute path. */
    val home: String

    /** The server's fingerprint to remember, when there was no [Connection.pin] yet. */
    val newPin: String?

    fun list(path: String): List<RemoteEntry>

    fun isDirectory(path: String): Boolean

    /** Creates the folder and any missing parents. */
    fun makeDirectories(path: String)

    /**
     * Stores [input] at [path]: first under a temporary name, then renamed,
     * so a music server never picks up half a file. Replaces what is there.
     */
    fun upload(input: InputStream, path: String, progress: (sent: Long) -> Unit = {})

    fun delete(path: String)
}

object Remote {
    fun open(connection: Connection): RemoteSession {
        require(connection.host.isNotBlank()) { "Enter the server's address." }
        return when (connection.protocol) {
            Protocol.SFTP -> SftpSession.open(connection)
            else -> FtpSession.open(connection)
        }
    }

    /** Opens, runs [block], closes. */
    inline fun <T> use(connection: Connection, block: (RemoteSession) -> T): T = open(connection).use(block)
}

/** A readable reason, with the original error as its cause. */
open class RemoteException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The server identified itself with something the user hasn't trusted:
 * an SSH key that differs from the saved one, or a certificate the phone
 * doesn't trust (self-signed, as on most NAS boxes) or that changed.
 */
class UntrustedServerException(val reason: Reason, val fingerprint: String, cause: Throwable? = null) :
    RemoteException(reason.message, cause) {
    enum class Reason(val message: String) {
        KEY_CHANGED("The server's SSH key has changed since it was saved. That happens after a reinstall — or when something is in the way."),
        CERTIFICATE_UNTRUSTED("The server's certificate isn't one this phone trusts (NAS boxes often make their own)."),
        CERTIFICATE_CHANGED("The server's certificate has changed since it was trusted."),
    }
}

object RemotePath {
    /** "/a//b/" → "/a/b"; "" stays "" (the start folder); "/" stays "/". */
    fun normalise(path: String): String {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isEmpty()) return ""
        val absolute = trimmed.startsWith("/")
        val parts = mutableListOf<String>()
        for (part in trimmed.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        val joined = parts.joinToString("/")
        return if (absolute) "/$joined" else joined
    }

    /** A folder relative to the start folder becomes absolute. */
    fun resolve(home: String, path: String): String {
        val p = normalise(path)
        if (p.startsWith("/")) return p
        return normalise(if (p.isEmpty()) home.ifEmpty { "/" } else "${home.trimEnd('/')}/$p")
    }

    fun join(base: String, vararg names: String): String =
        normalise((listOf(base) + names).filter { it.isNotEmpty() }.joinToString("/"))

    fun parent(path: String): String {
        val p = normalise(path)
        if (p == "/" || p.isEmpty()) return p
        val i = p.lastIndexOf('/')
        return when {
            i < 0 -> ""
            i == 0 -> "/"
            else -> p.substring(0, i)
        }
    }

    fun name(path: String): String = normalise(path).substringAfterLast('/')

    /** Every folder from the top down to [path]: "/a/b" → ["/a", "/a/b"]. */
    fun ancestors(path: String): List<String> {
        val p = normalise(path)
        val out = mutableListOf<String>()
        var current = p
        while (current.isNotEmpty() && current != "/") {
            out += current
            current = parent(current)
        }
        return out.reversed()
    }
}

/** "SHA256:…" as OpenSSH prints it (`ssh-keygen -lf`). */
fun fingerprint(bytes: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

/** Counts what is read, for progress; stops the transfer once [stop] says so. */
internal class CountingInputStream(
    input: InputStream,
    private val onCount: (Long) -> Unit,
    private val stop: () -> Boolean = { false },
) : FilterInputStream(input) {
    private var count = 0L
    private var reported = 0L

    override fun read(): Int {
        if (stop()) throw InterruptedIOException("Cancelled")
        val b = super.read()
        if (b >= 0) advance(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (stop()) throw InterruptedIOException("Cancelled")
        val n = super.read(b, off, len)
        if (n > 0) advance(n.toLong())
        return n
    }

    private fun advance(n: Long) {
        count += n
        if (count - reported >= 64 * 1024) {
            reported = count
            onCount(count)
        }
    }

    fun finish() = onCount(count)
}
