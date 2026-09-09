package com.t1dm.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.t1dm.alerts.AlarmSeverity
import com.t1dm.alerts.VibrationPreset
import com.t1dm.app.backup.BackupRoute
import com.t1dm.app.di.AppContainer
import com.t1dm.app.di.AppContainer.BolusAdviceUi
import com.t1dm.app.di.LogHandle
import com.t1dm.app.di.logReceipt
import com.t1dm.app.di.undoReceipt
import com.t1dm.app.notify.BgFormat
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.app.service.DoseCalcService
import com.t1dm.app.service.ExerciseService
import com.t1dm.app.settings.SettingsStore
import com.t1dm.app.sync.SyncStatus
import com.t1dm.app.sync.toPanelState
import com.t1dm.app.widget.STALE_MIN
import com.t1dm.core.design.BundledPalettes
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.HapticStrength
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalDeathMode
import com.t1dm.core.design.LocalT1dmHaptics
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.CgmStatusIcon
import com.t1dm.core.design.SignalBars
import com.t1dm.core.design.T1dmFontId
import com.t1dm.core.design.T1dmHaptics
import com.t1dm.core.design.ThemeBackdrop
import com.t1dm.core.design.ThemeIds
import com.t1dm.core.design.TimeOfDayIcon
import com.t1dm.core.design.hapticClickable
import com.t1dm.core.design.navEnter
import com.t1dm.core.design.navExit
import com.t1dm.core.design.parseThemeJson
import com.t1dm.core.model.BandCalibration
import com.t1dm.core.model.BandCalibrationOutcome
import com.t1dm.core.model.BezierCurve
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.DkaTimeline
import com.t1dm.core.model.ErrorGridLattices
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import com.t1dm.core.model.Food
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.LogMarker
import com.t1dm.core.model.ModelMetrics
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.SavedMeal
import com.t1dm.core.model.SensitivityEstimate
import com.t1dm.core.model.TrackPoint
import com.t1dm.core.model.UnitSpace
import com.t1dm.data.T1dmRepository
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.curve.ExerciseDisposal
import com.t1dm.data.settings.GraphSettingsStore
import com.t1dm.feature.dashboard.CircadianScreen
import com.t1dm.feature.dashboard.DashboardScreen
import com.t1dm.feature.exercise.ExerciseScreen
import com.t1dm.feature.exercise.ExerciseSessionScreen
import com.t1dm.feature.exercise.reviewWindow
import com.t1dm.feature.game.GameScreen
import com.t1dm.feature.hardware.HardwareScreen
import com.t1dm.feature.insulin.BolusCalculatorScreen
import com.t1dm.feature.insulin.InsulinScreen
import com.t1dm.feature.insulin.InsulinTypeBuilderScreen
import com.t1dm.feature.logs.LogsScreen
import com.t1dm.feature.meals.FoodEditorScreen
import com.t1dm.feature.meals.MealBuilderScreen
import com.t1dm.feature.meals.MealEditorScreen
import com.t1dm.feature.meals.MealsScreen
import com.t1dm.feature.models.LabScreen
import com.t1dm.feature.models.LoraPanel
import com.t1dm.feature.models.ModelDetailScreen
import com.t1dm.feature.models.ModelsScreen
import com.t1dm.feature.network.NetworkScreen
import com.t1dm.feature.pubs.PubsScreen
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
import com.t1dm.feature.settings.DeathClockSettingsScreen
import com.t1dm.feature.settings.DeathModeScreen
import com.t1dm.feature.settings.DeviceTempAlertScreen
import com.t1dm.feature.settings.DisplaySettingsScreen
import com.t1dm.feature.settings.ForecastSettingsScreen
import com.t1dm.feature.settings.GraphSettingsScreen
import com.t1dm.feature.settings.LocalSettingsFocus
import com.t1dm.feature.settings.NightscoutSettingsScreen
import com.t1dm.feature.settings.PowerSettingsScreen
import com.t1dm.feature.settings.RecordedSource
import com.t1dm.feature.settings.ServerSettingsScreen
import com.t1dm.feature.settings.SettingsFocusController
import com.t1dm.feature.settings.SettingsScreen
import com.t1dm.feature.settings.SettingsScreenKey
import com.t1dm.feature.settings.SignalSafetyScreen
import com.t1dm.feature.settings.WatchSettingsScreen
import com.t1dm.feature.stats.StatsScreen
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.HindsightFrame
import com.t1dm.ui.graph.MaskControls
import com.t1dm.ui.graph.PredictedClock
import com.t1dm.ui.graph.graphFrameOf
import com.t1dm.ui.graph.hindsightFrameOf
import com.t1dm.watch.WatchSecurityState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps `:feature:security` free of a `:watch` dependency. */
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

/** The extension the server splits on; jpg by default, the camera-capture format. */
private fun photoExtFor(ctx: android.content.Context, uri: android.net.Uri): String =
    when (runCatching { ctx.contentResolver.getType(uri) }.getOrNull()) {
        "image/png" -> "png"
        else -> "jpg"
    }

internal data class Destination(val route: String, val label: String)

// In wheel order.
internal val destinations = listOf(
    Destination("dashboard", "BG"),
    Destination("pubs", "Pubs"),
    Destination("circadian", "Clock"),
    Destination("stats", "Stats"),
    Destination("models", "Models"),
    Destination("lab", "Lab"),
    Destination("hardware", "Hardware"),
    Destination("network", "Network"),
    Destination("meals", "Meals"),
    Destination("insulin", "Insulin"),
    Destination("exercise", "Exercise"),
    Destination("security", "Watch"),
    Destination("backup", "Backup"),
    Destination("logs", "Logs"),
    Destination("settings", "Settings"),
)

