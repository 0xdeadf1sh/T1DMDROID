package com.t1dm.feature.settings

/**
 * The searchable index of every individual knob in Settings.
 *
 * Twenty-odd sub-screens hold something over eighty separately-tunable things, and the hub only lists
 * the screens — so a knob whose page you cannot name is effectively unreachable. This index is what
 * the search field on the hub matches against.
 *
 * Two invariants keep it honest, and both are structural rather than aspirational:
 *
 *  1. **An entry lives beside the control it describes.** Each screen file declares its own
 *     `settings*Knobs` list at the foot of the file; [SettingsIndex] only concatenates them. A knob
 *     and its index entry therefore move together in review, and a label edited on one line without
 *     the other is a one-screen diff, not an archaeology exercise.
 *  2. **An unindexed knob does not compile.** The four shared controls (SettingsComponents.kt) take a
 *     [SettingsKnob] where they used to take a bare label string, and read their label and subtitle
 *     out of it; bespoke controls are wrapped in [SettingsAnchor]. There is no way to render a knob
 *     that the index does not know about, which is the only anti-drift mechanism that survives
 *     contact with Compose — no host test can see a call site.
 *
 * The module stays route-free (feature screens know callbacks, never routes): an entry names a
 * [SettingsScreenKey], and `:app` maps the key to its route in one table beside `crumbsFor`.
 */
data class SettingsKnob(
    /** Globally unique, hand-assigned. Labels collide ("IOB ceiling" is both a rail and its
     *  threshold; "Play a sound" and "Vibration" each appear twice on one page), so an id derived
     *  from the label could not address a row. */
    val id: String,
    val screen: SettingsScreenKey,
    /** The section header the knob sits under, or "" on a headerless page. */
    val section: String,
    val label: String,
    val subtitle: String = "",
    /** Half-remembered words that should find this knob: the vocabulary the user has, rather than the
     *  vocabulary the label happens to use. Lower-case; matched whole, by prefix and by substring. */
    val synonyms: List<String> = emptyList(),
    /** False for whole-screen entries (About, the Death rite, the model pages) that have no in-page
     *  control to scroll to — navigation still happens, nothing is pulsed. */
    val anchored: Boolean = true,
)

/**
 * The settings destinations, as opaque keys. [breadcrumb] is the trail BELOW the "Settings" root, i.e.
 * exactly what `crumbsFor` renders minus its leading crumb; a `:app` test pins the two together so the
 * path a search result advertises cannot drift from the one the breadcrumb bar shows on arrival.
 */
enum class SettingsScreenKey(val breadcrumb: String, internal val indexed: Boolean = true) {
    /** The hub itself. Its rows are navigation, not knobs, so it carries no entries. */
    ROOT("Settings", indexed = false),
    DISPLAY("Display & theme"),
    GRAPH("Graph"),
    ALARM_THRESHOLDS("Alarms & safety › Thresholds"),
    SIGNAL("Alarms & safety › Signal safety"),
    ALERTS("Sound & vibration"),
    DEVICE_TEMP("Alarms & safety › Device temperature"),
    WARMUP("Warmup"),
    MODEL_COUNT("Models run at once"),
    FORECAST_CADENCE("Forecast cadence"),
    THERMAL("Thermal gate"),
    CALCULATOR("Bolus calculator"),
    CURVES("Curve & PK"),
    MODELS("Models"),
    CGM("CGM source"),
    SERVER("Server"),
    WATCH("Watch"),
    POWER("Low power"),
    DATA("Backup & reset"),
    ABOUT("About"),
    DEATH_CLOCK("Death clock"),
    DEATH_MODE("Death mode"),
}

