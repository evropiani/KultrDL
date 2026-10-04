package app.kultr.dl.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.sources.Subsonic
import app.kultr.dl.data.MessageKind
import app.kultr.dl.data.NavidromeRepository
import app.kultr.dl.data.SecretBox
import app.kultr.dl.data.describe
import app.kultr.dl.ui.LocalActions
import app.kultr.dl.ui.Routes
import app.kultr.dl.ui.chromePadding
import app.kultr.dl.ui.components.DestinationPicker
import app.kultr.dl.ui.components.Pill
import app.kultr.dl.ui.theme.Kultr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The user's Navidrome: sign in, let its history (plays, stars, ratings)
 * and collection feed the recommendations, and choose the SFTP/FTP folder
 * it reads music from, for "Download to Navidrome".
 */
@Composable
fun NavidromeScreen() {
    val actions = LocalActions.current
    val graph = actions.graph
    val config by graph.navidrome.config.collectAsStateWithLifecycle()
    val servers by graph.servers.servers.collectAsStateWithLifecycle()
    val songs by graph.recommender.navidromeSongs.collectAsStateWithLifecycle(0)
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var url by remember { mutableStateOf(config.url) }
    var username by remember { mutableStateOf(config.username) }
    var password by remember { mutableStateOf(SecretBox.decrypt(config.password).orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var adminName by remember { mutableStateOf("") }
    var adminPassword by remember { mutableStateOf("") }
    var checkingAdmin by remember { mutableStateOf(false) }
    val colors = Kultr.colors
    val savedPassword = remember(config.password) { SecretBox.decrypt(config.password).orEmpty() }
    val changed = Subsonic.baseUrl(url) != config.url || username.trim() != config.username || password != savedPassword
    val valid = url.isNotBlank() && username.isNotBlank() && password.isNotEmpty()

    // Saved before KultrDL asked: find out whether the account is an admin, for the hint below.
    LaunchedEffect(config.url, config.username, config.isAdmin) {
        if (!config.configured || config.isAdmin != null) return@LaunchedEffect
        val admin = try {
            graph.navidrome.client(graph.http)?.let { withContext(Dispatchers.IO) { it.isAdmin() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (admin != null) graph.navidrome.update { if (it.username == config.username) it.copy(isAdmin = admin) else it }
    }

    fun save() {
        val (newUrl, newUser, newPassword) = Triple(url, username.trim(), password)
        syncing = true
        actions.launch {
            try {
                val kept = graph.navidrome.signIn(graph.http, newUrl, newUser, newPassword)
                if (kept != null) {
                    graph.messages.show("Signed in as $newUser. KultrDL keeps $kept for rescans after downloads.", MessageKind.SUCCESS, long = true)
                } else {
                    graph.messages.show("Navidrome saved; reading your music in the background", MessageKind.SUCCESS)
                }
                graph.recommender.syncNavidrome()
                graph.recommender.refreshInBackground()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.messages.error("Navidrome: ${describe(e)}")
            } finally {
                syncing = false
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        PageHeader("Navidrome")
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Connect your Navidrome (or any Subsonic server). KultrDL reads what you own and what you play there — play counts, stars and ratings — " +
                    "to suggest music, never suggests what you already have, and plays your songs from it in mixes. It never changes anything on the server.\n\n" +
                    "Sign in with the account you listen with, here and in other apps (like Kultr): Navidrome keeps plays, stars and ratings per account.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                singleLine = true,
                label = { Text("Server address") },
                placeholder = { Text("http://192.168.1.20:4533 or https://music.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("navidrome-url"),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                label = { Text("Username") },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("navidrome-user"),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                label = { Text("Password") },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (showPassword) "Hide password" else "Show password")
                    }
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("navidrome-password"),
            )
            Text(
                "Use a local Navidrome account (LDAP accounts can't sign in from apps). Passwords are stored encrypted on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (testing) "Testing…" else "Test connection", icon = Icons.Rounded.NetworkCheck, enabled = valid && !testing, modifier = Modifier.testTag("navidrome-test"), onClick = {
                    focus.clearFocus()
                    testing = true
                    scope.launch {
                        report = try {
                            val client = NavidromeRepository.clientFor(graph.http, url, username.trim(), password)
                            val info = withContext(Dispatchers.IO) { client.ping() }
                            val admin = try {
                                withContext(Dispatchers.IO) { client.isAdmin() }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                            "Signed in to $info at ${Subsonic.baseUrl(url)} as ${username.trim()}." +
                                (if (admin == true) "\n\n${username.trim()} is an admin. If you listen with another account, sign in with that one: suggestions learn from its plays. KultrDL keeps this admin login for rescans." else "") +
                                (if (!info.openSubsonic) "\n\nIt doesn't say when songs were last played (that needs OpenSubsonic), so older plays count as much as recent ones." else "")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Couldn't connect: ${describe(e)}"
                        } finally {
                            testing = false
                        }
                    }
                })
                Pill("Save", accent = true, enabled = valid && changed && !syncing, modifier = Modifier.testTag("navidrome-save"), onClick = {
                    focus.clearFocus()
                    save()
                })
            }

            if (config.configured && config.isAdmin == true && !config.hasAdmin) {
                Text(
                    "${config.username} is an admin account. If you listen with a different account (in Kultr or another app), sign in with that one " +
                        "above, so suggestions learn from your plays. KultrDL then keeps ${config.username} for rescans.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accent,
                    modifier = Modifier.testTag("navidrome-admin-hint"),
                )
            }

            if (config.configured) {
                Spacer(Modifier.height(6.dp))
                Text("Suggestions", style = MaterialTheme.typography.titleMedium, color = colors.ink)
                ToggleRow(
                    "Use my Navidrome for suggestions",
                    "${config.username}'s plays, stars and ratings tell KultrDL what you like; what's on it is never suggested.",
                    config.useHistory,
                ) { v -> graph.navidrome.update { it.copy(useHistory = v) } }
                Text(
                    listOfNotNull(
                        if (songs > 0) "$songs songs read" else null,
                        config.lastSync,
                    ).joinToString(" · ").ifEmpty { "Not read yet" },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                )
                Pill(if (syncing) "Reading…" else "Read it again now", icon = Icons.Rounded.Sync, enabled = !syncing, onClick = {
                    syncing = true
                    scope.launch {
                        try {
                            graph.recommender.syncNavidrome()
                            graph.recommender.refreshInBackground()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            graph.messages.error("Navidrome: ${describe(e)}")
                        } finally {
                            syncing = false
                        }
                    }
                })

                Spacer(Modifier.height(6.dp))
                Text("Download to Navidrome", style = MaterialTheme.typography.titleMedium, color = colors.ink)
                Text(
                    "Choose the folder Navidrome reads music from, on one of your SFTP or FTP servers. “Download to Navidrome” then puts songs and albums " +
                        "straight there, in the format you download in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                )
                if (servers.isEmpty()) {
                    Pill("Add an SFTP or FTP server", icon = Icons.Rounded.Add, onClick = { actions.navigate(Routes.server(Routes.NEW)) })
                } else {
                    DestinationPicker(config.destination, allowPhone = false) { d -> graph.navidrome.update { it.copy(destination = d?.copy(keepOnPhone = false)) } }
                }
                ToggleRow(
                    "Rescan after downloads",
                    "Ask Navidrome to look for new files as soon as downloads arrive.",
                    config.rescan,
                ) { v -> graph.navidrome.update { it.copy(rescan = v) } }
                if (config.rescan && config.hasAdmin) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Rescans sign in as ${config.adminUsername}; everything else as ${config.username}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.ink3,
                            modifier = Modifier.weight(1f).testTag("navidrome-admin-kept"),
                        )
                        TextButton(onClick = { graph.navidrome.forgetAdmin() }) { Text("Remove") }
                    }
                } else if (config.rescan && config.isAdmin != true) {
                    Text(
                        "Only admins can start a scan. If ${config.username} isn't one, add an admin login, used for rescans and nothing else. " +
                            "Without one, Navidrome still finds new files on its own schedule.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.ink3,
                    )
                    OutlinedTextField(
                        value = adminName,
                        onValueChange = { adminName = it },
                        singleLine = true,
                        label = { Text("Admin username") },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().testTag("navidrome-admin-user"),
                    )
                    OutlinedTextField(
                        value = adminPassword,
                        onValueChange = { adminPassword = it },
                        singleLine = true,
                        label = { Text("Admin password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().testTag("navidrome-admin-password"),
                    )
                    Pill(
                        if (checkingAdmin) "Checking…" else "Use for rescans",
                        enabled = adminName.isNotBlank() && adminPassword.isNotEmpty() && !checkingAdmin,
                        modifier = Modifier.testTag("navidrome-admin-save"),
                        onClick = {
                            focus.clearFocus()
                            checkingAdmin = true
                            scope.launch {
                                try {
                                    if (graph.navidrome.keepAdmin(graph.http, adminName, adminPassword)) {
                                        graph.messages.show("Rescans will sign in as ${adminName.trim()}", MessageKind.SUCCESS)
                                        adminName = ""
                                        adminPassword = ""
                                    } else {
                                        graph.messages.error("${adminName.trim()} isn't an admin on this server.")
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    graph.messages.error("Couldn't sign in as ${adminName.trim()}: ${describe(e)}")
                                } finally {
                                    checkingAdmin = false
                                }
                            }
                        },
                    )
                }
                config.lastScan?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.ink3) }

                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    graph.navidrome.clear()
                    actions.launch { graph.recommender.forgetNavidrome() }
                    url = ""
                    username = ""
                    password = ""
                    graph.messages.show("Navidrome removed")
                }) { Text("Remove Navidrome", color = colors.danger) }
            }
            Spacer(Modifier.height(chromePadding(8.dp)))
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
}

@Composable
internal fun ToggleRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Kultr.colors.ink)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Kultr.colors.ink3)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
