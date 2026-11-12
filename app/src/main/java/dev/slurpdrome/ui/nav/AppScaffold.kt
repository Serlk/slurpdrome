package dev.slurpdrome.ui.nav

import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import dev.chrisbanes.haze.hazeSource

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import dev.slurpdrome.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.asPaddingValues
import dev.chrisbanes.haze.hazeSource
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.LocalNav
import dev.slurpdrome.ui.components.ActionSheetHost
import dev.slurpdrome.ui.components.Artwork
import dev.slurpdrome.ui.components.FIconButton
import dev.slurpdrome.ui.components.LocalCurrentSongId
import dev.slurpdrome.ui.components.LocalDownloads
import dev.slurpdrome.ui.components.LocalUi
import dev.slurpdrome.ui.components.dpadClickable
import dev.slurpdrome.ui.components.focusRing
import dev.slurpdrome.ui.components.rememberUiActions
import dev.slurpdrome.ui.keys.Hotkeys
import dev.slurpdrome.ui.keys.NavCommand
import dev.slurpdrome.ui.screens.AlbumScreen
import dev.slurpdrome.ui.screens.ArtistScreen
import dev.slurpdrome.ui.screens.GenreScreen
import dev.slurpdrome.ui.screens.HomeScreen
import dev.slurpdrome.ui.screens.LibraryScreen
import dev.slurpdrome.ui.screens.LoginScreen
import dev.slurpdrome.ui.screens.MixScreen
import dev.slurpdrome.ui.screens.NowPlayingContent
import dev.slurpdrome.ui.components.LocalPlayerSheet
import dev.slurpdrome.ui.components.MiniPlayerHeight
import dev.slurpdrome.ui.components.PlayerSheetHost
import dev.slurpdrome.ui.components.PlayerSheetState
import dev.slurpdrome.ui.components.rememberPlayerSheetState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.layout.onSizeChanged
import dev.slurpdrome.ui.screens.PlaylistScreen
import dev.slurpdrome.ui.screens.PlaylistsScreen
import dev.slurpdrome.ui.screens.SearchScreen
import dev.slurpdrome.ui.screens.SettingsScreen
import dev.slurpdrome.ui.screens.SmartEditorScreen
import dev.slurpdrome.ui.screens.SmartPlaylistScreen
import dev.slurpdrome.ui.theme.MonoStyle
import dev.slurpdrome.ui.theme.Motion
import androidx.compose.ui.graphics.graphicsLayer

private data class Tab(val route: Route, @StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Route.Home, R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Route.Library(), R.string.tab_library, Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
    Tab(Route.Search, R.string.tab_search, Icons.Default.Search, Icons.Default.Search),
    Tab(Route.Playlists, R.string.tab_playlists, Icons.AutoMirrored.Filled.QueueMusic, Icons.AutoMirrored.Filled.QueueMusic),
)

@Composable
fun AppRoot(hotkeys: Hotkeys) {
    val c = LocalContainer.current
    val hasSources by c.hasSources.collectAsState()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val sheet = rememberPlayerSheetState()
    val ui = rememberUiActions(c, snackbar) { route -> sheet.collapse(); nav.navigate(route) { launchSingleTop = true } }
    val downloads by c.downloads.downloads.collectAsState()
    val player by c.player.state.collectAsState()
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(hasSources) { if (hasSources) runCatching { c.player.connect() } }

    LaunchedEffect(Unit) {
        hotkeys.nav.collect { cmd ->
            if (!c.hasSources.value) return@collect
            when (cmd) {
                NavCommand.SEARCH -> { sheet.collapse(); nav.navigate(Route.Search) { launchSingleTop = true } }
                NavCommand.NOW_PLAYING -> if (sheet.isExpanded && sheet.tab == 0) sheet.collapse() else sheet.expand(0)
                NavCommand.QUEUE -> sheet.expand(1)
                NavCommand.LYRICS -> sheet.expand(2)
                NavCommand.HOME -> { sheet.collapse(); nav.popBackStack(Route.Home, inclusive = false) }
                NavCommand.LIBRARY -> nav.navigate(Route.Library()) { launchSingleTop = true }
                NavCommand.PLAYLISTS -> nav.navigate(Route.Playlists) { launchSingleTop = true }
                NavCommand.SETTINGS -> nav.navigate(Route.Settings) { launchSingleTop = true }
                NavCommand.HELP -> showHelp = true
            }
        }
    }

    CompositionLocalProvider(
        LocalNav provides nav,
        LocalUi provides ui,
        LocalPlayerSheet provides sheet,
        dev.slurpdrome.ui.theme.LocalReduceMotion provides c.settingsState.collectAsState().value.reduceMotion,
        LocalDownloads provides downloads,
        LocalCurrentSongId provides player.currentId,
    ) {
        if (!hasSources) {
            LoginScreen()
        } else {
            MainScaffold(nav, snackbar, sheet)
        }
        ActionSheetHost(ui)
        if (showHelp) HotkeyHelpDialog(hotkeys) { showHelp = false }
    }
}