object SettingsIndex {
    /** Declaration order is the tie-break for equally-scoring hits, so it follows the hub's own order. */
    val ALL: List<SettingsKnob> = buildList {
        addAll(settingsDisplayKnobs)
        addAll(settingsGraphKnobs)
        addAll(settingsAlarmThresholdKnobs)
        addAll(settingsSignalKnobs)
        addAll(settingsAlertKnobs)
        addAll(settingsDeviceTempKnobs)
        addAll(settingsWarmupKnobs)
        addAll(settingsModelCountKnobs)
        addAll(settingsForecastCadenceKnobs)
        addAll(settingsThermalKnobs)
        addAll(settingsCalculatorKnobs)
        addAll(settingsCurveKnobs)
        addAll(settingsModelsKnobs)
        addAll(settingsCgmKnobs)
        addAll(settingsServerKnobs)
        addAll(settingsWatchKnobs)
        addAll(settingsPowerKnobs)
        addAll(settingsDataKnobs)
        addAll(settingsAboutKnobs)
        addAll(settingsDeathClockKnobs)
        addAll(settingsDeathModeKnobs)
    }

    private val byId: Map<String, SettingsKnob> = ALL.associateBy { it.id }

    fun byId(id: String): SettingsKnob? = byId[id]

    /**
     * The index as the running flavor may present it. The public build withholds the DEATH-mode row
     * from the hub (the fail-open override is compiled out), so search must withhold it too — an
     * unfiltered index would otherwise let the public build reach a screen its own hub hides.
     */
    fun visible(deathModeSupported: Boolean): List<SettingsKnob> =
        if (deathModeSupported) ALL else ALL.filterNot { it.screen == SettingsScreenKey.DEATH_MODE }
}

/**
 * Rank [index] against [query]. Forgiving by design — the user is reaching for a knob whose exact
 * wording they do not remember:
 *
 *  - case-insensitive throughout;
 *  - every whitespace-separated token must match SOMETHING (AND across tokens, so "urgent low" beats
 *    "low" alone rather than drowning in it);
 *  - a token matches a field whole, by word-prefix, or as a bare substring, in that order of worth;
 *  - label > synonym > section > subtitle, so "risk" surfaces the objective picker before every screen
 *    whose prose happens to mention risk.
 *
 * Blank query ⇒ no results (the caller shows recent searches instead).
 */
fun searchSettings(query: String, index: List<SettingsKnob> = SettingsIndex.ALL): List<SettingsKnob> {
    val tokens = query.lowercase().split(' ', '\t', '\n', ',', '/').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return emptyList()
    return index
        .mapIndexed { position, knob -> Triple(knob, scoreKnob(knob, tokens), position) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Triple<SettingsKnob, Int, Int>> { it.second }.thenBy { it.third })
        .map { it.first }
        .take(MAX_RESULTS)
}

private const val MAX_RESULTS = 40

private fun scoreKnob(knob: SettingsKnob, tokens: List<String>): Int {
    var total = 0
    for (t in tokens) {
        val best = maxOf(
            fieldScore(knob.label, t, whole = 100, prefix = 82, word = 70, contains = 56),
            knob.synonyms.maxOfOrNull { fieldScore(it, t, whole = 66, prefix = 52, word = 48, contains = 40) } ?: 0,
            fieldScore(knob.section, t, whole = 34, prefix = 30, word = 28, contains = 24),
            fieldScore(knob.subtitle, t, whole = 20, prefix = 18, word = 17, contains = 14),
        )
        // AND across tokens: a query is a conjunction of hints, not a bag of alternatives.
        if (best == 0) return 0
        total += best
    }
    return total
}

private fun fieldScore(field: String, token: String, whole: Int, prefix: Int, word: Int, contains: Int): Int {
    if (field.isEmpty()) return 0
    val f = field.lowercase()
    return when {
        f == token -> whole
        f.startsWith(token) -> prefix
        hasWordStartingWith(f, token) -> word
        f.contains(token) -> contains
        else -> 0
    }
}

/** A token that opens any word in the field — what makes "vib" find "Vibration" mid-sentence. */
private fun hasWordStartingWith(field: String, token: String): Boolean {
    var i = field.indexOf(token)
    while (i >= 0) {
        if (i == 0 || !field[i - 1].isLetterOrDigit()) return true
        i = field.indexOf(token, i + 1)
    }
    return false
}