@Composable
fun T1dmApp(container: AppContainer) {
    val navController = rememberNavController()
    // The opaque base is painted here so the Scaffold can stay transparent and only the motif dims.
    val bgAlphaPct by container.settingsStore.backgroundAlphaPct
        .collectAsState(com.t1dm.app.settings.SettingsStore.DEFAULT_BG_ALPHA_PCT)
    // Hosted here (not per-route): a route scope cancels on exit and would drop the undo window.
    val snackbars = remember { SnackbarHostState() }
    val receiptScope = rememberCoroutineScope()
    val haptics = LocalT1dmHaptics.current
    VolumeKeyShortcuts(navController, container, haptics)
    // Hoisted: the hub sits in the `bottomBar` slot, the arc must draw over the content.
    val wheel = rememberNavWheelState(destinations.size)
    // Shared by the bar's hub and the overlay's; never read here, only inside draw lambdas.
    val wheelMotion = rememberNavWheelMotion()
    val onWheelSelect: (Int) -> Unit = remember(navController, haptics) {
        { index ->
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
            // imePadding on host: Scaffold/SDK36 skip IME resize; §3.7 inset covers content only.
            snackbarHost = { SnackbarHost(snackbars, Modifier.imePadding()) },
            // contentColorFor(Transparent) is Unspecified and collapses to black; pin the ink back.
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = { T1dmBottomBar(navController, container, wheel, wheelMotion, onWheelSelect) },
        ) { padding ->
            // Sole Scaffold-inset site (screens must not re-apply); avoids a doubled IME inset.
            Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                // Real text in the public build, a no-op in the personal one.
                Disclaimer(container)
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
        // Outside the Scaffold on purpose: the arc paints over the content.
        NavWheelArc(wheel, wheelMotion, destinations, onWheelSelect)
    }
}

/** The undo is partial: a push that already drained stays on the server (no DELETE in the API). */
private suspend fun SnackbarHostState.postLogReceipt(
    container: AppContainer,
    handle: LogHandle,
    haptics: T1dmHaptics,
) {
    haptics.perform(HapticEvent.Commit)
    // `showSnackbar` suspends until dismissed, and the undo window belongs to the newest write.
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

/** [route] non-null ⇒ tappable. */
internal data class Crumb(val label: String, val route: String?)

/** Most-recent last; tail (current screen) never tappable. Pure function of the route only. */
internal fun crumbsFor(route: String?, modelId: String?, editLabel: String? = null): List<Crumb> {
    fun settings(vararg tail: Crumb) = listOf(Crumb("Settings", "settings"), *tail)
    return when (route) {
        null, "dashboard" -> listOf(Crumb("BG", null))
        "pubs" -> listOf(Crumb("Pubs", null))
        "circadian" -> listOf(Crumb("Circadian clock", null))
        "stats" -> listOf(Crumb("Stats", null))
        "models" -> listOf(Crumb("Models", null))
        "models/{modelId}/lora" -> listOf(Crumb("Models", "models"), Crumb("Adapters", null))
        "models/{modelId}" -> listOf(Crumb("Models", "models"), Crumb(modelId ?: "model", null))
        "hardware" -> listOf(Crumb("Hardware", null))
        "network" -> listOf(Crumb("Network", null))
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
        "security" -> listOf(Crumb("Watch", null))
        "backup" -> listOf(Crumb("Backup", null))
        "logs" -> listOf(Crumb("Logs", null))
        "settings" -> listOf(Crumb("Settings", null))
        "about" -> settings(Crumb("About", null))
        "settings/display" -> settings(Crumb("Display", null))
        "settings/graph" -> settings(Crumb("Graph", null))
        "settings/alarms" -> settings(Crumb("Alarms", "settings"), Crumb("Thresholds", null))
        "settings/signal" -> settings(Crumb("Alarms", "settings"), Crumb("Signal", null))
        "settings/alerts" -> settings(Crumb("Sound", null))
        "settings/forecast" -> settings(Crumb("Forecast", null))
        "settings/temperature" -> settings(Crumb("Alarms", "settings"), Crumb("Device heat", null))
        "settings/deathclock" -> settings(Crumb("Death clock", null))
        "settings/calculator" -> settings(Crumb("Bolus calculator", null))
        "settings/curves" -> settings(Crumb("Curve & PK", null))
        "settings/cgm" -> settings(Crumb("CGM source", null))
        "settings/server" -> settings(Crumb("Server", null))
        "settings/nightscout" -> settings(Crumb("Nightscout", null))
        "settings/watch" -> settings(Crumb("Watch", null))
        "settings/power" -> settings(Crumb("Low power", null))
        "settings/data" -> settings(Crumb("Reset", null))
        "settings/death" -> settings(Crumb("Death mode", null))
        else -> listOf(Crumb(route, null))
    }
}

/** `:feature:settings` uses [SettingsScreenKey], never a route; test asserts both agree. */
internal fun settingsRouteFor(screen: SettingsScreenKey): String = when (screen) {
    SettingsScreenKey.ROOT -> "settings"
    SettingsScreenKey.DISPLAY -> "settings/display"
    SettingsScreenKey.GRAPH -> "settings/graph"
    SettingsScreenKey.ALARM_THRESHOLDS -> "settings/alarms"
    SettingsScreenKey.SIGNAL -> "settings/signal"
    SettingsScreenKey.ALERTS -> "settings/alerts"
    SettingsScreenKey.DEVICE_TEMP -> "settings/temperature"
    SettingsScreenKey.FORECAST -> "settings/forecast"
    SettingsScreenKey.CALCULATOR -> "settings/calculator"
    SettingsScreenKey.CURVES -> "settings/curves"
    SettingsScreenKey.MODELS -> "models"
    SettingsScreenKey.CGM -> "settings/cgm"
    SettingsScreenKey.SERVER -> "settings/server"
    SettingsScreenKey.NIGHTSCOUT -> "settings/nightscout"
    SettingsScreenKey.WATCH -> "settings/watch"
    SettingsScreenKey.POWER -> "settings/power"
    SettingsScreenKey.DATA -> "settings/data"
    SettingsScreenKey.BACKUP -> "backup"
    SettingsScreenKey.ABOUT -> "about"
    SettingsScreenKey.DEATH_CLOCK -> "settings/deathclock"
    SettingsScreenKey.DEATH_MODE -> "settings/death"
}

/** Pops at most once: popBackStack() returns before composition tears down (else ascends two). */
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

/** Only two editor routes (savedMeals costs a query/meal); null pre-emission or post-delete. */
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
    // Age only; the value, trend and link live in [T1dmBottomBar].
    val reading by container.latestReading.collectAsState(null)
    val death = LocalDeathMode.current
    val inference by container.inferenceState.collectAsState(InferenceState())
    // A flow, not the @Volatile snapshot: a Settings edit must invalidate this composition.
    val alarmCfg by container.alarmConfigFlow.collectAsState()
    // ADAPTIVE cadence republishes only on a reading; this tick keeps status live when stopped.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(CHROME_TICK_MS)
        }
    }
    val readingAgeMs = reading?.rxWallMs?.let { (nowMs - it).coerceAtLeast(0L) }
    val status = remember(inference, alarmCfg, readingAgeMs) {
        glycemicStatusOf(inference, alarmCfg.thresholds, nowMs, readingAgeMs)
    }
    val animationsOn = LocalAnimationsEnabled.current
    val trailScroll = rememberScrollState()
    Row(
        // Deliberately no background: the backdrop shows through.
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .then(
                    if (animationsOn) Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    else Modifier.horizontalScroll(trailScroll),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    } else {
                        Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    },
                )
            }
        }
        GlycemicStatusBadge(status)
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
}

