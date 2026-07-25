package com.t1dm.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity of the settings search index, and — the part that actually keeps it honest — the
 * assertion that no screen renders a knob the index has never heard of.
 *
 * The type system already forbids most of that: the four shared controls take a [SettingsKnob] and no
 * longer accept a bare label, so an unindexed stepper/toggle/picker does not compile. What it cannot
 * see is a BESPOKE control — the Bézier designers, the graph's own ± stepper, the server's text
 * fields, the warmup dial — which is why each of those is wrapped in [SettingsAnchor]. The source scan
 * below counts every anchoring call site per screen file and holds it equal to that screen's declared
 * entries, so both kinds of knob are covered by one number a reviewer can check.
 */
class SettingsIndexTest {

    // ── integrity ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `ids are unique`() {
        val dupes = SettingsIndex.ALL.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertEquals("duplicate anchor ids", emptySet<String>(), dupes)
    }

    @Test
    fun `every entry is legible`() {
        SettingsIndex.ALL.forEach { knob ->
            assertTrue("blank id", knob.id.isNotBlank())
            assertTrue("${knob.id}: blank label", knob.label.isNotBlank())
            assertTrue("${knob.id}: blank section", knob.section.isNotBlank())
            assertTrue("${knob.id}: no subtitle", knob.subtitle.isNotBlank())
            assertTrue("${knob.id}: no synonyms", knob.synonyms.isNotEmpty())
        }
    }

    @Test
    fun `synonyms are lower-case, trimmed and distinct`() {
        SettingsIndex.ALL.forEach { knob ->
            knob.synonyms.forEach { s ->
                assertEquals("${knob.id}: synonym not normalised", s.lowercase().trim(), s)
                assertTrue("${knob.id}: blank synonym", s.isNotBlank())
            }
            assertEquals(
                "${knob.id}: repeated synonym",
                knob.synonyms.size,
                knob.synonyms.distinct().size,
            )
        }
    }

    @Test
    fun `every indexed screen carries at least one entry`() {
        val covered = SettingsIndex.ALL.map { it.screen }.toSet()
        val missing = SettingsScreenKey.entries.filter { it.indexed && it !in covered }
        assertEquals("screens with no index entry", emptyList<SettingsScreenKey>(), missing)
    }

    @Test
    fun `the hub itself is not indexed — its rows navigate, they do not tune`() {
        assertTrue(SettingsIndex.ALL.none { it.screen == SettingsScreenKey.ROOT })
    }

    @Test
    fun `the public flavor cannot reach the Death rite through search`() {
        assertTrue(SettingsIndex.visible(true).any { it.screen == SettingsScreenKey.DEATH_MODE })
        assertTrue(SettingsIndex.visible(false).none { it.screen == SettingsScreenKey.DEATH_MODE })
        // Nothing else is withheld: the death CLOCK is a display-only projection the hub always shows.
        assertEquals(SettingsIndex.ALL.size - 1, SettingsIndex.visible(false).size)
    }

    @Test
    fun `every id resolves back through the lookup`() {
        SettingsIndex.ALL.forEach { assertEquals(it, SettingsIndex.byId(it.id)) }
        assertNotNull(SettingsIndex.byId("alerts.bypass_dnd"))
        assertEquals(null, SettingsIndex.byId("nope.not.a.knob"))
    }

    // ── no screen has knobs missing from the index ────────────────────────────────────────────────

    @Test
    fun `every anchoring call site on every screen has an index entry`() {
        val dir = screensDir()
        var checked = 0
        SCREEN_SOURCES.forEach { (screen, fileName) ->
            val expected = SettingsIndex.ALL.count { it.screen == screen && it.anchored }
            val actual = anchorCallSites(File(dir, fileName))
            assertEquals(
                "$fileName renders $actual anchored knob(s) but the index declares $expected for $screen",
                expected,
                actual,
            )
            checked++
        }
        assertEquals(SCREEN_SOURCES.size, checked)
    }

    @Test
    fun `screens declared knob-free render no anchored control`() {
        val dir = screensDir()
        KNOB_FREE_SOURCES.forEach { fileName ->
            assertEquals(
                "$fileName grew a knob but is registered as knob-free",
                0,
                anchorCallSites(File(dir, fileName)),
            )
        }
    }

    @Test
    fun `every source file in the package is accounted for`() {
        val known = SCREEN_SOURCES.values.toSet() + KNOB_FREE_SOURCES + INFRASTRUCTURE_SOURCES
        val present = screensDir().listFiles { f: File -> f.name.endsWith(".kt") }!!.map { it.name }.toSet()
        assertEquals("new source file — add it to the index map or the knob-free list", known, present)
    }

    // ── the matcher ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a blank query yields nothing`() {
        assertTrue(searchSettings("").isEmpty())
        assertTrue(searchSettings("   ").isEmpty())
    }

    @Test
    fun `a query that matches nothing yields nothing`() {
        assertTrue(searchSettings("qwertyuiop").isEmpty())
    }

    @Test
    fun `half-remembered words find the right knob`() {
        assertTop("buzz", "display.haptics")
        assertTop("dnd", "alerts.bypass_dnd")
        // The urgent bands merely SAY they pierce DND; the switch that decides it must still win.
        assertTop("do not disturb", "alerts.bypass_dnd")
        assertTop("savgol", "graph.smoothing")
        assertTop("erase", "data.reset")
        assertTop("qr", "server.scan_qr")
        assertTop("wallpaper", "display.background_opacity")
        assertTop("hypoglycaemia", "alarm.urgent_low")
        assertTop("ketoacidosis", "death_clock.dka")
        assertTop("humalog", "curves.rapid_preset")
        assertTop("stacking", "calc.rail_iob_ceiling")
        assertTop("vulkan", "models.compute_backend")
        assertTop("rssi", "signal.weak_enabled")
        assertTop("tailscale", "server.base_url")
        assertTop("factory reset", "data.reset")
    }

    @Test
    fun `a1c finds the stats target band`() {
        val hits = searchSettings("a1c").map { it.id }
        assertTrue("a1c -> $hits", "display.target_low" in hits && "display.target_high" in hits)
    }

    @Test
    fun `risk finds the calculator objective`() {
        assertTrue("calc.objective" in searchSettings("risk").map { it.id })
        assertTrue("calc.objective" in searchSettings("kovatchev").map { it.id })
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(searchSettings("dnd").map { it.id }, searchSettings("DND").map { it.id })
        assertEquals(searchSettings("Savgol").map { it.id }, searchSettings("savgol").map { it.id })
    }

    @Test
    fun `a word prefix matches mid-label`() {
        // "vib" opens a word inside "Alert sound & vibration" / the Vibration pickers.
        val hits = searchSettings("vib").map { it.id }
        assertTrue("vib -> $hits", "alerts.warning_vibration" in hits && "alerts.critical_vibration" in hits)
    }

    @Test
    fun `multiple tokens narrow rather than widen`() {
        val low = searchSettings("low").map { it.id }
        val urgentLow = searchSettings("urgent low").map { it.id }
        assertEquals("alarm.urgent_low", urgentLow.first())
        assertTrue("a conjunction must not match more than its parts", urgentLow.size < low.size)
        // Every hit for "urgent low" must have satisfied BOTH tokens.
        assertTrue(urgentLow.all { it in low })
    }

    @Test
    fun `colliding labels are both reachable and separated by their subtitle`() {
        val hits = searchSettings("iob ceiling").map { it.id }
        assertTrue("iob ceiling -> $hits", "calc.rail_iob_ceiling" in hits && "calc.iob_ceiling_units" in hits)
        val rail = SettingsIndex.byId("calc.rail_iob_ceiling")!!
        val threshold = SettingsIndex.byId("calc.iob_ceiling_units")!!
        assertEquals(rail.label, threshold.label)
        assertTrue("the two must not read identically", rail.subtitle != threshold.subtitle)
    }

    @Test
    fun `a label beats a passing mention in someone else's prose`() {
        assertEquals("alerts.snooze", searchSettings("snooze").first().id)
        assertEquals("graph.ceiling", searchSettings("ceiling").first().id)
    }

    private fun assertTop(query: String, expectedId: String) {
        val hits = searchSettings(query)
        assertTrue("\"$query\" found nothing", hits.isNotEmpty())
        assertEquals("\"$query\" ranked ${hits.map { it.id }}", expectedId, hits.first().id)
    }
}

