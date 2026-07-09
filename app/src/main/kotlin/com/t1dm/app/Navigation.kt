package com.t1dm.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.t1dm.app.di.AppContainer.BolusAdviceUi
import com.t1dm.app.service.DoseCalcService
import com.t1dm.feature.insulin.BolusCalculatorScreen
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
import com.t1dm.feature.insulin.InsulinTypeBuilderScreen
import com.t1dm.feature.meals.MealBuilderScreen
import com.t1dm.feature.journal.JournalScreen
import com.t1dm.feature.meals.MealsScreen
import com.t1dm.feature.models.ModelsScreen
import com.t1dm.feature.network.NetworkScreen
import com.t1dm.feature.security.SecurityPanelState
import com.t1dm.feature.security.SecurityScreen
import com.t1dm.feature.settings.CgmSettingsScreen
import com.t1dm.feature.settings.GraphSettingsScreen
import com.t1dm.feature.settings.SettingsScreen
import com.t1dm.feature.settings.WarmupSettingsScreen
import com.t1dm.feature.settings.WatchSettingsScreen
import com.t1dm.watch.WatchSecurityState
import com.t1dm.feature.stats.StatsScreen

/** Map the `:watch` security state onto the feature-local panel model (keeps `:feature:security`
 *  free of a `:watch` dependency — the removable seam). */