/** Fast enough that a stale badge is not stale advice; one chrome recomposition, no layout. */
private const val CHROME_TICK_MS = 20_000L

/** STABLE is a positive claim (§3.6-eligible forecast, no crossing); else VOID with reason. */
private sealed interface GlyStatus {
    val text: String
    object Stable : GlyStatus { override val text = "STABLE" }
    data class Excursion(val hyper: Boolean, val etaMin: Long) : GlyStatus {
        override val text: String get() = (if (hyper) "HYPER" else "HYPO") + " in ${etaMin}M"
    }
    data class Void(val reason: String) : GlyStatus { override val text = "VOID" }
}

/** Fail-closed: any ineligibility yields VOID, never STABLE. */
private fun glycemicStatusOf(
    inf: InferenceState,
    thr: com.t1dm.core.model.AlertThresholds?,
    nowMs: Long,
    readingAgeMs: Long?,
): GlyStatus {
    inf.warmup?.let {
        return GlyStatus.Void(
            "Collecting context — %.1f / %.0f h BG".format(it.measuredHours, it.requiredHours),
        )
    }
    // A forecast under a stale reading describes a world that has since stopped reporting.
    if (readingAgeMs == null || readingAgeMs > STALE_MIN * 60_000L) {
        return GlyStatus.Void(if (readingAgeMs == null) "No reading" else "Reading stale")
    }
    val p = inf.selectedPrediction
        ?: return GlyStatus.Void("No forecast yet")
    if (p.stale) {
        return GlyStatus.Void("Anchor reading stale")
    }
    // p.stale is stamped inside a cycle; only the clock term keeps moving after the link drops.
    if (nowMs - p.anchorTsMs > STALE_MIN * 60_000L) {
        return GlyStatus.Void("Anchor reading stale")
    }
    if (p.status != com.t1dm.core.model.ForecastStatus.OK) {
        return GlyStatus.Void("Forecast degenerate (collapsed or rail-pinned)")
    }
    thr ?: return GlyStatus.Void("No thresholds set")
    for (i in p.medianBg.indices) {
        val v = p.medianBg[i]
        val ts = p.anchorTsMs + (i + 1L) * p.stepMs
        val eta = ((ts - nowMs) / 60_000L).coerceAtLeast(0L)
        if (v <= thr.lowMgdl) return GlyStatus.Excursion(hyper = false, etaMin = eta)
        if (v >= thr.highMgdl) return GlyStatus.Excursion(hyper = true, etaMin = eta)
    }
    return GlyStatus.Stable
}

@Composable
private fun GlycemicStatusBadge(status: GlyStatus) {
    val animationsOn = LocalAnimationsEnabled.current
    val ctx = LocalContext.current
    val color = when (status) {
        is GlyStatus.Stable -> LocalT1dmSemantics.current.inRange
        is GlyStatus.Excursion -> MaterialTheme.colorScheme.error
        is GlyStatus.Void -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val periodMs = when (status) {
        is GlyStatus.Stable -> 2600
        is GlyStatus.Excursion -> 700
        is GlyStatus.Void -> 0
    }
    val floor = if (status is GlyStatus.Excursion) 0.35f else 0.8f
    // Held, not unwrapped: .value here would recompose row every frame; read inside graphicsLayer.
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
        status.text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        // The colour keeps its own alpha; the layer multiplies it.
        color = color,
        maxLines = 1,
        modifier = if (alphaState != null) {
            mod.graphicsLayer { alpha = alphaState.value }
        } else {
            mod
        },
    )
}

/** Past [STALE_MIN]: arrow drops, value reddens; no-rate source draws none. Uses [BgFormat]. */
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
    // VIEWED source; off-authoritative uses tertiary color. Privacy-resolved upstream in the flow.
    val sourceLabel by container.viewedSourceLabel.collectAsState(null)
    val sourceSerial by container.viewedSourceSerial.collectAsState(null)
    val sourceStatus by container.viewedStatus.collectAsState(CgmSourceStatus.Idle)
    val viewingOther by container.viewingNonAuthoritative.collectAsState(false)
    val viewedReading by container.viewedReading.collectAsState(null)

    // VIEWED sensor drives the read-out; `reading` stays authoritative (alarm/stats/calc/wire).
    val shown = if (viewingOther) viewedReading else reading

    // One clock for age chip/staleness; fast while young, coarse after (sits on every screen).
    val rxWallMs = shown?.rxWallMs
    val nowMs by produceState(System.currentTimeMillis(), rxWallMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(if (rxWallMs != null && (value - rxWallMs) in 0..60_000L) 1_000L else CHROME_TICK_MS)
        }
    }
    val ageMs = rxWallMs?.let { (nowMs - it).coerceAtLeast(0L) }
    val stale = ageMs == null || ageMs > STALE_MIN * 60_000L

    // No Surface (clips glow/pointer); LocalContentColor replaces its unset-Text color resolve.
    CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .hapticClickable(HapticEvent.SegmentTick) {
                        container.setUnitSpace(UnitSpace.entries[(unit.ordinal + 1) % UnitSpace.entries.size])
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = BgFormat.value(shown?.bgMgdl, unit),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        // Stale beats viewed-tint order (older number is the stronger warning).
                        color = when {
                            stale -> cs.error
                            viewingOther -> cs.tertiary
                            else -> Color.Unspecified
                        },
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TimeOfDayIcon()
                }
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
                )
            }
            NavWheelPuck(wheel, motion, destinations, current, onWheelSelect)
            SensorPanel(
                Modifier.weight(1f),
                sourceLabel = sourceLabel,
                sourceSerial = sourceSerial,
                // Matches VIEWED sensor; RSSI falls back to reading's if fresh (ADVERTISEMENT).
                rssi = viewedReading?.rssi?.takeUnless { stale },
                status = sourceStatus,
                ageMs = ageMs,
                stale = stale,
                viewingOther = viewingOther,
                onCycle = container::cycleViewedSource,
            )
        }
    }
}

/** A promoted reconstruction is a normal `cgm_reading` row; unlabelled it reads as real glucose. */
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
        // Em dash, not a zero: no fresh advert is an unknown, not a floor.
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

/** Age of [CgmReading.rxWallMs] pre-grid-snap; this line alone carries freshness. */
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

/** A ticker, not an inference-cycle key; called per route so figures stay fresh despite gating. */
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

