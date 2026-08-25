package com.t1dm.feature.settings

/** Every knob in Settings, for the hub's search field. Each screen file declares its own
 *  `settings*Knobs` list; the shared controls take a [SettingsKnob], so an unindexed knob does not
 *  compile. Entries name a [SettingsScreenKey], never a route. */
data class SettingsKnob(
    /** Globally unique, hand-assigned: labels collide, so an id derived from one could not address
     *  a row. */
    val id: String,
    val screen: SettingsScreenKey,
    /** The section header, or "" on a headerless page. */
    val section: String,
    val label: String,
    val subtitle: String = "",
    /** Lower-case; matched whole, by prefix and by substring. */
    val synonyms: List<String> = emptyList(),
    /** False for whole-screen entries with no in-page control to scroll to. */
    val anchored: Boolean = true,
)

/** [breadcrumb] is the trail BELOW the "Settings" root. */
enum class SettingsScreenKey(val breadcrumb: String, internal val indexed: Boolean = true) {
    ROOT("Settings", indexed = false),
    DISPLAY("Display"),
    GRAPH("Graph"),
    ALARM_THRESHOLDS("Alarms › Thresholds"),
    SIGNAL("Alarms › Signal"),
    ALERTS("Sound"),
    DEVICE_TEMP("Alarms › Device heat"),
    FORECAST("Forecast"),
    CALCULATOR("Bolus calculator"),
    CURVES("Curve & PK"),
    MODELS("Models"),
    CGM("CGM source"),
    SERVER("Server"),
    NIGHTSCOUT("Nightscout"),
    WATCH("Watch"),
    POWER("Low power"),
    DATA("Reset"),

    /** Rendered outside this module; indexed so a search for it still lands there. */
    BACKUP("Backup"),
    ABOUT("About"),
    DEATH_CLOCK("Death clock"),
    DEATH_MODE("Death mode"),
}

object SettingsIndex {
    /** Declaration order is the tie-break for equal scores, so it follows the hub's order. */
    val ALL: List<SettingsKnob> = buildList {
        addAll(settingsDisplayKnobs)
        addAll(settingsGraphKnobs)
        addAll(settingsAlarmThresholdKnobs)
        addAll(settingsSignalKnobs)
        addAll(settingsAlertKnobs)
        addAll(settingsDeviceTempKnobs)
        addAll(settingsForecastKnobs)
        addAll(settingsCalculatorKnobs)
        addAll(settingsCurveKnobs)
        addAll(settingsModelsKnobs)
        addAll(settingsCgmKnobs)
        addAll(settingsServerKnobs)
        addAll(settingsNightscoutKnobs)
        addAll(settingsWatchKnobs)
        addAll(settingsPowerKnobs)
        addAll(settingsDataKnobs)
        addAll(settingsBackupKnobs)
        addAll(settingsAboutKnobs)
        addAll(settingsDeathClockKnobs)
        addAll(settingsDeathModeKnobs)
    }

    private val byId: Map<String, SettingsKnob> = ALL.associateBy { it.id }

    fun byId(id: String): SettingsKnob? = byId[id]

    /** The public build hides the DEATH-mode row, so search must withhold it too. */
    fun visible(deathModeSupported: Boolean): List<SettingsKnob> =
        if (deathModeSupported) ALL else ALL.filterNot { it.screen == SettingsScreenKey.DEATH_MODE }
}

/** Case-insensitive. Field worth: label > synonym > section > subtitle. Blank query ⇒ no results. */
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
        // AND across tokens.
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

private fun hasWordStartingWith(field: String, token: String): Boolean {
    var i = field.indexOf(token)
    while (i >= 0) {
        if (i == 0 || !field[i - 1].isLetterOrDigit()) return true
        i = field.indexOf(token, i + 1)
    }
    return false
}
