package app.kultr.dl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kultr.dl.core.links.Links
import app.kultr.dl.ui.KultrDLUi
import app.kultr.dl.ui.UiRequests
import app.kultr.dl.ui.rememberAccent
import app.kultr.dl.ui.theme.KultrTheme
import app.kultr.dl.ui.theme.isDark

class MainActivity : ComponentActivity() {
    private var openPlayer by mutableIntStateOf(0)
    private var openDownloads by mutableIntStateOf(0)
    private var sharedLink by mutableStateOf<Pair<Int, String>?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = KultrDLApp.graph
        if (savedInstanceState == null) handleIntent(intent)
        askForNotifications()

        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            val player by graph.player.ui.collectAsStateWithLifecycle()
            val accent = rememberAccent(player.current?.artworkUrl, settings)
            val dark = isDark(settings)
            LaunchedEffect(dark) {
                val transparent = Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            KultrTheme(settings, accent) {
                KultrDLUi(UiRequests(openPlayer, openDownloads, sharedLink))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        KultrDLApp.graph.player.connect()
    }

    override fun onStop() {
        super.onStop()
        KultrDLApp.graph.player.disconnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer++
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) openDownloads++
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val link = text?.let(Links::find)
        if (link != null) sharedLink = ((sharedLink?.first ?: 0) + 1) to link
    }

    /** Download progress needs notifications; ask once, on first launch. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("kultrdl.ui", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "kultrdl.open_player"
        const val EXTRA_OPEN_DOWNLOADS = "kultrdl.open_downloads"
        private const val KEY_ASKED_NOTIFICATIONS = "askedNotifications"
    }
}
