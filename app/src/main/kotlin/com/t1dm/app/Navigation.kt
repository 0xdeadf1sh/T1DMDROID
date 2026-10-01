package com.t1dm.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.t1dm.app.widget.STALE_MIN
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import com.t1dm.core.model.LoggedEntry
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.HapticStrength
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalDeathMode
import com.t1dm.core.design.LocalT1dmHaptics
import com.t1dm.core.design.T1dmHaptics
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.CgmStatusIcon
import com.t1dm.core.design.SignalBars
import com.t1dm.core.design.TimeOfDayIcon
import com.t1dm.core.design.hapticClickable
import com.t1dm.core.design.ThemeBackdrop
import com.t1dm.core.design.navEnter
import com.t1dm.core.design.navExit
import com.t1dm.app.di.AppContainer.BolusAdviceUi
import com.t1dm.app.di.LogHandle
import com.t1dm.app.di.logReceipt
import com.t1dm.app.di.undoReceipt
import com.t1dm.app.service.BackgroundControls
import com.t1dm.app.service.DoseCalcService
import com.t1dm.app.service.ExerciseService
import com.t1dm.calc.AdviceGate
import com.t1dm.feature.insulin.BolusCalculatorScreen
import com.t1dm.ui.graph.MaskControls
import com.t1dm.app.notify.GlyStatus
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.BezierCurve
import com.t1dm.core.model.DkaTimeline
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.t1dm.app.di.AppContainer
import com.t1dm.app.backup.BackupRoute
import com.t1dm.feature.settings.NightscoutSettingsScreen
import com.t1dm.feature.settings.DeathModeScreen
import com.t1dm.app.notify.BgFormat
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.core.model.SensitivityEstimate
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.t1dm.feature.dashboard.DashboardScreen
import com.t1dm.feature.exercise.ExerciseScreen
import com.t1dm.feature.exercise.ExerciseSessionScreen
import com.t1dm.feature.exercise.reviewWindow
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import com.t1dm.core.model.TrackPoint
import com.t1dm.feature.game.GameScreen
import com.t1dm.feature.game.GolfScreen
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.GameKind
import com.t1dm.core.model.GamePropDensity
import com.t1dm.core.model.GolfTuning
import com.t1dm.feature.insulin.InsulinScreen
import com.t1dm.feature.insulin.InsulinTypeBuilderScreen
import com.t1dm.feature.meals.FoodEditorScreen
import com.t1dm.feature.meals.MealBuilderScreen
import com.t1dm.feature.meals.MealEditorScreen
import com.t1dm.feature.logs.LogsScreen
import com.t1dm.feature.meals.MealsScreen
import com.t1dm.core.model.Food
import com.t1dm.core.model.SavedMeal
import com.t1dm.feature.models.ModelDetailScreen
import com.t1dm.feature.models.LoraPanel
import com.t1dm.feature.models.ModelsScreen
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.BandCalibration
import com.t1dm.core.model.BandCalibrationOutcome
import com.t1dm.core.model.ErrorGridLattices
import com.t1dm.core.model.ModelMetrics
import com.t1dm.core.model.ModelPrediction
import com.t1dm.feature.security.SecurityPanelState
import com.t1dm.feature.security.SecurityScreen
import com.t1dm.feature.security.WatchPanelDevice
import com.t1dm.feature.settings.AboutScreen
import com.t1dm.feature.settings.AlarmThresholdsScreen
import com.t1dm.feature.settings.AlertsSettingsScreen
import com.t1dm.feature.settings.CalculatorSettingsScreen
import com.t1dm.feature.settings.CgmSettingsScreen
import com.t1dm.feature.settings.CurveParams
import com.t1dm.feature.settings.CurveParamsScreen
import com.t1dm.feature.settings.DataSettingsScreen
import com.t1dm.feature.settings.DeathClockSettingsScreen
import com.t1dm.feature.settings.DeviceTempAlertScreen
import com.t1dm.feature.settings.DisplaySettingsScreen
import com.t1dm.feature.settings.LocalSettingsFocus
import com.t1dm.feature.settings.SettingsFocusController
import com.t1dm.feature.settings.SettingsScreenKey
import com.t1dm.feature.settings.ForecastSettingsScreen
import com.t1dm.feature.settings.GraphSettingsScreen
import com.t1dm.feature.settings.GameSettingsScreen
import com.t1dm.feature.settings.BackgroundSettingsScreen
import com.t1dm.feature.settings.PowerSettingsScreen
import com.t1dm.feature.settings.SettingsScreen
import com.t1dm.feature.settings.SignalSafetyScreen
import com.t1dm.feature.settings.WatchSettingsScreen
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.PredictedClock
import com.t1dm.ui.graph.graphFrameOf
import com.t1dm.alerts.AlarmSeverity
import com.t1dm.alerts.VibrationPreset
import com.t1dm.app.settings.SettingsStore
import com.t1dm.data.T1dmRepository
import com.t1dm.data.curve.ChannelBuilder
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.curve.ExerciseDisposal
import com.t1dm.data.settings.GraphSettingsStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.t1dm.watch.WatchSecurityState
import com.t1dm.feature.cgm.CgmLogScreen
import com.t1dm.feature.cgm.CgmScreen
import com.t1dm.feature.cgm.cgmLogFileName
import com.t1dm.feature.stats.StatsScreen
import com.t1dm.feature.dashboard.CircadianScreen
import com.t1dm.core.design.BundledPalettes
import com.t1dm.core.design.T1dmFontId
import com.t1dm.core.design.ThemeIds
import com.t1dm.core.design.parseThemeJson

private fun WatchSecurityState.toPanelDevice() = WatchPanelDevice(
    id = deviceId.orEmpty(),
    name = deviceName,
    phase = phase.name.lowercase().replace('_', ' '),
    extended = extended,
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
    canConfirmSas = canConfirmSas,
    canRotate = canRotate,
    canReset = canReset,
)

internal data class Destination(val route: String, val label: String)

// The ring the nav wheel turns through, in wheel order.
internal val destinations = listOf(
    Destination("dashboard", "BG"),
    Destination("circadian", "Clock"),
    Destination("stats", "Stats"),
    Destination("models", "Models"),
    Destination("meals", "Meals"),
    Destination("insulin", "Insulin"),
    Destination("exercise", "Exercise"),
    Destination("cgm", "CGM"),
    Destination("security", "Watch"),
    Destination("backup", "Backup"),
    Destination("logs", "Logs"),
    Destination("settings", "Settings"),
)

