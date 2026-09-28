package app.kultr.dl.core.remote

import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient

/** FTP and FTPS through Apache Commons Net. */
internal class FtpSession private constructor(private val ftp: FTPClient, override val home: String) : RemoteSession {
    override val newPin: String? = null

    @Volatile private var closed = false

    override fun list(path: String): List<RemoteEntry> {
        val dir = RemotePath.resolve(home, path)
        // Change into the folder first: LIST with a path containing spaces confuses some servers.
        if (!ftp.changeWorkingDirectory(dir)) fail("open", dir)
        val files = if (ftp.hasFeature("MLST")) ftp.mlistDir() else ftp.listFiles()
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) fail("list", dir)
        return files.filterNotNull()
            .filter { it.name != null && it.name != "." && it.name != ".." }
            .map { RemoteEntry(it.name, it.isDirectory || it.isSymbolicLink, it.size.coerceAtLeast(0)) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun isDirectory(path: String): Boolean = ftp.changeWorkingDirectory(RemotePath.resolve(home, path))

    override fun makeDirectories(path: String) {
        for (dir in RemotePath.ancestors(RemotePath.resolve(home, path))) {
            if (ftp.changeWorkingDirectory(dir)) continue
            if (!ftp.makeDirectory(dir)) fail("create", dir)
        }
    }

    override fun upload(input: InputStream, path: String, progress: (sent: Long) -> Unit) {
        val target = RemotePath.resolve(home, path)
        val partial = "$target.part"
        val counting = CountingInputStream(input, progress, stop = { closed })
        if (!ftp.storeFile(partial, counting)) {
            if (closed) throw RemoteException("Cancelled")
            fail("write", target)
        }
        counting.finish()
        ftp.deleteFile(target)
        if (!ftp.rename(partial, target)) fail("rename", target)
    }

    override fun delete(path: String) {
        val target = RemotePath.resolve(home, path)
        if (!ftp.deleteFile(target)) fail("delete", target)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { ftp.disconnect() }
    }

    private fun fail(action: String, path: String): Nothing {
        val reply = ftp.replyString?.trim().orEmpty()
        val code = ftp.replyCode
        throw RemoteException(
            when {
                code == 550 && action == "open" -> "“$path” doesn't exist on the server, or can't be opened."
                code == 550 || code == 553 || code == 532 -> "The server doesn't allow KultrDL to $action “$path” ($reply)."
                code == 552 -> "The server is out of space ($reply)."
                code == 522 || reply.contains("session reuse", ignoreCase = true) ->
                    "The server wants TLS session reuse on data connections, which KultrDL can't do. Switch that off on the server (vsftpd: require_ssl_reuse=NO) or use SFTP."
                else -> "Couldn't $action “$path”: ${reply.ifEmpty { "no answer" }}"
            },
        )
    }

    companion object {
        fun open(c: Connection): FtpSession {
            val trust = if (c.protocol == Protocol.FTP) null else PinnedCertificates(c.pin)
            val ftp = when (c.protocol) {
                Protocol.FTPS -> FTPSClient("TLS", false)
                Protocol.FTPS_IMPLICIT -> FTPSClient("TLS", true)
                else -> FTPClient()
            }
            if (ftp is FTPSClient) {
                ftp.trustManager = trust
                // A certificate the user pinned is trusted as it is, whatever name it carries.
                ftp.isEndpointCheckingEnabled = c.pin == null
            }
            // Names like "Björk" go as UTF-8 (RFC 2640), which today's servers expect whether or not they say so.
            ftp.controlEncoding = "UTF-8"
            ftp.connectTimeout = c.timeoutMs
            ftp.defaultTimeout = c.timeoutMs
            ftp.setDataTimeout(Duration.ofMillis(c.timeoutMs.toLong()))
            ftp.setControlKeepAliveTimeout(Duration.ofSeconds(20))
            // EPSV names only a port, so the data connection goes to the same address as the control one.
            ftp.setUseEPSVwithIPv4(true)
            ftp.setPassiveNatWorkaroundStrategy(SameHostForPrivateAddresses(ftp))
            try {
                ftp.connect(c.host.trim(), c.port)
                if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
                    throw RemoteException("The server turned the connection away: ${ftp.replyString.trim()}")
                }
                if (!ftp.login(c.username.ifEmpty { "anonymous" }, c.password)) {
                    throw RemoteException("The server didn't accept the username or password.")
                }
                if (ftp is FTPSClient) {
                    ftp.execPBSZ(0)
                    ftp.execPROT("P")
                }
                if (ftp.hasFeature("UTF8")) ftp.sendCommand("OPTS UTF8 ON")
                if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) throw RemoteException("The server refused binary transfers: ${ftp.replyString.trim()}")
                if (c.passive) ftp.enterLocalPassiveMode() else ftp.enterLocalActiveMode()
                ftp.bufferSize = 64 * 1024
                val home = RemotePath.normalise(ftp.printWorkingDirectory() ?: "/").ifEmpty { "/" }
                return FtpSession(ftp, if (home.startsWith("/")) home else "/$home")
            } catch (e: Exception) {
                runCatching { ftp.disconnect() }
                throw explain(e, c, trust)
            }
        }

