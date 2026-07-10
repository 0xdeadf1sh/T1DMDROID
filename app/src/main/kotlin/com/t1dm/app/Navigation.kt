package com.t1dm.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.iconStyleForTheme
import com.t1dm.core.design.navEnter
import com.t1dm.core.design.navExit
import com.t1dm.core.design.navIcon
import com.t1dm.app.di.AppContainer.BolusAdviceUi
import com.t1dm.app.service.DoseCalcService
import com.t1dm.feature.insulin.BolusCalculatorScreen
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.BezierCurve
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
import kotlinx.coroutines.withContext
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
import com.t1dm.feature.settings.ForecastBackendScreen
import com.t1dm.feature.settings.WatchSettingsScreen
import com.t1dm.alerts.VibrationPreset
import com.t1dm.app.settings.SettingsStore
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.settings.GraphSettingsStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

private data class Destination(val route: String, val label: String)

// Item 13 — a LARGE-ICON, HORIZONTALLY-SCROLLABLE row (no fixed-bar overflow). Each tile draws the
// per-theme vector glyph (issues 2/6 — geometry re-derived from the active theme via [navIcon]); no
// icon dependency is on the classpath, so these are authored Compose ImageVectors.
private val destinations = listOf(
    Destination("dashboard", "BG"),
    Destination("circadian", "Clock"),
    Destination("stats", "Stats"),
    Destination("models", "Models"),
    Destination("hardware", "HW"),
    Destination("network", "Net"),
    Destination("meals", "Meals"),
    Destination("insulin", "Insulin"),
    Destination("security", "Sec"),
    Destination("journal", "Journal"),
    Destination("settings", "Set"),
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
            Breadcrumb(navController, container)
            T1dmNavHost(navController, container)
        }
    }
}

/** One node in the location trail (issue 14). [route] non-null ⇒ tappable to ascend to it. */
private data class Crumb(val label: String, val route: String?)

/** The path from a top-level section down to the current sub-screen, most-recent last. The final
 *  crumb is the current screen (never tappable). Intermediate crumbs ascend to their route. */
