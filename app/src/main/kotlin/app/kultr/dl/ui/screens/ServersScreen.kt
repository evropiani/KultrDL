package app.kultr.dl.ui.screens

import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.remote.Connection
import app.kultr.dl.core.remote.FolderLayout
import app.kultr.dl.core.remote.Protocol
import app.kultr.dl.core.remote.Remote
import app.kultr.dl.core.remote.RemoteEntry
import app.kultr.dl.core.remote.RemotePath
import app.kultr.dl.core.remote.RemoteSession
import app.kultr.dl.core.remote.UntrustedServerException
import app.kultr.dl.data.MessageKind
import app.kultr.dl.data.SavedServer
import app.kultr.dl.data.SecretBox
import app.kultr.dl.data.describe
import app.kultr.dl.data.folderLabel
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.Routes
import app.kultr.dl.ui.chromePadding
import app.kultr.dl.ui.components.ConfirmDialog
import app.kultr.dl.ui.components.EmptyState
import app.kultr.dl.ui.components.GlassPanel
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.components.Segmented
import app.kultr.dl.ui.components.Tag
import app.kultr.dl.ui.components.TextDialog
import app.kultr.dl.ui.theme.Kultr
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
internal fun PageHeader(title: String, action: (@Composable () -> Unit)? = null) {
    val actions = LocalActions.current
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { actions.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Kultr.colors.ink) }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Kultr.colors.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        action?.invoke()
    }
}

/** The saved FTP and SFTP servers. */
@Composable
fun ServersScreen() {
    val actions = LocalActions.current
    val servers by actions.graph.servers.servers.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = chromePadding(24.dp))) {
        item(key = "head") {
            PageHeader("Servers")
            Text(
                "Send downloads straight to a NAS, a seedbox or any computer that has SFTP or FTP. Save the folders you use, then choose one when you download.",
                style = MaterialTheme.typography.bodyMedium,
                color = Kultr.colors.ink2,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Pill("Add server", icon = Icons.Rounded.Add, accent = true, onClick = { actions.navigate(Routes.server(Routes.NEW)) })
            }
        }
        if (servers.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    Icons.Rounded.Dns,
                    "No servers yet",
                    body = "Add one with its address, a username and password (or an SSH key), and the folders music should go to.",
                )
            }
        }
        items(servers, key = { it.id }) { server -> ServerCard(server, onClick = { actions.navigate(Routes.server(server.id)) }) }
    }
}

@Composable
private fun ServerCard(server: SavedServer, onClick: () -> Unit) {
    val colors = Kultr.colors
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), padding = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Dns, contentDescription = null, tint = colors.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${server.protocol.label} · ${server.address}", style = MaterialTheme.typography.bodySmall, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.ink3)
            }
            if (server.folders.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    server.folders.forEach { Tag(folderLabel(it)) }
                }
            }
        }
    }
}

/** What is being typed into the editor; secrets in the clear until saved. */
@Stable
private class ServerDraft(val original: SavedServer?) {
    var name by mutableStateOf(original?.name.orEmpty())
    var protocol by mutableStateOf(original?.protocol ?: Protocol.SFTP)
    var host by mutableStateOf(original?.host.orEmpty())
    var port by mutableStateOf(original?.let { o -> o.port.takeIf { it != o.protocol.defaultPort }?.toString() }.orEmpty())
    var username by mutableStateOf(original?.username.orEmpty())
    var password by mutableStateOf(original?.let { SecretBox.decrypt(it.password) }.orEmpty())
    var privateKey by mutableStateOf(original?.let { SecretBox.decrypt(it.privateKey) }.orEmpty())
    var keyName by mutableStateOf(original?.keyName?.takeIf { privateKey.isNotEmpty() })
    var passphrase by mutableStateOf(original?.let { SecretBox.decrypt(it.passphrase) }.orEmpty())
    var pin by mutableStateOf(original?.pin)
    val folders = mutableStateListOf<String>().apply { addAll(original?.folders.orEmpty()) }
    var layout by mutableStateOf(original?.layout ?: FolderLayout.FLAT)
    var passive by mutableStateOf(original?.passive ?: true)

