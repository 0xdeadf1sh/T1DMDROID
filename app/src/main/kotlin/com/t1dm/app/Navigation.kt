package com.t1dm.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.t1dm.core.design.SignalBars
import com.t1dm.core.design.TimeOfDayIcon
import com.t1dm.core.design.hapticClickable
import com.t1dm.core.design.ThemeBackdrop
import com.t1dm.core.design.navEnter
import com.t1dm.core.design.navExit
import com.t1dm.app.di.AppContainer.BolusAdviceUi
import com.t1dm.app.di.LogHandle
import com.t1dm.app.di.deleteReceipt
import com.t1dm.app.di.logReceipt
import com.t1dm.app.di.undoReceipt
import com.t1dm.app.service.DoseCalcService
import com.t1dm.feature.insulin.BolusCalculatorScreen
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.BezierCurve
import com.t1dm.core.model.DkaTimeline
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.t1dm.app.di.AppContainer
import com.t1dm.app.backup.BackupRoute
import com.t1dm.app.sync.SyncStatus
import com.t1dm.app.sync.toPanelState
import com.t1dm.feature.settings.ServerSettingsScreen
import com.t1dm.feature.settings.DeathModeScreen
import com.t1dm.app.notify.BgFormat
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.core.model.SensitivityEstimate
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.t1dm.feature.dashboard.DashboardScreen
import com.t1dm.feature.game.GameScreen
import com.t1dm.core.model.CarTuning
import com.t1dm.feature.hardware.HardwareScreen
import com.t1dm.feature.insulin.InsulinScreen
import com.t1dm.feature.insulin.InsulinTypeBuilderScreen
import com.t1dm.feature.meals.FoodEditorScreen
import com.t1dm.feature.meals.MealBuilderScreen
import com.t1dm.feature.meals.MealEditorScreen
import com.t1dm.feature.logs.LogsScreen
import com.t1dm.feature.meals.MealsScreen
import com.t1dm.core.model.Food
import com.t1dm.core.model.SavedMeal
import com.t1dm.feature.pubs.PubsScreen
import com.t1dm.feature.models.ModelDetailScreen
import com.t1dm.feature.models.ModelsScreen
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.BandCalibration
import com.t1dm.core.model.BandCalibrationOutcome
import com.t1dm.core.model.ClarkeZoneGrid
import com.t1dm.core.model.ModelMetrics
import com.t1dm.core.model.ModelPrediction
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
import com.t1dm.feature.settings.DeathClockSettingsScreen
import com.t1dm.feature.settings.DeviceTempAlertScreen
import com.t1dm.feature.settings.DisplaySettingsScreen
import com.t1dm.feature.settings.ForecastCadenceSettingsScreen
import com.t1dm.feature.settings.LocalSettingsFocus
import com.t1dm.feature.settings.SettingsFocusController
import com.t1dm.feature.settings.SettingsScreenKey
import com.t1dm.feature.settings.GraphSettingsScreen
import com.t1dm.feature.settings.ModelCountSettingsScreen
import com.t1dm.feature.settings.PowerSettingsScreen
import com.t1dm.feature.settings.SettingsScreen
import com.t1dm.feature.settings.SignalSafetyScreen
import com.t1dm.feature.settings.ThermalSettingsScreen
import com.t1dm.feature.settings.WarmupSettingsScreen
import com.t1dm.feature.settings.WatchSettingsScreen
import com.t1dm.ui.graph.PredictedClock
import com.t1dm.alerts.AlarmSeverity
import com.t1dm.alerts.VibrationPreset
import com.t1dm.app.settings.SettingsStore
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.settings.GraphSettingsStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.t1dm.watch.WatchSecurityState
import com.t1dm.feature.stats.StatsScreen
import com.t1dm.feature.dashboard.CircadianScreen
import com.t1dm.core.design.BundledPalettes
import com.t1dm.core.design.T1dmFontId
import com.t1dm.core.design.ThemeIds
import com.t1dm.core.design.parseThemeJson

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

/** The photo filename extension the server splits on (issue 7): PNG stays PNG, everything else rides
 *  as JPEG. Derived from the Uri's MIME type, defaulting to jpg (the camera-capture format). */
private fun photoExtFor(ctx: android.content.Context, uri: android.net.Uri): String =
    when (runCatching { ctx.contentResolver.getType(uri) }.getOrNull()) {
        "image/png" -> "png"
        else -> "jpg"
    }

internal data class Destination(val route: String, val label: String)

// The ring the nav wheel turns through, in wheel order. Each slot draws the per-theme vector glyph
// (issues 2/6 — geometry re-derived from the active theme via [navIcon]); no icon dependency is on
// the classpath, so these are authored Compose ImageVectors.
internal val destinations = listOf(
    Destination("dashboard", "BG"),
    Destination("pubs", "Pubs"),
    Destination("circadian", "Clock"),
    Destination("stats", "Stats"),
    Destination("models", "Models"),
    Destination("hardware", "Hardware"),
    Destination("network", "Network"),
    Destination("meals", "Meals"),
    Destination("insulin", "Insulin"),
    Destination("security", "Watch"),
    Destination("backup", "Backup"),
    Destination("logs", "Logs"),
    Destination("settings", "Settings"),
)

@Composable
fun T1dmApp(container: AppContainer) {
    val navController = rememberNavController()
    // The per-theme Canvas backdrop (7D): an opaque theme-background base, the themed motif layered over
    // it at the user's chosen opacity, then a TRANSPARENT Scaffold so the motif reads behind every
    // screen. Painting the base ourselves is what lets the motif sit at a low alpha without the window
    // showing through (a colour composited over itself is unchanged, so the painter's own bg-fill is a
    // no-op on the flat regions and only the motif is dimmed).
    val bgAlphaPct by container.settingsStore.backgroundAlphaPct
        .collectAsState(com.t1dm.app.settings.SettingsStore.DEFAULT_BG_ALPHA_PCT)
    // The commit receipt for every logged meal/dose. It is hosted (and its coroutine scoped) HERE
    // rather than per-route so the undo window survives navigating away from the screen that wrote
    // the row — a route-scoped `rememberCoroutineScope` is cancelled on exit, which would silently
    // take the Undo with it. Feature modules never see any of this: they emit a callback and `:app`
    // turns the returned LogHandle into a receipt.
    //
    // The WRITES have the same hazard and are launched on `container.appScope` for the same reason:
    // hoisting only the snackbar left the row itself, its outbox enqueue and this `onLogged` call on
    // the cancellable route scope, so leaving the screen between the press and the commit dropped the
    // dose — silently, since the field is cleared before the write resolves.
    val snackbars = remember { SnackbarHostState() }
    val receiptScope = rememberCoroutineScope()
    val haptics = LocalT1dmHaptics.current
    VolumeKeyShortcuts(navController, container, haptics)
    // The wheel's gesture state is hoisted here because its two halves live on opposite sides of the
    // Scaffold: the hub is in the `bottomBar` slot, and the arc must draw OVER the content, which a
    // Scaffold draws its slots before its content and measures the bar to fit. See NavWheel.kt.
    val wheel = rememberNavWheelState(destinations.size)
    // Shared by the bar's hub and the overlay's, which draw the same dial at opposite ends of the
    // reveal. Created here and never read here — every field is unwrapped in a draw lambda.
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
            // imePadding on the host, not the content: Scaffold parks the snackbar above the bottom
            // bar but knows nothing of the IME, and targetSdk 36 never resizes the window — so a
            // receipt posted straight after typing carbs/units would sit under a raised keyboard with
            // its UNDO unreachable. With the keyboard up it now rides one bottom-bar height higher
            // than strictly needed (that inset is measured from the window edge); that is the cheap
            // side of the trade. §3.7's "insets applied in exactly one place" governs the content
            // column and the feature screens, which this slot is not.
            snackbarHost = { SnackbarHost(snackbars, Modifier.imePadding()) },
            // A transparent container makes Scaffold derive contentColor from contentColorFor(Transparent),
            // which is Unspecified and collapses to LocalContentColor (= black) — blacking out every
            // surface that inherits its colour (e.g. the big BG read-out + trend arrow). Pin it back to
            // the theme's on-background ink so inherited-colour content stays legible.
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = { T1dmBottomBar(navController, container, wheel, wheelMotion, onWheelSelect) },
        ) { padding ->
            // The ONE place the Scaffold insets are applied — feature screens must never re-apply them
            // or the gap above the bottom bar doubles. N10 — plus the IME: targetSdk 36 forces
            // edge-to-edge, so the window is never resized for the keyboard and a raised keyboard simply
            // covered the bottom of every text-entry panel (the "Log bolus"/"Log basal" buttons were
            // unreachable). `consumeWindowInsets(padding)` first, so `imePadding` only contributes what
            // the bottom bar has not already accounted for instead of stacking a second full inset.
            Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                // Flavor-specific: a first-run modal in the public build, no-op in the personal one.
                // It emits no layout node once acknowledged, so the column is the bare chrome after.
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
        // Outside the Scaffold on purpose: the arc opens upward out of the bar and has to paint over
        // the graph, the breadcrumb and the disclaimer. It is inert and draws nothing until the hub
        // is touched.
        NavWheelArc(wheel, wheelMotion, destinations, onWheelSelect)
    }
}