@Composable
fun T1dmApp(container: AppContainer) {
    val navController = rememberNavController()
    // The base is painted here so the motif can sit at a low alpha under a transparent Scaffold.
    val bgAlphaPct by container.settingsStore.backgroundAlphaPct
        .collectAsState(com.t1dm.app.settings.SettingsStore.DEFAULT_BG_ALPHA_PCT)
    // Not per-route: route scope dies on exit, killing the undo. Writes go on container.appScope.
    val snackbars = remember { SnackbarHostState() }
    val receiptScope = rememberCoroutineScope()
    val haptics = LocalT1dmHaptics.current
    VolumeKeyShortcuts(navController, container, haptics)
    // Hoisted: the hub is in the `bottomBar` slot, the arc draws over the content. See NavWheel.kt.
    val wheel = rememberNavWheelState(destinations.size)
    // Never read here: every field is unwrapped in a draw lambda.
    val wheelMotion = rememberNavWheelMotion()
    val onWheelSelect: (Int) -> Unit = remember(navController, haptics) {
        { index ->
            // The current tab is not re-entered: a re-navigation rebuilds the panel mid-game.
            if (navController.currentBackStackEntry?.destination?.route == destinations[index].route) return@remember Unit
            haptics.perform(HapticEvent.NavSwitch)
            navController.navigate(destinations[index].route) {
                launchSingleTop = true
                restoreState = true
                popUpTo("dashboard") { saveState = true }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(LocalT1dmSemantics.current.background)) {
        ThemeBackdrop(bgAlphaPct)
        Scaffold(
            containerColor = Color.Transparent,
            // targetSdk 36 never resizes window; imePadding keeps receipts from hiding under it.
            snackbarHost = { SnackbarHost(snackbars, Modifier.imePadding()) },
            // Transparent container derives contentColor from Transparent, collapsing to black.
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = { T1dmBottomBar(navController, container, wheel, wheelMotion, onWheelSelect) },
        ) { padding ->
            // Only insets apply here; consumeWindowInsets first avoids doubling with imePadding.
            Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                // Flavor-specific: real text in the public build, no-op in the personal build.
                Disclaimer()
                Breadcrumb(navController, container)
                T1dmNavHost(
                    navController,
                    container,
                    onNotice = { message ->
                        receiptScope.launch {
                            snackbars.currentSnackbarData?.dismiss()
                            snackbars.showSnackbar(message, duration = SnackbarDuration.Long)
                        }
                    },
                ) { handle ->
                    receiptScope.launch { snackbars.postLogReceipt(container, handle, haptics) }
                }
            }
        }
        // Outside the Scaffold on purpose: the arc must paint over the content.
        NavWheelArc(wheel, wheelMotion, destinations, onWheelSelect)
    }
}

/** Partial undo: a Nightscout mirror already sent stays on that host. */
private suspend fun SnackbarHostState.postLogReceipt(
    container: AppContainer,
    handle: LogHandle,
    haptics: T1dmHaptics,
) {
    haptics.perform(HapticEvent.Commit)
    // showSnackbar suspends until the current one dismisses; undo belongs to the newest.
    currentSnackbarData?.dismiss()
    val result = showSnackbar(
        message = logReceipt(handle),
        actionLabel = "UNDO",
        withDismissAction = true,
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed) {
        container.undoLog(handle)
        showSnackbar(undoReceipt(handle), duration = SnackbarDuration.Long)
    }
}

/** [route] non-null ⇒ tappable to ascend to it. */
internal data class Crumb(val label: String, val route: String?)

/** Most-recent last; final crumb never tappable. [editLabel] names the row an editor is open on. */
internal fun crumbsFor(route: String?, modelId: String?, editLabel: String? = null): List<Crumb> {
    fun settings(vararg tail: Crumb) = listOf(Crumb("Settings", "settings"), *tail)
    return when (route) {
        null, "dashboard" -> listOf(Crumb("BG", null))
        "circadian" -> listOf(Crumb("Circadian clock", null))
        "stats" -> listOf(Crumb("Stats", null))
        "models" -> listOf(Crumb("Models", null))
        "models/{modelId}/lora" -> listOf(Crumb("Models", "models"), Crumb("Adapters", null))
        "models/{modelId}" -> listOf(Crumb("Models", "models"), Crumb(modelId ?: "model", null))
        "meals" -> listOf(Crumb("Meals", null))
        "meals/builder" -> listOf(Crumb("Meals", "meals"), Crumb("Meal builder", null))
        "meals/builder/meal/{mealId}" -> listOf(
            Crumb("Meals", "meals"),
            Crumb("Meal builder", "meals/builder"),
            Crumb(editLabel?.let { "Edit “$it”" } ?: "Edit meal", null),
        )
        "meals/builder/food/{foodId}" -> listOf(
            Crumb("Meals", "meals"),
            Crumb("Meal builder", "meals/builder"),
            Crumb(editLabel?.let { "Edit “$it”" } ?: "Edit food", null),
        )
        "insulin" -> listOf(Crumb("Insulin", null))
        "insulin/types" -> listOf(Crumb("Insulin", "insulin"), Crumb("Types & curves", null))
        "insulin/bolusCalc" -> listOf(Crumb("Insulin", "insulin"), Crumb("Bolus advisor", null))
        "exercise" -> listOf(Crumb("Exercise", null))
        "exercise/{sessionId}" -> listOf(Crumb("Exercise", "exercise"), Crumb("Session", null))
        "cgm" -> listOf(Crumb("CGM", null))
        "cgm/log/{sensorId}" -> listOf(Crumb("CGM", "cgm"), Crumb("Log", null))
        "security" -> listOf(Crumb("Watch", null))
        "backup" -> listOf(Crumb("Backup", null))
        "logs" -> listOf(Crumb("Logs", null))
        "settings" -> listOf(Crumb("Settings", null))
        "about" -> settings(Crumb("About", null))
        "settings/display" -> settings(Crumb("Display", null))
        "settings/graph" -> settings(Crumb("Graph", null))
        "settings/games" -> settings(Crumb("Games", null))
        "settings/alarms" -> settings(Crumb("Alarms", "settings"), Crumb("Thresholds", null))
        "settings/signal" -> settings(Crumb("Alarms", "settings"), Crumb("Signal", null))
        "settings/alerts" -> settings(Crumb("Sound", null))
        "settings/forecast" -> settings(Crumb("Forecast", null))
        "settings/temperature" -> settings(Crumb("Alarms", "settings"), Crumb("Device heat", null))
        "settings/deathclock" -> settings(Crumb("Death clock", null))
        "settings/calculator" -> settings(Crumb("Bolus calculator", null))
        "settings/curves" -> settings(Crumb("Curve & PK", null))
        "settings/cgm" -> settings(Crumb("CGM source", null))
        "settings/nightscout" -> settings(Crumb("Nightscout", null))
        "settings/watch" -> settings(Crumb("Watch", null))
        "settings/power" -> settings(Crumb("Low power", null))
        "settings/background" -> settings(Crumb("Background", null))
        "settings/data" -> settings(Crumb("Reset", null))
        "settings/death" -> settings(Crumb("Death mode", null))
        else -> listOf(Crumb(route, null))
    }
}

/** settings has no route, only SettingsScreenKey; mapping sits beside crumbsFor so tests agree. */
internal fun settingsRouteFor(screen: SettingsScreenKey): String = when (screen) {
    SettingsScreenKey.ROOT -> "settings"
    SettingsScreenKey.DISPLAY -> "settings/display"
    SettingsScreenKey.GRAPH -> "settings/graph"
    SettingsScreenKey.GAMES -> "settings/games"
    SettingsScreenKey.ALARM_THRESHOLDS -> "settings/alarms"
    SettingsScreenKey.SIGNAL -> "settings/signal"
    SettingsScreenKey.ALERTS -> "settings/alerts"
    SettingsScreenKey.DEVICE_TEMP -> "settings/temperature"
    SettingsScreenKey.FORECAST -> "settings/forecast"
    SettingsScreenKey.CALCULATOR -> "settings/calculator"
    SettingsScreenKey.CURVES -> "settings/curves"
    SettingsScreenKey.MODELS -> "models"
    SettingsScreenKey.CGM -> "settings/cgm"
    SettingsScreenKey.NIGHTSCOUT -> "settings/nightscout"
    SettingsScreenKey.WATCH -> "settings/watch"
    SettingsScreenKey.POWER -> "settings/power"
    SettingsScreenKey.BACKGROUND -> "settings/background"
    SettingsScreenKey.DATA -> "settings/data"
    SettingsScreenKey.BACKUP -> "backup"
    SettingsScreenKey.ABOUT -> "about"
    SettingsScreenKey.DEATH_CLOCK -> "settings/deathclock"
    SettingsScreenKey.DEATH_MODE -> "settings/death"
}

/** Fires once: guards press + observed-gone both firing; popBackStack returns before teardown. */
@Composable
private fun rememberSingleAscent(navController: NavHostController): () -> Unit {
    var spent by remember { mutableStateOf(false) }
    return remember(navController) {
        {
            if (!spent) {
                spent = true
                if (!navController.popBackStack()) navController.navigate("meals/builder")
            }
        }
    }
}

/** Null: not yet emitted, or row gone. Scoped to two routes since savedMeals costs a query/meal. */
@Composable
private fun editedRowName(
    container: AppContainer,
    route: String?,
    mealId: Long?,
    foodId: Long?,
): String? = when {
    route == "meals/builder/meal/{mealId}" && mealId != null -> {
        val meals by container.savedMeals.collectAsState(emptyList())
        meals.firstOrNull { it.id == mealId }?.name
    }
    route == "meals/builder/food/{foodId}" && foodId != null -> {
        val foods by container.customFoods.collectAsState(emptyList())
        foods.firstOrNull { it.id == foodId }?.name
    }
    else -> null
}

@Composable
private fun Breadcrumb(navController: NavHostController, container: AppContainer) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val modelId = backStackEntry?.arguments?.getString("modelId")
    val editLabel = editedRowName(
        container = container,
        route = route,
        mealId = backStackEntry?.arguments?.getString("mealId")?.toLongOrNull(),
        foodId = backStackEntry?.arguments?.getString("foodId")?.toLongOrNull(),
    )
    val crumbs = remember(route, modelId, editLabel) { crumbsFor(route, modelId, editLabel) }
    val cs = MaterialTheme.colorScheme
    // Read for its AGE only.
    val reading by container.latestReading.collectAsState(null)
    val death = LocalDeathMode.current
    val inference by container.inferenceState.collectAsState(InferenceState())
    // A flow, not the @Volatile snapshot: a Settings edit has to invalidate this composition.
    val alarmCfg by container.alarmConfigFlow.collectAsState()
    // ADAPTIVE only republishes InferenceState on a reading; this tick keeps STABLE from pinning.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(CHROME_TICK_MS)
        }
    }
    val readingAgeMs = reading?.rxWallMs?.let { (nowMs - it).coerceAtLeast(0L) }
    val status = remember(inference, alarmCfg, readingAgeMs) {
        BgGlanceComputer.status(
            inference, alarmCfg.thresholds, container.alarmFanEdges, nowMs, readingAgeMs, STALE_MIN,
        )
    }
    val animationsOn = LocalAnimationsEnabled.current
    val trailScroll = rememberScrollState()
    val trailWidth = crumbTrailWidth(crumbs)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .then(
                        if (animationsOn) Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        else Modifier.horizontalScroll(trailScroll),
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CRUMB_GAP),
            ) {
                crumbs.forEachIndexed { i, crumb ->
                    if (i > 0) {
                        Text("›", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                    }
                    val isLast = i == crumbs.lastIndex
                    Text(
                        crumb.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            isLast -> cs.onSurface
                            crumb.route != null -> cs.primary
                            else -> cs.onSurfaceVariant
                        },
                        maxLines = 1,
                        modifier = if (!isLast && crumb.route != null) {
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .hapticClickable(HapticEvent.NavSwitch) {
                                    if (!navController.popBackStack(crumb.route, inclusive = false)) {
                                        navController.navigate(crumb.route) { launchSingleTop = true }
                                    }
                                }
                                .padding(horizontal = CRUMB_H_PAD, vertical = 8.dp)
                        } else {
                            Modifier.padding(horizontal = CRUMB_H_PAD, vertical = 8.dp)
                        },
                    )
                }
            }
            GlycemicStatusBadge(status, status.text(nowMs))
            if (death) {
                Text(
                    "☠",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 20.sp,
                    color = cs.error,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        // Centred on the bar, not on what the trail leaves: it yields rather than drift.
        if (trailWidth <= maxWidth / 2 - TOD_ICON_SIZE / 2 - CRUMB_GAP) {
            TimeOfDayIcon(TOD_ICON_SIZE, Modifier.align(Alignment.Center))
        }
    }
}

private val CRUMB_H_PAD = 8.dp
private val CRUMB_GAP = 4.dp
private val TOD_ICON_SIZE = 36.dp

/** Shared by the arrow and the R/S beneath it, so the two centre on one axis. */
private val TREND_COL_W = 28.dp

/** Laid-out width of the whole trail, so the centred clock can yield before the two meet. */
@Composable
private fun crumbTrailWidth(crumbs: List<Crumb>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleMedium
    val density = LocalDensity.current
    return remember(crumbs, style, density) {
        if (crumbs.isEmpty()) return@remember 0.dp
        var px = 0
        crumbs.forEachIndexed { i, crumb ->
            if (i > 0) px += measurer.measure("›", style).size.width
            val weight = if (i == crumbs.lastIndex) FontWeight.Bold else FontWeight.Normal
            px += measurer.measure(crumb.label, style.copy(fontWeight = weight)).size.width
        }
        val gaps = 2 * crumbs.size - 2
        with(density) { px.toDp() } + CRUMB_H_PAD * 2 * crumbs.size + CRUMB_GAP * gaps
    }
}

/** How often the chrome re-judges freshness: fast enough that a stale badge is not stale advice. */
private const val CHROME_TICK_MS = 20_000L

private const val CGM_LOG_REFRESH_MS = 300L

private const val LOG_PAGE_ROWS = 20

/** [start]: position of the first of [entries] in the whole log, from 0. */
private data class LogPage(val start: Int, val entries: List<LoggedEntry>, val hasNext: Boolean)

private val EMPTY_LOG_PAGE = LogPage(0, emptyList(), false)

/** First entry doubles as the label for an unknown key, which the store reads as Kovatchev. */
private val CALC_OBJECTIVES = listOf(
    SettingsStore.OBJ_KOVATCHEV to "Min Kovatchev risk",
    SettingsStore.OBJ_MIN_TOR to "Min time out of range",
    SettingsStore.OBJ_HIT_TARGET to "Hit target (1 h)",
)

private fun GlyStatus.text(nowMs: Long): String = when (this) {
    GlyStatus.Stable -> "STABLE"
    GlyStatus.Unsure -> "UNSURE"
    is GlyStatus.Excursion -> "${kind.name} in ${((atMs - nowMs) / 60_000L).coerceAtLeast(0L)}M"
    is GlyStatus.Void -> "VOID"
}

@Composable
private fun GlycemicStatusBadge(status: GlyStatus, text: String) {
    val animationsOn = LocalAnimationsEnabled.current
    val ctx = LocalContext.current
    val color = when (status) {
        is GlyStatus.Stable -> LocalT1dmSemantics.current.inRange
        is GlyStatus.Unsure -> MaterialTheme.colorScheme.onSurface
        is GlyStatus.Excursion -> MaterialTheme.colorScheme.error
        is GlyStatus.Void -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val periodMs = when (status) {
        is GlyStatus.Stable -> 2600
        is GlyStatus.Unsure -> 0
        is GlyStatus.Excursion -> 700
        is GlyStatus.Void -> 0
    }
    val floor = if (status is GlyStatus.Excursion) 0.35f else 0.8f
    // HELD not unwrapped: reading .value here would recompose this row every frame; see Pulse.kt.
    val alphaState = if (animationsOn && periodMs > 0) {
        val transition = rememberInfiniteTransition(label = "status")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = floor,
            animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Reverse),
            label = "statusAlpha",
        )
    } else null
    val mod = if (status is GlyStatus.Void) {
        Modifier
            .clip(RoundedCornerShape(6.dp))
            // Warn, not tap: the answer is always a refusal.
            .hapticClickable(HapticEvent.Warn) {
                android.widget.Toast.makeText(ctx, status.reason, android.widget.Toast.LENGTH_LONG).show()
            }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    } else {
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    }
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = if (alphaState != null) {
            mod.graphicsLayer { alpha = alphaState.value }
        } else {
            mod
        },
    )
}

