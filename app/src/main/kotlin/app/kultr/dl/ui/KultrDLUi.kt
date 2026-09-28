package app.kultr.dl.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.kultr.dl.KultrDLApp
import app.kultr.dl.MainActivity
import app.kultr.dl.core.util.ArtworkColor
import app.kultr.dl.data.MessageKind
import app.kultr.dl.data.Settings
import app.kultr.dl.data.db.DownloadState
import app.kultr.dl.ui.components.AddToPlaylistDialog
import app.kultr.dl.ui.components.ArtworkBackdropPlain
import app.kultr.dl.ui.components.DownloadAsDialog
import app.kultr.dl.ui.components.LocalGlassBackdrop
import app.kultr.dl.ui.components.glassSource
import app.kultr.dl.ui.components.rememberGlassBackdrop
import app.kultr.dl.ui.player.ArtworkBackdrop
import app.kultr.dl.ui.player.MiniPlayer
import app.kultr.dl.ui.player.NowPlayingScreen
import app.kultr.dl.ui.screens.CollectionScreen
import app.kultr.dl.ui.screens.DownloadIndicator
import app.kultr.dl.ui.screens.DownloadsScreen
import app.kultr.dl.ui.screens.HomeScreen
import app.kultr.dl.ui.screens.LibraryScreen
import app.kultr.dl.ui.screens.LibraryTab
import app.kultr.dl.ui.screens.PlaylistScreen
import app.kultr.dl.ui.screens.SearchScreen
import app.kultr.dl.ui.screens.SettingsScreen
import app.kultr.dl.ui.theme.DEFAULT_ACCENT
import app.kultr.dl.ui.theme.Kultr
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Requests from outside the interface: notification taps and shared links. */
data class UiRequests(val openPlayer: Int = 0, val openDownloads: Int = 0, val sharedLink: Pair<Int, String>? = null)

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME(Routes.HOME, "Home", Icons.Rounded.Home),
    LIBRARY(Routes.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
    DOWNLOADS(Routes.DOWNLOADS, "Downloads", Icons.Rounded.Download),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
    SEARCH(Routes.SEARCH, "Search", Icons.Rounded.Search),
}

/** The tabs in the floating bar; Search has its own round button beside it. */
private val BAR_TABS = listOf(Tab.HOME, Tab.LIBRARY, Tab.DOWNLOADS, Tab.SETTINGS)
private val GLASS_TABS = BAR_TABS.map { GlassTab(it.label, it.icon) }

private class Message(override val message: String, val kind: MessageKind, long: Boolean) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = if (long) SnackbarDuration.Long else SnackbarDuration.Short
}

/** The accent the whole interface is tinted with: from the artwork of what is playing, or fixed. */
@Composable
fun rememberAccent(artworkUrl: String?, settings: Settings): Color {
    val chosen = ArtworkColor.parseHex(settings.accent)?.let { Color(it) } ?: DEFAULT_ACCENT
    val context = LocalContext.current
    val url = if (settings.accentFromArtwork) artworkUrl else null
    val sampled by produceState<Int?>(null, url) {
        value = if (url == null) null else withContext(Dispatchers.Default) { sampleArtwork(context, url) }
    }
    return sampled?.let { Color(it) } ?: chosen
}

private suspend fun sampleArtwork(context: Context, url: String): Int? = runCatching {
    val request = ImageRequest.Builder(context).data(url).size(48).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    val bitmap = result.image.toBitmap()
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    ArtworkColor.dominant(pixels)
}.getOrNull()

