package com.t1dm.core.model

/** Persisted by [key]; the source value is always Celsius. */
enum class TempUnit(val key: String, val label: String) {
    CELSIUS("C", "°C"),
    FAHRENHEIT("F", "°F"),
    KELVIN("K", "K");

    fun format(celsius: Double): String = when (this) {
        CELSIUS -> "%.1f°C".format(celsius)
        FAHRENHEIT -> "%.1f°F".format(celsius * 9.0 / 5.0 + 32.0)
        KELVIN -> "%.1fK".format(celsius + 273.15)
    }

    companion object {
        fun fromKey(key: String?): TempUnit = entries.firstOrNull { it.key == key } ?: CELSIUS
    }
}