/** Volume up = Meals, down = Insulin; stands down while an alarm is ACTIVE or predictive is up. */
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

/** [onNotice] posts a bare line, no Undo; hoisted to [T1dmApp], whose scope outlives the route. */
@Composable
private fun T1dmNavHost(
    navController: NavHostController,
    container: AppContainer,
    onNotice: (String) -> Unit,
    onLogged: (LogHandle) -> Unit,
) {
    val animationsOn = LocalAnimationsEnabled.current
    // The footer drill-down links are `:app`'s own navigation, so the haptic is spoken here.
    val navHaptics = LocalT1dmHaptics.current
    // Hoisted to outlive navigation; not a route arg (crumbsFor matches literals exactly).
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
            // Recomputed off-main.
            val iobCob by container.iobCob.collectAsState()
            val range by container.graphRange.collectAsState(com.t1dm.data.settings.BgRange.DEFAULT)
            val windowHours by container.graphWindowHours.collectAsState(6)
            val reachability by container.bgReachability.collectAsState(null)
            val signals by container.bgSignals.collectAsState(null)
            val tempUnit by container.temperatureUnit.collectAsState(com.t1dm.core.model.TempUnit.CELSIUS)
            // The battery sensor; there is no fan RPM to read.
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
            // Display-only; never reaches alerts or dosing.
            val rolled by container.rolledForecast.collectAsState()
            val rollComputing by container.rollComputing.collectAsState()
            val pulses by container.bgPulses.collectAsState(null)
            val sensorExpiry by container.sensorExpiryMs.collectAsState(null)
            // Non-null only while the active sensor is warming up.
            val sensorWarmupEnd by container.sensorWarmupEndMs.collectAsState(null)
            val lowPowerActive by container.lowPowerActive.collectAsState(false)
            val forecastMode by container.settingsStore.forecastMode.collectAsState(SettingsStore.FORECAST_MODE_ADAPTIVE)
            val forecastPeriod by container.settingsStore.forecastPeriodMin.collectAsState(SettingsStore.DEFAULT_FORECAST_PERIOD_MIN)
            // A disabled gate passes null, so the TEMP chip keeps its neutral colour.
            val thermalGateOn by container.thermalGateEnabled.collectAsState(SettingsStore.DEFAULT_THERMAL_ON)
            val thermalMaxC by container.inferenceMaxTempC.collectAsState(SettingsStore.DEFAULT_MAX_TEMP_C)
            val thermalWarn by container.thermalWarnMarginC.collectAsState(SettingsStore.DEFAULT_WARN_MARGIN_C)
            val glucoseUnit by container.statsRepository.unitSpace.collectAsState(UnitSpace.MgDl)
            // BG filter the model consumes (INFERENCE.md §7.1); passed by value, not just captured
            val savgolWindow by container.savgolWindow.collectAsState(SettingsStore.DEFAULT_SAVGOL_WINDOW)
            // Memoised on window only (container is Compose-UNSTABLE); else re-runs FFI smoothing.
            val smoothMgdl = remember(savgolWindow) {
                { arr: DoubleArray ->
                    container.nativeCore.causalSmooth(arr.toList(), 20.0, 500.0, savgolWindow).toDoubleArray()
                }
            }
            // `:feature:dashboard` and `:ui:graph` see no store.
            val paintStrokes by container.paintStrokes.collectAsState(emptyList())
            // Same feed the Logs panel binds; screen reduces to markers, graph never sees amount.
            val logEntries by container.loggedEntries.collectAsState(emptyList())
            val insulins by container.insulinChoices.collectAsState(emptyList())
            // §8.4: remembered on the map, so lambda identity changes only when a fit lands.
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
            // Probe costs three fp32 forwards; driven from panels showing it, TTL absorbs bursts.
            val sensitivity = rememberSensitivity(container)
            // Forecasts derive from AUTHORITATIVE sensor; emptying withholds all three together.
            val viewingOther by container.viewingNonAuthoritative.collectAsState(false)
            val viewedSourceKey by container.viewedSourceKey.collectAsState(null)
            // Off the UNWITHHELD predictions: withholding the fan must not move the trace.
            val forecastEndMs = inference.predictions.firstOrNull { it.selected }
                ?.takeIf { it.horizonSteps > 0 }
                ?.let { it.anchorTsMs + it.horizonSteps * it.stepMs }
            // maskControls null until a model has a descriptor; hides rather than offers a no-op.
            val maskNote by container.panelMaskNote.collectAsState()
            val bgEditDepth by container.bgEditDepth.collectAsState()
            val tauPreview by container.tauPreview.collectAsState()
            var maskControls by remember { mutableStateOf<MaskControls?>(null) }
            // Keyed on reading too (cut moves newestMeasuredMs/contextFloorMs, geometry basis).
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
                sourceKey = viewedSourceKey,
                unit = glucoseUnit,
                thresholds = container.alarmConfig.thresholds,
                predictions = if (viewingOther) emptyList() else inference.predictions,
                kovatchevF = container.nativeCore::kovatchevF,
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
                // Withheld with forecast (fill uses authoritative history); nulls edit mode too.
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
                signals = signals,
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
                // Withheld with the fan, for the same reason; the control goes with it.
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
                gameSlot = { m, fromMs, dropMs, spanMin, clock, ready, exit ->
                    DashboardGamePanel(container, m, fromMs, dropMs, spanMin, clock, ready, exit)
                },
            )
        }
        composable("pubs") {
            PubsScreen(container.pubsRepository)
        }
        composable("circadian") {
            val inference by container.inferenceState.collectAsState(InferenceState())
            val iobCob by container.iobCob.collectAsState()
            val dkaTl by container.dkaTimeline.collectAsState(DkaTimeline.DEFAULT)
            CircadianScreen(
                predictedTime = inference.selectedPredictedTime,
                realBackendAvailable = inference.realBackendAvailable,
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
                val composite = container.statsViewModel.state.value.composite
                if (uri == null) { exportStatus = "Export cancelled" }
                else if (composite == null) { exportStatus = "No stats to export" }
                else scope.launch {
                    exportStatus = runCatching {
                        ctx.contentResolver.openOutputStream(uri)?.use {
                            com.t1dm.app.stats.StatsPdf.write(it, composite, container.nativeCore.clinicalCuts())
                        } ?: error("could not open file")
                        "Report exported"
                    }.getOrElse { "Export failed — ${it.message ?: it::class.simpleName}" }
                }
            }
            StatsScreen(
                state = statsState,
                kovatchevF = container.nativeCore::kovatchevF,
                // Read here to keep the screen free of a NativeCore dependency.
                cuts = remember { container.nativeCore.clinicalCuts() },
                onSelectWindow = container.statsViewModel::selectWindow,
                onSetUnitSpace = container::setUnitSpace,
                onSetTargetRange = container.statsViewModel::setTargetRange,
                onRecompute = container.statsViewModel::recompute,
                onExportPdf = { exportStatus = null; pdfLauncher.launch("t1dm-stats-${statsState.window.wire}.pdf") },
                exportStatus = exportStatus,
            )
        }
        composable("lab") {
            val inference by container.inferenceState.collectAsState(InferenceState())
            val lab = container.labController
            val labState by lab.state.collectAsState()
            val scope = rememberCoroutineScope()
            // Running set changes under the Lab; the surface follows it, not a one-time sample.
            LaunchedEffect(inference.running) {
                lab.refresh(inference.running.map { it.modelId })
            }
            LabScreen(
                state = labState,
                onPickModel = { lab.pickModel(it); scope.launch { lab.refresh(labState.models) } },
                onSeed = lab::setSeed,
                onGenerate = { scope.launch { lab.generate() } },
                onOpenAdapters = { id -> navController.navigate("models/$id/lora") },
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
                    scope.launch { lab.rename(id, adapterId, name); container.refreshLoraPanel(id) }
                },
                onDelete = { adapterId ->
                    scope.launch { lab.delete(id, adapterId); container.refreshLoraPanel(id) }
                },
                onExport = { adapterId -> scope.launch { container.exportAdapter(adapterId) } },
                onImport = { scope.launch { container.importAdapters(id) } },
            )
        }

        composable("models") {
            val inference by container.inferenceState.collectAsState(InferenceState())
            val pendingUpdates by container.pendingModelUpdates.collectAsState(emptySet())
            val scope = rememberCoroutineScope()
            ModelsScreen(
                state = inference,
                onSelect = { id ->
                    scope.launch {
                        container.inferenceController.selectModel(id)
                        // Figures belong to the model; BG panel isn't composed to notice a switch.
                        container.refreshSensitivityIfStale()
                    }
                },
                onOpen = { id -> navController.navigate("models/$id") },
                onOpenAdapters = { id -> navController.navigate("models/$id/lora") },
                pendingUpdates = pendingUpdates,
                onApplyUpdate = { id -> scope.launch { container.applyModelUpdate(id) } },
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
            // Fetched not passed (25 600 cells across FFI); null until landed (else "computing").
            var lattices by remember { mutableStateOf<ErrorGridLattices?>(null) }
            LaunchedEffect(Unit) { lattices = container.errorGridLattices() }
            // §6.3: keyed on own tick; zeroing cancels an in-flight walk (else it lands late).
            var cgEga by remember(modelId) { mutableStateOf<CgEga?>(null) }
            var cgEgaLoading by remember(modelId) { mutableStateOf(false) }
            var cgEgaTick by remember(modelId) { mutableStateOf(0) }
            LaunchedEffect(modelId, reloadTick) { cgEga = null; cgEgaLoading = false }
            LaunchedEffect(modelId, cgEgaTick) {
                if (cgEgaTick == 0) return@LaunchedEffect
                cgEgaLoading = true
                val walked = runCatching { container.modelCgEga(modelId) }
                // Blocks stale writes: runCatching hides cancellation; the walk can't stop.
                if (!isActive) return@LaunchedEffect
                cgEga = walked.getOrNull()
                cgEgaLoading = false
            }
            // §8.4: local correction observed; fitTick==0 guards against re-announcing on reopen
            val bandCalibrations by container.bandCalibrations.collectAsState()
            val bandCalibration: BandCalibration? = bandCalibrations[modelId]
            var fitOutcome by remember(modelId) { mutableStateOf<BandCalibrationOutcome?>(null) }
            var fitting by remember(modelId) { mutableStateOf(false) }
            var fitTick by remember(modelId) { mutableStateOf(0) }
            LaunchedEffect(modelId, fitTick) {
                if (fitTick == 0) return@LaunchedEffect
                fitting = true
                val outcome = runCatching { container.fitBandCalibration(modelId) }
                // Cancelled fit mustn't overwrite its successor; persists once, only if sufficient
                if (!isActive) return@LaunchedEffect
                fitOutcome = outcome.getOrNull()
                fitting = false
            }
            ModelDetailScreen(
                state = inference,
                modelId = modelId,
                accuracy = accuracy,
                accuracyLoading = loading,
                // Zeroing tick cancels a running walk; relaunch reuses the never-asked-for guard.
                onRecomputeAccuracy = { reloadTick++; cgEgaTick = 0 },
                // Data-independent, so the container builds one pair for every model's drill-down.
                lattices = lattices,
                trendBinEdges = container.trendBinEdges,
                cgEga = cgEga,
                cgEgaLoading = cgEgaLoading,
                onComputeCgEga = { cgEgaTick++ },
                bandCalibration = bandCalibration,
                bandCalibrationFitting = fitting,
                bandCalibrationOutcome = fitOutcome,
                // Three guards; only container's holds when a fit starts elsewhere.
                onFitBandCalibration = { if (!fitting) fitTick++ },
                onDropBandCalibration = { scope.launch { container.dropBandCalibration(modelId) } },
            )
        }
        composable("hardware") {
            val inference by container.inferenceState.collectAsState(InferenceState())
            var hardware by remember { mutableStateOf(com.t1dm.feature.hardware.HardwareInfo.UNKNOWN) }
            LaunchedEffect(Unit) { hardware = container.detectHardware() }
            val tempUnit by container.temperatureUnit.collectAsState(com.t1dm.core.model.TempUnit.CELSIUS)
            HardwareScreen(state = inference, hardware = hardware, temperatureUnit = tempUnit)
        }
        composable("network") {
            val status by container.syncStatus.collectAsState(SyncStatus())
            val active by container.activeServerProfile.collectAsState(null)
            // The SyncStatus mapping is device-net-agnostic, so the posture is attached here.
            val net by produceState<com.t1dm.feature.network.NetworkDiagnostics?>(null) {
                while (true) {
                    value = container.networkDiagnostics()
                    kotlinx.coroutines.delay(4000)
                }
            }
            val ns by produceState<Pair<Boolean, String?>>(false to null) {
                val store = container.nightscoutConfigStore
                value = (store.current() != null) to store.url()
            }
            NetworkScreen(
                state = status.toPanelState(
                    active,
                    container.outboxMaxSize,
                    container.outboxMaxAgeMs,
                    nightscoutEnabled = ns.first,
                    nightscoutUrl = ns.second,
                ).copy(net = net),
            )
        }
        composable("meals") {
            val scope = rememberCoroutineScope()
            val ctx = LocalContext.current
            val iobCob by container.iobCob.collectAsState()
            val sensitivity = rememberSensitivity(container)
            val glucoseUnit by container.statsRepository.unitSpace.collectAsState(UnitSpace.MgDl)
            val recent by container.recentMeals.collectAsState(emptyList())
            var pendingPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
            var photoThumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
            var uploadStatus by remember { mutableStateOf<String?>(null) }

            suspend fun loadThumbnail(uri: android.net.Uri) {
                photoThumbnail = withContext(container.dispatchers.io) {
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use { stream ->
                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                            android.graphics.BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
                        }
                    }.getOrNull()
                }
            }

            val cameraLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.TakePicture(),
            ) { ok ->
                val uri = pendingPhotoUri
                if (ok && uri != null) {
                    uploadStatus = null
                    scope.launch { loadThumbnail(uri) }
                } else {
                    pendingPhotoUri = null
                }
            }
            val galleryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia(),
            ) { uri ->
                if (uri != null) {
                    pendingPhotoUri = uri
                    uploadStatus = null
                    scope.launch { loadThumbnail(uri) }
                }
            }

            MealsScreen(
                iobCob = iobCob,
                sensitivity = sensitivity,
                unit = glucoseUnit,
                recentMeals = recent,
                previewCurve = container.previewCarbCurve,
                photoThumbnail = photoThumbnail,
                // The Uri gates the POST below; the thumbnail is only what the preview draws.
                photoAttached = pendingPhotoUri != null,
                uploadStatus = uploadStatus,
                onTakePhoto = {
                    val uri = runCatching {
                        val dir = java.io.File(ctx.cacheDir, "meal_photos").apply { mkdirs() }
                        val file = java.io.File(dir, "meal_${System.currentTimeMillis()}.jpg")
                        androidx.core.content.FileProvider.getUriForFile(
                            ctx, "${ctx.packageName}.fileprovider", file,
                        )
                    }.getOrNull()
                    if (uri != null) {
                        pendingPhotoUri = uri
                        uploadStatus = null
                        runCatching { cameraLauncher.launch(uri) }
                    }
                },
                onChoosePhoto = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onClearPhoto = {
                    pendingPhotoUri = null
                    photoThumbnail = null
                    uploadStatus = null
                },
                onLogMeal = { grams, gi, note ->
                    container.appScope.launch {
                        val uri = pendingPhotoUri
                        // Direct POST, no outbox/delete route; undo leaves the photo behind.
                        onLogged(
                            container.logCarb(grams, gi, note).let { h ->
                                if (uri == null) h
                                else h.copy(caveats = h.caveats + "Any uploaded photo stays on the server")
                            },
                        )
                        if (uri != null) {
                            val now = System.currentTimeMillis()
                            val bytes = withContext(container.dispatchers.io) {
                                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                            }
                            uploadStatus = if (bytes == null) {
                                "Logged; photo unreadable"
                            } else {
                                container.uploadMealPhoto(now, bytes, photoExtFor(ctx, uri)).fold(
                                    onSuccess = { "Logged, photo uploaded" },
                                    onFailure = { "Logged; photo upload failed — ${it.message ?: it::class.simpleName}" },
                                )
                            }
                            pendingPhotoUri = null
                            photoThumbnail = null
                        }
                    }
                },
            ) {
                // Screen's footer in its scroll column; wrapped in Column after a full-axis child.
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
            // Null until first emission; popping on initial empty value makes route unreachable.
            val saved: List<SavedMeal>? by container.savedMeals.collectAsState(null)
            val meal = saved?.firstOrNull { it.id == mealId }
            val ascend = rememberSingleAscent(navController)
            // Deleted under the editor: leave rather than hold a Save at a header that's gone.
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
            // Only user foods are listed, so a miss means deleted.
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
            // Read once each; panel owns selection after, re-seeding mid-entry moves the chip.
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
            val insulinLabel by produceState<String?>(null) { value = container.resolvedRapidLabel() }
            val ready = ui as? BolusAdviceUi.Ready
            BolusCalculatorScreen(
                result = ready?.result,
                targetLowMgdl = targetLow,
                targetHighMgdl = targetHigh,
                initialTargetMgdl = targetMid,
                isComputing = ui is BolusAdviceUi.Running,
                insulinLabel = insulinLabel,
                onAccept = { c ->
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
                    // Clears bolusAdvice to Idle; Undo can't restore the card (see caveat above).
                    DoseCalcService.cancel(ctx)
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
            // Bout's own reason while recording; else the refusal that stopped the service.
            val degraded by container.exerciseDegraded.collectAsState()
            // Saveable: survives a low-memory kill mid permission trip (no configChanges).
            var pendingKind by rememberSaveable { mutableStateOf<ExerciseKind?>(null) }
            val locationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                val kind = pendingKind
                pendingKind = null
                when {
                    kind == null -> Unit
                    // Either grant opens a bout; coarse-only shows on the card, not a track.
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
                    // FINE only: coarse grant fuzzes to ~2 km and the bucketer refuses every fix.
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
                // Container's scope: write and re-forecast must survive leaving the panel.
                onReplay = { session, startMs ->
                    container.appScope.launch { container.replayExercise(session, startMs) }
                },
            )
        }
        composable("exercise/{sessionId}") { entry ->
            val id = entry.arguments?.getString("sessionId")?.toLongOrNull() ?: return@composable
            val inference by container.inferenceState.collectAsState(InferenceState())
            val unit by container.statsRepository.unitSpace.collectAsState(UnitSpace.MgDl)
            val range by container.graphRange.collectAsState(com.t1dm.data.settings.BgRange.DEFAULT)
            val bandCalibrations by container.bandCalibrations.collectAsState()
            // Keyed on the id, not Unit: the list this is reached from re-sorts under it.
            val session by produceState<ExerciseSession?>(null, id) {
                value = container.exercise.session(id)
            }
            val track by produceState(emptyList<TrackPoint>(), id) {
                value = container.exercise.track(id)
            }
            // Resolved once, so the load and the viewport cannot disagree.
            val window = session?.let { reviewWindow(it) }
            val frame by produceState(GraphFrame.EMPTY, window, unit) {
                val w = window
                value = if (w == null) GraphFrame.EMPTY
                else graphFrameOf(
                    container.sessionReadings(w.first, w.last),
                    unit,
                    kovatchevF = container.nativeCore::kovatchevF,
                )
            }
            // The model whose fan the panel shows, so the sweep is not two models' history mixed.
            val modelId = inference.selectedPrediction?.modelId
                ?: inference.running.firstOrNull { it.selected }?.modelId
            // Same §8.4 correction as BG panel's fans; ungated here (no roll on this screen).
            val calibrateSessionFans: (String, () -> List<Double>, Int, Int) -> List<Double>? =
                remember(bandCalibrations) {
                    { m, fans, steps, nq -> container.calibratedFanBatch(bandCalibrations, m, fans, steps, nq) }
                }
            val hindsight by produceState<HindsightFrame?>(null, window, unit, modelId, calibrateSessionFans) {
                val w = window
                val m = modelId
                value = if (w == null || m == null) null
                else hindsightFrameOf(
                    container.repository.predictionsForModelInRange(m, w.first, w.last),
                    unit,
                    container.nativeCore::kovatchevF,
                    { fans, steps, nq -> calibrateSessionFans(m, fans, steps, nq) },
                )
            }
            // Not live Logs feed (bounded, empty for old bouts); reduced to marks, no amounts.
            val sessionMarkers by produceState(emptyList<LogMarker>(), window) {
                val w = window
                value = if (w == null) {
                    emptyList()
                } else {
                    runCatching {
                        val meals = container.repository.loggedMealsInRange(w.first, w.last)
                            .map { LogMarker(it.tsMs, CurveKind.CARB) }
                        val doses = container.repository.loggedDosesInRange(w.first, w.last)
                            .map { LogMarker(it.tsMs, CurveKind.INSULIN) }
                        val replays = container.repository.loggedExerciseInRange(w.first, w.last)
                            .map { LogMarker(it.tsMs, CurveKind.EXERCISE) }
                        (meals + doses + replays).sortedBy { it.tsMs }
                    }.getOrDefault(emptyList())
                }
            }
            ExerciseSessionScreen(
                session = session,
                gridMs = T1dmRepository.GRID_MS,
                track = track,
                logMarkers = sessionMarkers,
                frame = frame,
                hindsight = hindsight,
                unit = unit,
                thresholds = container.alarmConfig.thresholds,
                rangeMinMgdl = range.minMgdl,
                rangeMaxMgdl = range.maxMgdl,
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
            val inf by container.inferenceState.collectAsState(InferenceState())
            val scope = rememberCoroutineScope()
            val recentSearches by container.settingsStore.recentSearches.collectAsState(emptyList())
            SettingsScreen(
                onOpenDisplay = { navController.navigate("settings/display") },
                onOpenGraph = { navController.navigate("settings/graph") },
                onOpenAlarmThresholds = { navController.navigate("settings/alarms") },
                onOpenSignalSafety = { navController.navigate("settings/signal") },
                onOpenAlerts = { navController.navigate("settings/alerts") },
                onOpenForecast = { navController.navigate("settings/forecast") },
                onOpenCalculator = { navController.navigate("settings/calculator") },
                onOpenCurveParams = { navController.navigate("settings/curves") },
                onOpenModels = { navController.navigate("models") },
                onOpenCgm = { navController.navigate("settings/cgm") },
                onOpenServer = { navController.navigate("settings/server") },
                onOpenNightscout = { navController.navigate("settings/nightscout") },
                onOpenWatch = { navController.navigate("settings/watch") },
                onOpenPower = { navController.navigate("settings/power") },
                onOpenData = { navController.navigate("settings/data") },
                onOpenAbout = { navController.navigate("about") },
                onOpenDeath = { navController.navigate("settings/death") },
                onOpenDeviceTemp = { navController.navigate("settings/temperature") },
                onOpenDeathClock = { navController.navigate("settings/deathclock") },
                recentSearches = recentSearches,
                onOpenKnob = { knob ->
                    // Before nav: destination reads the pending anchor on first composition.
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
            // Seeded from the hot snapshot to avoid a WARNING→SEALED flash.
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
            // The user's own recent trace, so a detent is judged against real sensor noise.
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
            // preview takes strength explicitly; chip felt before the kv round-trip returns.
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
            CurveParamsScreen(
                params = CurveParams(
                    basalKaPerHour = CurveEngine.Presets.BASAL_KA_PER_HOUR,
                    basalKePerHour = CurveEngine.Presets.BASAL_KE_PER_HOUR,
                    lantusDiaHours = CurveEngine.Presets.LANTUS_DIA_MIN / 60.0,
                    tresibaDiaHours = CurveEngine.Presets.TRESIBA_DIA_MIN / 60.0,
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
            var resetting by remember { mutableStateOf(false) }
            DataSettingsScreen(
                resetting = resetting,
                onOpenBackup = { navController.navigate("backup") { launchSingleTop = true } },
                onReset = {
                    if (!resetting) {
                        resetting = true
                        scope.launch {
                            container.resetAllData()
                            container.restartApp() // in-place relaunch; keeps sensor connected
                        }
                    }
                },
            )
        }
        composable("backup") {
            BackupRoute(container, onNotice)
        }
        composable("settings/watch") {
            val watch by container.watchSecurity.collectAsState()
            WatchSettingsScreen(
                linkStatus = watch.phase.name.lowercase().replace('_', ' '),
                deviceName = watch.deviceName,
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
            // One atomic config; each field edit re-saves the tuple with its siblings.
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
        composable("settings/server") {
            val active by container.activeServerProfile.collectAsState(null)
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            var health by remember { mutableStateOf<String?>(null) }
            val syncStatus by container.modelSyncStatus.collectAsState(null)
            ServerSettingsScreen(
                initialLabel = active?.label ?: "local",
                initialBaseUrl = active?.baseUrl ?: "http://127.0.0.1:8443",
                hasToken = active != null,
                isActive = active != null,
                busy = busy,
                healthStatus = health,
                syncStatus = syncStatus,
                onSyncModels = {
                    scope.launch {
                        busy = true
                        container.syncModelsFromServer()
                        busy = false
                    }
                },
                onSave = { label, baseUrl, token ->
                    scope.launch {
                        busy = true
                        container.saveServerProfile(label, baseUrl, token)
                        health = "Checking health…"
                        health = container.checkServerHealth()
                        // Refills an empty store after a reset → re-add-profile round trip.
                        val merged = runCatching { container.resyncFromServer() }.getOrDefault(0)
                        if (merged > 0) health = (health ?: "") + " · re-downloaded $merged history point(s)"
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
        composable("settings/nightscout") {
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf<String?>(null) }
            // Read once: edit-time facts, and the field is uncontrolled after first composition.
            var initial by remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }
            LaunchedEffect(Unit) {
                initial = Triple(
                    container.nightscoutConfigStore.url() ?: "",
                    container.nightscoutConfigStore.hasSecret(),
                    container.nightscoutConfigStore.enabled(),
                )
            }
            val loaded = initial
            if (loaded != null) {
                NightscoutSettingsScreen(
                    initialUrl = loaded.first,
                    hasSecret = loaded.second,
                    initialEnabled = loaded.third,
                    busy = busy,
                    status = status,
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
                )
            }
        }
        composable("settings/cgm") {
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()
            val active by container.authoritativeSource.collectAsState(null)
            val sources by container.allSources.collectAsState(emptyList())
            val signals by container.bgSignals.collectAsState(null)
            val expiry by container.sensorExpiryMs.collectAsState(null)
            val aggEnabled by container.aggressiveScanEnabled.collectAsState(false)
            val aggShowBg by container.aggressiveShowGlucose.collectAsState(true)
            val aggOnlyCharging by container.aggressiveOnlyCharging.collectAsState(false)
            var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
            val overlayLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { canOverlay = Settings.canDrawOverlays(ctx) }
            val activeCgmIds by container.registry.activeIds.collectAsState()
            CgmSettingsScreen(
                activeSourceName = active?.displayName,
                activeStatus = active?.let { "active" },
                // Registry keeps whole set; authoritative sensor lists regardless of hidden flag.
                recordedSources = sources.mapNotNull {
                    val isAuthoritative = it.id == active?.id
                    if (it.hidden && !isAuthoritative) null
                    else RecordedSource(
                        id = it.id.value,
                        name = it.displayName,
                        active = isAuthoritative || it.id in activeCgmIds,
                        authoritative = isAuthoritative,
                    )
                },
                onRemoveSource = { id -> container.hideCgm(id) },
                onMakeAuthoritative = { id -> container.makeAuthoritativeCgm(id) },
                onStartReading = { id -> container.activateCgm(id) },
                onStopReading = { id -> container.deactivateCgm(id) },
                activeRssi = signals?.cgmRssi,
                sensorExpiryMs = expiry,
                // Persisted column, not the in-memory set: it is what warmup classifies against.
                warmupWindowMin = active?.warmupWindowMin,
                onSetWarmupMin = { m -> scope.launch { container.setSensorWarmupMin(m) } },
                onSetSensorLifetime = { d, h, m -> scope.launch { container.setSensorLifetime(d, h, m) } },
                onClearSensorLifetime = { scope.launch { container.clearSensorLifetime() } },
                aggressiveEnabled = aggEnabled,
                aggressiveShowGlucose = aggShowBg,
                aggressiveOnlyCharging = aggOnlyCharging,
                hasOverlayPermission = canOverlay,
                onSetAggressiveEnabled = { on -> scope.launch { container.setAggressiveScanEnabled(on) } },
                onSetAggressiveShowGlucose = { on -> scope.launch { container.setAggressiveShowGlucose(on) } },
                onSetAggressiveOnlyCharging = { on -> scope.launch { container.setAggressiveOnlyCharging(on) } },
                onRequestOverlay = {
                    overlayLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                    )
                },
            )
        }
        composable("logs") {
            val scope = rememberCoroutineScope()
            val entries by container.loggedEntries.collectAsState(emptyList())
            val holdMin by container.pushHoldMin.collectAsState(SettingsStore.DEFAULT_PUSH_HOLD_MIN)
            val mood by container.latestMood.collectAsState(null)
            val insulins by container.insulinChoices.collectAsState(emptyList())
            LogsScreen(
                entries = entries,
                holdMin = holdMin,
                holdMaxMin = SettingsStore.MAX_PUSH_HOLD_MIN,
                currentMood = mood,
                onSetHoldMin = { m -> scope.launch { container.setPushHoldMin(m) } },
                // Container's scope: leaving between the row and its push must not cancel it.
                onPickMood = { m -> container.appScope.launch { container.saveMood(m) } },
                // Container's scope; silent on success (row leaving is the feedback).
                onDelete = { entry ->
                    container.appScope.launch { container.deleteLoggedEntry(entry) }
                },
                onEdit = { entry, edit -> container.appScope.launch { container.applyLogEdit(entry, edit) } },
                insulins = insulins,
            )
        }
    }
    }
}

/** Cosmetic, writes nothing; solver runs on `t1dm-game` thread, never inference/default. */
@Composable
private fun DashboardGamePanel(
    container: AppContainer,
    modifier: Modifier,
    trackFromMs: Long,
    dropAtMs: Long,
    spanMinutes: Float,
    // Panel's own clock (warmup-surviving circadian fallback); a second derivation would drift.
    predictedClock: PredictedClock?,
    onReady: () -> Unit,
    onExit: () -> Unit,
) {
    val glucoseUnit by container.statsRepository.unitSpace.collectAsState(UnitSpace.MgDl)
    val range by container.graphRange.collectAsState(com.t1dm.data.settings.BgRange.DEFAULT)
    val paintStrokes by container.paintStrokes.collectAsState(emptyList())
    val latest by container.latestReading.collectAsState(null)
    val alarm by container.alarmState.collectAsState()
    val predicted by container.predictiveAlertRaised.collectAsState()
    val death by container.deathMode.collectAsState(false)
    // FFI: the art needs the same numbers the solver was built with, not a transcription.
    val tuning by produceState<CarTuning?>(null) {
        value = withContext(container.dispatchers.default) {
            runCatching { container.nativeCore.defaultCarTuning() }.getOrNull()
        }
    }
    GameScreen(
        modifier = modifier,
        trackFromMs = trackFromMs,
        dropAtMs = dropAtMs,
        thresholds = container.alarmConfig.thresholds,
        onReady = onReady,
        spanMinutes = spanMinutes,
        predictedClock = predictedClock,
        latestReadingMs = latest?.tsMs,
        unit = glucoseUnit,
        kovatchevF = container.nativeCore::kovatchevF,
        rangeMinMgdl = range.minMgdl,
        rangeMaxMgdl = range.maxMgdl,
        paintStrokes = paintStrokes,
        carTuning = tuning,
        readingsFrom = container::gameReadings,
        openWorld = { terrain, t -> container.nativeCore.createGameWorld(terrain, t) },
        gameDispatcher = container.dispatchers.game,
        // CRITICAL only; DEATH fail-opens §3.6 presentation, over-temp keeps the interlock.
        alarmRaised = (
            alarm.alarms.any { it.severity == AlarmSeverity.CRITICAL } ||
                alarm.overTemperature != null ||
                predicted
            ) && (!death || alarm.overTemperature != null),
        onExit = onExit,
    )
}