/**
 * Show what was just written and offer to take it back.
 *
 * [HapticEvent.Commit] fires here rather than at the dialog's Accept: Commit is the vocabulary's
 * heaviest pattern and is reserved for a row that actually exists, so it lands when the write has
 * returned its [LogHandle], not when the intent was expressed.
 *
 * The undo is genuinely partial in one direction only, and the second snackbar says which: the local
 * row and its still-queued push are gone unconditionally, but a push that already drained is on the
 * server for good (no DELETE in the API, and the WS catch-up re-hydrates it by `clientId`).
 */
private suspend fun SnackbarHostState.postLogReceipt(
    container: AppContainer,
    handle: LogHandle,
    haptics: T1dmHaptics,
) {
    haptics.perform(HapticEvent.Commit)
    // A second log while the first receipt is up must not queue behind it — the undo window belongs
    // to the newest write, and `showSnackbar` suspends until the current one is dismissed.
    currentSnackbarData?.dismiss()
    val result = showSnackbar(
        message = logReceipt(handle),
        actionLabel = "UNDO",
        withDismissAction = true,
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed) {
        val outcome = container.undoLog(handle)
        showSnackbar(undoReceipt(handle, outcome), duration = SnackbarDuration.Long)
    }
}

/** One node in the location trail (issue 14). [route] non-null ⇒ tappable to ascend to it. */
internal data class Crumb(val label: String, val route: String?)

/**
 * The path from a top-level section down to the current sub-screen, most-recent last. The final
 * crumb is the current screen (never tappable). Intermediate crumbs ascend to their route.
 *
 * [editLabel] names the row an editor route is open on. A route argument carries an id, never a name,
 * so the lookup cannot happen here (this is a pure function of the route); [Breadcrumb] resolves it
 * and the tail falls back to a static label when it cannot.
 */
internal fun crumbsFor(route: String?, modelId: String?, editLabel: String? = null): List<Crumb> {
    fun settings(vararg tail: Crumb) = listOf(Crumb("Settings", "settings"), *tail)
    return when (route) {
        null, "dashboard" -> listOf(Crumb("BG", null))
        "pubs" -> listOf(Crumb("Pubs", null))
        "circadian" -> listOf(Crumb("Circadian clock", null))
        "stats" -> listOf(Crumb("Stats", null))
        "models" -> listOf(Crumb("Models", null))
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
        "security" -> listOf(Crumb("Watch", null))
        "backup" -> listOf(Crumb("Backup", null))
        "logs" -> listOf(Crumb("Logs", null))
        "settings" -> listOf(Crumb("Settings", null))
        "about" -> settings(Crumb("About", null))
        "settings/display" -> settings(Crumb("Display & theme", null))
        "settings/graph" -> settings(Crumb("Graph", null))
        "settings/alarms" -> settings(Crumb("Alarms & safety", "settings"), Crumb("Thresholds", null))
        "settings/signal" -> settings(Crumb("Alarms & safety", "settings"), Crumb("Signal safety", null))
        "settings/alerts" -> settings(Crumb("Sound & vibration", null))
        "settings/warmup" -> settings(Crumb("Warmup", null))
        "settings/models_running" -> settings(Crumb("Models run at once", null))
        "settings/forecast" -> settings(Crumb("Forecast cadence", null))
        "settings/thermal" -> settings(Crumb("Thermal gate", null))
        "settings/temperature" -> settings(Crumb("Alarms & safety", "settings"), Crumb("Device temperature", null))
        "settings/deathclock" -> settings(Crumb("Death clock", null))
        "settings/calculator" -> settings(Crumb("Bolus calculator", null))
        "settings/curves" -> settings(Crumb("Curve & PK", null))
        "settings/cgm" -> settings(Crumb("CGM source", null))
        "settings/server" -> settings(Crumb("Server", null))
        "settings/watch" -> settings(Crumb("Watch", null))
        "settings/power" -> settings(Crumb("Low power", null))
        "settings/data" -> settings(Crumb("Reset", null))
        "settings/death" -> settings(Crumb("Death mode", null))
        else -> listOf(Crumb(route, null))
    }
}

/**
 * Where each settings destination lives, as `:app`'s half of the search index.
 *
 * `:feature:settings` names a screen by an opaque [SettingsScreenKey] and never sees a route — the
 * same seam every other cross-module id crosses (theme ids, font ids, haptic names, vibration presets,
 * calculator objectives). Keeping the mapping here, beside [crumbsFor], is what lets a host test
 * assert that every index entry resolves to a registered route AND that the breadcrumb a search result
 * advertises is the one the trail will actually render on arrival.
 *
 * [SettingsScreenKey.MODELS] leaves the module for `:feature:models`; the hub's "Compute backend" row
 * drills further into `models/{id}`, but that id is only known at press time, so a search hit lands on
 * the model list.
 */
internal fun settingsRouteFor(screen: SettingsScreenKey): String = when (screen) {
    SettingsScreenKey.ROOT -> "settings"
    SettingsScreenKey.DISPLAY -> "settings/display"
    SettingsScreenKey.GRAPH -> "settings/graph"
    SettingsScreenKey.ALARM_THRESHOLDS -> "settings/alarms"
    SettingsScreenKey.SIGNAL -> "settings/signal"
    SettingsScreenKey.ALERTS -> "settings/alerts"
    SettingsScreenKey.DEVICE_TEMP -> "settings/temperature"
    SettingsScreenKey.WARMUP -> "settings/warmup"
    SettingsScreenKey.MODEL_COUNT -> "settings/models_running"
    SettingsScreenKey.FORECAST_CADENCE -> "settings/forecast"
    SettingsScreenKey.THERMAL -> "settings/thermal"
    SettingsScreenKey.CALCULATOR -> "settings/calculator"
    SettingsScreenKey.CURVES -> "settings/curves"
    SettingsScreenKey.MODELS -> "models"
    SettingsScreenKey.CGM -> "settings/cgm"
    SettingsScreenKey.SERVER -> "settings/server"
    SettingsScreenKey.WATCH -> "settings/watch"
    SettingsScreenKey.POWER -> "settings/power"
    SettingsScreenKey.DATA -> "settings/data"
    // Leaves `:feature:settings` entirely, the way MODELS does: backup is its own top-level panel,
    // and a search hit for "export" should land there rather than on the reset screen it left.
    SettingsScreenKey.BACKUP -> "backup"
    SettingsScreenKey.ABOUT -> "about"
    SettingsScreenKey.DEATH_CLOCK -> "settings/deathclock"
    SettingsScreenKey.DEATH_MODE -> "settings/death"
}