/** Past STALE_MIN the arrow drops, value reddens; a rate-less source draws no arrow, never FLAT. */
@Composable
private fun T1dmBottomBar(
    navController: NavHostController,
    container: AppContainer,
    wheel: NavWheelState,
    motion: NavWheelMotion,
    onWheelSelect: (Int) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination?.route
    val cs = MaterialTheme.colorScheme
    val reading by container.latestReading.collectAsState(null)
    val unit by container.statsRepository.unitSpace.collectAsState(UnitSpace.MgDl)
    // Viewed vs authoritative sets ColorScheme.tertiary; label is pre-resolved, privacy-filtered.
    val sourceLabel by container.viewedSourceLabel.collectAsState(null)
    val sourceSerial by container.viewedSourceSerial.collectAsState(null)
    val sourceStatus by container.viewedStatus.collectAsState(CgmSourceStatus.Idle)
    val viewingOther by container.viewingNonAuthoritative.collectAsState(false)
    // The chip's own reading, so its name and its signal bars describe the same sensor.
    val viewedReading by container.viewedReading.collectAsState(null)
    val viewedLinkRssi by container.viewedLinkRssi.collectAsState(null)
    val direction by container.viewedDirection.collectAsState(null)

    // reading stays authoritative: what the alarm engine, statistics, dose calc and wire read.
    val shown = if (viewingOther) viewedReading else reading

    // Fast while the reading is young so the chip counts seconds; coarse after, on every screen.
    val rxWallMs = shown?.rxWallMs
    val nowMs by produceState(System.currentTimeMillis(), rxWallMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(if (rxWallMs != null && (value - rxWallMs) in 0..60_000L) 1_000L else CHROME_TICK_MS)
        }
    }
    val ageMs = rxWallMs?.let { (nowMs - it).coerceAtLeast(0L) }
    val stale = ageMs == null || ageMs > STALE_MIN * 60_000L
    val shownDirection = direction.takeUnless { stale }

    // No Surface: it clips; the glow and pointer both reach past the bar. Colour set here instead.
    CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 28sp, not displaySmall's 36: a signed risk value plus the icon overruns 132dp.
            val bgStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
            val bgText = BgFormat.valueSignAligned(shown?.bgMgdl, unit)
            Column(
                Modifier
                    // Equal to the sensor panel, or the puck between them leaves the centre.
                    .weight(1f)
                    .hapticClickable(HapticEvent.SegmentTick) {
                        container.setUnitSpace(UnitSpace.entries[(unit.ordinal + 1) % UnitSpace.entries.size])
                    }
                    // Clearance from the puck.
                    .padding(end = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bgText,
                        style = bgStyle,
                        // Order matters: STALE wins over the off-the-believed-sensor tint.
                        color = when {
                            stale -> cs.error
                            viewingOther -> cs.tertiary
                            else -> Color.Unspecified
                        },
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = BgFormat.arrow(shownDirection?.trend),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (viewingOther) cs.tertiary else Color.Unspecified,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(TREND_COL_W),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = BgFormat.unitLabel(unit) + (shown?.let { readingSuffix(it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            stale -> cs.error
                            viewingOther -> cs.tertiary
                            else -> cs.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Under the first digit: this line is narrower than the value's.
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = leadGlyphWidth(bgText, bgStyle)),
                    )
                    Text(
                        text = shownDirection?.let { if (it.reported) "R" else "S" } ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(TREND_COL_W),
                    )
                }
            }
            NavWheelPuck(wheel, motion, destinations, current, onWheelSelect)
            SensorPanel(
                Modifier.weight(1f),
                sourceLabel = sourceLabel,
                sourceSerial = sourceSerial,
                // Held link first; stored RSSI is the ADVERTISEMENT fallback, only while fresh.
                rssi = viewedLinkRssi ?: viewedReading?.rssi?.takeUnless { stale },
                status = sourceStatus,
                ageMs = ageMs,
                stale = stale,
                viewingOther = viewingOther,
                onCycle = container::cycleViewedSource,
            )
        }
    }
}

/** Width of [text]'s first glyph, measured against a digit: a lone space measures zero. */
@Composable
private fun leadGlyphWidth(text: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lead = text.take(1)
    return remember(lead, style, density) {
        if (lead.isEmpty()) {
            0.dp
        } else {
            with(density) {
                (measurer.measure(lead + "0", style).size.width - measurer.measure("0", style).size.width).toDp()
            }
        }
    }
}

/** A promoted reconstruction is a cgm_reading row like any other; unlabelled reads as measured. */
private fun readingSuffix(r: CgmReading): String = when {
    r.flag == ReadingFlag.WARMUP -> "  • warmup"
    r.provenance == ReadingProvenance.INTERPOLATED -> "  • interpolated"
    r.provenance == ReadingProvenance.RECONSTRUCTED -> "  • reconstructed"
    else -> ""
}

/** Name, serial, signal, then state and reading age. Tapping anywhere steps to the next sensor. */
@Composable
private fun SensorPanel(
    modifier: Modifier,
    sourceLabel: String?,
    sourceSerial: String?,
    rssi: Int?,
    status: CgmSourceStatus,
    ageMs: Long?,
    stale: Boolean,
    viewingOther: Boolean,
    onCycle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tint = if (viewingOther) cs.tertiary else Color.Unspecified
    val muted = cs.onSurface.copy(alpha = 0.7f)
    Column(
        modifier.hapticClickable(HapticEvent.NavSwitch) { onCycle() },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = sourceLabel ?: "no source",
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = sourceSerial ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (viewingOther) cs.tertiary else muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Em dash, not a zero: no held link and no fresh advert is an unknown, not a floor.
        if (rssi == null) {
            Text("—", style = MaterialTheme.typography.bodyMedium, color = muted)
        } else {
            SignalBars(rssi)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CgmStatusIcon(status)
            ageMs?.let { LastReadingChip(it, stale) }
        }
    }
}

/** Age from rxWallMs, pre grid-snap; this line alone carries freshness. */
@Composable
private fun LastReadingChip(ageMs: Long, stale: Boolean) {
    Text(
        formatReadingAge(ageMs),
        style = MaterialTheme.typography.bodyMedium,
        // Wrapping here would grow the bottomBar slot and move the content inset with it.
        maxLines = 1,
        softWrap = false,
        // Monospace so the per-second tick does not shift the chip.
        fontFamily = FontFamily.Monospace,
        color = if (stale) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        },
    )
}

/** Space-padded to a constant 7 monospace columns; coarse past the hour so the row cannot grow. */
private fun formatReadingAge(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "%2ds ago".format(s)
        s < 3600 -> "%2dm ago".format(s / 60)
        s < 86_400 -> "%2dh ago".format(s / 3600)
        else -> "%2dd ago".format(s / 86_400)
    }
}

/** A ticker not a cycle key: lastCycleTsMs stops on the same paths that stop forecasts. */
@Composable
private fun Libre3Provision(container: AppContainer, name: String, onDismiss: () -> Unit) {
    com.t1dm.app.cgm.Libre3ProvisionSheet(
        name = name,
        onDismiss = onDismiss,
        prefs = { container.libre3AccountPrefs() },
        onPersist = { accountId, regionName -> container.setLibre3AccountPrefs(accountId, regionName) },
        provision = { accountId, region, link -> container.provisionLibre3Sensor(accountId, region, link) },
    )
}

@Composable
private fun rememberSensitivity(container: AppContainer): SensitivityEstimate? {
    val sensitivity by container.sensitivity.collectAsState()
    LaunchedEffect(Unit) {
        while (isActive) {
            container.refreshSensitivityIfStale()
            kotlinx.coroutines.delay(60_000)
        }
    }
    return sensitivity
}

/** Up=Meals, down=Insulin; also checks predictive, which self-suppresses under CRITICAL alarms. */
@Composable
private fun VolumeKeyShortcuts(
    navController: NavHostController,
    container: AppContainer,
    haptics: T1dmHaptics,
) {
    val activity = LocalContext.current.findMainActivity() ?: return
    val enabled by container.settingsStore.volumeNavEnabled.collectAsState(true)
    val alarm by container.alarmState.collectAsState()
    val predictive by container.predictiveAlertRaised.collectAsState()
    val announcing = alarm.isActive || predictive
    DisposableEffect(activity, navController, haptics, enabled, announcing) {
        activity.volumeShortcut = shortcut@{ up ->
            if (!enabled || announcing) return@shortcut false
            haptics.perform(HapticEvent.NavSwitch)
            navController.navigate(if (up) "meals" else "insulin") {
                launchSingleTop = true
                restoreState = true
                popUpTo("dashboard") { saveState = true }
            }
            true
        }
        onDispose { activity.volumeShortcut = null }
    }
}

/** Not `LocalActivity`: activity-compose is pinned at 1.9.3 here. */
private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}

