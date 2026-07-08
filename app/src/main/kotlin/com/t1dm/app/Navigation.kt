package com.t1dm.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.t1dm.feature.dashboard.DashboardScreen
import com.t1dm.feature.hardware.HardwareScreen
import com.t1dm.feature.insulin.InsulinScreen
import com.t1dm.feature.journal.JournalScreen
import com.t1dm.feature.meals.MealsScreen
import com.t1dm.feature.models.ModelsScreen
import com.t1dm.feature.network.NetworkScreen
import com.t1dm.feature.security.SecurityScreen
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
fun T1dmApp() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { T1dmBottomBar(navController) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Flavor-specific: real text in the public build, no-op in the personal build.
            Disclaimer()
            T1dmNavHost(navController)
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
private fun T1dmNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") { DashboardScreen() }
        composable("stats") { StatsScreen() }
        composable("models") { ModelsScreen() }
        composable("hardware") { HardwareScreen() }
        composable("network") { NetworkScreen() }
        composable("meals") { MealsScreen() }
        composable("insulin") { InsulinScreen() }
        composable("security") { SecurityScreen() }
        composable("settings") { SettingsScreen() }
        composable("journal") { JournalScreen() }
    }
}