/**
 * A pop that fires at most once for this composition.
 *
 * The editor routes ascend from two directions — the user pressing Save/Cancel/Delete, and the
 * observed list reporting the row gone — and the write that triggers the second is the same press
 * that fires the first. `popBackStack()` returns before the composition is torn down, so an
 * unguarded pair would land the user TWO screens up, back on the Meals hub.
 */
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

/**
 * The name of the row a meal/food editor route is open on, for the trail's tail crumb.
 *
 * The collection is scoped to those two routes on purpose: `savedMeals` costs one item query per meal
 * on every emission, and the breadcrumb bar is composed on every screen in the app. Null while the
 * Flow has yet to emit (or after the row is gone), which the caller renders as a static label rather
 * than a flicker.
 */
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

/**
 * The breadcrumb bar (issue 14): a nav-aware trail of where the user is as they descend through
 * panels. Each ancestor crumb is tappable to ascend to it; the current screen is the bold tail. Kept
 * flat (a single scrollable row) so long trails never wrap.
 */
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
    // The reading is read for its AGE only — the badge's freshness verdict below. The value, trend and
    // link now live in [T1dmBottomBar], present on every screen this trail is.
    val reading by container.latestReading.collectAsState(null)
    val death = LocalDeathMode.current
    // U1 — the app-wide glycemic status, recomputed as the forecast state changes.
    val inference by container.inferenceState.collectAsState(InferenceState())
    // Thresholds as a FLOW, not the @Volatile snapshot: a Settings edit has to invalidate this
    // composition, or the badge keeps judging against bounds the user has already replaced.
    val alarmCfg by container.alarmConfigFlow.collectAsState()
    // Both the badge and the number below are freshness verdicts, and freshness is a function of the
    // CLOCK — but in the default ADAPTIVE cadence the only thing that republishes InferenceState is a
    // reading arriving. Exactly when readings stop, therefore, nothing re-evaluates, and a memo keyed
    // on the forecast alone pins a green STABLE over every screen for as long as the app stays up.
    // This tick is what makes "no longer true" reachable without new data.
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
    // I6 — when animations are on the trail MARQUEES (scrolls itself) so a long path is eventually
    // readable in full; with motion disabled it falls back to a manual horizontal scroll (never a moving
    // marquee). Either way weight(1f) keeps it from ever overlapping the status badge / version.
    val animationsOn = LocalAnimationsEnabled.current
    val trailScroll = rememberScrollState()
    Row(
        // N1 — the breadcrumb bar reads as chrome over the same canvas, so it stays TRANSPARENT: the
        // per-theme backdrop shows through it at the same opacity as the rest of the app (its base sits
        // on the app background painted by T1dmApp).
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            // The trail scrolls sideways so a long path never wraps; the status + version stay pinned.
            // I6 — marquee when animations are on, manual scroll otherwise.
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
                    // U4 — materially larger, more legible breadcrumb type.
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
                                // Ascend: pop the stack back to the already-present ancestor (dropping the
                                // child sub-view). If it isn't on the stack, navigate to it fresh.
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
        // U1 — the prominent, app-wide glycemic status: GREEN "STABLE", RED "HYPO/HYPER in NM", or a
        // neutral tappable "VOID" when the forecast is ineligible (never green off a stale/degenerate
        // /warmup forecast — that is the false-reassurance §3.6 exists to prevent).
        GlycemicStatusBadge(status)
        // DEATH mode: a themed skull pinned to the far end of the trail.
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

/** How often the breadcrumb re-judges freshness. Fast enough that a stale badge is not stale ADVICE,
 *  slow enough to be free: one recomposition of the chrome row, no layout, no work off it. */
private const val CHROME_TICK_MS = 20_000L

/** The app-wide glycemic status (U1). It is a strict function of the CURRENT forecast eligibility:
 *  a green STABLE is a positive claim and is emitted ONLY for a §3.6-eligible forecast with no
 *  predicted crossing; every ineligible state is VOID with a plain-language reason. */
private sealed interface GlyStatus {
    val text: String
    object Stable : GlyStatus { override val text = "STABLE" }
    data class Excursion(val hyper: Boolean, val etaMin: Long) : GlyStatus {
        override val text: String get() = (if (hyper) "HYPER" else "HYPO") + " in ${etaMin}M"
    }
    data class Void(val reason: String) : GlyStatus { override val text = "VOID" }
}

/** Derive the status from the selected model's forecast + the alarm thresholds. Fail-closed: any
 *  ineligibility (warmup / no forecast / stale / degenerate) yields VOID, never STABLE. */
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
    // No reading at all, or one past the staleness cutoff: the forecast under it describes a world
    // that has since stopped reporting, whatever the cycle concluded at the time.
    if (readingAgeMs == null || readingAgeMs > STALE_MIN * 60_000L) {
        return GlyStatus.Void(if (readingAgeMs == null) "No reading" else "Reading stale")
    }
    val p = inf.selectedPrediction
        ?: return GlyStatus.Void("No forecast yet")
    if (p.stale) {
        return GlyStatus.Void("Anchor reading stale")
    }
    // `p.stale` was stamped INSIDE a forecast cycle, and in the default ADAPTIVE cadence a cycle only
    // runs when a reading lands — so it can never become true on its own once the link drops. Judge
    // the anchor against the clock as well, which is the only term that keeps moving.
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
    val stableGreen = Color(0xFF3DD68C)
    val color = when (status) {
        is GlyStatus.Stable -> stableGreen
        is GlyStatus.Excursion -> MaterialTheme.colorScheme.error
        is GlyStatus.Void -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // A gentle breathe for STABLE, an urgent pulse for a predicted excursion, static for VOID — and
    // static whenever the global "disable all animations" flag is off (LocalAnimationsEnabled).
    val periodMs = when (status) {
        is GlyStatus.Stable -> 2600
        is GlyStatus.Excursion -> 700
        is GlyStatus.Void -> 0
    }
    val floor = if (status is GlyStatus.Excursion) 0.35f else 0.8f
    // The State is HELD, not unwrapped. Reading `.value` here would put a never-ending animation in the
    // COMPOSITION phase, so this scope — which sits in the app chrome, on every screen — would
    // recompose and relayout every frame for as long as the status is anything but Void. Read inside
    // `graphicsLayer` below it is a layer property: the RenderNode's alpha changes and nothing
    // re-composes, re-measures or re-records. This is the rule `core/design/Pulse.kt` states.
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
            // VOID is the §3.6 fail-closed verdict: the app declining to claim a glycemic status at
            // all. Tapping it asks WHY, and the answer is always a refusal — so the badge warns rather
            // than taps, and is the one chrome element in the app that does.
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
        // The colour keeps its OWN alpha; the pulse multiplies it at the layer. One leaf Text, so the
        // composite is the same one `color.copy(alpha = color.alpha * pulse)` produced.
        color = color,
        maxLines = 1,
        modifier = if (alphaState != null) {
            mod.graphicsLayer { alpha = alphaState.value }
        } else {
            mod
        },
    )
}