@Composable
fun KultrDLUi(requests: UiRequests) {
    val graph = KultrDLApp.graph
    val context = LocalContext.current
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val dialogs = remember { Dialogs() }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val player by graph.player.ui.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val flags by graph.library.flags.collectAsStateWithLifecycle()
    val downloadRows by graph.downloads.all.collectAsStateWithLifecycle(emptyList())
    val liveProgress by graph.downloads.progress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val badges = remember(downloadRows, liveProgress) {
        downloadRows.mapNotNull { row ->
            val state = runCatching { DownloadState.valueOf(row.state) }.getOrNull() ?: return@mapNotNull null
            if (state != DownloadState.RUNNING && state != DownloadState.QUEUED) return@mapNotNull null
            val progress = liveProgress?.takeIf { it.trackId == row.trackId }?.fraction ?: row.progress
            row.trackId to DownloadBadge(state, progress)
        }.toMap()
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val actions = remember(nav) {
        AppActions(
            graph = graph,
            scope = scope,
            context = context.applicationContext,
            navigate = { target ->
                playerOpen = false
                nav.navigate(target) { launchSingleTop = true }
            },
            back = { nav.popBackStack() },
            openPlayer = { playerOpen = true },
            search = { query ->
                searchQuery = query
                playerOpen = false
                nav.navigate(Routes.SEARCH) { launchSingleTop = true }
            },
            dialogs = dialogs,
            askStoragePermission = {
                if (Build.VERSION.SDK_INT < 29) (context as? MainActivity)?.storagePermission?.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            },
        )
    }

    LaunchedEffect(requests.openPlayer) { if (requests.openPlayer > 0) playerOpen = true }
    LaunchedEffect(requests.openDownloads) { if (requests.openDownloads > 0) actions.openDownloads() }
    LaunchedEffect(requests.sharedLink) { requests.sharedLink?.let { (_, link) -> actions.search(link) } }
    LaunchedEffect(Unit) {
        graph.messages.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(Message(message.text, message.kind, message.long))
        }
    }

    var selectedTab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val currentTab = Tab.entries.firstOrNull { it.route == route }
    LaunchedEffect(currentTab) { if (currentTab != null) selectedTab = currentTab }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val glass = rememberGlassBackdrop()
    var chromeHeight by remember { mutableIntStateOf(0) }
    val searching = route == Routes.SEARCH
    var lastTab by rememberSaveable { mutableStateOf(Tab.HOME) }
    LaunchedEffect(selectedTab) { if (selectedTab != Tab.SEARCH) lastTab = selectedTab }
    val showChrome = !keyboardOpen || searching
    val chromeInset = if (showChrome) with(LocalDensity.current) { chromeHeight.toDp() } else 0.dp

    fun openTab(tab: Tab) {
        selectedTab = tab
        playerOpen = false
        if (route == tab.route) return
        if (tab == Tab.HOME) {
            nav.popBackStack(Routes.HOME, inclusive = false)
        } else {
            nav.navigate(if (tab == Tab.LIBRARY) Routes.library() else tab.route) {
                popUpTo(nav.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
    }

    CompositionLocalProvider(
        LocalActions provides actions,
        LocalGlassBackdrop provides glass,
        LocalChromeInset provides chromeInset,
        LocalTrackFlags provides flags,
        LocalDownloadBadges provides badges,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().glassSource(glass)) {
                if (settings.backdropArtwork && player.current != null) ArtworkBackdrop(player.current?.artworkUrl) else ArtworkBackdropPlain()
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
                    modifier = Modifier.fillMaxSize().imePadding(),
                    enterTransition = { fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 24 } },
                    exitTransition = { fadeOut(tween(120)) },
                    popEnterTransition = { fadeIn(tween(200)) },
                    popExitTransition = { fadeOut(tween(140)) + slideOutVertically(tween(200)) { it / 24 } },
                ) {
                    composable(Routes.HOME) { HomeScreen() }
                    composable(
                        Routes.LIBRARY,
                        arguments = listOf(navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { entry ->
                        val tab = entry.arguments?.getString("tab")?.let { name -> LibraryTab.entries.firstOrNull { it.name == name } }
                        LibraryScreen(tab ?: LibraryTab.FAVOURITES)
                    }
                    composable(Routes.SEARCH) { SearchScreen(searchQuery) }
                    composable(Routes.DOWNLOADS) { DownloadsScreen() }
                    composable(Routes.SETTINGS) { SettingsScreen() }
                    composable(Routes.COLLECTION) { CollectionScreen(it.arguments?.getString("id").orEmpty()) }
                    composable(
                        Routes.PLAYLIST,
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { PlaylistScreen(it.arguments?.getLong("id") ?: 0L) }
                }
            }

            if (showChrome) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .imePadding()
                        .onSizeChanged { chromeHeight = it.height }
                        .navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!keyboardOpen) {
                        if (route != Routes.DOWNLOADS) DownloadIndicator(onOpen = { actions.openDownloads() })
                        MiniPlayer(player)
                    }
                    GlassNavigationBar(
                        tabs = GLASS_TABS,
                        selected = BAR_TABS.indexOf(selectedTab),
                        onSelect = { index -> openTab(BAR_TABS[index]) },
                        searching = searching,
                        onSearch = { openTab(Tab.SEARCH) },
                        back = GlassTab(lastTab.label, lastTab.icon),
                        onBack = { openTab(lastTab) },
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                    )
                }
            }

            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(bottom = if (keyboardOpen) 8.dp else chromeInset),
            ) { data ->
                val colors = Kultr.colors
                val kind = (data.visuals as? Message)?.kind ?: MessageKind.INFO
                Snackbar(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    containerColor = colors.elevated,
                    contentColor = when (kind) {
                        MessageKind.ERROR -> colors.danger
                        MessageKind.WARNING -> colors.warning
                        else -> colors.ink
                    },
                ) { Text(data.visuals.message) }
            }

            AnimatedVisibility(
                visible = playerOpen && player.current != null,
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
            ) {
                NowPlayingScreen(player, onClose = { playerOpen = false })
            }
            BackHandler(enabled = playerOpen && player.current != null) { playerOpen = false }
        }

        dialogs.addToPlaylist?.let { tracks -> AddToPlaylistDialog(tracks, onDismiss = { dialogs.addToPlaylist = null }) }
        dialogs.downloadAs?.let { tracks -> DownloadAsDialog(tracks, onDismiss = { dialogs.downloadAs = null }) }
    }
}
