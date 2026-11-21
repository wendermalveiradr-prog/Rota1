package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.service.ServiceRuntimeBus
import com.example.ui.MainViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.NeighborhoodsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.RotaInteligenteTheme
import kotlinx.serialization.Serializable

@Serializable
data object DashboardRoute

@Serializable
data object HistoryRoute

@Serializable
data object NeighborhoodsRoute

@Serializable
data object SettingsRoute

private data class TopLevelDestination<T : Any>(
    val route: T,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RotaInteligenteTheme(darkTheme = true) {
                RotaInteligenteRoot()
            }
        }
    }
}

@Composable
fun RotaInteligenteRoot(
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ServiceRuntimeBus.onForegroundAppChanged(
            packageName = context.packageName,
            isMobLets = false
        )
        viewModel.refreshPermissions()
    }

    val destinations = listOf(
        TopLevelDestination(
            route = DashboardRoute,
            label = "Painel",
            selectedIcon = Icons.Rounded.Dashboard,
            unselectedIcon = Icons.Outlined.Dashboard,
            testTag = "nav_dashboard"
        ),
        TopLevelDestination(
            route = HistoryRoute,
            label = "Histórico",
            selectedIcon = Icons.Rounded.History,
            unselectedIcon = Icons.Outlined.History,
            testTag = "nav_history"
        ),
        TopLevelDestination(
            route = NeighborhoodsRoute,
            label = "Bairros",
            selectedIcon = Icons.Rounded.Map,
            unselectedIcon = Icons.Outlined.Map,
            testTag = "nav_neighborhoods"
        ),
        TopLevelDestination(
            route = SettingsRoute,
            label = "Ajustes",
            selectedIcon = Icons.Rounded.Settings,
            unselectedIcon = Icons.Outlined.Settings,
            testTag = "nav_settings"
        )
    )

    val navigateTo: (Any) -> Unit = { target ->
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar {
                        destinations.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail {
                        destinations.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = DashboardRoute,
                    modifier = Modifier.weight(1f)
                ) {
                    composable<DashboardRoute> {
                        DashboardScreen(
                            state = uiState,
                            onRefreshPermissions = viewModel::refreshPermissions,
                            onToggleOverlay = viewModel::toggleFloatingOverlay,
                            onAnalyzeManual = viewModel::analyzeManualOffer,
                            onAnalyzeOcrImage = viewModel::analyzeScreenshotUri,
                            onDismissFeedback = viewModel::clearFeedback
                        )
                    }
                    composable<HistoryRoute> {
                        HistoryScreen(
                            offers = uiState.offerHistory,
                            onDeleteOffer = viewModel::deleteOffer,
                            onClearAll = viewModel::clearHistory
                        )
                    }
                    composable<NeighborhoodsRoute> {
                        NeighborhoodsScreen(
                            rules = uiState.neighborhoodRules,
                            onUpdateStatus = viewModel::updateNeighborhoodStatus,
                            onAddNeighborhood = viewModel::addCustomNeighborhood,
                            onDeleteCustom = viewModel::deleteCustomNeighborhood
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            settings = uiState.settings,
                            onUpdateTargetPerKm = viewModel::updateMinTargetPerKm,
                            onToggleOcrFallback = viewModel::setOcrFallbackEnabled,
                            onToggleOsrmRouting = viewModel::setUseOsrmRoadRouting,
                            onToggleAutoShowOverlay = viewModel::setAutoShowOverlayOnOffer,
                            onToggleStrictMobLets = viewModel::setStrictMobLetsPackageFilter
                        )
                    }
                }
            }
        }
    }
}
