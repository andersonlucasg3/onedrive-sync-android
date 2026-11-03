package com.anderson.onedrivesync.ui.nav

import android.content.Context
import android.os.Environment
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.notify.Notifier
import com.anderson.onedrivesync.sync.SyncState
import com.anderson.onedrivesync.ui.components.UpdateDialog
import com.anderson.onedrivesync.ui.screens.ConflictsScreen
import com.anderson.onedrivesync.ui.screens.LogScreen
import com.anderson.onedrivesync.ui.screens.OnboardingScreen
import com.anderson.onedrivesync.ui.screens.PENDING_TAB_CONFLICTS
import com.anderson.onedrivesync.ui.screens.PairsScreen
import com.anderson.onedrivesync.ui.screens.PendingScreen
import com.anderson.onedrivesync.ui.screens.SettingsScreen
import com.anderson.onedrivesync.ui.screens.WizardScreen
import com.anderson.onedrivesync.ui.vm.ShellViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class BottomItem(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
)

private val BOTTOM_ITEMS = listOf(
    BottomItem(Routes.PAIRS, R.string.nav_pairs, Icons.Outlined.Folder),
    BottomItem(Routes.PENDING, R.string.nav_pending, Icons.Filled.Notifications),
    BottomItem(Routes.LOG, R.string.nav_log, Icons.Filled.History),
    BottomItem(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

/**
 * Shell: bottom navigation with the pending badge around a [NavHost]. Onboarding and the pair
 * wizard are full-screen routes, so the bar is hidden there.
 *
 * [screenRequest] carries a notification screen arriving through `onNewIntent`; it is handled here
 * because the NavController lives here, and [onScreenRequestHandled] clears it to avoid replaying
 * the same navigation (for example on rotation).
 */
@Composable
fun AppNavHost(
    startDestination: String,
    screenRequest: String? = null,
    onScreenRequestHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val shellViewModel: ShellViewModel = viewModel(factory = ShellViewModel.factory(context))
    val pendingTotal by shellViewModel.pendingTotal.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != null && currentRoute in Routes.bottomBarRoutes

    // One dialog over the whole shell: the app is single-activity, so this covers every screen.
    val updateManager = (context.applicationContext as App).container.updateManager
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // A sync can queue conflicts/deletions behind the shell's back; refresh the badge when it ends.
    // The mapped flow only emits on idle transitions, so running progress does not recompose the
    // shell for every file.
    val syncIdle by (context.applicationContext as App).container.syncEngine.state
        .map { it is SyncState.Idle }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(currentRoute, syncIdle) { shellViewModel.refresh() }

    LaunchedEffect(screenRequest) {
        val request = screenRequest ?: return@LaunchedEffect
        val route = when (request) {
            Notifier.SCREEN_CONFLICTS -> Routes.CONFLICTS
            Notifier.SCREEN_PENDING -> Routes.PENDING
            Notifier.SCREEN_SETTINGS -> Routes.SETTINGS
            else -> null
        }
        // Onboarding is the gate: a stale notification must not open a screen that cannot work.
        if (route != null && navController.currentDestination?.route != Routes.ONBOARDING) {
            navController.navigate(route) { launchSingleTop = true }
        }
        onScreenRequestHandled()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        BOTTOM_ITEMS.forEach { item ->
                            NavigationBarItem(
                                selected = backStackEntry?.destination?.hierarchy?.any { destination ->
                                    destination.route == item.route ||
                                        (item.route == Routes.PENDING && destination.route == Routes.CONFLICTS)
                                } == true,
                                onClick = { navController.navigateToTab(item.route) },
                                icon = {
                                    val badgeCount = if (item.route == Routes.PENDING) pendingTotal else 0
                                    if (badgeCount > 0) {
                                        BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) {
                                            Icon(imageVector = item.icon, contentDescription = null)
                                        }
                                    } else {
                                        Icon(imageVector = item.icon, contentDescription = null)
                                    }
                                },
                                label = { Text(stringResource(item.label)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onContinue = {
                            navController.navigate(Routes.PAIRS) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(Routes.PAIRS) {
                    PairsScreen(
                        onNewPair = { navController.navigate(Routes.WIZARD) },
                        onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) },
                        onOpenActivity = { navController.navigate(Routes.LOG) { launchSingleTop = true } },
                    )
                }
                // The notification route only preselects the conflicts tab; the bar keeps one item.
                composable(Routes.CONFLICTS) {
                    ConflictsScreen(onCountsChanged = { shellViewModel.refresh() })
                }
                composable(Routes.PENDING) {
                    PendingScreen(
                        initialTab = PENDING_TAB_CONFLICTS,
                        onCountsChanged = { shellViewModel.refresh() },
                    )
                }
                composable(Routes.LOG) { LogScreen() }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(Routes.WIZARD) {
                    WizardScreen(
                        onClose = { navController.popBackStack() },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
            }
        }

        UpdateDialog(
            state = updateState,
            onDownload = { scope.launch { updateManager.downloadAndInstall(context) } },
            onDismiss = updateManager::dismiss,
            onSkip = updateManager::skipCurrentVersion,
        )
    }
}

/**
 * The app is only usable with all-files access and a stored token. A notification screen only wins
 * when those hold, otherwise the user would land on a screen that cannot work.
 */
fun initialDestination(context: Context, screen: String?): String {
    val container = (context.applicationContext as? App)?.container ?: return Routes.ONBOARDING
    val needsOnboarding = !Environment.isExternalStorageManager() ||
        !container.authManager.isSignedIn()
    if (needsOnboarding) return Routes.ONBOARDING
    return when (screen) {
        Notifier.SCREEN_CONFLICTS -> Routes.CONFLICTS
        Notifier.SCREEN_PENDING -> Routes.PENDING
        Notifier.SCREEN_SETTINGS -> Routes.SETTINGS
        else -> Routes.PAIRS
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