@Composable
private fun MainScaffold(nav: androidx.navigation.NavHostController, snackbar: SnackbarHostState, sheet: PlayerSheetState) {
    val c = LocalContainer.current
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val net by c.network.state.collectAsState()
    val settings by c.settingsState.collectAsState()
    val sync by c.sync.state.collectAsState()
    val player by c.player.state.collectAsState()
    val hasSong = player.current != null
    val config = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    var topPx by remember { mutableStateOf(0) }
    sheet.reduceMotion = settings.reduceMotion

    fun go(tab: Tab) {
        sheet.collapse()
        // Home is the graph's start destination: the usual saveState/restoreState pattern would
        // save whatever was on top of it (Search, Settings…) under Home's key and restore it right
        // back. For Home just pop to the root.
        if (tab.route == Route.Home) {
            if (!nav.popBackStack(Route.Home, inclusive = false)) nav.navigate(Route.Home) { launchSingleTop = true }
            return
        }
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun selected(tab: Tab) = dest?.hierarchy?.any { it.hasRoute(tab.route::class) } == true

    val motion = !settings.reduceMotion
    // Keyboards (incl. Plektra's suggestion strip on hardware-keyboard phones) push the mini player up.
    val bottomInset = androidx.compose.foundation.layout.WindowInsets.ime
        .union(androidx.compose.foundation.layout.WindowInsets.navigationBars)
        .asPaddingValues().calculateBottomPadding()
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        // Content runs edge to edge under the glass; lists add this so their end can scroll into view.
        val bottomPad by androidx.compose.animation.core.animateDpAsState(
            bottomInset + if (hasSong) MiniPlayerHeight else 0.dp,
            Motion.follow(), label = "bottomInset",
        )
        CompositionLocalProvider(
            dev.slurpdrome.ui.LocalBottomInset provides bottomPad,
            dev.slurpdrome.ui.LocalTopInset provides with(density) { topPx.toDp() },
            dev.slurpdrome.ui.LocalHaze provides haze,
        ) {
            Box(Modifier.fillMaxSize()) {
                // Depth: the app recedes a little as the player rises over it.
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val p = sheet.progress.value.coerceIn(0f, 1f)
                    val sc = 1f - 0.05f * p
                    scaleX = sc; scaleY = sc
                }) {
                    // ---- content: drawn edge to edge, the blur source for every glass surface
                    Column(Modifier.fillMaxSize().hazeSource(haze)) {
                        Box(Modifier.weight(1f)) {
                            NavHost(
                                nav, startDestination = Route.Home, modifier = Modifier.fillMaxSize(),
                                // Material "shared axis X": outgoing fades fast, incoming glides in on an emphasized curve.
                                enterTransition = {
                                    if (!motion) EnterTransition.None
                                    else fadeIn(Motion.enter(210, delay = 60)) + slideInHorizontally(Motion.enter(Motion.LONG)) { it / 12 }
                                },
                                exitTransition = {
                                    if (!motion) ExitTransition.None
                                    else fadeOut(Motion.exit(90)) + slideOutHorizontally(Motion.enter(Motion.LONG)) { -it / 24 }
                                },
                                popEnterTransition = {
                                    if (!motion) EnterTransition.None
                                    else fadeIn(Motion.enter(210, delay = 60)) + slideInHorizontally(Motion.enter(Motion.LONG)) { -it / 24 }
                                },
                                popExitTransition = {
                                    if (!motion) ExitTransition.None
                                    else fadeOut(Motion.exit(90)) + slideOutHorizontally(Motion.enter(Motion.LONG)) { it / 12 }
                                },
                            ) {
                                composable<Route.Home> { HomeScreen() }
                                composable<Route.Library> { LibraryScreen(it.toRoute<Route.Library>().tab) }
                                composable<Route.Search> { SearchScreen() }
                                composable<Route.Playlists> { PlaylistsScreen() }
                                composable<Route.Settings> { SettingsScreen() }
                                composable<Route.Sources> { dev.slurpdrome.ui.screens.SourcesScreen() }
                                composable<Route.Equalizer> { dev.slurpdrome.ui.screens.EqualizerScreen() }
                                composable<Route.Album> { AlbumScreen(it.toRoute<Route.Album>().id) }
                                composable<Route.Artist> { ArtistScreen(it.toRoute<Route.Artist>().id) }
                                composable<Route.Playlist> { PlaylistScreen(it.toRoute<Route.Playlist>().id) }
                                composable<Route.LocalPlaylist> { dev.slurpdrome.ui.screens.LocalPlaylistScreen(it.toRoute<Route.LocalPlaylist>().id) }
                                composable<Route.SmartPlaylist> { SmartPlaylistScreen(it.toRoute<Route.SmartPlaylist>().id) }
                                composable<Route.SmartEditor> { val r = it.toRoute<Route.SmartEditor>(); SmartEditorScreen(r.id, r.template) }
                                composable<Route.Genre> { GenreScreen(it.toRoute<Route.Genre>().name) }
                                composable<Route.MixDetail> { MixScreen(it.toRoute<Route.MixDetail>().key) }
                            }
                            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                        }
                    }

                    // ---- floating glass chrome on top
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.onSizeChanged { topPx = it.height }) {
                        GlassNavBar(
                            haze = haze,
                            tabs = tabs.map { GlassTab(stringResource(it.label), it.icon, it.selectedIcon) },
                            selected = tabs.indexOfFirst { selected(it) },
                            compact = config.screenWidthDp < 480,
                            onSelect = { go(tabs[it]) },
                        ) {
                            val onSettings = dest?.hasRoute(Route.Settings::class) == true
                            FIconButton(
                                Icons.Default.Settings, stringResource(R.string.home_settings), size = 44.dp,
                                tint = if (onSettings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            ) { sheet.collapse(); nav.navigate(Route.Settings) { launchSingleTop = true } }
                        }
                        }
                        // Status chips under the bar: sync progress / offline.
                        AnimatedVisibility(sync.running, enter = fadeIn() + androidx.compose.animation.expandVertically(), exit = fadeOut() + androidx.compose.animation.shrinkVertically()) {
                            Row(
                                Modifier.padding(top = 6.dp).glass(haze, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(sync.stage, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        val offline = !net.online || settings.offlineMode
                        AnimatedVisibility(offline, enter = fadeIn() + androidx.compose.animation.expandVertically(), exit = fadeOut() + androidx.compose.animation.shrinkVertically()) {
                            Row(
                                Modifier.padding(top = 6.dp).glass(haze, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(if (settings.offlineMode) R.string.banner_offline_mode else R.string.banner_no_network),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
                PlayerSheetHost(sheet, bottomInset = bottomInset, haze = haze) { NowPlayingContent(sheet) }
            }
        }
    }
}

@Composable
private fun HotkeyHelpDialog(hotkeys: Hotkeys, onDismiss: () -> Unit) {
    val config = LocalConfiguration.current
    val keypad = hotkeys.keypadActive(config)
    val keypadTitle = stringResource(R.string.hotkeys_section_keypad)
    val keyboardTitle = stringResource(R.string.hotkeys_section_keyboard)
    val sections = buildList {
        if (keypad) add(keypadTitle to Hotkeys.keypadHelp)
        add(keyboardTitle to Hotkeys.keyboardHelp)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hotkeys_title)) },
        text = {
            LazyColumn {
                sections.forEach { (title, list) ->
                    item { Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(list) { h ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Text(if (h.keysRes != 0) stringResource(h.keysRes) else h.keys, style = MonoStyle, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(h.action), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } },
    )
}