private fun crumbsFor(route: String?, modelId: String?): List<Crumb> {
    fun settings(vararg tail: Crumb) = listOf(Crumb("Settings", "settings"), *tail)
    return when (route) {
        null, "dashboard" -> listOf(Crumb("BG", null))
        "circadian" -> listOf(Crumb("Circadian clock", null))
        "stats" -> listOf(Crumb("Stats", null))
        "models" -> listOf(Crumb("Models", null))
        "models/{modelId}" -> listOf(Crumb("Models", "models"), Crumb(modelId ?: "model", null))
        "hardware" -> listOf(Crumb("Hardware", null))
        "network" -> listOf(Crumb("Network", null))
        "meals" -> listOf(Crumb("Meals", null))
        "meals/builder" -> listOf(Crumb("Meals", "meals"), Crumb("Meal builder", null))
        "insulin" -> listOf(Crumb("Insulin", null))
        "insulin/types" -> listOf(Crumb("Insulin", "insulin"), Crumb("Types & curves", null))
        "insulin/bolusCalc" -> listOf(Crumb("Insulin", "insulin"), Crumb("Bolus advisor", null))
        "security" -> listOf(Crumb("Security", null))
        "journal" -> listOf(Crumb("Journal", null))
        "settings" -> listOf(Crumb("Settings", null))
        "about" -> settings(Crumb("About", null))
        "settings/display" -> settings(Crumb("Display & theme", null))
        "settings/graph" -> settings(Crumb("Graph", null))
        "settings/alarms" -> settings(Crumb("Alarms & safety", "settings"), Crumb("Thresholds", null))
        "settings/signal" -> settings(Crumb("Alarms & safety", "settings"), Crumb("Signal safety", null))
        "settings/alerts" -> settings(Crumb("Sound & vibration", null))
        "settings/warmup" -> settings(Crumb("Warmup", null))
        "settings/backend" -> settings(Crumb("Compute backend", null))
        "settings/calculator" -> settings(Crumb("Bolus calculator", null))
        "settings/curves" -> settings(Crumb("Curve & PK", null))
        "settings/cgm" -> settings(Crumb("CGM source", null))
        "settings/server" -> settings(Crumb("Server", null))
        "settings/watch" -> settings(Crumb("Watch", null))
        "settings/power" -> settings(Crumb("Low power", null))
        "settings/data" -> settings(Crumb("Backup & reset", null))
        else -> listOf(Crumb(route, null))
    }
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
    val crumbs = remember(route, modelId) { crumbsFor(route, modelId) }
    val cs = MaterialTheme.colorScheme
    // N10 — the app short name + short version (major.minor.patch, suffix stripped) on the right.
    val shortVersion = remember { BuildConfig.VERSION_NAME.substringBefore('-') }
    // U1 — the app-wide glycemic status, recomputed as the forecast state changes.
    val inference by container.inferenceState.collectAsState(InferenceState())
    val status = remember(inference) { glycemicStatusOf(inference, container.alarmConfig.thresholds) }
    Row(
        // N1 — the breadcrumb bar shares the app BACKGROUND (not a surface tint) so it reads as chrome
        // over the same canvas.
        Modifier
            .fillMaxWidth()
            .background(cs.background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            // The trail scrolls sideways so a long path never wraps; the status + version stay pinned.
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
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
                            .clickable {
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
        Text(
            "T1DM · $shortVersion",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

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
private fun glycemicStatusOf(inf: InferenceState, thr: com.t1dm.core.model.AlertThresholds?): GlyStatus {
    inf.warmup?.let {
        return GlyStatus.Void(
            "Collecting context — %.1f / %.0f h of measured glucose so far. No stable-or-excursion call is made until enough real history exists to forecast on."
                .format(it.measuredHours, it.requiredHours),
        )
    }
    val p = inf.selectedPrediction
        ?: return GlyStatus.Void("No forecast yet — waiting for the first model cycle on live glucose.")
    if (p.stale) {
        return GlyStatus.Void("The forecast's anchor reading is stale (older than the freshness gate). No status is claimed off an aged reading.")
    }
    if (p.status != com.t1dm.core.model.ForecastStatus.OK) {
        return GlyStatus.Void("The forecast is degenerate (collapsed or rail-pinned) and is ineligible, so no glycemic status is claimed.")
    }
    thr ?: return GlyStatus.Void("No glucose thresholds are configured to judge a crossing against.")
    val nowMs = System.currentTimeMillis()
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
    val alpha = if (animationsOn && periodMs > 0) {
        val transition = rememberInfiniteTransition(label = "status")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = floor,
            animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Reverse),
            label = "statusAlpha",
        ).value
    } else 1f
    val mod = if (status is GlyStatus.Void) {
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { android.widget.Toast.makeText(ctx, status.reason, android.widget.Toast.LENGTH_LONG).show() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    } else {
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    }
    Text(
        status.text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = color.copy(alpha = color.alpha * alpha),
        maxLines = 1,
        modifier = mod,
    )
}

/**
 * The bottom navigation (item 13): a horizontally-scrollable row of large-icon tiles, one per
 * destination, so all ~11 tabs are reachable by scrolling instead of cramming into a fixed bar. The
 * selected tile is marked with a themed pill + a coloured label; on selection it is scrolled into
 * view so the current destination always stays visible.
 */
@Composable
private fun T1dmBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination?.route
    val scrollState = rememberScrollState()
    val selectedIndex = destinations.indexOfFirst { it.route == current }
    val animationsOn = LocalAnimationsEnabled.current

    // U7 — scroll so the SELECTED tile is always FULLY visible, first and last tiles included. The
    // prior heuristic (an averaged tile width times the index) overshot for edge tiles because tiles
    // aren't equal-width (the selected one is wider). Instead we record each tile's real content-space
    // edges + the viewport width and nudge the scroll only as far as needed to reveal the selected one.
    val tileEdges = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    var viewportW by remember { mutableStateOf(0) }
    val selectedEdges = tileEdges[selectedIndex]
    LaunchedEffect(selectedIndex, selectedEdges, viewportW, scrollState.maxValue, animationsOn) {
        val edges = selectedEdges ?: return@LaunchedEffect
        if (viewportW <= 0) return@LaunchedEffect
        val (l, r) = edges
        val pad = 12
        val cur = scrollState.value
        val target = when {
            l - pad < cur -> l - pad
            r + pad > cur + viewportW -> r + pad - viewportW
            else -> cur
        }.coerceIn(0, scrollState.maxValue)
        if (target != cur) {
            if (animationsOn) scrollState.animateScrollTo(target) else scrollState.scrollTo(target)
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { viewportW = it.width }
                .horizontalScroll(scrollState)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            destinations.forEachIndexed { index, d ->
                NavTile(
                    destination = d,
                    selected = current == d.route,
                    onEdges = { l, r -> tileEdges[index] = l to r },
                    onClick = {
                        navController.navigate(d.route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo("dashboard") { saveState = true }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NavTile(destination: Destination, selected: Boolean, onEdges: (Int, Int) -> Unit, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.primary.copy(alpha = 0.16f) else Color.Transparent
    val style = iconStyleForTheme(LocalT1dmSemantics.current.id)
    val icon = remember(destination.route, style) { navIcon(destination.route, style) }
    Column(
        Modifier
            .onGloballyPositioned {
                val left = it.positionInParent().x.toInt()
                onEdges(left, left + it.size.width)
            }
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .widthIn(min = 64.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = destination.label,
            tint = if (selected) cs.primary else cs.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Text(
            destination.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) cs.primary else cs.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun T1dmNavHost(navController: NavHostController, container: AppContainer) {
    // Issue 17 — the "disable all animations" flag must collapse the screen crossfade to a snap.
    val animationsOn = LocalAnimationsEnabled.current
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
            val latest by container.latestReading.collectAsState(null)
            val active by container.activeSource.collectAsState(null)
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
            DashboardScreen(
                readings = readings,
                latest = latest,
                activeSourceName = active?.displayName,
                thresholds = container.alarmConfig.thresholds,
                predictions = inference.predictions,
                kovatchevF = container.nativeCore::kovatchevF,
                iobCob = iobCob,
                curveChannels = container::dashboardCurveChannels,
                basalChannel = container::dashboardBasalChannel,
                warmup = inference.warmup,
                rangeMinMgdl = range.minMgdl,
                rangeMaxMgdl = range.maxMgdl,
                initialWindowHours = windowHours,
                onSetWindowHours = { h -> scope.launch { container.setGraphWindowHours(h) } },
                reachability = reachability,
                signals = signals,
                deviceTempC = deviceTempC,
                temperatureUnit = tempUnit,
                circadianTime = inference.circadianTime,
                circadianAnchorMs = inference.circadianAnchorMs,
                smoothMgdl = { arr -> container.nativeCore.causalSmooth(arr.toList(), 20.0, 500.0).toDoubleArray() },
            )
        }
        composable("circadian") {
            val inference by container.inferenceState.collectAsState(InferenceState())
            CircadianScreen(
                predictedTime = inference.selectedPredictedTime,
                realBackendAvailable = inference.realBackendAvailable,
                hasTimeSection = inference.selectedHasTimeSection,
                // The real reason during warmup / before the first cycle — never "no time section".
                warmingUp = inference.warmup != null ||
                    inference.lastCause == InferenceCause.COLLECTING_CONTEXT ||
                    inference.lastCycleTsMs == null,
                lowContext = inference.circadianLowContext,
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
                if (uri == null) { exportStatus = "PDF export cancelled." }
                else if (composite == null) { exportStatus = "No statistics to export yet." }
                else scope.launch {
                    exportStatus = runCatching {
                        ctx.contentResolver.openOutputStream(uri)?.use {
                            com.t1dm.app.stats.StatsPdf.write(it, composite)
                        } ?: error("could not open the chosen file for writing")
                        "Exported the statistics report to the chosen file."
                    }.getOrElse { "PDF export failed — ${it.message ?: it::class.simpleName}." }
                }
            }
            StatsScreen(
                state = statsState,
                kovatchevF = container.nativeCore::kovatchevF,
                onSelectWindow = container.statsViewModel::selectWindow,
                onSetUnitSpace = container.statsViewModel::setUnitSpace,
                onSetTargetRange = container.statsViewModel::setTargetRange,
                onRecompute = container.statsViewModel::recompute,
                onExportPdf = { exportStatus = null; pdfLauncher.launch("t1dm-stats-${statsState.window.wire}.pdf") },
                exportStatus = exportStatus,
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
            val tempUnit by container.temperatureUnit.collectAsState(com.t1dm.core.model.TempUnit.CELSIUS)
            HardwareScreen(state = inference, hardware = hardware, temperatureUnit = tempUnit)
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
                    previewBasal = container.previewBasalCurve,
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
                onOpenComputeBackend = { navController.navigate("settings/backend") },
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
            val ctx = LocalContext.current
            val statsState by container.statsViewModel.state.collectAsState()
            val ss = container.settingsStore
            val animations by ss.animationsEnabled.collectAsState(true)
            val themeId by ss.themeId.collectAsState(SettingsStore.DEFAULT_THEME)
            val fontId by ss.fontId.collectAsState(SettingsStore.DEFAULT_FONT)
            val tempUnit by container.temperatureUnit.collectAsState(com.t1dm.core.model.TempUnit.CELSIUS)
            val customJson by ss.customThemeJson.collectAsState(null)
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
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri == null) { importStatus = "Theme import cancelled." } else scope.launch {
                    importStatus = runCatching {
                        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                            ?: error("could not open the chosen file for reading")
                        val palette = parseThemeJson(text) // validates + throws a plain-language message
                        ss.setCustomThemeJson(text)
                        ss.setThemeId(ThemeIds.CUSTOM)
                        "Loaded custom theme \"${palette.displayName}\"."
                    }.getOrElse { it.message ?: "Theme import failed." }
                }
            }
            DisplaySettingsScreen(
                unitSpace = statsState.unitSpace,
                targetLow = statsState.targetRange.lowMgdl,
                targetHigh = statsState.targetRange.highMgdl,
                animationsEnabled = animations,
                themeOptions = themeOptions,
                selectedThemeId = themeId,
                fontOptions = fontOptions,
                selectedFontId = fontId,
                customThemeName = customName,
                importStatus = importStatus,
                temperatureUnit = tempUnit,
                onSetUnitSpace = { container.statsViewModel.setUnitSpace(it) },
                onSetTargetRange = { lo, hi -> container.statsViewModel.setTargetRange(lo, hi) },
                onSetAnimationsEnabled = { on -> scope.launch { ss.setAnimationsEnabled(on) } },
                onSelectTheme = { id -> scope.launch { ss.setThemeId(id) } },
                onSelectFont = { id -> scope.launch { ss.setFontId(id) } },
                onImportCustomTheme = { importStatus = null; importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onSetTemperatureUnit = { u -> scope.launch { container.setTemperatureUnit(u) } },
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
            val presetCatalog by produceState(emptyList<com.t1dm.core.model.InsulinPresetSpec>()) {
                value = container.insulinPresetCatalog()
            }
            val selRapid by container.settingsStore.selectedRapidPreset.collectAsState("")
            val selBasal by container.settingsStore.selectedBasalPreset.collectAsState("")
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
                carbCurve = carbCurve,
                insulinCurve = insulinCurve,
                onSaveCarbCurve = { c -> scope.launch { container.settingsStore.setCarbBezier(BezierCurve.encode(c)) } },
                onSaveInsulinCurve = { c -> scope.launch { container.settingsStore.setInsulinBezier(BezierCurve.encode(c)) } },
                presetCatalog = presetCatalog,
                selectedRapidLabel = selRapid,
                selectedBasalLabel = selBasal,
                onSelectRapid = { l -> scope.launch { container.settingsStore.setRapidPreset(l) } },
                onSelectBasal = { l -> scope.launch { container.settingsStore.setBasalPreset(l) } },
                previewPreset = { spec -> container.previewPresetCurve(spec) },
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
            var resetting by remember { mutableStateOf(false) }
            DataSettingsScreen(
                status = status,
                resetting = resetting,
                onExport = { status = null; exportLauncher.launch("t1dm-config.json") },
                onImport = { status = null; importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onReset = {
                    if (!resetting) {
                        resetting = true
                        scope.launch {
                            container.resetAllData()
                            container.restartApp() // fresh process ⇒ first-run state; never returns
                        }
                    }
                },
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
        composable("settings/backend") {
            val scope = rememberCoroutineScope()
            val st by container.inferenceState.collectAsState()
            val requested by container.forecastBackendSetting.collectAsState(null)
            val sel = st.running.firstOrNull { it.selected }
            ForecastBackendScreen(
                catalog = st.backendCatalog,
                requested = requested,
                executing = sel?.backend,
                executingPrecision = sel?.precision,
                comparison = st.backendComparison,
                onSelect = { b -> scope.launch { container.setForecastBackend(b) } },
                onRunComparison = { scope.launch { container.runBackendComparison() } },
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