private fun WatchSecurityState.toPanelState() = SecurityPanelState(
    phase = phase.name.lowercase().replace('_', ' '),
    deviceName = deviceName,
    sessionState = sessionState.name.lowercase(),
    epoch = epoch,
    keyFingerprint = keyFingerprint,
    sendSeq = sendSeq,
    recvSeq = recvSeq,
    sas = sas?.digits,
    sasWords = sas?.words,
    lastPush = lastPushMs?.let { "${(System.currentTimeMillis() - it) / 1000}s ago" },
    lastAckSeq = lastAckSeq,
    lowPowerSuspended = lowPowerSuspended,
    lastError = lastError,
    canPair = canPair,
    canConfirmSas = canConfirmSas,
    canRotate = canRotate,
    canReset = canReset,
)

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
            val scope = rememberCoroutineScope()
            val readings by container.dashboardReadings.collectAsState(emptyList())
            val latest by container.latestReading.collectAsState(null)
            val active by container.activeSource.collectAsState(null)
            val inference by container.inferenceState.collectAsState(InferenceState())
            // IOB/COB recomputed off-main on any reading emit OR dose/meal write (shared StateFlow).
            val iobCob by container.iobCob.collectAsState()
            val range by container.graphRange.collectAsState(com.t1dm.data.settings.BgRange.DEFAULT)
            val windowHours by container.graphWindowHours.collectAsState(6)
            val reachability by container.bgReachability.collectAsState(null)
            val signals by container.bgSignals.collectAsState(null)
            DashboardScreen(
                readings = readings,
                latest = latest,
                activeSourceName = active?.displayName,
                thresholds = container.alarmConfig.thresholds,
                predictions = inference.predictions,
                kovatchevF = container.nativeCore::kovatchevF,
                iobCob = iobCob,
                curveChannels = container::dashboardCurveChannels,
                warmup = inference.warmup,
                rangeMinMgdl = range.minMgdl,
                rangeMaxMgdl = range.maxMgdl,
                initialWindowHours = windowHours,
                onSetWindowHours = { h -> scope.launch { container.setGraphWindowHours(h) } },
                reachability = reachability,
                signals = signals,
            )
        }
        composable("stats") {
            val statsState by container.statsViewModel.state.collectAsState()
            StatsScreen(
                state = statsState,
                kovatchevF = container.nativeCore::kovatchevF,
                onSelectWindow = container.statsViewModel::selectWindow,
                onSetUnitSpace = container.statsViewModel::setUnitSpace,
                onSetTargetRange = container.statsViewModel::setTargetRange,
                onRecompute = container.statsViewModel::recompute,
            )
        }
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
        composable("meals") {
            val scope = rememberCoroutineScope()
            val iobCob by container.iobCob.collectAsState()
            Column {
                MealsScreen(
                    iobCob = iobCob,
                    previewCurve = container.previewCarbCurve,
                    onLogMeal = { grams, gi -> scope.launch { container.logCarb(grams, gi) } },
                )
                TextButton(onClick = { navController.navigate("meals/builder") }) {
                    Text("Open meal builder →")
                }
            }
        }
        composable("meals/builder") {
            val scope = rememberCoroutineScope()
            val saved by container.savedMeals.collectAsState(emptyList())
            val custom by container.customFoods.collectAsState(emptyList())
            MealBuilderScreen(
                savedMeals = saved,
                customFoods = custom,
                onSearch = { q -> container.mealsController.searchFoods(q) },
                onResolve = { comps -> container.mealsController.resolvePreview(comps) },
                onLogMeal = { comps -> scope.launch { container.mealsController.logMeal(comps) } },
                onSaveMeal = { name, comps -> scope.launch { container.mealsController.saveMeal(name, comps) } },
                onSaveFood = { food -> scope.launch { container.mealsController.saveCustomFood(food) } },
                onDeleteFood = { id -> scope.launch { container.mealsController.deleteCustomFood(id) } },
                onDeleteMeal = { id -> scope.launch { container.mealsController.deleteSavedMeal(id) } },
            )
        }
        composable("insulin") {
            val scope = rememberCoroutineScope()
            val iobCob by container.iobCob.collectAsState()
            Column {
                InsulinScreen(
                    iobCob = iobCob,
                    previewBolus = container.previewBolusCurve,
                    onLogBolus = { units, preset -> scope.launch { container.logBolus(units, preset) } },
                    onLogBasal = { units, preset -> scope.launch { container.logBasal(units, preset) } },
                )
                TextButton(onClick = { navController.navigate("insulin/types") }) {
                    Text("Insulin types & custom curves →")
                }
                TextButton(onClick = { navController.navigate("insulin/bolusCalc") }) {
                    Text("Bolus advisor (model-driven) →")
                }
            }
        }
        composable("insulin/bolusCalc") {
            val scope = rememberCoroutineScope()
            val ctx = LocalContext.current
            val ui by container.bolusAdvice.collectAsState()
            BolusCalculatorScreen(
                result = (ui as? BolusAdviceUi.Ready)?.result,
                onAccept = { c ->
                    scope.launch { container.acceptAdvisedBolus(c.doseU) }
                    DoseCalcService.cancel(ctx)
                },
                onRecompute = { DoseCalcService.recommend(ctx) },
            )
        }
        composable("insulin/types") {
            val scope = rememberCoroutineScope()
            val types by container.insulinTypes.collectAsState(emptyList())
            InsulinTypeBuilderScreen(
                types = types,
                onResolve = { type, units -> container.insulinController.resolvePreview(type, units) },
                onSaveType = { type -> scope.launch { container.insulinController.saveCustomType(type) } },
                onDeleteType = { id -> scope.launch { container.insulinController.deleteCustomType(id) } },
                onLogDose = { type, units -> scope.launch { container.insulinController.logDose(type, units) } },
            )
        }
        composable("security") {
            val watch by container.watchSecurity.collectAsState()
            SecurityScreen(
                state = watch.toPanelState(),
                onPair = container::pairWatch,
                onConfirmSas = container::confirmWatchSas,
                onRotate = container::rotateWatchKeys,
                onUnpair = container::unpairWatch,
            )
        }
        composable("settings") {
            SettingsScreen(
                onOpenCgm = { navController.navigate("settings/cgm") },
                onOpenServer = { navController.navigate("settings/server") },
                onOpenWarmup = { navController.navigate("settings/warmup") },
                onOpenWatch = { navController.navigate("settings/watch") },
                onOpenGraph = { navController.navigate("settings/graph") },
            )
        }
        composable("settings/graph") {
            val scope = rememberCoroutineScope()
            val range by container.graphRange.collectAsState(com.t1dm.data.settings.BgRange.DEFAULT)
            GraphSettingsScreen(
                minMgdl = range.minMgdl,
                maxMgdl = range.maxMgdl,
                onChange = { min, max -> scope.launch { container.setGraphRange(min, max) } },
            )
        }
        composable("settings/watch") {
            val watch by container.watchSecurity.collectAsState()
            WatchSettingsScreen(
                linkStatus = watch.phase.name.lowercase().replace('_', ' '),
                deviceName = watch.deviceName,
                onOpenSecurity = { navController.navigate("security") },
            )
        }
        composable("settings/warmup") {
            val scope = rememberCoroutineScope()
            val hours by container.warmupHoursSetting.collectAsState(24)
            WarmupSettingsScreen(
                hours = hours,
                onChange = { h -> scope.launch { container.setWarmupHours(h) } },
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
        composable("journal") {
            val scope = rememberCoroutineScope()
            val notes by container.journalNotes.collectAsState(emptyList())
            val mood by container.latestMood.collectAsState(null)
            JournalScreen(
                notes = notes,
                currentMood = mood,
                onSaveNote = { text -> scope.launch { container.saveNote(text) } },
                onPickMood = { m -> scope.launch { container.saveMood(m) } },
            )
        }
    }
}