    /** A saved password that this phone can no longer open (the app's key was reset). */
    val lostSecrets: Boolean = original != null &&
        listOf(original.password, original.privateKey, original.passphrase).any { it.isNotEmpty() && SecretBox.decrypt(it) == null }

    val portNumber: Int get() = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: protocol.defaultPort

    val valid: Boolean get() = host.isNotBlank() && (port.isEmpty() || port.toIntOrNull() in 1..65535)

    fun connection() = Connection(protocol, host.trim(), portNumber, username.trim(), password, privateKey, passphrase, pin, passive)

    fun toServer(): SavedServer = SavedServer(
        id = original?.id ?: UUID.randomUUID().toString(),
        name = name.trim().ifEmpty { host.trim() },
        protocol = protocol,
        host = host.trim(),
        port = portNumber,
        username = username.trim(),
        password = SecretBox.encrypt(password),
        privateKey = SecretBox.encrypt(privateKey),
        passphrase = SecretBox.encrypt(passphrase),
        keyName = keyName,
        pin = pin,
        folders = folders.toList().ifEmpty { listOf("") },
        layout = layout,
        passive = passive,
    )
}

/**
 * One connection kept open while folders are browsed, used one call at a
 * time off the main thread.
 */
private class Browser(private val connection: Connection) : Closeable {
    private var session: RemoteSession? = null
    private val lock = Mutex()
    var newPin: String? = null
        private set

    suspend fun <T> run(block: (RemoteSession) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            val s = session ?: Remote.open(connection).also {
                session = it
                newPin = it.newPin
            }
            block(s)
        }
    }

    override fun close() {
        val s = session ?: return
        session = null
        // Closing talks to the server; keep that off the main thread.
        Thread { runCatching { s.close() } }.start()
    }
}

private data class TestOutcome(val home: String, val newPin: String?, val folders: List<Pair<String, Boolean>>)