        private fun explain(e: Exception, c: Connection, trust: PinnedCertificates?): Exception {
            val rejected = trust?.rejected
            val message = e.message.orEmpty()
            return when {
                rejected != null -> UntrustedServerException(
                    if (c.pin == null) UntrustedServerException.Reason.CERTIFICATE_UNTRUSTED else UntrustedServerException.Reason.CERTIFICATE_CHANGED,
                    rejected,
                    e,
                )
                e is RemoteException -> e
                e is UnknownHostException -> RemoteException("Can't find “${c.host}”. Check the address and that the phone is online.", e)
                e is ConnectException -> RemoteException("Nothing answered on ${c.host}:${c.port}. Check the port and the protocol.", e)
                e is NoRouteToHostException -> RemoteException("No route to ${c.host}. Is the phone on the same network?", e)
                e is SocketTimeoutException -> RemoteException("${c.host} didn't answer in time.", e)
                e is SSLException && c.protocol == Protocol.FTPS && Regex("^\\s*5\\d\\d").containsMatchIn(message) ->
                    RemoteException("The server doesn't offer FTPS (it answered: ${message.trim()}). Choose FTP, or switch TLS on at the server.", e)
                e is SSLException -> RemoteException("The encrypted connection failed: ${message.ifEmpty { e.javaClass.simpleName }}. Is it the right port for ${c.protocol.label}?", e)
                else -> RemoteException("${c.protocol.label}: ${message.ifEmpty { e.javaClass.simpleName }}", e)
            }
        }
    }
}

/**
 * A server behind NAT (or in a container) may answer PASV with an address
 * only it can reach. Use the address the control connection went to
 * instead, as FileZilla does.
 */
private class SameHostForPrivateAddresses(private val ftp: FTPClient) : FTPClient.HostnameResolver {
    override fun resolve(hostname: String): String {
        val reply = InetAddress.getByName(hostname)
        val remote = ftp.remoteAddress ?: return hostname
        val private = reply.isSiteLocalAddress || reply.isLoopbackAddress || reply.isAnyLocalAddress || reply.isLinkLocalAddress
        return if (private && reply != remote) remote.hostAddress else hostname
    }
}

/**
 * Trusts what the phone trusts, or exactly the certificate the user pinned.
 * Remembers the fingerprint of a certificate it turned down, so the user can
 * be asked whether to trust it.
 */
private class PinnedCertificates(private val pin: String?) : X509ExtendedTrustManager() {
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    @Volatile var rejected: String? = null

    private inline fun check(chain: Array<out X509Certificate>, systemCheck: () -> Unit) {
        val fp = fingerprint(chain.first().encoded)
        if (pin != null) {
            if (fp == pin) return
            rejected = fp
            throw CertificateException("The certificate isn't the one that was trusted.")
        }
        try {
            systemCheck()
        } catch (e: CertificateException) {
            rejected = fp
            throw e
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = check(chain) {
        val s = system
        if (s is X509ExtendedTrustManager) s.checkServerTrusted(chain, authType, socket) else s.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = check(chain) {
        val s = system
        if (s is X509ExtendedTrustManager) s.checkServerTrusted(chain, authType, engine) else s.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = check(chain) { system.checkServerTrusted(chain, authType) }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = throw CertificateException("Not a server")
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = throw CertificateException("Not a server")
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("Not a server")
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}
