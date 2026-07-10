package com.t1dm.core.model

/**
 * The device-temperature display unit (U9 — no fan, so we show a genuinely readable device
 * temperature instead). Persisted by [key]; the source value is always Celsius (the raw
 * BatteryManager reading), converted for display. The fan RPM is unreadable on the K90 (permission
 * denied even to adb shell), so no fan figure is ever synthesised — only a labelled temperature.
 */
enum class TempUnit(val key: String, val label: String) {
    CELSIUS("C", "°C"),
    FAHRENHEIT("F", "°F"),
    KELVIN("K", "K");

    /** Format a Celsius reading in this unit, e.g. `30.2°C` / `86.4°F` / `303.4K`. */
    fun format(celsius: Double): String = when (this) {
        CELSIUS -> "%.1f°C".format(celsius)
        FAHRENHEIT -> "%.1f°F".format(celsius * 9.0 / 5.0 + 32.0)
        KELVIN -> "%.1fK".format(celsius + 273.15)
    }

    companion object {
        fun fromKey(key: String?): TempUnit = entries.firstOrNull { it.key == key } ?: CELSIUS
    }
}