// ── the source-scan machinery ─────────────────────────────────────────────────────────────────────

/** Gradle runs unit tests with the module directory as the working directory. */
private fun screensDir(): File {
    val dir = File(System.getProperty("user.dir"), "src/main/kotlin/com/t1dm/feature/settings")
    assertTrue("cannot find the settings sources at ${dir.absolutePath}", dir.isDirectory)
    return dir
}

private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val LINE_COMMENT = Regex("""//[^\n]*""")

/** Every route by which a knob can acquire an anchor: the four shared controls, plus the wrapper. */
private val ANCHORING_CALL = Regex("""\b(IntStepper|DoubleStepper|ToggleRow|ChipPicker|SettingsAnchor)\s*\(""")

private fun anchorCallSites(file: File): Int {
    assertTrue("missing source file ${file.name}", file.isFile)
    val body = LINE_COMMENT.replace(BLOCK_COMMENT.replace(file.readText(), ""), "")
    return ANCHORING_CALL.findAll(body).count()
}

/** Screen key → the file that renders it. MODELS is `:feature:models`, so the hub file stands in. */
private val SCREEN_SOURCES = mapOf(
    SettingsScreenKey.DISPLAY to "DisplaySettingsScreen.kt",
    SettingsScreenKey.GRAPH to "GraphSettingsScreen.kt",
    SettingsScreenKey.ALARM_THRESHOLDS to "AlarmThresholdsScreen.kt",
    SettingsScreenKey.SIGNAL to "SignalSafetyScreen.kt",
    SettingsScreenKey.ALERTS to "AlertsSettingsScreen.kt",
    SettingsScreenKey.DEVICE_TEMP to "DeviceTempAlertScreen.kt",
    SettingsScreenKey.WARMUP to "WarmupSettingsScreen.kt",
    SettingsScreenKey.MODEL_COUNT to "ModelCountSettingsScreen.kt",
    SettingsScreenKey.FORECAST_CADENCE to "ForecastCadenceSettingsScreen.kt",
    SettingsScreenKey.THERMAL to "ThermalSettingsScreen.kt",
    SettingsScreenKey.CALCULATOR to "CalculatorSettingsScreen.kt",
    SettingsScreenKey.CURVES to "CurveParamsScreen.kt",
    SettingsScreenKey.CGM to "CgmSettingsScreen.kt",
    SettingsScreenKey.SERVER to "ServerSettingsScreen.kt",
    SettingsScreenKey.WATCH to "WatchSettingsScreen.kt",
    SettingsScreenKey.POWER to "PowerSettingsScreen.kt",
    SettingsScreenKey.DATA to "DataSettingsScreen.kt",
    SettingsScreenKey.DEATH_CLOCK to "DeathClockSettingsScreen.kt",
)

/** Screens whose entries are whole-page: read-only panels, a signpost, and the Death rite. */
private val KNOB_FREE_SOURCES = setOf(
    "SettingsScreen.kt",
    "AboutScreen.kt",
    "DeathModeScreen.kt",
    "DeathArt.kt",
    "FuneralToll.kt",
)

/** Not screens: the shared controls, the anchor plumbing, the index and the search UI. */
private val INFRASTRUCTURE_SOURCES = setOf(
    "SettingsComponents.kt",
    "SettingsAnchors.kt",
    "SettingsIndex.kt",
    "SettingsSearch.kt",
)
