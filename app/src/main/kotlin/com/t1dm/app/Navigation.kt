package com.t1dm.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.t1dm.core.model.InferenceState
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.t1dm.app.di.AppContainer
import com.t1dm.app.sync.SyncStatus
import com.t1dm.app.sync.toPanelState
import com.t1dm.feature.settings.ServerSettingsScreen
import kotlinx.coroutines.launch
import com.t1dm.feature.dashboard.DashboardScreen
import com.t1dm.feature.hardware.HardwareScreen
import com.t1dm.feature.insulin.InsulinScreen
import com.t1dm.feature.journal.JournalScreen
import com.t1dm.feature.meals.MealsScreen
import com.t1dm.feature.models.ModelsScreen
import com.t1dm.feature.network.NetworkScreen
import com.t1dm.feature.security.SecurityScreen
import com.t1dm.feature.settings.CgmSettingsScreen
import com.t1dm.feature.settings.SettingsScreen
import com.t1dm.feature.stats.StatsScreen

private data class Destination(val route: String, val label: String)

private val destinations = listOf(
    Destination("dashboard", "BG"),
    Destination("stats", "Stats"),
    Destination("models", "Models"),
    Destination("hardware", "HW"),
    Destination("network", "Net"),
    Destination("meals", "Meals"),
    Destination("insulin", "Insulin"),
    Destination("security", "Sec"),
    Destination("settings", "Set"),
    Destination("journal", "Journal"),
)

@Composable
fun T1dmApp(container: AppContainer) {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { T1dmBottomBar(navController) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Flavor-specific: real text in the public build, no-op in the personal build.
            Disclaimer()
            T1dmNavHost(navController, container)
        }
    }
}

@Composable
private fun T1dmBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination?.route
    NavigationBar {
        destinations.forEach { d ->
            NavigationBarItem(
                selected = current == d.route,
                onClick = {
                    navController.navigate(d.route) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo("dashboard") { saveState = true }
                    }
                },
                icon = { Text(d.label) },
            )
        }
    }
}

@Composable
private fun T1dmNavHost(navController: NavHostController, container: AppContainer) {
    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            val readings by container.dashboardReadings.collectAsState(emptyList())
            val latest by container.latestReading.collectAsState(null)
            val active by container.activeSource.collectAsState(null)
            val inference by container.inferenceState.collectAsState(InferenceState())
            DashboardScreen(
                readings = readings,
                latest = latest,
                activeSourceName = active?.displayName,
                thresholds = container.alarmConfig.thresholds,
                predictions = inference.predictions,
                kovatchevF = container.nativeCore::kovatchevF,
            )
        }
        composable("stats") { StatsScreen() }
        composable("models") {
            val inference by container.inferenceState.collectAsState(InferenceState())
            ModelsScreen(state = inference, onSelect = container.inferenceController::selectModel)
        }
        composable("hardware") {
            val inference by container.inferenceState.collectAsState(InferenceState())
            HardwareScreen(state = inference)
        }
        composable("network") {
            val status by container.syncStatus.collectAsState(SyncStatus())
            val active by container.activeServerProfile.collectAsState(null)
            NetworkScreen(
                state = status.toPanelState(active, container.outboxMaxSize, container.outboxMaxAgeMs),
            )
        }
        composable("meals") { MealsScreen() }
        composable("insulin") { InsulinScreen() }
        composable("security") { SecurityScreen() }
        composable("settings") {
            SettingsScreen(
                onOpenCgm = { navController.navigate("settings/cgm") },
                onOpenServer = { navController.navigate("settings/server") },
            )
        }
        composable("settings/server") {
            val active by container.activeServerProfile.collectAsState(null)
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            var health by remember { mutableStateOf<String?>(null) }
            ServerSettingsScreen(
                initialLabel = active?.label ?: "local",
                initialBaseUrl = active?.baseUrl ?: "http://127.0.0.1:8443",
                hasToken = active != null,
                isActive = active != null,
                busy = busy,
                healthStatus = health,
                onSave = { label, baseUrl, token ->
                    scope.launch {
                        busy = true
                        container.saveServerProfile(label, baseUrl, token)
                        health = "saved — running health check…"
                        health = container.checkServerHealth()
                        busy = false
                    }
                },
                onHealthCheck = {
                    scope.launch {
                        busy = true
                        health = container.checkServerHealth()
                        busy = false
                    }
                },
            )
        }
        composable("settings/cgm") {
            val active by container.activeSource.collectAsState(null)
            val sources by container.allSources.collectAsState(emptyList())
            CgmSettingsScreen(
                activeSourceName = active?.displayName,
                activeStatus = active?.let { "active" },
                allSourceNames = sources.map { it.displayName },
            )
        }
        composable("journal") { JournalScreen() }
    }
}
