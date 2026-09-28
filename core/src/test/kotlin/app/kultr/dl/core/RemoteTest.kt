package app.kultr.dl.core

import app.kultr.dl.core.remote.Connection
import app.kultr.dl.core.remote.FolderLayout
import app.kultr.dl.core.remote.Protocol
import app.kultr.dl.core.remote.Remote
import app.kultr.dl.core.remote.RemoteException
import app.kultr.dl.core.remote.RemotePath
import app.kultr.dl.core.remote.RemoteSession
import app.kultr.dl.core.remote.UntrustedServerException
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemotePathTest {
    @Test
    fun normalisesAndJoins() {
        assertEquals("/a/b", RemotePath.normalise("/a//b/"))
        assertEquals("a/b", RemotePath.normalise("a/./b"))
        assertEquals("/", RemotePath.normalise("/"))
        assertEquals("", RemotePath.normalise("  "))
        assertEquals("/a", RemotePath.normalise("/a/b/.."))
        assertEquals("/home/me/music", RemotePath.resolve("/home/me", "music"))
        assertEquals("/srv", RemotePath.resolve("/home/me", "/srv/"))
        assertEquals("/home/me", RemotePath.resolve("/home/me", ""))
        assertEquals("/music/Artist/x.mp3", RemotePath.join("/music", "Artist", "x.mp3"))
        assertEquals("/x.mp3", RemotePath.join("/", "x.mp3"))
        assertEquals("/a", RemotePath.parent("/a/b"))
        assertEquals("/", RemotePath.parent("/a"))
        assertEquals(listOf("/a", "/a/b"), RemotePath.ancestors("/a/b"))
        assertEquals(emptyList(), RemotePath.ancestors("/"))
    }

    @Test
    fun layoutsNameFoldersSafely() {
        assertEquals(emptyList(), FolderLayout.FLAT.folders("A", null, "B"))
        assertEquals(listOf("AC_DC"), FolderLayout.ARTIST.folders("AC/DC", null, "Back in Black"))
        assertEquals(listOf("Various", "Now 1"), FolderLayout.ARTIST_ALBUM.folders("Someone", "Various", "Now 1"))
        assertEquals(listOf("Someone"), FolderLayout.ARTIST_ALBUM.folders("Someone", null, null))
    }
}

/** Every protocol against a real server in the test JVM: SFTP (Apache MINA SSHD), FTP and FTPS (Apache FtpServer). */
class RemoteServersTest {
    @get:Rule val tmp = TemporaryFolder()

    private var ssh: SshServer? = null
    private var ftp: FtpServer? = null

    @After
    fun stop() {
        ssh?.stop(true)
        ftp?.stop()
    }

    private fun root(): File = File(tmp.root, "root").apply { mkdirs() }

