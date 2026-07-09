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
import androidx.compose.runtime.LaunchedEffect
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
import com.t1dm.feature.models.ModelDetailScreen
import com.t1dm.feature.models.ModelsScreen
import com.t1dm.core.model.AccuracyReport
import com.t1dm.feature.network.NetworkScreen
import com.t1dm.feature.security.SecurityPanelState
import com.t1dm.feature.security.SecurityScreen
import com.t1dm.feature.settings.AboutScreen
import com.t1dm.feature.settings.AlarmThresholdsScreen
import com.t1dm.feature.settings.AlertsSettingsScreen
import com.t1dm.feature.settings.CalculatorSettingsScreen
import com.t1dm.feature.settings.CgmSettingsScreen
import com.t1dm.feature.settings.CurveParams
import com.t1dm.feature.settings.CurveParamsScreen
import com.t1dm.feature.settings.DataSettingsScreen
import com.t1dm.feature.settings.DisplaySettingsScreen
import com.t1dm.feature.settings.GraphSettingsScreen
import com.t1dm.feature.settings.PowerSettingsScreen
import com.t1dm.feature.settings.SettingsScreen
import com.t1dm.feature.settings.SignalSafetyScreen
import com.t1dm.feature.settings.WarmupSettingsScreen
import com.t1dm.feature.settings.WatchSettingsScreen
import com.t1dm.alerts.VibrationPreset
import com.t1dm.app.settings.SettingsStore
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.settings.GraphSettingsStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    rssiDbm = rssiDbm,
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
            ModelsScreen(
                state = inference,
                onSelect = container.inferenceController::selectModel,
                onOpen = { id -> navController.navigate("models/$id") },
            )
        }
        composable("models/{modelId}") { entry ->
            val modelId = entry.arguments?.getString("modelId") ?: return@composable
            val inference by container.inferenceState.collectAsState(InferenceState())
            var accuracy by remember(modelId) { mutableStateOf<AccuracyReport?>(null) }
            var loading by remember(modelId) { mutableStateOf(true) }
            var reloadTick by remember(modelId) { mutableStateOf(0) }
            LaunchedEffect(modelId, reloadTick) {
                loading = true
                accuracy = runCatching { container.modelAccuracy(modelId) }.getOrNull()
                loading = false
            }
            ModelDetailScreen(
                state = inference,
                modelId = modelId,
                accuracy = accuracy,
                accuracyLoading = loading,
                onRecomputeAccuracy = { reloadTick++ },
            )
        }
        composable("hardware") {
            val inference by container.inferenceState.collectAsState(InferenceState())
            var hardware by remember { mutableStateOf(com.t1dm.feature.hardware.HardwareInfo.UNKNOWN) }
            LaunchedEffect(Unit) { hardware = container.detectHardware() }
            HardwareScreen(state = inference, hardware = hardware)
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
            val recent by container.recentMeals.collectAsState(emptyList())
            Column {
                MealsScreen(
                    iobCob = iobCob,
                    recentMeals = recent,
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
                onOpenDisplay = { navController.navigate("settings/display") },
                onOpenGraph = { navController.navigate("settings/graph") },
                onOpenAlarmThresholds = { navController.navigate("settings/alarms") },
                onOpenSignalSafety = { navController.navigate("settings/signal") },
                onOpenAlerts = { navController.navigate("settings/alerts") },
                onOpenWarmup = { navController.navigate("settings/warmup") },
                onOpenCalculator = { navController.navigate("settings/calculator") },
                onOpenCurveParams = { navController.navigate("settings/curves") },
                onOpenModels = { navController.navigate("models") },
                onOpenCgm = { navController.navigate("settings/cgm") },
                onOpenServer = { navController.navigate("settings/server") },
                onOpenWatch = { navController.navigate("settings/watch") },
                onOpenPower = { navController.navigate("settings/power") },
                onOpenData = { navController.navigate("settings/data") },
                onOpenAbout = { navController.navigate("about") },
            )
        }
        composable("about") {
            AboutScreen(info = container.aboutInfo())
        }
        composable("settings/graph") {
            val scope = rememberCoroutineScope()
            val range by container.graphRange.collectAsState(com.t1dm.data.settings.BgRange.DEFAULT)
            val windowHours by container.graphWindowHours.collectAsState(GraphSettingsStore.DEFAULT_WINDOW_HOURS)
            GraphSettingsScreen(
                minMgdl = range.minMgdl,
                maxMgdl = range.maxMgdl,
                windowHours = windowHours,
                windowPresets = GraphSettingsStore.WINDOW_PRESETS,
                onChange = { min, max -> scope.launch { container.setGraphRange(min, max) } },
                onSetWindow = { h -> scope.launch { container.setGraphWindowHours(h) } },
            )
        }
        composable("settings/display") {
            val scope = rememberCoroutineScope()
            val statsState by container.statsViewModel.state.collectAsState()
            val animations by container.settingsStore.animationsEnabled.collectAsState(true)
            DisplaySettingsScreen(
                unitSpace = statsState.unitSpace,
                targetLow = statsState.targetRange.lowMgdl,
                targetHigh = statsState.targetRange.highMgdl,
                animationsEnabled = animations,
                onSetUnitSpace = { container.statsViewModel.setUnitSpace(it) },
                onSetTargetRange = { lo, hi -> container.statsViewModel.setTargetRange(lo, hi) },
                onSetAnimationsEnabled = { on -> scope.launch { container.settingsStore.setAnimationsEnabled(on) } },
            )
        }
        composable("settings/alarms") {
            val scope = rememberCoroutineScope()
            val ul by container.settingsStore.alarmUrgentLow.collectAsState(55)
            val lo by container.settingsStore.alarmLow.collectAsState(70)
            val hi by container.settingsStore.alarmHigh.collectAsState(180)
            val uh by container.settingsStore.alarmUrgentHigh.collectAsState(250)
            AlarmThresholdsScreen(
                urgentLow = ul, low = lo, high = hi, urgentHigh = uh,
                onChange = { a, b, c, d -> scope.launch { container.saveAlarmThresholds(a, b, c, d) } },
            )
        }
        composable("settings/signal") {
            val scope = rememberCoroutineScope()
            val lossMin by container.settingsStore.lossMin.collectAsState(20)
            val lossEsc by container.settingsStore.lossEscalatedMin.collectAsState(12)
            val staleMin by container.settingsStore.calcFreshnessMin.collectAsState(15)
            SignalSafetyScreen(
                lossMin = lossMin,
                lossEscalatedMin = lossEsc,
                dosingStaleMin = staleMin,
                onSetLoss = { a, b -> scope.launch { container.saveLossWindows(a, b) } },
                onSetDosingStale = { m -> scope.launch { container.settingsStore.setCalcFreshnessMin(m) } },
            )
        }
        composable("settings/alerts") {
            val scope = rememberCoroutineScope()
            val warnVib by container.settingsStore.warningVibration.collectAsState(VibrationPreset.DOUBLE.name)
            val critVib by container.settingsStore.criticalVibration.collectAsState(VibrationPreset.INSISTENT.name)
            val warnSound by container.settingsStore.warningSoundOn.collectAsState(false)
            val critSound by container.settingsStore.criticalSoundOn.collectAsState(true)
            val bypass by container.settingsStore.bypassDnd.collectAsState(true)
            val cadence by container.settingsStore.repeatCadenceMin.collectAsState(5)
            AlertsSettingsScreen(
                vibrationOptions = VibrationPreset.entries.map { it.name },
                warningVibration = warnVib,
                criticalVibration = critVib,
                warningSoundOn = warnSound,
                criticalSoundOn = critSound,
                bypassDnd = bypass,
                repeatCadenceMin = cadence,
                onSetWarningVibration = { n -> scope.launch { container.settingsStore.setWarningVibration(VibrationPreset.valueOf(n)) } },
                onSetCriticalVibration = { n -> scope.launch { container.settingsStore.setCriticalVibration(VibrationPreset.valueOf(n)) } },
                onSetWarningSoundOn = { on -> scope.launch { container.settingsStore.setWarningSoundOn(on) } },
                onSetCriticalSoundOn = { on -> scope.launch { container.settingsStore.setCriticalSoundOn(on) } },
                onSetBypassDnd = { on -> scope.launch { container.settingsStore.setBypassDnd(on) } },
                onSetRepeatCadence = { m -> scope.launch { container.saveRepeatCadence(m) } },
            )
        }
        composable("settings/calculator") {
            val scope = rememberCoroutineScope()
            val ss = container.settingsStore
            val objective by ss.calcObjective.collectAsState(SettingsStore.OBJ_KOVATCHEV)
            val tLow by ss.calcTargetLow.collectAsState(70.0)
            val tHigh by ss.calcTargetHigh.collectAsState(180.0)
            val tMid by ss.calcTargetMid.collectAsState(110.0)
            val hypoW by ss.calcHypoWeight.collectAsState(3.0)
            val hyperW by ss.calcHyperWeight.collectAsState(1.0)
            val predLow by ss.calcPredictedLow.collectAsState(70.0)
            val iobCeil by ss.calcIobCeiling.collectAsState(12.0)
            val gridMax by ss.calcGridMaxU.collectAsState(15.0)
            val gridStep by ss.calcGridStepU.collectAsState(0.5)
            val rFresh by ss.railFreshness.collectAsState(true)
            val rPred by ss.railPredictedLow.collectAsState(true)
            val rIob by ss.railIobCeiling.collectAsState(true)
            val rConfirm by ss.railConfirm.collectAsState(true)
            val rHypo by ss.railHypoTreatment.collectAsState(true)
            CalculatorSettingsScreen(
                objectiveOptions = listOf(
                    SettingsStore.OBJ_KOVATCHEV to "Min Kovatchev risk",
                    SettingsStore.OBJ_MIN_TOR to "Min time out of range",
                    SettingsStore.OBJ_HIT_TARGET to "Hit target (1 h)",
                ),
                objective = objective,
                targetLow = tLow, targetHigh = tHigh, targetMid = tMid,
                hypoWeight = hypoW, hyperWeight = hyperW,
                predictedLow = predLow, iobCeiling = iobCeil,
                gridMaxU = gridMax, gridStepU = gridStep,
                railFreshness = rFresh, railPredictedLow = rPred, railIobCeiling = rIob,
                railConfirm = rConfirm, railHypoTreatment = rHypo,
                onSetObjective = { k -> scope.launch { ss.setCalcObjective(k) } },
                onSetTarget = { lo, hi, mid -> scope.launch { ss.setCalcTarget(lo, hi, mid) } },
                onSetAsymmetry = { hypo, hyper -> scope.launch { ss.setCalcAsymmetry(hypo, hyper) } },
                onSetPredictedLow = { v -> scope.launch { ss.setCalcPredictedLow(v) } },
                onSetIobCeiling = { v -> scope.launch { ss.setCalcIobCeiling(v) } },
                onSetGrid = { mx, st -> scope.launch { ss.setCalcGrid(mx, st) } },
                onSetRailFreshness = { on -> scope.launch { ss.setRail(SettingsStore.RAIL_FRESHNESS, on) } },
                onSetRailPredictedLow = { on -> scope.launch { ss.setRail(SettingsStore.RAIL_PREDICTED_LOW, on) } },
                onSetRailIobCeiling = { on -> scope.launch { ss.setRail(SettingsStore.RAIL_IOB, on) } },
                onSetRailConfirm = { on -> scope.launch { ss.setRail(SettingsStore.RAIL_CONFIRM, on) } },
                onSetRailHypoTreatment = { on -> scope.launch { ss.setRail(SettingsStore.RAIL_HYPO, on) } },
            )
        }
        composable("settings/curves") {
            val hi = CurveEngine.Presets.carbGammaForGi(100.0)
            val lo = CurveEngine.Presets.carbGammaForGi(0.0)
            CurveParamsScreen(
                params = CurveParams(
                    bolusGammaK = CurveEngine.Presets.BOLUS_GAMMA_K,
                    bolusGammaTheta = CurveEngine.Presets.BOLUS_GAMMA_THETA,
                    bolusDiaBaseHours = CurveEngine.Presets.BOLUS_DIA_BASE_HOURS,
                    basalKaPerHour = CurveEngine.Presets.BASAL_KA_PER_HOUR,
                    basalKePerHour = CurveEngine.Presets.BASAL_KE_PER_HOUR,
                    lantusDiaHours = CurveEngine.Presets.LANTUS_DIA_MIN / 60.0,
                    tresibaDiaHours = CurveEngine.Presets.TRESIBA_DIA_MIN / 60.0,
                    carbHighGiK = hi.first, carbHighGiTheta = hi.second,
                    carbLowGiK = lo.first, carbLowGiTheta = lo.second,
                ),
            )
        }
        composable("settings/power") {
            val scope = rememberCoroutineScope()
            val enabled by container.settingsStore.lowPowerEnabled.collectAsState(true)
            val pct by container.settingsStore.lowPowerPercent.collectAsState(SettingsStore.DEFAULT_LOW_POWER_PCT)
            val osSaver by container.settingsStore.lowPowerUseOsSaver.collectAsState(true)
            PowerSettingsScreen(
                enabled = enabled, percent = pct, useOsSaver = osSaver,
                onSetEnabled = { on -> scope.launch { container.settingsStore.setLowPowerEnabled(on) } },
                onSetPercent = { p -> scope.launch { container.settingsStore.setLowPowerPercent(p) } },
                onSetUseOsSaver = { on -> scope.launch { container.settingsStore.setLowPowerUseOsSaver(on) } },
            )
        }
        composable("settings/data") {
            val scope = rememberCoroutineScope()
            val ctx = LocalContext.current
            var status by remember { mutableStateOf<String?>(null) }
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                if (uri == null) { status = "Export cancelled." } else scope.launch {
                    status = runCatching {
                        val json = container.exportConfigJson()
                        ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                            ?: error("could not open the chosen file for writing")
                        "Exported settings to the chosen file."
                    }.getOrElse { "Export failed — ${it.message ?: it::class.simpleName}." }
                }
            }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri == null) { status = "Import cancelled." } else scope.launch {
                    status = runCatching {
                        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                            ?: error("could not open the chosen file for reading")
                        val n = container.importConfigJson(text)
                        "Imported $n settings. Reopen the app for alarm-threshold changes to fully apply."
                    }.getOrElse { "Import failed — ${it.message ?: it::class.simpleName}." }
                }
            }
            DataSettingsScreen(
                status = status,
                onExport = { status = null; exportLauncher.launch("t1dm-config.json") },
                onImport = { status = null; importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
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