/**
 * The bottom bar: the BG read-out with the nav wheel's hub filling the gap that used to sit empty
 * between its two halves.
 *
 * It is the dashboard's old header, promoted. Promoting it is what lets the hub be GLOBAL — the wheel
 * has to be reachable from every screen, and the read-out is the one piece of chrome worth carrying
 * everywhere in a CGM app. Layout mirrors the panel it replaced: value + time-of-day on the left,
 * trend + link + age on the right, and the hub between them.
 *
 * The figures come from [BgFormat] rather than the dashboard's native Kovatchev `f`: this is chrome,
 * drawn on screens where the graph's transform is not in scope. [BgFormat] goes through the
 * pure-Kotlin `KovatchevScale` mirror for the same reason the notification and the widgets do.
 *
 * **Past [STALE_MIN] this bar stops asserting a direction.** It is the app's only always-on BG
 * surface, and a bold value beside a live-looking arrow is a claim about NOW; the trend was measured
 * whenever the last reading arrived. So the arrow is dropped, the value turns [ColorScheme.error],
 * and the age carries the message instead — the rule the Breadcrumb's chip used to hold, moved here
 * with the read-out. A missing reading is stale by the same argument: `classifyTrend(null, …)` is
 * FLAT, which would draw a steady arrow beside "--".
 */
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
    val source by container.activeSource.collectAsState(null)

    // ONE clock for both the age chip and the staleness verdict. Fast while the reading is young so
    // the chip counts seconds honestly, coarse afterwards — the verdict only has to be right to within
    // a fraction of STALE_MIN, and this composition sits on every screen in the app.
    val rxWallMs = reading?.rxWallMs
    val nowMs by produceState(System.currentTimeMillis(), rxWallMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(if (rxWallMs != null && (value - rxWallMs) in 0..60_000L) 1_000L else CHROME_TICK_MS)
        }
    }
    val ageMs = rxWallMs?.let { (nowMs - it).coerceAtLeast(0L) }
    val stale = ageMs == null || ageMs > STALE_MIN * 60_000L

    // NO Surface. The read-out, the hub and the link figures are chrome over the app's own canvas,
    // not a panel laid on it — a filled surface drew a lighter slab across the bottom of every screen.
    // But a TRANSPARENT Surface is not enough either: `Surface` clips to its shape, and the hub's glow
    // and its pointer both reach past the bar's bounds by design. Clipped, the glow ended in a hard
    // horizontal edge and the pointer vanished entirely. A bare Row does not clip.
    //
    // Losing the Surface means losing what it provided for content colour, so that is provided here:
    // an unset `Text` colour resolves through LocalContentColor, and nothing else in this slot sets it.
    CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // The hub is the tallest thing in the row and sets the bar's height; the padding is
                // what keeps it off the window edge and off the read-out beside it.
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = BgFormat.value(reading?.bgMgdl, unit),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (stale) cs.error else Color.Unspecified,
                        maxLines = 1,
                    )
                    TimeOfDayIcon()
                }
                Text(
                    text = BgFormat.unitLabel(unit) + (reading?.let { readingSuffix(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stale) cs.error else cs.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            NavWheelPuck(wheel, motion, destinations, current, onWheelSelect)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                // A direction measured minutes ago is not a current one.
                if (!stale) {
                    Text(
                        text = BgFormat.arrow(
                            BgGlanceComputer.classifyTrend(reading?.trendTenthsPerMin, reading?.bgMgdl, null),
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = source?.shortName ?: "no source",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                    // Identical to `bgSignals.cgmRssi`, which is defined as exactly this — and taking it
                    // from the reading keeps a second always-on combine off every screen in the app.
                    reading?.rssi?.let { SignalBars(it) }
                }
                ageMs?.let { LastReadingChip(it, stale) }
            }
        }
    }

}

/** Warm-up / interpolated provenance, appended to the unit label the way the BG panel's header did. */
private fun readingSuffix(r: CgmReading): String = when {
    r.flag == ReadingFlag.WARMUP -> "  • warmup"
    r.provenance == ReadingProvenance.INTERPOLATED -> "  • interpolated"
    else -> ""
}

/** How long ago the CGM actually delivered the reading ([CgmReading.rxWallMs], before the grid snap),
 *  so the freshness of the link is legible at a glance. [stale] reddens it: past the cutoff this line
 *  is carrying the whole freshness message, because the trend arrow above it has been withdrawn. */
@Composable
private fun LastReadingChip(ageMs: Long, stale: Boolean) {
    Text(
        "received ${formatReadingAge(ageMs)}",
        style = MaterialTheme.typography.labelSmall,
        // Tabular monospace so the per-second reflow (proportional digits) no longer shifts the chip.
        fontFamily = FontFamily.Monospace,
        color = if (stale) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        },
    )
}

/** Elapsed-time phrasing for [LastReadingChip]. The numeric fields are space-padded to a fixed width
 *  so — under the chip's monospace font — the string keeps a constant width as it ticks (a leading
 *  space is one digit cell), and the End-anchored chip never shifts when 9s rolls over to 10s. */
private fun formatReadingAge(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "%2ds ago".format(s)
        s < 3600 -> "%2dm ago".format(s / 60)
        s < 86_400 -> "%2dh %2dm ago".format(s / 3600, (s % 3600) / 60)
        else -> "%2dd ago".format(s / 86_400)
    }
}

/**
 * The ISF/ICR estimate, plus the tick that keeps it current, for any panel that shows it.
 *
 * A TICKER, not a key on the inference cycle: `lastCycleTsMs` stops advancing on exactly the gated
 * paths — thermal, warm-up, no context — that stop forecasts, so keying on it meant the container's
 * lapse check never ran in the states where a held figure is most likely to be out of date. The
 * container decides whether to re-probe, to drop, or to do nothing; this only has to ask it often
 * enough that the lapse is honoured, so the tick is cheap and coarse.
 *
 * It lives here, called from each route that renders the figures, so a panel cannot show them
 * without also keeping them fresh — and so the loop exists once rather than once per panel.
 */
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

/**
 * Volume up ⇒ Meals, volume down ⇒ Insulin. Registered on the Activity, which is where the key
 * arrives; the decision stays here, where the NavController and the state it depends on live.
 *
 * Two gates, and only one of them is the user's. With the setting off the keys are the volume they
 * always were. And **while an alarm is announcing the shortcut stands down regardless of the
 * setting** — an alarm is precisely when the hardware keys must do the obvious thing, and this app
 * has no business holding the volume down at that moment. That gate is deliberately keyed on an
 * alarm being active rather than on it currently sounding: a snoozed low is still a low.
 *
 * **Both actuators, not just the deterministic one.** `alarmState` carries the §3.6-A engine's
 * conditions only. The model-predictive urgent alert is a second, independently published actuator
 * (`predictiveAlertRaised`) that posts on the critical channel with a full-screen intent and buzzes
 * the same vibrator — and it suppresses itself when a deterministic CRITICAL breach is up, so its
 * whole working life is spent while `alarmState` reads CLEAR. Gating on `alarmState` alone left the
 * keys hijacked through exactly the DND-bypassing announcement this gate exists for. The minigame's
 * interlock reads the same pair, for the same reason.
 *
 * The navigation options match the nav wheel's exactly, so arriving by key and arriving by thumb
 * leave the same back stack.
 */
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

/** The hosting [MainActivity], through however many ContextWrappers the composition sits behind.
 *  Not `LocalActivity`: activity-compose is pinned at 1.9.3 here. */
private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}

/**
 * [onLogged] posts the commit receipt for a meal/dose write; see [postLogReceipt]. [onNotice] posts a
 * bare line with no action — the outcome of a write that has no row left to offer an Undo on (a Logs
 * deletion, and its refusal). Both are hoisted to [T1dmApp] for the same reason: the host owns the
 * snackbar and a scope that survives leaving the route that spoke.
 */
