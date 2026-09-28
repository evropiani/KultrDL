package app.kultr.dl.core.remote

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchAlgoNegoFailException
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** SFTP through JSch. */
internal class SftpSession private constructor(
    private val session: Session,
    private val channel: ChannelSftp,
    override val newPin: String?,
) : RemoteSession {
    override val home: String = RemotePath.normalise(runCatching { channel.pwd() }.getOrNull() ?: "/").ifEmpty { "/" }

    @Volatile private var closed = false

    override fun list(path: String): List<RemoteEntry> = sftp("list", path) {
        val dir = RemotePath.resolve(home, path)
        channel.ls(dir).mapNotNull { e ->
            val name = e.filename
            if (name == "." || name == "..") return@mapNotNull null
            val attrs = e.attrs
            // A link to a folder is a folder here.
            val isDir = attrs.isDir || (attrs.isLink && runCatching { channel.stat(RemotePath.join(dir, name)).isDir }.getOrDefault(false))
            RemoteEntry(name, isDir, attrs.size)
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun isDirectory(path: String): Boolean = try {
        channel.stat(RemotePath.resolve(home, path)).isDir
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw explain(e, "check", path)
    }

    override fun makeDirectories(path: String) {
        for (dir in RemotePath.ancestors(RemotePath.resolve(home, path))) {
            if (isDirectory(dir)) continue
            sftp("create", dir) { channel.mkdir(dir) }
        }
    }

    override fun upload(input: InputStream, path: String, progress: (sent: Long) -> Unit) {
        val target = RemotePath.resolve(home, path)
        val partial = "$target.part"
        val counting = CountingInputStream(input, progress, stop = { closed })
        sftp("write", target) { channel.put(counting, partial, ChannelSftp.OVERWRITE) }
        counting.finish()
        // SFTP v3 can't rename over a file, so the old one goes first.
        runCatching { channel.rm(target) }
        sftp("rename", target) { channel.rename(partial, target) }
    }

    override fun delete(path: String) = sftp("delete", path) { channel.rm(RemotePath.resolve(home, path)) }

    override fun close() {
        closed = true
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }

    private inline fun <T> sftp(action: String, path: String, block: () -> T): T = try {
        block()
    } catch (e: SftpException) {
        throw explain(e, action, path)
    }

    private fun explain(e: SftpException, action: String, path: String): RemoteException = RemoteException(
        when (e.id) {
            ChannelSftp.SSH_FX_NO_SUCH_FILE -> "“$path” doesn't exist on the server."
            ChannelSftp.SSH_FX_PERMISSION_DENIED -> "The server doesn't allow KultrDL to $action “$path”."
            else -> "Couldn't $action “$path”: ${e.message ?: "error ${e.id}"}"
        },
        e,
    )

    companion object {
        init {
            // Android has no X25519 or Ed25519 in its JCE, so JSch uses Bouncy Castle for those
            // there. Use it everywhere, so the tests exercise what phones run.
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        }

        fun open(c: Connection): SftpSession {
            val jsch = JSch()
            val keys = PinnedHostKeys(c.pin)
            jsch.hostKeyRepository = keys
            if (c.privateKey.isNotBlank()) {
                try {
                    jsch.addIdentity("key", c.privateKey.trim().toByteArray(), null, c.passphrase.takeIf { it.isNotEmpty() }?.toByteArray())
                } catch (e: JSchException) {
                    throw RemoteException("The private key couldn't be read${if (c.passphrase.isEmpty()) " (does it need a passphrase?)" else " — check the passphrase"}.", e)
                }
            }
            val session = jsch.getSession(c.username, c.host.trim(), c.port)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            if (c.password.isNotEmpty()) session.setPassword(c.password)
            session.userInfo = Answers(c.password, c.passphrase)
            session.timeout = c.timeoutMs
            session.setServerAliveInterval(15_000)
            try {
                session.connect(c.timeoutMs)
            } catch (e: JSchException) {
                session.disconnect()
                throw explain(e, c, keys)
            }
            val channel = try {
                (session.openChannel("sftp") as ChannelSftp).also { it.connect(c.timeoutMs) }
            } catch (e: JSchException) {
                session.disconnect()
                throw RemoteException("Signed in, but the server has no SFTP. Is it an SSH server with file transfer switched on?", e)
            }
            return SftpSession(session, channel, if (c.pin == null) keys.seen else null)
        }

        private fun explain(e: JSchException, c: Connection, keys: PinnedHostKeys): Exception {
            val cause = e.cause
            val message = e.message.orEmpty()
            return when {
                e is JSchChangedHostKeyException && keys.seen != null ->
                    UntrustedServerException(UntrustedServerException.Reason.KEY_CHANGED, keys.seen!!, e)
                cause is UnknownHostException -> RemoteException("Can't find “${c.host}”. Check the address and that the phone is online.", e)
                cause is ConnectException -> RemoteException("Nothing answered on ${c.host}:${c.port}. Check the port, and that SSH is switched on.", e)
                cause is NoRouteToHostException -> RemoteException("No route to ${c.host}. Is the phone on the same network?", e)
                cause is SocketTimeoutException || message.startsWith("timeout") ->
                    RemoteException("${c.host} didn't answer in time.", e)
                e is JSchAlgoNegoFailException -> RemoteException("KultrDL and the server share no encryption method: ${e.message}", e)
                message.startsWith("Auth fail") || message.startsWith("Auth cancel") ->
                    RemoteException("The server didn't accept the username, password or key.", e)
                message.contains("invalid privatekey") -> RemoteException("The private key couldn't be read.", e)
                else -> RemoteException("SFTP: ${message.ifEmpty { e.javaClass.simpleName }}", e)
            }
        }
    }
}

/**
 * Trusts the pinned key, or — when nothing is pinned yet — the first key
 * the server shows, which the caller then saves (as OpenSSH's
 * "accept-new" does). A different key stops the connection before any
 * password is sent.
 */
private class PinnedHostKeys(private val pin: String?) : HostKeyRepository {
    @Volatile var seen: String? = null

    override fun check(host: String?, key: ByteArray): Int {
        val fp = fingerprint(key)
        seen = fp
        return if (pin == null || pin == fp) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "KultrDL"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/** Answers the server's password prompts (keyboard-interactive, as PAM asks) with the saved password. */
private class Answers(private val password: String, private val passphrase: String) : UserInfo, UIKeyboardInteractive {
    override fun getPassphrase(): String? = passphrase.ifEmpty { null }
    override fun getPassword(): String? = password.ifEmpty { null }
    override fun promptPassword(message: String?): Boolean = password.isNotEmpty()
    override fun promptPassphrase(message: String?): Boolean = passphrase.isNotEmpty()
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) = Unit

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String>? {
        if (password.isEmpty() || prompt == null) return null
        // One answer per prompt; servers that ask more than for a password (one-time codes) aren't supported.
        return Array(prompt.size) { password }
    }
}