/** Adds or edits a server: how to reach it, how to sign in, and its folders. */
@Composable
fun ServerEditorScreen(id: String) {
    val actions = LocalActions.current
    val graph = actions.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = remember(id) { ServerDraft(graph.servers.get(id)) }
    var showPassword by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var untrusted by remember { mutableStateOf<UntrustedServerException?>(null) }
    var browsing by remember { mutableStateOf(false) }
    var typingFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val colors = Kultr.colors
    // Leaving a field before a dialog opens keeps the page from jumping back to it afterwards.
    val focus = LocalFocusManager.current

    fun test() {
        testing = true
        val connection = draft.connection()
        val folders = draft.folders.toList()
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    Remote.use(connection) { s ->
                        TestOutcome(s.home, s.newPin, folders.map { f -> f to s.isDirectory(RemotePath.resolve(s.home, f)) })
                    }
                }
                outcome.newPin?.let { draft.pin = it }
                report = buildString {
                    append("Signed in to ${draft.host.trim()}. It starts in ${outcome.home}.")
                    if (outcome.folders.isNotEmpty()) {
                        append("\n\n")
                        outcome.folders.forEach { (f, exists) -> append("${folderLabel(f)}: ${if (exists) "found" else "will be created"}\n") }
                    }
                    if (draft.protocol == Protocol.SFTP && draft.pin != null) {
                        append("\nServer key: ${draft.pin}")
                        if (outcome.newPin != null) append("\nIt will be trusted from now on; if it ever changes, KultrDL stops and asks.")
                    }
                    if (draft.protocol == Protocol.FTP) append("\nPlain FTP isn't encrypted. Use SFTP or FTPS if the server has them.")
                }.trim()
                Log.i("KultrDL", "Server test: signed in to ${connection.protocol.label} ${connection.host}:${connection.port}, folders ${outcome.folders}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: UntrustedServerException) {
                Log.i("KultrDL", "Server test: ${e.reason} ${e.fingerprint}")
                untrusted = e
            } catch (e: Exception) {
                Log.w("KultrDL", "Server test: couldn't connect to ${connection.protocol.label} ${connection.host}:${connection.port}", e)
                report = "Couldn't connect: ${describe(e)}"
            } finally {
                testing = false
            }
        }
    }

    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        actions.launch {
            val (name, text) = withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "key"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                name to if (bytes.size > 64 * 1024) "" else bytes.decodeToString()
            }
            if (!text.contains("PRIVATE KEY") && !text.startsWith("PuTTY-User-Key-File")) {
                graph.messages.error("That doesn't look like a private key. Choose the key file without .pub.")
            } else {
                draft.privateKey = text
                draft.keyName = name
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        PageHeader(if (draft.original == null) "Add server" else draft.original.name) {
            if (draft.original != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = colors.danger) }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (draft.lostSecrets) {
                Text("The saved password or key can't be read on this phone any more. Enter it again.", color = colors.warning, style = MaterialTheme.typography.bodySmall)
            }
            Field("Name", draft.name, placeholder = "My NAS", tag = "server-name") { draft.name = it }
            Text("Protocol", style = MaterialTheme.typography.labelLarge, color = colors.ink2)
            Segmented(
                options = Protocol.entries.map { it to it.label },
                selected = draft.protocol,
                onSelect = { draft.protocol = it; draft.pin = null },
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
            Text(draft.protocol.hint, style = MaterialTheme.typography.bodySmall, color = if (draft.protocol == Protocol.FTP) colors.warning else colors.ink3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Host", draft.host, placeholder = "192.168.1.20 or nas.example.com", keyboard = KeyboardType.Uri, tag = "server-host", modifier = Modifier.weight(1f)) {
                    draft.host = it.trim()
                    draft.pin = null
                }
                Field("Port", draft.port, placeholder = draft.protocol.defaultPort.toString(), keyboard = KeyboardType.Number, tag = "server-port", modifier = Modifier.width(96.dp)) {
                    draft.port = it.filter(Char::isDigit).take(5)
                    draft.pin = null
                }
            }
            Field("Username", draft.username, placeholder = if (draft.protocol == Protocol.SFTP) "" else "anonymous", tag = "server-user") { draft.username = it.trim() }
            OutlinedTextField(
                value = draft.password,
                onValueChange = { draft.password = it },
                singleLine = true,
                label = { Text(if (draft.protocol == Protocol.SFTP && draft.privateKey.isNotEmpty()) "Password (if the key isn't enough)" else "Password") },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (showPassword) "Hide password" else "Show password")
                    }
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("server-password"),
            )
            if (draft.protocol == Protocol.SFTP) {
                InlineRow(
                    "SSH key",
                    hint = draft.keyName?.let { "Using $it" } ?: "Optional: sign in with a private key file (OpenSSH, PEM or PuTTY).",
                    onClick = { pickKey.launch(arrayOf("*/*")) },
                ) {
                    if (draft.privateKey.isNotEmpty()) {
                        IconButton(onClick = { draft.privateKey = ""; draft.keyName = null; draft.passphrase = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove key", tint = colors.ink3)
                        }
                    } else {
                        Icon(Icons.Rounded.Key, contentDescription = null, tint = colors.ink3)
                    }
                }
                if (draft.privateKey.isNotEmpty()) {
                    OutlinedTextField(
                        value = draft.passphrase,
                        onValueChange = { draft.passphrase = it },
                        singleLine = true,
                        label = { Text("Key passphrase (if it has one)") },
                        visualTransformation = PasswordVisualTransformation(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                draft.pin?.let { pin ->
                    Text("Trusted server key: $pin", style = MaterialTheme.typography.bodySmall, color = colors.ink3, fontFamily = FontFamily.Monospace)
                }
            } else {
                draft.pin?.let { pin ->
                    Text("Trusted certificate: $pin", style = MaterialTheme.typography.bodySmall, color = colors.ink3, fontFamily = FontFamily.Monospace)
                }
                InlineRow(
                    "Passive mode",
                    hint = "Works through routers and firewalls. Turn off only if the server asks for active mode.",
                    onClick = { draft.passive = !draft.passive },
                ) { Switch(checked = draft.passive, onCheckedChange = { draft.passive = it }) }
            }

            Spacer(Modifier.height(4.dp))
            Text("Folders", style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(
                "The places on the server music can go. You choose one of them when you download.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
            )
            draft.folders.forEach { folder ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(folderLabel(folder), color = colors.ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { draft.folders.remove(folder) }) { Icon(Icons.Rounded.Close, contentDescription = "Remove ${folderLabel(folder)}", tint = colors.ink3) }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Browse…", icon = Icons.Rounded.FolderOpen, enabled = draft.valid, onClick = { focus.clearFocus(); browsing = true })
                Pill("Type a path", icon = Icons.Rounded.CreateNewFolder, onClick = { focus.clearFocus(); typingFolder = true }, modifier = Modifier.testTag("folder-type"))
            }
            Text("Inside the folder", style = MaterialTheme.typography.labelLarge, color = colors.ink2)
            Segmented(
                options = FolderLayout.entries.map { it to it.label },
                selected = draft.layout,
                onSelect = { draft.layout = it },
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
            Text(
                when (draft.layout) {
                    FolderLayout.FLAT -> "Every file straight into the folder."
                    FolderLayout.ARTIST -> "A folder per artist, as Plex, Jellyfin and Navidrome like it."
                    FolderLayout.ARTIST_ALBUM -> "A folder per artist, and one per album inside it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).padding(bottom = chromePadding(0.dp)),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Pill(
                if (testing) "Testing…" else "Test connection",
                icon = Icons.Rounded.NetworkCheck,
                enabled = draft.valid && !testing,
                onClick = { focus.clearFocus(); test() },
                modifier = Modifier.weight(1f).testTag("server-test"),
            )
            Pill("Save", accent = true, enabled = draft.valid, modifier = Modifier.weight(1f).testTag("server-save"), onClick = {
                val server = draft.toServer()
                graph.servers.save(server)
                graph.messages.show("Saved “${server.name}”", MessageKind.SUCCESS)
                actions.back()
            })
        }
    }

    report?.let { text ->
        AlertDialog(
            onDismissRequest = { report = null },
            containerColor = colors.elevated,
            title = { Text("Test connection") },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) },
            confirmButton = { TextButton(onClick = { report = null }) { Text("OK") } },
        )
    }
    untrusted?.let { e ->
        TrustDialog(e, onTrust = {
            draft.pin = e.fingerprint
            untrusted = null
            test()
        }, onDismiss = { untrusted = null })
    }
    if (typingFolder) {
        TextDialog(
            title = "Add a folder",
            initial = "",
            placeholder = "/music, or music for one in the start folder",
            confirm = "Add",
            onConfirm = { path ->
                val folder = RemotePath.normalise(path)
                if (folder !in draft.folders) draft.folders += folder
                typingFolder = false
            },
            onDismiss = { typingFolder = false },
        )
    }
    if (browsing) {
        FolderBrowserDialog(
            connection = draft.connection(),
            start = draft.folders.lastOrNull(),
            onPick = { folder ->
                if (folder !in draft.folders) draft.folders += folder
                browsing = false
            },
            onPin = { draft.pin = it },
            onUntrusted = { e ->
                browsing = false
                untrusted = e
            },
            onDismiss = { browsing = false },
        )
    }
    if (confirmDelete && draft.original != null) {
        ConfirmDialog(
            title = "Delete “${draft.original.name}”?",
            body = "Files already on the server stay there. Downloads waiting to go to it will fail.",
            confirm = "Delete",
            onConfirm = {
                graph.servers.remove(draft.original.id)
                graph.settings.update { if (it.destination?.serverId == draft.original.id) it.copy(destination = null) else it }
                actions.back()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A label and hint with a control on the right, inside the editor's own padding. */
@Composable
private fun InlineRow(label: String, hint: String?, onClick: () -> Unit, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3)
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    tag: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = MaterialTheme.shapes.medium,
        modifier = if (tag != null) modifier.testTag(tag) else modifier,
    )
}

/** Asks before trusting a certificate the phone doesn't know, or a key that changed. */
@Composable
private fun TrustDialog(e: UntrustedServerException, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val colors = Kultr.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.elevated,
        title = {
            Text(
                when (e.reason) {
                    UntrustedServerException.Reason.KEY_CHANGED -> "The server's key changed"
                    UntrustedServerException.Reason.CERTIFICATE_UNTRUSTED -> "Trust this certificate?"
                    UntrustedServerException.Reason.CERTIFICATE_CHANGED -> "The certificate changed"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(e.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
                Text(e.fingerprint, style = MaterialTheme.typography.bodySmall, color = colors.ink, fontFamily = FontFamily.Monospace)
                Text(
                    if (e.reason == UntrustedServerException.Reason.KEY_CHANGED) {
                        "Compare it with what the server shows (ssh-keygen -lf on its host key). Trust it only if they match."
                    } else {
                        "Compare it with the certificate in the server's settings. Trust it only if they match; KultrDL will then accept exactly this certificate."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Trust") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Walks the server's folders and picks one. */
@Composable
private fun FolderBrowserDialog(
    connection: Connection,
    start: String?,
    onPick: (String) -> Unit,
    onPin: (String) -> Unit,
    onUntrusted: (UntrustedServerException) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Kultr.colors
    val scope = rememberCoroutineScope()
    val browser = remember { Browser(connection) }
    DisposableEffect(browser) { onDispose { browser.close() } }
    var path by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    fun open(target: String?) {
        entries = null
        error = null
        scope.launch {
            try {
                val (resolved, listing) = browser.run { s ->
                    val wanted = target?.let { RemotePath.resolve(s.home, it) }?.takeIf { s.isDirectory(it) } ?: s.home
                    wanted to s.list(wanted)
                }
                browser.newPin?.let(onPin)
                path = resolved
                entries = listing.filter { it.isDirectory }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UntrustedServerException) {
                onUntrusted(e)
            } catch (e: Exception) {
                error = describe(e)
            }
        }
    }
    LaunchedEffect(Unit) { open(start) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.elevated,
        title = { Text("Choose a folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(path ?: "Connecting…", style = MaterialTheme.typography.bodySmall, color = colors.ink2, fontFamily = FontFamily.Monospace)
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                    val list = entries
                    when {
                        error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(error.orEmpty(), color = colors.danger, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { open(path ?: start) }) { Text("Try again") }
                        }
                        list == null -> CircularProgressIndicator(color = colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp).align(Alignment.Center))
                        else -> LazyColumn {
                            val current = path
                            if (current != null && current != "/") {
                                item(key = "..") { FolderRow("..", "Up one folder") { open(RemotePath.parent(current)) } }
                            }
                            if (list.isEmpty()) {
                                item(key = "none") { Text("No folders in here.", color = colors.ink3, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
                            }
                            items(list, key = { it.name }) { entry ->
                                FolderRow(entry.name, null) { open(RemotePath.join(current ?: "/", entry.name)) }
                            }
                        }
                    }
                }
                TextButton(onClick = { creating = true }, enabled = path != null) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("New folder")
                }
            }
        },
        confirmButton = { TextButton(onClick = { path?.let(onPick) }, enabled = path != null) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (creating) {
        TextDialog(
            title = "New folder",
            initial = "",
            placeholder = "Name",
            confirm = "Create",
            onConfirm = { name ->
                creating = false
                val target = RemotePath.join(path ?: "/", name.replace('/', '_'))
                scope.launch {
                    try {
                        browser.run { it.makeDirectories(target) }
                        open(target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = describe(e)
                    }
                }
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun FolderRow(name: String, hint: String?, onClick: () -> Unit) {
    val colors = Kultr.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Folder, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.ink, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hint != null) Text(hint, color = colors.ink3, style = MaterialTheme.typography.bodySmall)
        }
    }
}