/** [onNotice] posts a bare line with no action: a write with no row left to offer an Undo on. */
@Composable
private fun T1dmNavHost(
    navController: NavHostController,
    container: AppContainer,
    onNotice: (String) -> Unit,
    onLogged: (LogHandle) -> Unit,
) {
    val animationsOn = LocalAnimationsEnabled.current
    val navHaptics = LocalT1dmHaptics.current
    // Not a route argument: crumbsFor matches route literals; an arg would replay pulse on Back.
    val settingsFocus = remember { SettingsFocusController() }
    CompositionLocalProvider(LocalSettingsFocus provides settingsFocus) {
    NavHost(
        navController = navController,
        startDestination = "dashboard",
        enterTransition = { navEnter(animationsOn) },
        exitTransition = { navExit(animationsOn) },
        popEnterTransition = { navEnter(animationsOn) },
        popExitTransition = { navExit(animationsOn) },
    ) {
        composable("dashboard") {
            val scope = rememberCoroutineScope()
            val readings by container.dashboardReadings.collectAsState(emptyList())
            val historyFloorMs by container.historyFloorMs.collectAsState(null)
            val inference by container.inferenceState.collectAsState(InferenceState())
            val iobCob by container.iobCob.collectAsState()
            val range by container.graphRange.collectAsState(com.t1dm.data.settings.BgRange.DEFAULT)
            val windowHours by container.graphWindowHours.collectAsState(6)
            val reachability by container.bgReachability.collectAsState(null)
            val tempUnit by container.temperatureUnit.collectAsState(com.t1dm.core.model.TempUnit.CELSIUS)
            val deviceTempC by produceState<Double?>(null) {
                while (true) {
                    value = withContext(container.dispatchers.io) { container.readDeviceTempC() }
                    kotlinx.coroutines.delay(30_000)
                }
            }
            val stepsToday by produceState<Int?>(null) {
                while (true) {
                    value = withContext(container.dispatchers.io) { runCatching { container.stepsToday() }.getOrNull() }
                    kotlinx.coroutines.delay(30_000)
                }
            }
            // Display-only: never reaches alerts or dosing.
            val rolled by container.rolledForecast.collectAsState()
            val rollComputing by container.rollComputing.collectAsState()
            val pulses by container.bgPulses.collectAsState(null)
            val sensorExpiry by container.sensorExpiryMs.collectAsState(null)
            // Non-null only while the active sensor is warming up.
            val sensorWarmupEnd by container.sensorWarmupEndMs.collectAsState(null)
            val lowPowerActive by container.lowPowerActive.collectAsState(false)
            val forecastMode by container.settingsStore.forecastMode.collectAsState(SettingsStore.FORECAST_MODE_ADAPTIVE)
            val forecastPeriod by container.settingsStore.forecastPeriodMin.collectAsState(SettingsStore.DEFAULT_FORECAST_PERIOD_MIN)
            // A disabled gate passes null, so the chip keeps its neutral colour.
            val thermalGateOn by container.thermalGateEnabled.collectAsState(SettingsStore.DEFAULT_THERMAL_ON)
            val thermalMaxC by container.inferenceMaxTempC.collectAsState(SettingsStore.DEFAULT_MAX_TEMP_C)
            val thermalWarn by container.thermalWarnMarginC.collectAsState(SettingsStore.DEFAULT_WARN_MARGIN_C)
            val storedUnit by container.statsRepository.unitSpace.collectAsState(null)
            val glucoseUnit = storedUnit ?: UnitSpace.MgDl
            // INFERENCE.md §7.1: also passed as value, else memoized lambda goes stale on edit.
            val savgolWindow by container.savgolWindow.collectAsState(SettingsStore.DEFAULT_SAVGOL_WINDOW)
            // Memoised: container is Compose-unstable, so an inline lambda re-runs FFI smoothing.
            val smoothMgdl = remember(savgolWindow) {
                { arr: DoubleArray ->
                    container.nativeCore.causalSmooth(arr.toList(), 20.0, 500.0, savgolWindow).toDoubleArray()
                }
            }
            val paintStrokes by container.paintStrokes.collectAsState(emptyList())
            val logEntries by container.loggedEntries.collectAsState(emptyList())
            val insulins by container.insulinChoices.collectAsState(emptyList())
            // §8.4: remembered against the map, so identity changes exactly when a fit lands.
            val bandCalibrations by container.bandCalibrations.collectAsState()
            val calibrateBands: (ModelPrediction) -> List<Double>? = remember(bandCalibrations) {
                { p ->
                    container.calibratedBands(
                        bandCalibrations,
                        p.modelId,
                        p.bandsMgdl,
                        p.horizonSteps,
                        p.nQuantiles,
                    )
                }
            }
            val calibrateFans: (String, () -> List<Double>, Int, Int) -> List<Double>? =
                remember(bandCalibrations) {
                    { modelId, fans, steps, nq ->
                        container.calibratedFanBatch(bandCalibrations, modelId, fans, steps, nq)
                    }
                }
            val sensitivity = rememberSensitivity(container)
            // Forecasts come from the AUTHORITATIVE sensor; emptying the list withholds all three.
            val viewingOther by container.viewingNonAuthoritative.collectAsState(false)
            val viewedSourceKey by container.viewedSourceKey.collectAsState(null)
            // Null until both load, so the mg/dL placeholder on entry does not dissolve.
            val swapKey = storedUnit?.let { u -> viewedSourceKey?.let { it to u } }
            // Off the UNWITHHELD predictions: withholding the fan must not move the trace.
            val forecastEndMs = inference.predictions.firstOrNull { it.selected }
                ?.takeIf { it.horizonSteps > 0 }
                ?.let { it.anchorTsMs + it.horizonSteps * it.stepMs }
            // maskControls is null until a model with a descriptor is selected, hiding the gesture.
            val maskNote by container.panelMaskNote.collectAsState()
            val bgEditDepth by container.bgEditDepth.collectAsState()
            val tauPreview by container.tauPreview.collectAsState()
            var maskControls by remember { mutableStateOf<MaskControls?>(null) }
            // Keyed on the newest reading too: the geometry is derived from it, and a cut moves it.
            LaunchedEffect(
                inference.predictions.firstOrNull { it.selected }?.modelId,
                readings.lastOrNull()?.tsMs,
            ) {
                maskControls = runCatching { container.maskControls() }.getOrNull()
            }
            val reconWindowStart = (historyFloorMs ?: (System.currentTimeMillis() - 86_400_000L))
            val reconstructed by remember(reconWindowStart) {
                container.panelReconstructed(reconWindowStart, System.currentTimeMillis() + 86_400_000L)
            }.collectAsState(emptyList())
            DashboardScreen(
                readings = readings,
                swapKey = swapKey,
                unit = glucoseUnit,
                thresholds = container.alarmConfig.thresholds,
                predictions = if (viewingOther) emptyList() else inference.predictions,
                kovatchevF = container.nativeCore::kovatchevF,
                kovatchevFClinicalBatch = container.nativeCore::kovatchevFClinicalBatch,
                calibrateBands = calibrateBands,
                calibrateFans = calibrateFans,
                iobCob = iobCob,
                sensitivity = sensitivity,
                curveChannels = container::dashboardOverlayChannels,
                stepSeries = container::dashboardStepSeries,
                logEntries = logEntries,
                insulins = insulins,
                onEditLog = { entry, edit ->
                    container.appScope.launch { container.applyLogEdit(entry, edit) }
                },
                onDeleteLog = { entry ->
                    container.appScope.launch { container.deleteLoggedEntry(entry) }
                },
                // Withheld with the forecast, and for the same reason.
                reconstructed = if (viewingOther) emptyList() else reconstructed,
                maskControls = if (viewingOther) null else maskControls,
                onFillSpan = { sel, geometry -> container.runPanelMask(sel, geometry) },
                maskNote = maskNote,
                onCutBg = container::cutBgRange,
                onUndoBgEdit = container::undoBgEdit,
                canUndoBgEdit = bgEditDepth > 0,
                onPromoteSpan = container::promoteSpan,
                onDemoteSpan = container::demoteSpan,
                onDiscardSpan = container::discardSpan,
                onRetauSpan = container::retauSpan,
                onPreviewTau = container::previewTau,
                tauPreview = tauPreview,
                historyFloorMs = historyFloorMs,
                onExtendHistory = container::extendHistoryBackTo,
                forecastEndMs = forecastEndMs,
                warmup = inference.warmup,
                rangeMinMgdl = range.minMgdl,
                rangeMaxMgdl = range.maxMgdl,
                initialWindowHours = windowHours,
                onSetWindowHours = { h -> scope.launch { container.setGraphWindowHours(h) } },
                reachability = reachability,
                pulses = pulses,
                deviceTempC = deviceTempC,
                temperatureUnit = tempUnit,
                stepsToday = stepsToday,
                sensorExpiryMs = sensorExpiry,
                sensorWarmupEndMs = sensorWarmupEnd,
                circadianTime = inference.circadianTime,
                circadianAnchorMs = inference.circadianAnchorMs,
                smoothMgdl = smoothMgdl,
                smoothingWindow = savgolWindow,
                // Withheld with the fan, and for the same reason; the control goes with it.
                rolledForecast = if (viewingOther) null else rolled,
                rollComputing = if (viewingOther) false else rollComputing,
                onRoll = if (viewingOther) null else ({ hours: Double -> container.requestRollForDisplay(hours) }),
                onClearRoll = { container.clearRoll() },
                lowPowerActive = lowPowerActive,
                forecastAdaptive = forecastMode == SettingsStore.FORECAST_MODE_ADAPTIVE,
                forecastPeriodMin = forecastPeriod,
                thermalThresholdC = if (thermalGateOn) thermalMaxC else null,
                thermalWarnMarginC = thermalWarn,
                paintStrokes = paintStrokes,
                onAddPaintStroke = container::addPaintStroke,
                onDeletePaintStroke = container::deletePaintStroke,
                hindsightIn = container.repository::predictionsForModelInRange,
                gameSlot = { m, kind, fromMs, dropMs, spanMin, clock, ready, exit ->
                    DashboardGamePanel(
                        container, m, kind, fromMs, dropMs, spanMin, clock, ready, exit, range, paintStrokes,
                    )
                },
            )
        }
        composable("circadian") {
            val inference by container.inferenceState.collectAsState(InferenceState())
            val iobCob by container.iobCob.collectAsState()
            val dkaTl by container.dkaTimeline.collectAsState(DkaTimeline.DEFAULT)
            CircadianScreen(
                predictedTime = inference.selectedPredictedTime,
                realBackendAvailable = inference.realBackendAvailable,
                noModel = inference.running.isEmpty(),
                hasTimeSection = inference.selectedHasTimeSection,
                // The real reason during warmup / before the first cycle — never "no time section".
                warmingUp = inference.warmup != null ||
                    inference.lastCause == InferenceCause.COLLECTING_CONTEXT ||
                    inference.lastCycleTsMs == null,
                lowContext = inference.circadianLowContext,
                iobU = iobCob?.iobU,
                iobZeroMs = iobCob?.iobZeroMs,
                dkaTimeline = dkaTl,
            )
        }
        composable("stats") {
            val statsState by container.statsViewModel.state.collectAsState()
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()
            var exportStatus by remember { mutableStateOf<String?>(null) }
            val pdfLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/pdf"),
            ) { uri ->
                if (uri == null) { exportStatus = "Export cancelled" }
                else scope.launch {
                    val composite = container.statsViewModel.fresh()
                    exportStatus = if (composite == null) "No stats to export" else runCatching {
                        ctx.contentResolver.openOutputStream(uri)?.use {
                            com.t1dm.app.stats.StatsPdf.write(it, composite, container.nativeCore.clinicalCuts())
                        } ?: error("could not open file")
                        "Report exported"
                    }.getOrElse { "Export failed — ${it.message ?: it::class.simpleName}" }
                }
            }
            LaunchedEffect(Unit) { container.statsViewModel.refresh() }
            StatsScreen(
                state = statsState,
                kovatchevF = container.nativeCore::kovatchevF,
                cuts = remember { container.nativeCore.clinicalCuts() },
                onSelectWindow = container.statsViewModel::selectWindow,
                onSetUnitSpace = container::setUnitSpace,
                onSetTargetRange = container.statsViewModel::setTargetRange,
                onRecompute = container.statsViewModel::recompute,
                onExportPdf = { exportStatus = null; pdfLauncher.launch("t1dm-stats-${statsState.window.wire}.pdf") },
                exportStatus = exportStatus,
            )
        }
        composable("models/{modelId}/lora") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("modelId").orEmpty()
            val lab = container.labController
            val panel by container.loraPanel.collectAsState()
            val scope = rememberCoroutineScope()
            LaunchedEffect(id) { container.refreshLoraPanel(id) }
            LoraPanel(
                state = panel,
                onFit = { spec -> container.fitAdapter(id, spec) },
                onAttach = { adapterId -> scope.launch { container.attachAdapter(id, adapterId) } },
                onProbe = { adapterId -> container.probeAdapter(id, adapterId) },
                onOverride = { adapterId, typedName ->
                    scope.launch { container.overrideAdapterGuard(id, adapterId, typedName) }
                },
                onDetach = { scope.launch { container.detachAdapter(id) } },
                onRename = { adapterId, name ->
                    scope.launch { lab.rename(adapterId, name); container.refreshLoraPanel(id) }
                },
                onDelete = { adapterId ->
                    scope.launch { lab.delete(adapterId); container.refreshLoraPanel(id) }
                },
                onExport = { adapterId -> scope.launch { container.exportAdapter(adapterId) } },
                onImport = { scope.launch { container.importAdapters(id) } },
            )
        }

        composable("models") {
            val inference by container.inferenceState.collectAsState(InferenceState())
            val scope = rememberCoroutineScope()
            ModelsScreen(
                state = inference,
                onSelect = { id ->
                    scope.launch {
                        container.inferenceController.selectModel(id)
                        // The figures belong to the model, and the panel is not composed to notice.
                        container.refreshSensitivityIfStale()
                    }
                },
                onOpen = { id -> navController.navigate("models/$id") },
                onOpenAdapters = { id -> navController.navigate("models/$id/lora") },
                onDelete = { id -> scope.launch { container.removeModel(id) } },
            )
        }
        composable("models/{modelId}") { entry ->
            val modelId = entry.arguments?.getString("modelId") ?: return@composable
            val scope = rememberCoroutineScope()
            val inference by container.inferenceState.collectAsState(InferenceState())
            var accuracy by remember(modelId) { mutableStateOf<ModelMetrics?>(null) }
            var loading by remember(modelId) { mutableStateOf(true) }
            var reloadTick by remember(modelId) { mutableStateOf(0) }
            LaunchedEffect(modelId, reloadTick) {
                loading = true
                accuracy = runCatching { container.modelMetrics(modelId) }.getOrNull()
                loading = false
            }
            // Building it classifies 25 600 cells across the FFI seam; null until it lands.
            var lattices by remember { mutableStateOf<ErrorGridLattices?>(null) }
            LaunchedEffect(Unit) { lattices = container.errorGridLattices() }
            // §6.3: zeroing tick on reload cancels the walk; clearing the result is not enough.
            var cgEga by remember(modelId) { mutableStateOf<CgEga?>(null) }
            var cgEgaLoading by remember(modelId) { mutableStateOf(false) }
            var cgEgaTick by remember(modelId) { mutableStateOf(0) }
            LaunchedEffect(modelId, reloadTick) { cgEga = null; cgEgaLoading = false }
            LaunchedEffect(modelId, cgEgaTick) {
                if (cgEgaTick == 0) return@LaunchedEffect
                cgEgaLoading = true
                val walked = runCatching { container.modelCgEga(modelId) }
                // Uninterruptible: runCatching swallows cancellation; a superseded pass can resume.
                if (!isActive) return@LaunchedEffect
                cgEga = walked.getOrNull()
                cgEgaLoading = false
            }
            // §8.4: last outcome is local so reopen does not re-announce a fit already read.
            val backtests by container.backtests.collectAsState()
            val bandCalibrations by container.bandCalibrations.collectAsState()
            val bandCalibration: BandCalibration? = bandCalibrations[modelId]
            var fitOutcome by remember(modelId) { mutableStateOf<BandCalibrationOutcome?>(null) }
            var fitting by remember(modelId) { mutableStateOf(false) }
            var fitTick by remember(modelId) { mutableStateOf(0) }
            LaunchedEffect(modelId, fitTick) {
                if (fitTick == 0) return@LaunchedEffect
                fitting = true
                val outcome = runCatching { container.fitBandCalibration(modelId) }
                // Uninterruptible: a cancelled fit resumes here, must not overwrite its successor.
                if (!isActive) return@LaunchedEffect
                fitOutcome = outcome.getOrNull()
                fitting = false
            }
            ModelDetailScreen(
                state = inference,
                modelId = modelId,
                accuracy = accuracy,
                accuracyLoading = loading,
                // Zeroing the tick is a key change, which cancels a walk still running.
                onRecomputeAccuracy = { reloadTick++; cgEgaTick = 0 },
                lattices = lattices,
                trendBinEdges = container.trendBinEdges,
                cgEga = cgEga,
                cgEgaLoading = cgEgaLoading,
                onComputeCgEga = { cgEgaTick++ },
                bandCalibration = bandCalibration,
                bandCalibrationFitting = fitting,
                bandCalibrationOutcome = fitOutcome,
                onFitBandCalibration = { if (!fitting) fitTick++ },
                onDropBandCalibration = { scope.launch { container.dropBandCalibration(modelId) } },
                backtest = backtests[modelId],
                onBacktest = { days -> container.startBacktest(modelId, days) },
                onCancelBacktest = { container.cancelBacktest(modelId) },
            )
        }
        composable("meals") {
            val iobCob by container.iobCob.collectAsState()
            val sensitivity = rememberSensitivity(container)
            val glucoseUnit by container.statsRepository.unitSpace.collectAsState(UnitSpace.MgDl)
            val recent by container.recentMeals.collectAsState(emptyList())

            MealsScreen(
                iobCob = iobCob,
                sensitivity = sensitivity,
                unit = glucoseUnit,
                recentMeals = recent,
                previewCurve = container.previewCarbCurve,
                onLogMeal = { grams, gi, note ->
                    container.appScope.launch { onLogged(container.logCarb(grams, gi, note)) }
                },
            ) {
                // Inside the screen's own scroll column; wrapped around it, measures zero height.
                TextButton(onClick = { navHaptics.perform(HapticEvent.NavSwitch); navController.navigate("meals/builder") }) {
                    Text("Meal builder →")
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
                onLogMeal = { comps -> container.appScope.launch { onLogged(container.logBuilderMeal(comps)) } },
                onSaveMeal = { name, comps -> scope.launch { container.mealsController.saveMeal(name, comps) } },
                onSaveFood = { food -> scope.launch { container.mealsController.saveCustomFood(food) } },
                onEditMeal = { id ->
                    navHaptics.perform(HapticEvent.NavSwitch)
                    navController.navigate("meals/builder/meal/$id")
                },
                onEditFood = { id ->
                    navHaptics.perform(HapticEvent.NavSwitch)
                    navController.navigate("meals/builder/food/$id")
                },
                onDeleteMeal = { id -> scope.launch { container.mealsController.deleteSavedMeal(id) } },
                onDeleteFood = { id -> scope.launch { container.mealsController.deleteCustomFood(id) } },
            )
        }
        composable("meals/builder/meal/{mealId}") { entry ->
            val mealId = entry.arguments?.getString("mealId")?.toLongOrNull() ?: return@composable
            // Null until list emits: popping the initial empty value makes route unreachable.
            val saved: List<SavedMeal>? by container.savedMeals.collectAsState(null)
            val meal = saved?.firstOrNull { it.id == mealId }
            val ascend = rememberSingleAscent(navController)
            // Deleted from under the editor: leave rather than hold a Save on a gone row.
            LaunchedEffect(saved, meal) { if (saved != null && meal == null) ascend() }
            if (meal == null) return@composable
            MealEditorScreen(
                meal = meal,
                onSearch = { q -> container.mealsController.searchFoods(q) },
                onResolve = { comps -> container.mealsController.resolvePreview(comps) },
                // The container's scope: the pop that follows would cancel this composition's.
                onSave = { name, comps ->
                    container.appScope.launch { container.mealsController.updateMeal(mealId, name, comps) }
                    ascend()
                },
                onSaveAsNew = { name, comps ->
                    container.appScope.launch { container.mealsController.saveMeal(name, comps) }
                    ascend()
                },
                onCancel = ascend,
            )
        }
        composable("meals/builder/food/{foodId}") { entry ->
            val foodId = entry.arguments?.getString("foodId")?.toLongOrNull() ?: return@composable
            // Only USER foods are listed here, so a miss means deleted.
            val custom: List<Food>? by container.customFoods.collectAsState(null)
            val food = custom?.firstOrNull { it.id == foodId }
            val ascend = rememberSingleAscent(navController)
            LaunchedEffect(custom, food) { if (custom != null && food == null) ascend() }
            if (food == null) return@composable
            FoodEditorScreen(
                food = food,
                onSave = { edited ->
                    container.appScope.launch { container.mealsController.updateCustomFood(edited) }
                    ascend()
                },
                onDelete = {
                    container.appScope.launch { container.mealsController.deleteCustomFood(foodId) }
                    ascend()
                },
                onCancel = ascend,
            )
        }
        composable("insulin") {
            val scope = rememberCoroutineScope()
            val iobCob by container.iobCob.collectAsState()
            val sensitivity = rememberSensitivity(container)
            val glucoseUnit by container.statsRepository.unitSpace.collectAsState(UnitSpace.MgDl)
            // Read once: panel owns selection here; re-seeding mid-entry moves the chip.
            val presetCatalog by produceState(emptyList<InsulinPresetSpec>()) { value = container.insulinPresetCatalog() }
            val rapidLabel by produceState<String?>(null) { value = container.resolvedRapidLabel() }
            val basalLabel by produceState<String?>(null) { value = container.resolvedBasalLabel() }
            InsulinScreen(
                iobCob = iobCob,
                sensitivity = sensitivity,
                unit = glucoseUnit,
                presetCatalog = presetCatalog,
                initialRapidLabel = rapidLabel,
                initialBasalLabel = basalLabel,
                previewCurve = container.previewDoseCurve,
                onLogBolus = { units, label -> container.appScope.launch { onLogged(container.logBolus(units, label)) } },
                onLogBasal = { units, label -> container.appScope.launch { onLogged(container.logBasal(units, label)) } },
            ) {
                TextButton(onClick = { navHaptics.perform(HapticEvent.NavSwitch); navController.navigate("insulin/types") }) {
                    Text("Insulin types & curves →")
                }
                TextButton(onClick = { navHaptics.perform(HapticEvent.NavSwitch); navController.navigate("insulin/bolusCalc") }) {
                    Text("Bolus advisor →")
                }
            }
        }
        composable("insulin/bolusCalc") {
            val scope = rememberCoroutineScope()
            val ctx = LocalContext.current
            val ss = container.settingsStore
            val ui by container.bolusAdvice.collectAsState()
            val targetLow by ss.calcTargetLow.collectAsState(70.0)
            val targetHigh by ss.calcTargetHigh.collectAsState(180.0)
            val targetMid by ss.calcTargetMid.collectAsState(110.0)
            val objective by ss.calcObjective.collectAsState(SettingsStore.OBJ_KOVATCHEV)
            val insulinLabel by produceState<String?>(null) { value = container.resolvedRapidLabel() }
            val ready = ui as? BolusAdviceUi.Ready
            val lastCurveWrite by container.lastCurveWriteMs.collectAsState()
            val computedAt = ready?.computedAtMs
            // Keyed: a new result must not be judged against the previous result's clock.
            var nowMs by remember(computedAt) { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(computedAt) {
                if (computedAt == null) return@LaunchedEffect
                val left = computedAt + AdviceGate.TTL_MS - System.currentTimeMillis()
                if (left > 0L) delay(left)
                nowMs = System.currentTimeMillis()
            }
            BolusCalculatorScreen(
                result = ready?.result,
                targetLowMgdl = targetLow,
                targetHighMgdl = targetHigh,
                initialTargetMgdl = targetMid,
                resultTargetMgdl = ready?.targetMgdl,
                objectiveLabel = (CALC_OBJECTIVES.firstOrNull { it.first == objective } ?: CALC_OBJECTIVES.first()).second,
                stale = ready?.let { AdviceGate.staleness(it.computedAtMs, lastCurveWrite, nowMs) },
                isComputing = ui is BolusAdviceUi.Running,
                insulinLabel = insulinLabel,
                onAccept = { c ->
                    val now = System.currentTimeMillis()
                    val current = container.bolusAdvice.value as? BolusAdviceUi.Ready
                    if (current == null || current !== ready ||
                        !AdviceGate.fresh(current.computedAtMs, container.lastCurveWriteMs.value, now)
                    ) {
                        nowMs = now
                    } else {
                        scope.launch {
                            // A 0 U / carb-rescue acceptance writes no dose, so there is no handle.
                            container.acceptAdvisedBolus(c.doseU)?.let { h ->
                                onLogged(
                                    h.copy(
                                        caveats = h.caveats +
                                            "Recommendation cleared — recompute to see it again",
                                    ),
                                )
                            }
                        }
                        // Clears `bolusAdvice` back to Idle; an Undo cannot bring the card back.
                        DoseCalcService.cancel(ctx)
                    }
                },
                onRecompute = { target -> DoseCalcService.recommend(ctx, targetMgdl = target) },
            )
        }
        composable("insulin/types") {
            val scope = rememberCoroutineScope()
            val types by container.insulinTypes.collectAsState(emptyList())
            InsulinTypeBuilderScreen(
                types = types,
                onResolve = { type, units -> container.insulinController.resolvePreview(type, units) },
                maxActionMin = ChannelBuilder.PAD_MIN,
                onSaveType = { type -> scope.launch { container.insulinController.saveCustomType(type) } },
                onDeleteType = { id -> scope.launch { container.insulinController.deleteCustomType(id) } },
                onLogDose = { type, units -> container.appScope.launch { onLogged(container.logTypedDose(type, units)) } },
            )
        }
        composable("exercise") {
            val ctx = LocalContext.current
            val sessions by container.exercise.sessions.collectAsState(emptyList())
            val active by container.exercise.active.collectAsState()
            val bodyMassKg by container.exercise.bodyMassKg.collectAsState(null)
            val degraded by container.exerciseDegraded.collectAsState()
            // SAVEABLE: a low-memory kill can land on the permission dialog with no configChanges.
            var pendingKind by rememberSaveable { mutableStateOf<ExerciseKind?>(null) }
            val locationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                val kind = pendingKind
                pendingKind = null
                when {
                    kind == null -> Unit
                    // Either grant opens a bout: seconds are recorded whatever the receiver does.
                    grants.values.any { it } ->
                        container.appScope.launch { container.exercise.start(kind) }
                    // Permanent denial shows no system dialog; this is the only feedback there is.
                    else -> container.exerciseRefusal.value = ExerciseService.NO_PERMISSION
                }
            }
            ExerciseScreen(
                sessions = sessions,
                active = active,
                degraded = degraded,
                bodyMassKg = bodyMassKg,
                onSetBodyMassKg = { kg -> container.appScope.launch { container.exercise.setBodyMassKg(kg) } },
                onStart = { kind ->
                    // A refusal must not outlive the attempt that produced it.
                    container.exerciseRefusal.value = null
                    // FINE specifically: coarse fixes fuzz to ~2 km, and the bucketer refuses each.
                    if (ExerciseService.hasPreciseLocation(ctx)) {
                        container.appScope.launch { container.exercise.start(kind) }
                    } else {
                        pendingKind = kind
                        locationLauncher.launch(ExerciseService.LOCATION_PERMISSIONS)
                    }
                },
                onStop = { container.appScope.launch { container.exercise.stop() } },
                onOpen = { id ->
                    navHaptics.perform(HapticEvent.NavSwitch)
                    navController.navigate("exercise/$id")
                },
                onDelete = { id -> container.appScope.launch { container.exercise.delete(id) } },
                previewExerciseCurve = container.previewExerciseCurve,
                // Container scope: write and its re-forecast must survive leaving the panel.
                onReplay = { session, startMs ->
                    container.appScope.launch { container.replayExercise(session, startMs) }
                },
            )
        }
        composable("exercise/{sessionId}") { entry ->
            val id = entry.arguments?.getString("sessionId")?.toLongOrNull() ?: return@composable
            val unit by container.statsRepository.unitSpace.collectAsState(UnitSpace.MgDl)
            val range by container.graphRange.collectAsState(com.t1dm.data.settings.BgRange.DEFAULT)
            // Keyed on the id, not Unit: the list this is reached from re-sorts under it.
            val session by produceState<ExerciseSession?>(null, id) {
                value = container.exercise.session(id)
            }
            val track by produceState(emptyList<TrackPoint>(), id) {
                value = container.exercise.track(id)
            }
            // Once per open: an unfinished bout's window must not move on every recomposition.
            val openedAtMs = remember(id) { System.currentTimeMillis() }
            // The window the SCREEN draws, resolved once so load and viewport cannot disagree.
            val window = session?.let { reviewWindow(it, openedAtMs) }
            val frame by produceState(GraphFrame.EMPTY, window, unit) {
                val w = window
                value = if (w == null) GraphFrame.EMPTY
                else graphFrameOf(
                    container.sessionReadings(w.first, w.last),
                    unit,
                    kovatchevFClinicalBatch = container.nativeCore::kovatchevFClinicalBatch,
                )
            }
            // Not the live Logs feed: bounded at a few hundred rows, empty for an old bout.
            val sessionLogs by produceState(emptyList<LoggedEntry>(), window) {
                val w = window ?: return@produceState
                container.loggedEntriesIn(w.first, w.last)
                    .catch { emit(emptyList()) }
                    .collect { value = it }
            }
            val insulins by container.insulinChoices.collectAsState(emptyList())
            ExerciseSessionScreen(
                session = session,
                gridMs = T1dmRepository.GRID_MS,
                nowMs = openedAtMs,
                track = track,
                logEntries = sessionLogs,
                insulins = insulins,
                onEditLog = { entry, edit ->
                    container.appScope.launch { container.applyLogEdit(entry, edit) }
                },
                onDeleteLog = { entry ->
                    container.appScope.launch { container.deleteLoggedEntry(entry) }
                },
                frame = frame,
                unit = unit,
                kovatchevF = container.nativeCore::kovatchevF,
                thresholds = container.alarmConfig.thresholds,
                rangeMinMgdl = range.minMgdl,
                rangeMaxMgdl = range.maxMgdl,
            )
        }
        composable("cgm") {
            val cgm by container.cgmPanel.collectAsState()
            var provisioningId by remember { mutableStateOf<String?>(null) }
            CgmScreen(
                state = cgm,
                onMakeAuthoritative = { id -> container.makeAuthoritativeCgm(id) },
                onStartReading = { id -> container.activateCgm(id) },
                onStopReading = { id -> container.deactivateCgm(id) },
                onRemove = { id -> container.hideCgm(id) },
                onActivate = { id -> container.activateCgmSensor(id) },
                onBind = { id -> container.bindCgmSensor(id) },
                onScan = { container.rescanCgm() },
                onReconnect = { id -> container.reconnectCgm(id) },
                onFetchHistory = { id -> container.fetchCgmHistory(id) },
                onRecoverKey = { id -> container.recoverCgmKey(id) },
                onRepairHistory = { id -> container.repairCgmHistory(id) },
                onProvision = { id -> provisioningId = id },
                onOpenLog = { id -> navController.navigate("cgm/log/${android.net.Uri.encode(id)}") },
            )
            provisioningId?.let { id ->
                val row = cgm.sensors.firstOrNull { it.id == id }
                Libre3Provision(container, name = row?.name ?: id, onDismiss = { provisioningId = null })
            }
        }
        composable("cgm/log/{sensorId}") { entry ->
            val id = entry.arguments?.getString("sensorId") ?: return@composable
            val cgm by container.cgmPanel.collectAsState()
            val row = cgm.sensors.firstOrNull { it.id == id }
            val name = row?.name ?: id
            // At most ~3 folds a second however fast a handshake or a pull writes.
            val lines by produceState<List<com.t1dm.core.model.CgmLogEntry>?>(null, id) {
                container.cgmLog(id).conflate().collect {
                    value = it
                    delay(CGM_LOG_REFRESH_MS)
                }
            }
            val scope = rememberCoroutineScope()
            val fontSp by container.settingsStore.cgmLogFontSp.collectAsState(com.t1dm.feature.cgm.CGM_LOG_FONT_SP_DEFAULT)
            var saveNote by remember { mutableStateOf<String?>(null) }
            val savePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/plain"),
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                scope.launch { saveNote = if (container.exportCgmLog(id, name, uri)) "saved" else "save failed" }
            }
            val save = {
                savePicker.launch(cgmLogFileName(id, System.currentTimeMillis(), java.time.ZoneId.systemDefault()))
            }
            var provisioning by remember { mutableStateOf(false) }
            val consoleHost = remember(id) {
                com.t1dm.app.cgm.AppCgmConsoleHost(container, id, onSave = save, onProvision = { provisioning = true })
            }
            CgmLogScreen(
                name = name,
                entries = lines,
                onSave = save,
                saveNote = saveNote,
                fontSp = fontSp,
                onFontSp = { sp -> scope.launch { container.settingsStore.setCgmLogFontSp(sp) } },
                sensor = row,
                panel = cgm,
                console = consoleHost,
                onExit = {
                    if (!navController.popBackStack("cgm", inclusive = false)) {
                        navController.navigate("cgm") { launchSingleTop = true }
                    }
                },
            )
            if (provisioning) Libre3Provision(container, name, onDismiss = { provisioning = false })
        }
        composable("security") {
            val devices by container.watchDevices.collectAsState()
            val pairing by container.watchPairing.collectAsState()
            SecurityScreen(
                state = SecurityPanelState(
                    devices = devices.map { it.toPanelDevice() },
                    pairing = pairing?.toPanelDevice(),
                ),
                onPair = container::pairWatch,
                onCancelPairing = container::cancelWatchPairing,
                onConfirmSas = container::confirmWatchSas,
                onRotate = { container.rotateWatchKeys(it) },
                onUnpair = { container.unpairWatch(it) },
            )
        }
        composable("settings") {
            val inf by container.inferenceState.collectAsState(InferenceState())
            val scope = rememberCoroutineScope()
            val recentSearches by container.settingsStore.recentSearches.collectAsState(emptyList())
            SettingsScreen(
                onOpenDisplay = { navController.navigate("settings/display") },
                onOpenGraph = { navController.navigate("settings/graph") },
                onOpenGames = { navController.navigate("settings/games") },
                onOpenAlarmThresholds = { navController.navigate("settings/alarms") },
                onOpenSignalSafety = { navController.navigate("settings/signal") },
                onOpenAlerts = { navController.navigate("settings/alerts") },
                onOpenForecast = { navController.navigate("settings/forecast") },
                onOpenCalculator = { navController.navigate("settings/calculator") },
                onOpenCurveParams = { navController.navigate("settings/curves") },
                onOpenModels = { navController.navigate("models") },
                onOpenCgm = { navController.navigate("settings/cgm") },
                onOpenNightscout = { navController.navigate("settings/nightscout") },
                onOpenWatch = { navController.navigate("settings/watch") },
                onOpenPower = { navController.navigate("settings/power") },
                onOpenBackground = { navController.navigate("settings/background") },
                onOpenData = { navController.navigate("settings/data") },
                onOpenAbout = { navController.navigate("about") },
                onOpenDeath = { navController.navigate("settings/death") },
                onOpenDeviceTemp = { navController.navigate("settings/temperature") },
                onOpenDeathClock = { navController.navigate("settings/deathclock") },
                recentSearches = recentSearches,
                onOpenKnob = { knob ->
                    // Before navigating: the destination reads the anchor on its first composition.
                    settingsFocus.request(knob.id.takeIf { knob.anchored })
                    navController.navigate(settingsRouteFor(knob.screen))
                },
                onRecordSearch = { q -> scope.launch { container.settingsStore.pushRecentSearch(q) } },
                onClearRecentSearches = { scope.launch { container.settingsStore.clearRecentSearches() } },
                deathModeSupported = DeathFlavor.SUPPORTED,
            )
        }
        composable("settings/death") {
            val scope = rememberCoroutineScope()
            // Seeded from the hot snapshot; an already-engaged DEATH mode skips WARNING first.
            val death by container.deathMode.collectAsState(container.deathModeSnapshot)
            DeathModeScreen(
                active = death,
                onActivate = { scope.launch { container.setDeathMode(true) } },
                onDeactivate = { scope.launch { container.setDeathMode(false) } },
            )
        }
        composable("about") {
            AboutScreen(info = container.aboutInfo())
        }
        composable("settings/graph") {
            val scope = rememberCoroutineScope()
            val range by container.graphRange.collectAsState(com.t1dm.data.settings.BgRange.DEFAULT)
            val windowHours by container.graphWindowHours.collectAsState(GraphSettingsStore.DEFAULT_WINDOW_HOURS)
            val savgolWindow by container.savgolWindow.collectAsState(SettingsStore.DEFAULT_SAVGOL_WINDOW)
            val smoothingPreview by container.smoothingPreviewMgdl.collectAsState(DoubleArray(0))
            GraphSettingsScreen(
                minMgdl = range.minMgdl,
                maxMgdl = range.maxMgdl,
                windowHours = windowHours,
                windowPresets = GraphSettingsStore.WINDOW_PRESETS,
                onChange = { min, max -> scope.launch { container.setGraphRange(min, max) } },
                onSetWindow = { h -> scope.launch { container.setGraphWindowHours(h) } },
                smoothingWindow = savgolWindow,
                smoothingStops = SettingsStore.SAVGOL_STOPS,
                onSetSmoothing = { w -> scope.launch { container.setSmoothingWindow(w) } },
                smoothingPreviewMgdl = smoothingPreview,
                smoothMgdl = { arr, w -> container.nativeCore.causalSmooth(arr.toList(), 20.0, 500.0, w).toDoubleArray() },
            )
        }
        composable("settings/display") {
            val scope = rememberCoroutineScope()
            val ctx = LocalContext.current
            val statsState by container.statsViewModel.state.collectAsState()
            val ss = container.settingsStore
            val animations by ss.animationsEnabled.collectAsState(true)
            val volumeNav by ss.volumeNavEnabled.collectAsState(true)
            val showSensorNames by ss.showSensorNames.collectAsState(SettingsStore.DEFAULT_SHOW_SENSOR_NAMES)
            val themeId by ss.themeId.collectAsState(SettingsStore.DEFAULT_THEME)
            val fontId by ss.fontId.collectAsState(SettingsStore.DEFAULT_FONT)
            val tempUnit by container.temperatureUnit.collectAsState(com.t1dm.core.model.TempUnit.CELSIUS)
            val customJson by ss.customThemeJson.collectAsState(null)
            val bgAlphaPct by ss.backgroundAlphaPct.collectAsState(com.t1dm.app.settings.SettingsStore.DEFAULT_BG_ALPHA_PCT)
            val hapticsKey by ss.hapticsLevel.collectAsState(SettingsStore.DEFAULT_HAPTICS)
            // preview takes strength explicitly so the tap is felt before the kv round-trips.
            val haptics = LocalT1dmHaptics.current
            var importStatus by remember { mutableStateOf<String?>(null) }
            val customName = remember(customJson) {
                customJson?.takeIf { it.isNotBlank() }?.let {
                    runCatching { parseThemeJson(it).displayName }.getOrNull()
                }
            }
            val themeOptions = remember {
                BundledPalettes.map { it.id to it.displayName } + (ThemeIds.CUSTOM to "Custom")
            }
            val fontOptions = remember { T1dmFontId.entries.map { it.storageKey to it.displayName } }
            val hapticOptions = remember { HapticStrength.entries.map { it.name to it.displayName } }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri == null) { importStatus = "Import cancelled" } else scope.launch {
                    importStatus = runCatching {
                        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                            ?: error("could not open file")
                        val palette = parseThemeJson(text) // throws a plain-language message
                        ss.setCustomThemeJson(text)
                        ss.setThemeId(ThemeIds.CUSTOM)
                        "Loaded theme \"${palette.displayName}\""
                    }.getOrElse { it.message ?: "Import failed" }
                }
            }
            DisplaySettingsScreen(
                unitSpace = statsState.unitSpace,
                targetLow = statsState.targetRange.lowMgdl,
                targetHigh = statsState.targetRange.highMgdl,
                animationsEnabled = animations,
                volumeNavEnabled = volumeNav,
                showSensorNames = showSensorNames,
                backgroundAlphaPct = bgAlphaPct,
                themeOptions = themeOptions,
                selectedThemeId = themeId,
                fontOptions = fontOptions,
                selectedFontId = fontId,
                customThemeName = customName,
                importStatus = importStatus,
                temperatureUnit = tempUnit,
                hapticOptions = hapticOptions,
                selectedHaptic = hapticsKey,
                onSetUnitSpace = { container.setUnitSpace(it) },
                onSetTargetRange = { lo, hi -> container.statsViewModel.setTargetRange(lo, hi) },
                onSetAnimationsEnabled = { on -> scope.launch { ss.setAnimationsEnabled(on) } },
                onSetVolumeNavEnabled = { on -> scope.launch { ss.setVolumeNavEnabled(on) } },
                onSetShowSensorNames = { on -> scope.launch { ss.setShowSensorNames(on) } },
                onSetBackgroundAlpha = { pct -> scope.launch { ss.setBackgroundAlphaPct(pct) } },
                onSelectTheme = { id -> scope.launch { ss.setThemeId(id) } },
                onSelectFont = { id -> scope.launch { ss.setFontId(id) } },
                onImportCustomTheme = { importStatus = null; importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onSetTemperatureUnit = { u -> scope.launch { container.setTemperatureUnit(u) } },
                onSelectHaptic = { n -> scope.launch { ss.setHapticsLevel(HapticStrength.forKey(n)) } },
                onPreviewHaptic = { n -> haptics.preview(HapticStrength.forKey(n), HapticEvent.Confirm) },
                widgetPinSupported = com.t1dm.app.widget.WidgetPinner.isSupported(ctx),
                widgetPinActions = com.t1dm.app.widget.WidgetPinner.Widget.entries.map { w ->
                    w.label to { com.t1dm.app.widget.WidgetPinner.request(ctx, w); Unit }
                },
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
            val weakOn by container.settingsStore.weakSignalEnabled.collectAsState(true)
            val weakDbm by container.settingsStore.weakSignalDbm.collectAsState(-90)
            val weakSustain by container.settingsStore.weakSignalSustainMin.collectAsState(3)
            SignalSafetyScreen(
                lossMin = lossMin,
                lossEscalatedMin = lossEsc,
                weakSignalEnabled = weakOn,
                weakSignalDbm = weakDbm,
                weakSignalSustainMin = weakSustain,
                onSetLoss = { a, b -> scope.launch { container.saveLossWindows(a, b) } },
                onSetWeakSignal = { e, d, s -> scope.launch { container.saveWeakSignal(e, d, s) } },
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
            val minActuation by container.settingsStore.minActuationMin.collectAsState(5)
            val snoozeMin by container.snoozeMin.collectAsState(SettingsStore.DEFAULT_SNOOZE_MIN)
            AlertsSettingsScreen(
                vibrationOptions = VibrationPreset.entries.map { it.name },
                warningVibration = warnVib,
                criticalVibration = critVib,
                warningSoundOn = warnSound,
                criticalSoundOn = critSound,
                bypassDnd = bypass,
                repeatCadenceMin = cadence,
                minActuationMin = minActuation,
                snoozeMin = snoozeMin,
                onSetWarningVibration = { n -> scope.launch { container.saveWarningVibration(VibrationPreset.valueOf(n)) } },
                onSetCriticalVibration = { n -> scope.launch { container.saveCriticalVibration(VibrationPreset.valueOf(n)) } },
                onSetWarningSoundOn = { on -> scope.launch { container.saveWarningSoundOn(on) } },
                onSetCriticalSoundOn = { on -> scope.launch { container.saveCriticalSoundOn(on) } },
                onSetBypassDnd = { on -> scope.launch { container.saveBypassDnd(on) } },
                onSetRepeatCadence = { m -> scope.launch { container.saveRepeatCadence(m) } },
                onSetMinActuation = { m -> scope.launch { container.saveMinActuationMin(m) } },
                onSetSnoozeMin = { m -> scope.launch { container.setSnoozeMin(m) } },
                onPreviewVibration = { n -> container.previewVibration(n) },
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
            val rPred by ss.railPredictedLow.collectAsState(true)
            val rIob by ss.railIobCeiling.collectAsState(true)
            val rConfirm by ss.railConfirm.collectAsState(true)
            val rHypo by ss.railHypoTreatment.collectAsState(true)
            CalculatorSettingsScreen(
                objectiveOptions = CALC_OBJECTIVES,
                objective = objective,
                targetLow = tLow, targetHigh = tHigh, targetMid = tMid,
                hypoWeight = hypoW, hyperWeight = hyperW,
                predictedLow = predLow, iobCeiling = iobCeil,
                gridMaxU = gridMax, gridStepU = gridStep,
                railPredictedLow = rPred, railIobCeiling = rIob,
                railConfirm = rConfirm, railHypoTreatment = rHypo,
                onSetObjective = { k -> scope.launch { ss.setCalcObjective(k) } },
                onSetTarget = { lo, hi, mid -> scope.launch { ss.setCalcTarget(lo, hi, mid) } },
                onSetAsymmetry = { hypo, hyper -> scope.launch { ss.setCalcAsymmetry(hypo, hyper) } },
                onSetPredictedLow = { v -> scope.launch { ss.setCalcPredictedLow(v) } },
                onSetIobCeiling = { v -> scope.launch { ss.setCalcIobCeiling(v) } },
                onSetGrid = { mx, st -> scope.launch { ss.setCalcGrid(mx, st) } },
                onSetRailPredictedLow = { on -> scope.launch { ss.setRail(SettingsStore.RAIL_PREDICTED_LOW, on) } },
                onSetRailIobCeiling = { on -> scope.launch { ss.setRail(SettingsStore.RAIL_IOB, on) } },
                onSetRailConfirm = { on -> scope.launch { ss.setRail(SettingsStore.RAIL_CONFIRM, on) } },
                onSetRailHypoTreatment = { on -> scope.launch { ss.setRail(SettingsStore.RAIL_HYPO, on) } },
            )
        }
        composable("settings/curves") {
            val scope = rememberCoroutineScope()
            val hi = CurveEngine.Presets.carbGammaForGi(100.0)
            val lo = CurveEngine.Presets.carbGammaForGi(0.0)
            val carbEnc by container.settingsStore.carbBezier.collectAsState(null)
            val insEnc by container.settingsStore.insulinBezier.collectAsState(null)
            val carbCurve = remember(carbEnc) { BezierCurve.decode(carbEnc) ?: BezierCurve.default(180.0) }
            val insulinCurve = remember(insEnc) { BezierCurve.decode(insEnc) ?: BezierCurve.default(300.0) }
            val carbEquiv by container.settingsStore.carbEquivPerMin
                .collectAsState(ExerciseDisposal.DEFAULT_CARB_EQUIV_PER_MIN)
            val insulinPresets by produceState(emptyList<InsulinPresetSpec>()) { value = container.insulinPresetCatalog() }
            CurveParamsScreen(
                params = CurveParams(
                    insulinPresets = insulinPresets,
                    carbHighGiK = hi.first, carbHighGiTheta = hi.second,
                    carbLowGiK = lo.first, carbLowGiTheta = lo.second,
                    exerciseK = ExerciseDisposal.K, exerciseTheta = ExerciseDisposal.THETA,
                ),
                carbCurve = carbCurve,
                insulinCurve = insulinCurve,
                onSaveCarbCurve = { c -> scope.launch { container.settingsStore.setCarbBezier(BezierCurve.encode(c)) } },
                onSaveInsulinCurve = { c -> scope.launch { container.settingsStore.setInsulinBezier(BezierCurve.encode(c)) } },
                exerciseCarbEquivPerMin = carbEquiv,
                exerciseCarbEquivRange =
                    ExerciseDisposal.MIN_CARB_EQUIV_PER_MIN..ExerciseDisposal.MAX_CARB_EQUIV_PER_MIN,
                onSetExerciseCarbEquivPerMin = { g ->
                    scope.launch { container.settingsStore.setCarbEquivPerMin(g) }
                },
            )
        }
        composable("settings/games") {
            val scope = rememberCoroutineScope()
            val props by container.settingsStore.gameProps.collectAsState(GamePropDensity.Sparse)
            GameSettingsScreen(
                props = props,
                onSetProps = { d -> scope.launch { container.settingsStore.setGameProps(d) } },
            )
        }
        composable("settings/background") {
            val context = LocalContext.current
            var access by remember { mutableStateOf(BackgroundControls.read(context)) }
            // Every fix happens on a system page; the state is re-read on the way back.
            LifecycleResumeEffect(Unit) {
                access = BackgroundControls.read(context)
                onPauseOrDispose {}
            }
            BackgroundSettingsScreen(access = access, onFix = { BackgroundControls.open(context, it) })
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
            val resetting by container.resetting.collectAsState()
            DataSettingsScreen(
                resetting = resetting,
                onOpenBackup = { navController.navigate("backup") { launchSingleTop = true } },
                onReset = container::eraseAllAndRestart,
            )
        }
        composable("backup") {
            BackupRoute(container, onNotice)
        }
        composable("settings/watch") {
            val devices by container.watchDevices.collectAsState()
            WatchSettingsScreen(
                devices = devices.map { (it.deviceName ?: "—") to it.phase.name.lowercase().replace('_', ' ') },
                onOpenSecurity = { navController.navigate("security") },
            )
        }
        composable("settings/forecast") {
            val scope = rememberCoroutineScope()
            val ss = container.settingsStore
            val hours by container.warmupHoursSetting.collectAsState(24)
            val count by container.maxModelsSetting.collectAsState(5)
            val mode by ss.forecastMode.collectAsState(SettingsStore.FORECAST_MODE_ADAPTIVE)
            val period by ss.forecastPeriodMin.collectAsState(SettingsStore.DEFAULT_FORECAST_PERIOD_MIN)
            val logDebounce by ss.logReforecastDebounceS
                .collectAsState(SettingsStore.DEFAULT_LOG_REFORECAST_DEBOUNCE_S)
            val thermalOn by container.thermalGateEnabled.collectAsState(SettingsStore.DEFAULT_THERMAL_ON)
            val maxC by container.inferenceMaxTempC.collectAsState(SettingsStore.DEFAULT_MAX_TEMP_C)
            val warn by container.thermalWarnMarginC.collectAsState(SettingsStore.DEFAULT_WARN_MARGIN_C)
            ForecastSettingsScreen(
                warmupHoursValue = hours,
                onSetWarmupHours = { h -> scope.launch { container.setWarmupHours(h) } },
                modelCount = count,
                onSetModelCount = { n -> scope.launch { container.setMaxModels(n) } },
                adaptive = mode == SettingsStore.FORECAST_MODE_ADAPTIVE,
                periodMinutes = period,
                logDebounceSeconds = logDebounce,
                onSetAdaptive = { on ->
                    scope.launch {
                        ss.setForecastMode(if (on) SettingsStore.FORECAST_MODE_ADAPTIVE else SettingsStore.FORECAST_MODE_TIMED)
                    }
                },
                onSetPeriodMinutes = { m -> scope.launch { ss.setForecastPeriodMin(m) } },
                onSetLogDebounceSeconds = { sec -> scope.launch { ss.setLogReforecastDebounceS(sec) } },
                thermalOn = thermalOn,
                maxTempC = maxC,
                warnMarginC = warn,
                onSetThermalEnabled = { on -> scope.launch { container.setThermalGateEnabled(on) } },
                onSetMaxTempC = { c -> scope.launch { container.setInferenceMaxTempC(c) } },
                onSetWarnMarginC = { c -> scope.launch { container.setThermalWarnMarginC(c) } },
            )
        }
        composable("settings/temperature") {
            val scope = rememberCoroutineScope()
            val ss = container.settingsStore
            // One atomic config: each per-field edit re-saves the tuple with its siblings.
            val enabled by ss.overTempEnabled.collectAsState(SettingsStore.DEFAULT_OVERTEMP_ENABLED)
            val alertC by ss.overTempAlertC.collectAsState(SettingsStore.DEFAULT_OVERTEMP_ALERT_C)
            val clearC by ss.overTempClearC.collectAsState(SettingsStore.DEFAULT_OVERTEMP_CLEAR_C)
            val critical by ss.overTempCritical.collectAsState(SettingsStore.DEFAULT_OVERTEMP_CRITICAL)
            DeviceTempAlertScreen(
                enabled = enabled,
                alertC = alertC,
                clearC = clearC,
                critical = critical,
                onSetEnabled = { v -> scope.launch { container.saveOverTempConfig(v, alertC, clearC, critical) } },
                onSetAlertC = { v -> scope.launch { container.saveOverTempConfig(enabled, v, clearC, critical) } },
                onSetClearC = { v -> scope.launch { container.saveOverTempConfig(enabled, alertC, v, critical) } },
                onSetCritical = { v -> scope.launch { container.saveOverTempConfig(enabled, alertC, clearC, v) } },
            )
        }
        composable("settings/deathclock") {
            val scope = rememberCoroutineScope()
            val ss = container.settingsStore
            val dka by ss.dkaAfterIobZeroH.collectAsState(SettingsStore.DEFAULT_DKA_AFTER_IOB_ZERO_H)
            val coma by ss.comaAfterDkaH.collectAsState(SettingsStore.DEFAULT_COMA_AFTER_DKA_H)
            val death by ss.deathAfterComaH.collectAsState(SettingsStore.DEFAULT_DEATH_AFTER_COMA_H)
            DeathClockSettingsScreen(
                dkaAfterIobZeroH = dka,
                comaAfterDkaH = coma,
                deathAfterComaH = death,
                onSetDka = { h -> scope.launch { ss.setDkaAfterIobZeroH(h) } },
                onSetComa = { h -> scope.launch { ss.setComaAfterDkaH(h) } },
                onSetDeath = { h -> scope.launch { ss.setDeathAfterComaH(h) } },
            )
        }
        composable("settings/nightscout") {
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf<String?>(null) }
            val lastError by container.nightscoutError.collectAsState()
            // Read once: the field below is uncontrolled after first composition anyway.
            var initial by remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }
            LaunchedEffect(Unit) {
                initial = Triple(
                    container.nightscoutConfigStore.url() ?: "",
                    container.nightscoutConfigStore.hasSecret(),
                    container.nightscoutConfigStore.enabled(),
                )
            }
            val holdMin by container.pushHoldMin.collectAsState(SettingsStore.DEFAULT_PUSH_HOLD_MIN)
            val loaded = initial
            if (loaded != null) {
                NightscoutSettingsScreen(
                    initialUrl = loaded.first,
                    hasSecret = loaded.second,
                    initialEnabled = loaded.third,
                    busy = busy,
                    status = status,
                    lastError = lastError,
                    holdMin = holdMin,
                    holdMaxMin = SettingsStore.MAX_PUSH_HOLD_MIN,
                    onSave = { url, secret, enabled ->
                        scope.launch {
                            busy = true
                            status = container.saveNightscoutBridge(url, secret, enabled)
                            initial = Triple(url, loaded.second || secret.isNotBlank(), enabled)
                            busy = false
                        }
                    },
                    onTest = {
                        scope.launch {
                            busy = true
                            status = container.probeNightscout()
                            busy = false
                        }
                    },
                    onSetHoldMin = { m -> scope.launch { container.setPushHoldMin(m) } },
                )
            }
        }
        composable("settings/cgm") {
            // The label, not the descriptor: it obeys the sensor-name privacy setting.
            val activeLabel by container.authoritativeSourceLabel.collectAsState(null)
            val latest by container.latestReading.collectAsState(null)
            val scope = rememberCoroutineScope()
            val cgm by container.cgmPanel.collectAsState()
            CgmSettingsScreen(
                activeSourceName = activeLabel,
                activeStatus = activeLabel?.let { "active" },
                activeRssi = latest?.rssi,
                // Absent, not defaulted, while there is no authoritative sensor on record.
                warmupMin = cgm.sensors.firstOrNull { it.authoritative }?.warmupWindowMin,
                onSetWarmupMin = { m -> scope.launch { container.setSensorWarmupMin(m) } },
            )
        }
        composable("logs") {
            val scope = rememberCoroutineScope()
            var logPage by rememberSaveable { mutableIntStateOf(0) }
            val pageFeed = remember(logPage) {
                val start = logPage * LOG_PAGE_ROWS
                val end = start + LOG_PAGE_ROWS
                // One row past the page: its presence is what enables ›.
                container.loggedEntryFeed(end + 1).map { rows ->
                    val slice = rows.subList(minOf(start, rows.size), minOf(end, rows.size))
                    LogPage(start, slice, hasNext = rows.size > end)
                }
            }
            val page by pageFeed.collectAsState(EMPTY_LOG_PAGE)
            // Emptied by deletes: step back until a page holds rows.
            LaunchedEffect(page) {
                if (page.entries.isEmpty() && page.start > 0) logPage = page.start / LOG_PAGE_ROWS - 1
            }
            val mood by container.latestMood.collectAsState(null)
            val insulins by container.insulinChoices.collectAsState(emptyList())
            LogsScreen(
                entries = page.entries,
                currentMood = mood,
                // Container scope: leaving the panel mid-write must not cancel the push.
                onPickMood = { m -> container.appScope.launch { container.saveMood(m) } },
                // Says nothing on success: the row leaving the list is the feedback.
                onDelete = { entry ->
                    container.appScope.launch { container.deleteLoggedEntry(entry) }
                },
                onEdit = { entry, edit -> container.appScope.launch { container.applyLogEdit(entry, edit) } },
                insulins = insulins,
                firstRow = page.start,
                hasNext = page.hasNext,
                onPrev = { if (logPage > 0) logPage -= 1 },
                onNext = { logPage += 1 },
            )
        }
    }
    }
}