    private fun startSsh(algorithm: String = "EC"): Int {
        val server = SshServer.setUpDefaultServer()
        server.host = "127.0.0.1"
        server.port = 0
        server.keyPairProvider = SimpleGeneratorHostKeyProvider(File(tmp.root, "host-$algorithm").toPath()).apply { this.algorithm = algorithm }
        server.passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "kultr" && password == "secret" }
        server.publickeyAuthenticator = AcceptAllPublickeyAuthenticator.INSTANCE
        server.subsystemFactories = listOf(SftpSubsystemFactory())
        server.fileSystemFactory = VirtualFileSystemFactory(root().toPath())
        server.start()
        ssh = server
        return server.port
    }

    private fun exercise(s: RemoteSession, base: String) {
        s.makeDirectories("$base/Björk/Homogenic")
        assertTrue(s.isDirectory("$base/Björk"))
        assertTrue(!s.isDirectory("$base/Nobody"))
        var sent = 0L
        s.upload("first".byteInputStream(), "$base/Björk/Homogenic/01 - Hunter.mp3") { sent = it }
        assertEquals(5L, sent)
        // Again: replaces the file, leaves no partial one behind.
        s.upload("second take".byteInputStream(), "$base/Björk/Homogenic/01 - Hunter.mp3")
        assertEquals(listOf("01 - Hunter.mp3"), s.list("$base/Björk/Homogenic").map { it.name })
        val top = s.list(base)
        assertEquals(listOf("Björk"), top.map { it.name })
        assertTrue(top.single().isDirectory)
        assertEquals("second take", File(root(), "${base.trim('/')}/Björk/Homogenic/01 - Hunter.mp3").readText())
        s.delete("$base/Björk/Homogenic/01 - Hunter.mp3")
        assertEquals(emptyList(), s.list("$base/Björk/Homogenic"))
    }

    @Test
    fun sftpUploadsAndPinsTheHostKey() {
        val port = startSsh()
        val connection = Connection(Protocol.SFTP, "127.0.0.1", port, "kultr", "secret", timeoutMs = 10_000)
        val pin = Remote.use(connection) { s ->
            assertEquals("/", s.home)
            exercise(s, "music")
            assertNotNull(s.newPin)
        }
        assertTrue(pin.startsWith("SHA256:"))
        Remote.use(connection.copy(pin = pin)) { assertNull(it.newPin) }
        val changed = assertFailsWith<UntrustedServerException> { Remote.open(connection.copy(pin = "SHA256:someoneelse")) }
        assertEquals(UntrustedServerException.Reason.KEY_CHANGED, changed.reason)
        assertEquals(pin, changed.fingerprint)
    }

    @Test
    fun sftpWorksWithAnEd25519HostKey() {
        val port = startSsh("EdDSA")
        Remote.use(Connection(Protocol.SFTP, "127.0.0.1", port, "kultr", "secret", timeoutMs = 10_000)) { s ->
            s.upload("x".byteInputStream(), "/one.flac")
        }
        assertEquals("x", File(root(), "one.flac").readText())
    }

    @Test
    fun sftpSignsInWithAKeyFile() {
        val keygen = listOf("/usr/bin/ssh-keygen", "/bin/ssh-keygen").firstOrNull { File(it).canExecute() }
        assumeTrue("ssh-keygen is not installed", keygen != null)
        val port = startSsh()
        for ((type, passphrase) in listOf("ed25519" to "", "ed25519" to "open sesame", "ecdsa" to "", "rsa" to "")) {
            val file = File(tmp.root, "id_${type}_${passphrase.length}")
            val p = ProcessBuilder(keygen, "-q", "-t", type, "-N", passphrase, "-C", "test", "-f", file.path).redirectErrorStream(true).start()
            assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, p.inputStream.bufferedReader().readText())
            val connection = Connection(Protocol.SFTP, "127.0.0.1", port, "kultr", privateKey = file.readText(), passphrase = passphrase, timeoutMs = 10_000)
            Remote.use(connection) { it.upload(type.byteInputStream(), "/$type-${passphrase.length}.txt") }
        }
        assertEquals("ed25519", File(root(), "ed25519-11.txt").readText())
        val wrongPhrase = Connection(
            Protocol.SFTP, "127.0.0.1", port, "kultr",
            privateKey = File(tmp.root, "id_ed25519_11").readText(), passphrase = "wrong", timeoutMs = 10_000,
        )
        assertFailsWith<RemoteException> { Remote.open(wrongPhrase) }
    }

    @Test
    fun sftpExplainsAWrongPasswordAndAClosedPort() {
        val port = startSsh()
        val wrong = assertFailsWith<RemoteException> { Remote.open(Connection(Protocol.SFTP, "127.0.0.1", port, "kultr", "nope", timeoutMs = 10_000)) }
        assertContains(wrong.message!!, "didn't accept")
        val closed = assertFailsWith<RemoteException> { Remote.open(Connection(Protocol.SFTP, "127.0.0.1", freePort(), "kultr", "secret", timeoutMs = 5_000)) }
        assertContains(closed.message!!, "Nothing answered")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun keystore(): File {
        val file = File(tmp.root, "ftps.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "ftp", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=nas.local",
            "-validity", "3", "-storetype", "PKCS12", "-keystore", file.path, "-storepass", "secret", "-keypass", "secret",
        ).redirectErrorStream(true).start()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, p.inputStream.bufferedReader().readText())
        return file
    }

    private fun startFtp(tls: Boolean = false, implicit: Boolean = false): Int {
        val factory = FtpServerFactory()
        val port = freePort()
        val listener = ListenerFactory()
        listener.serverAddress = "127.0.0.1"
        listener.port = port
        if (tls) {
            val ssl = SslConfigurationFactory()
            ssl.keystoreFile = keystore()
            ssl.keystorePassword = "secret"
            ssl.keystoreType = "PKCS12"
            ssl.keyPassword = "secret"
            listener.sslConfiguration = ssl.createSslConfiguration()
            listener.isImplicitSsl = implicit
        }
        factory.addListener("default", listener.createListener())
        val users = PropertiesUserManagerFactory().createUserManager()
        users.save(
            BaseUser().apply {
                name = "kultr"
                password = "secret"
                homeDirectory = root().absolutePath
                authorities = listOf(WritePermission())
            },
        )
        factory.userManager = users
        ftp = factory.createServer().apply { start() }
        return port
    }

    @Test
    fun ftpUploads() {
        val port = startFtp()
        Remote.use(Connection(Protocol.FTP, "127.0.0.1", port, "kultr", "secret", timeoutMs = 10_000)) { s ->
            assertEquals("/", s.home)
            assertNull(s.newPin)
            exercise(s, "/music")
        }
        val wrong = assertFailsWith<RemoteException> { Remote.open(Connection(Protocol.FTP, "127.0.0.1", port, "kultr", "nope", timeoutMs = 10_000)) }
        assertContains(wrong.message!!, "didn't accept")
    }

    @Test
    fun ftpsAsksBeforeTrustingASelfSignedCertificate() {
        val port = startFtp(tls = true)
        val connection = Connection(Protocol.FTPS, "127.0.0.1", port, "kultr", "secret", timeoutMs = 10_000)
        val untrusted = assertFailsWith<UntrustedServerException> { Remote.open(connection) }
        assertEquals(UntrustedServerException.Reason.CERTIFICATE_UNTRUSTED, untrusted.reason)
        Remote.use(connection.copy(pin = untrusted.fingerprint)) { exercise(it, "music") }
        val changed = assertFailsWith<UntrustedServerException> { Remote.open(connection.copy(pin = "SHA256:another")) }
        assertEquals(UntrustedServerException.Reason.CERTIFICATE_CHANGED, changed.reason)
    }

    @Test
    fun implicitFtps() {
        val port = startFtp(tls = true, implicit = true)
        val connection = Connection(Protocol.FTPS_IMPLICIT, "127.0.0.1", port, "kultr", "secret", timeoutMs = 10_000)
        val untrusted = assertFailsWith<UntrustedServerException> { Remote.open(connection) }
        Remote.use(connection.copy(pin = untrusted.fingerprint)) { it.upload("tls".byteInputStream(), "/implicit.mp3") }
        assertEquals("tls", File(root(), "implicit.mp3").readText())
    }

    @Test
    fun ftpsAgainstAPlainServerIsExplained() {
        val port = startFtp()
        val e = assertFailsWith<RemoteException> { Remote.open(Connection(Protocol.FTPS, "127.0.0.1", port, "kultr", "secret", timeoutMs = 10_000)) }
        assertContains(e.message!!, "FTPS")
    }
}