@Composable
private fun T1dmNavHost(
    navController: NavHostController,
    container: AppContainer,
    onNotice: (String) -> Unit,
    onLogged: (LogHandle) -> Unit,
) {
    // Issue 17 — the "disable all animations" flag must collapse the screen crossfade to a snap.
    val animationsOn = LocalAnimationsEnabled.current
    // The three "→" drill-down links a feature screen renders through its `footer` slot are `:app`'s
    // own navigation, not the screen's, so they speak the NavSwitch here rather than reaching for the
    // engine from inside a module that knows nothing of where the press leads.
    val navHaptics = LocalT1dmHaptics.current
    // The Settings-search landing request, hoisted ABOVE the graph so it outlives the navigation it
    // triggers: the hub sets it, the destination scaffold consumes it. It is deliberately not a route
    // argument — `crumbsFor` matches route literals exactly, and an argument would replay the pulse on
    // every Back-navigation into the screen (SettingsAnchors.kt).
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
            val inference by container.inferenceState.collectAsState(InferenceState())
            // IOB/COB recomputed off-main on any reading emit OR dose/meal write (shared StateFlow).
            val iobCob by container.iobCob.collectAsState()
            val range by container.graphRange.collectAsState(com.t1dm.data.settings.BgRange.DEFAULT)
            val windowHours by container.graphWindowHours.collectAsState(6)
            val reachability by container.bgReachability.collectAsState(null)
            val signals by container.bgSignals.collectAsState(null)
            val tempUnit by container.temperatureUnit.collectAsState(com.t1dm.core.model.TempUnit.CELSIUS)
            // Poll the device (battery-sensor) temperature off-main every 30 s (U9 — no fan RPM).
            val deviceTempC by produceState<Double?>(null) {
                while (true) {
                    value = withContext(container.dispatchers.io) { container.readDeviceTempC() }
                    kotlinx.coroutines.delay(30_000)
                }
            }
            // Today's steps for the BG-panel STEPS chip, polled off-main on the same 30 s cadence.
            val stepsToday by produceState<Int?>(null) {
                while (true) {
                    value = withContext(container.dispatchers.io) { runCatching { container.stepsToday() }.getOrNull() }
                    kotlinx.coroutines.delay(30_000)
                }
            }
            // I2 — the ephemeral, display-only rolled forecast (never reaches alerts/dosing).
            val rolled by container.rolledForecast.collectAsState()
            val rollComputing by container.rollComputing.collectAsState()
            // I12 — per-channel data-movement pulses + the sensor-derived time-left countdown.
            val pulses by container.bgPulses.collectAsState(null)
            val sensorExpiry by container.sensorExpiryMs.collectAsState(null)
            // Non-null only while the active sensor is actually warming up; the chip then counts down to
            // the end of warm-up instead of to expiry.
            val sensorWarmupEnd by container.sensorWarmupEndMs.collectAsState(null)
            // Issue 1 — battery-saver / low-power indicator (polled off-main).
            val lowPowerActive by container.lowPowerActive.collectAsState(false)
            // F2 — the forecast cadence (adaptive vs a fixed period) drives the next-cycle countdown.
            val forecastMode by container.settingsStore.forecastMode.collectAsState(SettingsStore.FORECAST_MODE_ADAPTIVE)
            val forecastPeriod by container.settingsStore.forecastPeriodMin.collectAsState(SettingsStore.DEFAULT_FORECAST_PERIOD_MIN)
            // F6 — the thermal gate's threshold colours the TEMP chip; a disabled gate passes null so the
            // chip keeps its neutral colour regardless of the reading.
            val thermalGateOn by container.thermalGateEnabled.collectAsState(SettingsStore.DEFAULT_THERMAL_ON)
            val thermalMaxC by container.inferenceMaxTempC.collectAsState(SettingsStore.DEFAULT_MAX_TEMP_C)
            val thermalWarn by container.thermalWarnMarginC.collectAsState(SettingsStore.DEFAULT_WARN_MARGIN_C)
            // Issue 3 — the Display glucose unit (mg/dL | mmol/L | Kovatchev) the BG panel honours.
            val glucoseUnit by container.statsRepository.unitSpace.collectAsState(UnitSpace.MgDl)
            // The BG input filter the MODEL consumes (INFERENCE.md §7.1). Passed through as a value as
            // well as captured in the smoother below: a Compose-memoized lambda would otherwise leave a
            // stale trace on screen after a Settings change.
            val savgolWindow by container.savgolWindow.collectAsState(SettingsStore.DEFAULT_SAVGOL_WINDOW)
            // Memoised on the only thing it closes over that varies. Written inline it was a fresh
            // lambda per recomposition capturing `container`, which is Compose-UNSTABLE (it carries
            // public `var`s) — so it could never compare equal, and as a `produceState` key downstream
            // it re-ran a full-history smoothing pass across the FFI on every recomposition of this
            // route. `container` and `nativeCore` are singletons for the process, so the window is the
            // whole of the key.
            val smoothMgdl = remember(savgolWindow) {
                { arr: DoubleArray ->
                    container.nativeCore.causalSmooth(arr.toList(), 20.0, 500.0, savgolWindow).toDoubleArray()
                }
            }
            // The BG panel's freehand annotation layer; `:feature:dashboard` and `:ui:graph` see no store.
            val paintStrokes by container.paintStrokes.collectAsState(emptyList())
            // What the panel marks at its foot, and what a tap on one of those marks restates. The SAME
            // feed the Logs panel binds, so the two can never disagree about whether a row has reached
            // the server; the screen reduces it to markers for the graph, which still sees no amount.
            val logEntries by container.loggedEntries.collectAsState(emptyList())
            // §8.4's on-device band recalibration. Remembered against the calibration map so the
            // lambda's identity changes exactly when a fit lands — which is what makes the panel's
            // overlay `produceState` rebuild then, and not on every recomposition.
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
            // The model-probed ISF/ICR beside the IOB/COB read-out. A probe costs three forwards on
            // the fp32 authority, so it is driven from the panels that show it: off-screen the
            // read-out costs nothing at all, and the container's TTL keeps a burst of ticks from
            // re-probing.
            val sensitivity = rememberSensitivity(container)
            DashboardScreen(
                readings = readings,
                unit = glucoseUnit,
                thresholds = container.alarmConfig.thresholds,
                predictions = inference.predictions,
                kovatchevF = container.nativeCore::kovatchevF,
                calibrateBands = calibrateBands,
                iobCob = iobCob,
                sensitivity = sensitivity,
                curveChannels = container::dashboardOverlayChannels,
                stepSeries = container::dashboardStepSeries,
                logEntries = logEntries,
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
                rolledForecast = rolled,
                rollComputing = rollComputing,
                onRoll = { hours -> container.requestRollForDisplay(hours) },
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
            // F5 — the IOB-exhaustion countdown needs live IOB + its projected zero-crossing, and the
            // user-tunable DKA→coma→death offsets.
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
                // Two crate constants; cheap enough to read per composition, and reading them here
                // keeps the screen free of a NativeCore dependency.
                cuts = remember { container.nativeCore.clinicalCuts() },
                onSelectWindow = container.statsViewModel::selectWindow,
                onSetUnitSpace = container::setUnitSpace,
                onSetTargetRange = container.statsViewModel::setTargetRange,
                onRecompute = container.statsViewModel::recompute,
                onExportPdf = { exportStatus = null; pdfLauncher.launch("t1dm-stats-${statsState.window.wire}.pdf") },
                exportStatus = exportStatus,
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
                        // Re-probe ISF/ICR here rather than leaving it to the BG panel's tick: the
                        // figures belong to the model, so switching is exactly when they are wrong,
                        // and the panel is not composed to notice.
                        container.refreshSensitivityIfStale()
                    }
                },
                onOpen = { id -> navController.navigate("models/$id") },
                pendingUpdates = pendingUpdates,
                onApplyUpdate = { id -> scope.launch { container.applyModelUpdate(id) } },
                onDelete = { id -> scope.launch { container.removeModel(id) } },
                onFitBaseline = {
                    container.inferenceController.fitBaseline(System.currentTimeMillis())
                        .also { container.reevaluateInferenceNow() }
                },
            )
        }
        composable("models/{modelId}") { entry ->
            val modelId = entry.arguments?.getString("modelId") ?: return@composable
            val scope = rememberCoroutineScope()
            val inference by container.inferenceState.collectAsState(InferenceState())
            val requested by container.forecastBackendSetting(modelId).collectAsState(null)
            var accuracy by remember(modelId) { mutableStateOf<ModelMetrics?>(null) }
            var loading by remember(modelId) { mutableStateOf(true) }
            var reloadTick by remember(modelId) { mutableStateOf(0) }
            LaunchedEffect(modelId, reloadTick) {
                loading = true
                accuracy = runCatching { container.modelMetrics(modelId) }.getOrNull()
                loading = false
            }
            // The zone lattice the error grid paints its regions from. Fetched rather than read as a
            // plain argument because building it classifies 25 600 cells across the FFI seam, and this
            // screen puts nothing that heavy on the frame that composes it. Null until it lands, which
            // the figure states as "computing" rather than as regions it does not have.
            var clarkeGrid by remember { mutableStateOf<ClarkeZoneGrid?>(null) }
            LaunchedEffect(Unit) { clarkeGrid = container.clarkeZoneGrid() }
            // §6.3's CG-EGA walks every step of every window through the P-EGA × R-EGA grid, so it is
            // computed only when the panel asks for it and dropped whenever the cheap suite reloads
            // (it would otherwise outlive the window it was measured over). Clearing the RESULT is not
            // enough to drop it: the pass is keyed on its own tick, so a reload left an in-flight walk
            // running and it landed afterwards, repopulating the figure under freshly reloaded tables.
            // Zeroing the tick on reload is what actually cancels it — see `onRecomputeAccuracy` below.
            var cgEga by remember(modelId) { mutableStateOf<CgEga?>(null) }
            var cgEgaLoading by remember(modelId) { mutableStateOf(false) }
            var cgEgaTick by remember(modelId) { mutableStateOf(0) }
            LaunchedEffect(modelId, reloadTick) { cgEga = null; cgEgaLoading = false }
            LaunchedEffect(modelId, cgEgaTick) {
                if (cgEgaTick == 0) return@LaunchedEffect
                cgEgaLoading = true
                val walked = runCatching { container.modelCgEga(modelId) }
                // A superseded pass must not write back. `runCatching` swallows the cancellation, and
                // the native walk is uninterruptible, so a cancelled job resumes here seconds later —
                // by which time its successor may already be running and would have its result and its
                // loading flag cleared out from under it.
                if (!isActive) return@LaunchedEffect
                cgEga = walked.getOrNull()
                cgEgaLoading = false
            }
            // §8.4's band recalibration. The stored correction is observed (it survives process
            // death in Room, and a fit on another surface would land here too); the outcome of THIS
            // screen's last tap is local, so a reopen shows the correction without re-announcing a
            // fit the user has already read. The fit is keyed on its own tick, like CG-EGA's walk,
            // and `fitTick == 0` is the never-asked-for guard rather than a fit of nothing.
            val bandCalibrations by container.bandCalibrations.collectAsState()
            val bandCalibration: BandCalibration? = bandCalibrations[modelId]
            var fitOutcome by remember(modelId) { mutableStateOf<BandCalibrationOutcome?>(null) }
            var fitting by remember(modelId) { mutableStateOf(false) }
            var fitTick by remember(modelId) { mutableStateOf(0) }
            LaunchedEffect(modelId, fitTick) {
                if (fitTick == 0) return@LaunchedEffect
                fitting = true
                val outcome = runCatching { container.fitBandCalibration(modelId) }
                // A cancelled fit must not write its verdict back over its successor's. The window
                // walk and the native fit are both uninterruptible, so this resumes long after the
                // job was cancelled — the same trap the CG-EGA walk above documents. The CORRECTION
                // is safe either way: `fitBandCalibration` persists once, at the end, and only a
                // sufficient fit, so a cancellation leaves the previous one exactly as it was.
                if (!isActive) return@LaunchedEffect
                fitOutcome = outcome.getOrNull()
                fitting = false
            }
            ModelDetailScreen(
                state = inference,
                modelId = modelId,
                accuracy = accuracy,
                accuracyLoading = loading,
                // Zeroing the tick is a key change on the CG-EGA effect, so a walk still running is
                // cancelled here rather than allowed to land on the reloaded window; the relaunch then
                // returns early on the same guard a never-asked-for pass does.
                onRecomputeAccuracy = { reloadTick++; cgEgaTick = 0 },
                // A picture of the zone algebra, not of this patient: data-independent, so the
                // container builds it once and every model's drill-down shares the one lattice.
                clarkeGrid = clarkeGrid,
                cgEga = cgEga,
                cgEgaLoading = cgEgaLoading,
                onComputeCgEga = { cgEgaTick++ },
                catalog = inference.backendCatalog,
                requestedBackend = requested,
                comparison = inference.backendComparison,
                onSelectBackend = { b -> scope.launch { container.setForecastBackend(modelId, b) } },
                onRunComparison = { scope.launch { container.runBackendComparison() } },
                bandCalibration = bandCalibration,
                bandCalibrationFitting = fitting,
                bandCalibrationOutcome = fitOutcome,
                // A second tap while one is running must not start a second fit. The button is
                // disabled meanwhile, this drops the tick bump if it somehow arrives, and the
                // container refuses a re-entrant call outright — three, because only the last of
                // them holds when the fit is started from somewhere this screen cannot see.
                onFitBandCalibration = { if (!fitting) fitTick++ },
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
            // Issue 2 — poll the device's own network posture off-main every ~4 s and attach it to the
            // panel model (the SyncStatus mapping is device-net-agnostic; we .copy(net = …) it in here).
            val net by produceState<com.t1dm.feature.network.NetworkDiagnostics?>(null) {
                while (true) {
                    value = container.networkDiagnostics()
                    kotlinx.coroutines.delay(4000)
                }
            }
            NetworkScreen(
                state = status.toPanelState(active, container.outboxMaxSize, container.outboxMaxAgeMs)
                    .copy(net = net),
            )
        }
        composable("meals") {
            val scope = rememberCoroutineScope()
            val ctx = LocalContext.current
            val iobCob by container.iobCob.collectAsState()
            val sensitivity = rememberSensitivity(container)
            val glucoseUnit by container.statsRepository.unitSpace.collectAsState(UnitSpace.MgDl)
            val recent by container.recentMeals.collectAsState(emptyList())
            // Issue 7 — the pending photo (a content:// Uri from the camera or the gallery), its decoded
            // thumbnail, and the last upload status. Every Uri/IO touch is wrapped so nothing can crash.
            var pendingPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
            var photoThumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
            var uploadStatus by remember { mutableStateOf<String?>(null) }

            // Decode the Uri's bytes to a downscaled thumbnail off-main; a failure clears the preview.
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
                // The Uri is what gates the POST below, so it is also what the dialog and the Remove
                // affordance must be told about — the thumbnail is only what the preview can draw.
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
                onLogMeal = { grams, gi ->
                    container.appScope.launch {
                        val uri = pendingPhotoUri
                        // Issue 7 — the photo rides a direct POST, not the outbox, and the server has
                        // no delete endpoint, so an undone meal leaves its photo behind. Say so on the
                        // receipt rather than implying a clean unwind. CONDITIONAL, because the receipt
                        // is posted before the upload is even attempted (it must be: the undo window
                        // belongs to the write, not to a network round trip) and the POST may never
                        // happen — an unreadable stream and a failed upload both land below.
                        onLogged(
                            container.logCarb(grams, gi).let { h ->
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
                // N10 — the sub-screen links are passed as the screen's own footer, INSIDE its single
                // scroll column. Wrapped in a Column around the screen (as they were), they sat after a
                // child that had already claimed the whole main axis and were measured at zero height.
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
            // Null until the observed list has emitted at least once: an ABSENT meal is only conclusive
            // after that, and popping on the initial empty value would make the route unreachable.
            val saved: List<SavedMeal>? by container.savedMeals.collectAsState(null)
            val meal = saved?.firstOrNull { it.id == mealId }
            val ascend = rememberSingleAscent(navController)
            // Deleted out from under the editor (the 🗑 sits in the row the ✎ does). Leave rather than
            // hold a Save aimed at a header that is gone — the repository refuses that write anyway.
            LaunchedEffect(saved, meal) { if (saved != null && meal == null) ascend() }
            if (meal == null) return@composable
            MealEditorScreen(
                meal = meal,
                onSearch = { q -> container.mealsController.searchFoods(q) },
                onResolve = { comps -> container.mealsController.resolvePreview(comps) },
                // Deliberately the container's scope, not this composition's: the ascent is immediate,
                // and `rememberCoroutineScope()` is cancelled by the very pop that follows the launch.
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
            // Only USER foods are listed here, so a miss means deleted — the store refuses a seed row
            // regardless (`updateCustomFood`), but the view must never be opened on one either.
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
            // The panel's own chips, and the seeds for them. The catalogue is the single set of
            // insulins a dose row can name; the two labels are the last one actually LOGGED per kind,
            // resolved so an empty or stale memory still yields a real preset. Read once each — the
            // panel owns the selection from here, and re-seeding it mid-entry would move the chip out
            // from under the finger.
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
            // The target-BG slider spans the calculator's own low/high target (the same band the in-range
            // objective is scored against); the aim point seeds the initial slider position.
            val targetLow by ss.calcTargetLow.collectAsState(70.0)
            val targetHigh by ss.calcTargetHigh.collectAsState(180.0)
            val targetMid by ss.calcTargetMid.collectAsState(110.0)
            val insulinLabel by produceState<String?>(null) { value = container.resolvedRapidLabel() }
            // The advice carries its own expiry, but nothing republishes the flow once the search
            // ends — so without a tick the screen would sit on a live Accept indefinitely. Ticking
            // only while a Ready result is on screen keeps this off every other route.
            val ready = ui as? BolusAdviceUi.Ready
            val adviceExpired by produceState(false, ready) {
                val r = ready
                if (r == null) {
                    value = false
                    return@produceState
                }
                while (true) {
                    value = System.currentTimeMillis() - r.computedAtMs > r.staleAfterMs
                    if (value) return@produceState
                    delay(CHROME_TICK_MS)
                }
            }
            BolusCalculatorScreen(
                result = ready?.result,
                targetLowMgdl = targetLow,
                targetHighMgdl = targetHigh,
                initialTargetMgdl = targetMid,
                isComputing = ui is BolusAdviceUi.Running,
                insulinLabel = insulinLabel,
                adviceExpired = adviceExpired,
                onAccept = { c ->
                    scope.launch {
                        // A 0 U / carb-rescue acceptance writes no dose, so there is no handle and no
                        // receipt — an Undo there would dangle on a row that never existed.
                        container.acceptAdvisedBolus(c.doseU)?.let { h ->
                            onLogged(
                                h.copy(
                                    caveats = h.caveats +
                                        "Recommendation cleared — recompute to see it again",
                                ),
                            )
                        }
                    }
                    // Clears `bolusAdvice` back to Idle: an Undo cannot bring the card back, hence the
                    // caveat above rather than reordering the cancel behind the undo window.
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
                onOpenWarmup = { navController.navigate("settings/warmup") },
                onOpenModelCount = { navController.navigate("settings/models_running") },
                onOpenComputeBackend = {
                    val id = inf.running.firstOrNull { it.selected }?.modelId ?: inf.running.firstOrNull()?.modelId
                    navController.navigate(if (id != null) "models/$id" else "models")
                },
                onOpenCalculator = { navController.navigate("settings/calculator") },
                onOpenCurveParams = { navController.navigate("settings/curves") },
                onOpenModels = { navController.navigate("models") },
                onOpenCgm = { navController.navigate("settings/cgm") },
                onOpenServer = { navController.navigate("settings/server") },
                onOpenWatch = { navController.navigate("settings/watch") },
                onOpenPower = { navController.navigate("settings/power") },
                onOpenData = { navController.navigate("settings/data") },
                onOpenAbout = { navController.navigate("about") },
                onOpenDeath = { navController.navigate("settings/death") },
                onOpenForecastCadence = { navController.navigate("settings/forecast") },
                onOpenThermal = { navController.navigate("settings/thermal") },
                onOpenDeviceTemp = { navController.navigate("settings/temperature") },
                onOpenDeathClock = { navController.navigate("settings/deathclock") },
                recentSearches = recentSearches,
                onOpenKnob = { knob ->
                    // Request BEFORE navigating: the destination scaffold reads the pending anchor on
                    // its first composition, so setting it afterwards would miss the frame.
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
            // Seed the initial value from the hot snapshot so opening the page while DEATH mode is
            // already engaged shows the sealed reaper straight away, not a WARNING(skull)→SEALED flash.
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
            // The miniature runs on the user's OWN recent trace, so the effect of a detent is judged
            // against real sensor noise rather than a synthetic curve.
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
            val themeId by ss.themeId.collectAsState(SettingsStore.DEFAULT_THEME)
            val fontId by ss.fontId.collectAsState(SettingsStore.DEFAULT_FONT)
            val tempUnit by container.temperatureUnit.collectAsState(com.t1dm.core.model.TempUnit.CELSIUS)
            val customJson by ss.customThemeJson.collectAsState(null)
            val bgAlphaPct by ss.backgroundAlphaPct.collectAsState(com.t1dm.app.settings.SettingsStore.DEFAULT_BG_ALPHA_PCT)
            val hapticsKey by ss.hapticsLevel.collectAsState(SettingsStore.DEFAULT_HAPTICS)
            // Preview through the ambient engine (not an AppContainer-scoped one as the alarm preview
            // needs): `preview` takes the strength explicitly, so the tapped chip is felt at its own
            // level before the kv write has round-tripped back through the flow above.
            val haptics = LocalT1dmHaptics.current
            var importStatus by remember { mutableStateOf<String?>(null) }
            // Parse the loaded custom-theme JSON only for its display name; failures degrade quietly.
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
                        val palette = parseThemeJson(text) // validates + throws a plain-language message
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
            val staleMin by container.settingsStore.calcFreshnessMin.collectAsState(15)
            val weakOn by container.settingsStore.weakSignalEnabled.collectAsState(true)
            val weakDbm by container.settingsStore.weakSignalDbm.collectAsState(-90)
            val weakSustain by container.settingsStore.weakSignalSustainMin.collectAsState(3)
            SignalSafetyScreen(
                lossMin = lossMin,
                lossEscalatedMin = lossEsc,
                dosingStaleMin = staleMin,
                weakSignalEnabled = weakOn,
                weakSignalDbm = weakDbm,
                weakSignalSustainMin = weakSustain,
                onSetLoss = { a, b -> scope.launch { container.saveLossWindows(a, b) } },
                onSetDosingStale = { m -> scope.launch { container.settingsStore.setCalcFreshnessMin(m) } },
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
            val scope = rememberCoroutineScope()
            val hi = CurveEngine.Presets.carbGammaForGi(100.0)
            val lo = CurveEngine.Presets.carbGammaForGi(0.0)
            val carbEnc by container.settingsStore.carbBezier.collectAsState(null)
            val insEnc by container.settingsStore.insulinBezier.collectAsState(null)
            val carbCurve = remember(carbEnc) { BezierCurve.decode(carbEnc) ?: BezierCurve.default(180.0) }
            val insulinCurve = remember(insEnc) { BezierCurve.decode(insEnc) ?: BezierCurve.default(300.0) }
            CurveParamsScreen(
                params = CurveParams(
                    basalKaPerHour = CurveEngine.Presets.BASAL_KA_PER_HOUR,
                    basalKePerHour = CurveEngine.Presets.BASAL_KE_PER_HOUR,
                    lantusDiaHours = CurveEngine.Presets.LANTUS_DIA_MIN / 60.0,
                    tresibaDiaHours = CurveEngine.Presets.TRESIBA_DIA_MIN / 60.0,
                    carbHighGiK = hi.first, carbHighGiTheta = hi.second,
                    carbLowGiK = lo.first, carbLowGiTheta = lo.second,
                ),
                carbCurve = carbCurve,
                insulinCurve = insulinCurve,
                onSaveCarbCurve = { c -> scope.launch { container.settingsStore.setCarbBezier(BezierCurve.encode(c)) } },
                onSaveInsulinCurve = { c -> scope.launch { container.settingsStore.setInsulinBezier(BezierCurve.encode(c)) } },
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
                            container.restartApp() // in-place first-run relaunch; keeps the sensor connected
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
        composable("settings/warmup") {
            val scope = rememberCoroutineScope()
            val hours by container.warmupHoursSetting.collectAsState(24)
            WarmupSettingsScreen(
                hours = hours,
                onChange = { h -> scope.launch { container.setWarmupHours(h) } },
            )
        }
        composable("settings/models_running") {
            val scope = rememberCoroutineScope()
            val count by container.maxModelsSetting.collectAsState(5)
            ModelCountSettingsScreen(
                count = count,
                onChange = { n -> scope.launch { container.setMaxModels(n) } },
            )
        }
        composable("settings/forecast") {
            val scope = rememberCoroutineScope()
            val ss = container.settingsStore
            val mode by ss.forecastMode.collectAsState(SettingsStore.FORECAST_MODE_ADAPTIVE)
            val period by ss.forecastPeriodMin.collectAsState(SettingsStore.DEFAULT_FORECAST_PERIOD_MIN)
            val logDebounce by ss.logReforecastDebounceS
                .collectAsState(SettingsStore.DEFAULT_LOG_REFORECAST_DEBOUNCE_S)
            ForecastCadenceSettingsScreen(
                adaptive = mode == SettingsStore.FORECAST_MODE_ADAPTIVE,
                periodMinutes = period,
                logDebounceSeconds = logDebounce,
                onSetAdaptive = { on ->
                    scope.launch {
                        ss.setForecastMode(if (on) SettingsStore.FORECAST_MODE_ADAPTIVE else SettingsStore.FORECAST_MODE_TIMED)
                    }
                },
                onSetPeriodMinutes = { m -> scope.launch { ss.setForecastPeriodMin(m) } },
                onSetLogDebounceSeconds = { s -> scope.launch { ss.setLogReforecastDebounceS(s) } },
            )
        }
        composable("settings/thermal") {
            val scope = rememberCoroutineScope()
            val enabled by container.thermalGateEnabled.collectAsState(SettingsStore.DEFAULT_THERMAL_ON)
            val maxC by container.inferenceMaxTempC.collectAsState(SettingsStore.DEFAULT_MAX_TEMP_C)
            val warn by container.thermalWarnMarginC.collectAsState(SettingsStore.DEFAULT_WARN_MARGIN_C)
            ThermalSettingsScreen(
                enabled = enabled,
                maxTempC = maxC,
                warnMarginC = warn,
                onSetEnabled = { on -> scope.launch { container.setThermalGateEnabled(on) } },
                onSetMaxTempC = { c -> scope.launch { container.setInferenceMaxTempC(c) } },
                onSetWarnMarginC = { c -> scope.launch { container.setThermalWarnMarginC(c) } },
            )
        }
        composable("settings/temperature") {
            val scope = rememberCoroutineScope()
            val ss = container.settingsStore
            // The four over-temp knobs persist as one atomic config (saveOverTempConfig also refreshes the
            // alarm snapshot), so each per-field edit re-saves the tuple with the current siblings.
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
                        // Re-download the historical series (Phase-3 REST catch-up). This is what
                        // refills an empty store after a reset → re-add-profile round-trip.
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
        composable("settings/cgm") {
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()
            val active by container.activeSource.collectAsState(null)
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
            CgmSettingsScreen(
                activeSourceName = active?.displayName,
                activeStatus = active?.let { "active" },
                allSourceNames = sources.map { it.displayName },
                activeRssi = signals?.cgmRssi,
                sensorExpiryMs = expiry,
                // Read back from STORAGE (the active source's persisted column), not off the registry's
                // in-memory set: the knob writes the column, and Room's Flow is what makes the edit
                // visible again, so the panel shows the value that will actually be classified against.
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
            LogsScreen(
                entries = entries,
                holdMin = holdMin,
                holdMaxMin = SettingsStore.MAX_PUSH_HOLD_MIN,
                currentMood = mood,
                onSetHoldMin = { m -> scope.launch { container.setPushHoldMin(m) } },
                // The container's scope for the same reason the delete below uses it: the pick writes a
                // row and enqueues its ingest push, and leaving the panel between the two must not
                // cancel it.
                onPickMood = { m -> container.appScope.launch { container.saveMood(m) } },
                // The container's scope, not this composition's: a delete is a transaction plus a
                // notice, and leaving the panel between the press and the commit must not cancel it —
                // the same hazard the log writers are hoisted off the route scope for.
                onDelete = { entry ->
                    container.appScope.launch {
                        deleteReceipt(container.deleteLoggedEntry(entry))?.let(onNotice)
                    }
                },
            )
        }
    }
    }
}

/**
 * The hill-climb minigame. Cosmetic end to end: it READS the record (readings, the annotation
 * layer, the panel's unit + axis) and writes nothing at all, and its solver runs on the
 * dedicated `t1dm-game` thread — never `t1dm-inference`, never a `default` worker the alarm
 * engine and the decode path share.
 *
 * Hosted INSIDE the BG panel as a mode, not as a destination: the dashboard swaps this in where the
 * graph was and keeps every other read-out, so leaving the game is a chip, not a back-stack pop.
 */
@Composable
private fun DashboardGamePanel(
    container: AppContainer,
    modifier: Modifier,
    trackFromMs: Long,
    dropAtMs: Long,
    spanMinutes: Float,
    // The panel's OWN clock, handed down rather than derived again here: the dashboard's value
    // carries the warmup-surviving circadian fallback, and a second derivation would drift from
    // the axis the chart just drew.
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
    // The Rust car tune, fetched once off-main: it is an FFI call, and the art needs the same
    // numbers the solver was built with rather than a transcription of them.
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
        // BOTH in-process alarm writers, because the game yields the ACTUATOR and there are two
        // things that can seize it: the deterministic engine through `AndroidAlarmNotifier`,
        // and the predictive presenter, which owns a second `VibrationActuator` of its own and
        // is not routed through the notifier at all.
        //
        // DEATH mode is a deliberate fail-OPEN override of §3.6's PRESENTATION: the engine goes
        // on firing, but the user has said they want none of it announced. A game that froze
        // itself while every alarm was silenced would contradict exactly that, so the interlock
        // is reported clear here — and only here, never in the engine.
        //
        // The over-temperature alarm is the exception, because it is not one of the announcements
        // DEATH overrides: it is about the phone, not the glucose, and the user consented to
        // silencing the latter. It keeps the interlock whatever DEATH says.
        // CRITICAL TIERS ONLY, by explicit choice. `alarm.isActive` here meant that ANY active alarm
        // took the actuator off the game, so an ordinary hypo or hyper — a WARNING tier, which on a
        // normal day is up for hours — left the game mute with nothing to say why, and read as a fault.
        // The user asked for sound and touch through those, and they are advisory announcements rather
        // than ones meant to wake anybody.
        //
        // What still yields: the urgent tiers (URGENT_LOW / URGENT_HIGH are `AlarmSeverity.CRITICAL`),
        // the predictive urgent alert, and the over-temperature notice. Those are the announcements that
        // must not be masked — `:alerts` buzzes the SAME actuator, and a game holding a sustained bed
        // could supersede exactly the alarm intended to wake a sleeping user. That is the property worth
        // keeping, and narrowing the tier keeps it while costing nothing on a routine excursion.
        alarmRaised = (
            alarm.alarms.any { it.severity == AlarmSeverity.CRITICAL } ||
                alarm.overTemperature != null ||
                predicted
            ) && (!death || alarm.overTemperature != null),
        onExit = onExit,
    )
}