/** Writes nothing. Solver runs on t1dm-game, never t1dm-inference or a shared default worker. */
@Composable
private fun DashboardGamePanel(
    container: AppContainer,
    modifier: Modifier,
    kind: GameKind,
    trackFromMs: Long,
    dropAtMs: Long,
    spanMinutes: Float,
    // The panel's own clock: a second derivation would drift from the axis the chart just drew.
    predictedClock: PredictedClock?,
    onReady: () -> Unit,
    onExit: () -> Unit,
    // The route's live values: a collect started here reads its defaults when the scene is built.
    range: com.t1dm.data.settings.BgRange,
    paintStrokes: List<com.t1dm.core.model.PaintStroke>,
) {
    val glucoseUnit by container.statsRepository.unitSpace.collectAsState(UnitSpace.MgDl)
    val propDensity by container.gameProps.collectAsState()
    val alarm by container.alarmState.collectAsState()
    val predicted by container.predictiveAlertRaised.collectAsState()
    val death by container.deathMode.collectAsState(false)
    // Fetched from the FFI so the art uses the same numbers the solver was built with.
    val tuning by produceState<CarTuning?>(null, kind) {
        value = if (kind != GameKind.Drive) null else withContext(container.dispatchers.default) {
            runCatching { container.nativeCore.defaultCarTuning() }.getOrNull()
        }
    }
    val golfTuning by produceState<GolfTuning?>(null, kind) {
        value = if (kind != GameKind.Golf) null else withContext(container.dispatchers.default) {
            runCatching { container.nativeCore.defaultGolfTuning() }.getOrNull()
        }
    }
    // CRITICAL only; DEATH mode fail-opens §3.6 PRESENTATION except over-temperature.
    val alarmRaised = (
        alarm.alarms.any { it.severity == AlarmSeverity.CRITICAL } ||
            alarm.overTemperature != null ||
            predicted
        ) && (!death || alarm.overTemperature != null)
    when (kind) {
        GameKind.Drive -> GameScreen(
            modifier = modifier,
            trackFromMs = trackFromMs,
            dropAtMs = dropAtMs,
            thresholds = container.alarmConfig.thresholds,
            onReady = onReady,
            spanMinutes = spanMinutes,
            predictedClock = predictedClock,
            unit = glucoseUnit,
            kovatchevF = container.nativeCore::kovatchevF,
            rangeMinMgdl = range.minMgdl,
            rangeMaxMgdl = range.maxMgdl,
            paintStrokes = paintStrokes,
            carTuning = tuning,
            propDensity = propDensity,
            readingsFrom = container::gameReadings,
            openWorld = { terrain, t, o -> container.nativeCore.createGameWorld(terrain, t, o) },
            gameDispatcher = container.dispatchers.game,
            alarmRaised = alarmRaised,
            onExit = onExit,
        )
        GameKind.Golf -> GolfScreen(
            modifier = modifier,
            trackFromMs = trackFromMs,
            dropAtMs = dropAtMs,
            thresholds = container.alarmConfig.thresholds,
            onReady = onReady,
            spanMinutes = spanMinutes,
            predictedClock = predictedClock,
            unit = glucoseUnit,
            kovatchevF = container.nativeCore::kovatchevF,
            rangeMinMgdl = range.minMgdl,
            rangeMaxMgdl = range.maxMgdl,
            paintStrokes = paintStrokes,
            golfTuning = golfTuning,
            propDensity = propDensity,
            readingsFrom = container::gameReadings,
            openWorld = { terrain, t, o -> container.nativeCore.createGolfWorld(terrain, t, o) },
            gameDispatcher = container.dispatchers.game,
            alarmRaised = alarmRaised,
            onExit = onExit,
        )
    }
}
