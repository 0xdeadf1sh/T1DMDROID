package com.t1dm.core.model

/** One output of a twin scan; [key] is the stored name, so renaming a label never orphans rows. */
enum class TwinTrait(val key: String, val label: String, val unit: String, val min: Double, val max: Double) {
    AGE("age_years", "Age", "y", 18.0, 90.0),
    SEX("sex_male_p", "Sex", "P(male)", 0.0, 1.0),
    HEIGHT("height_cm", "Height", "cm", 145.0, 205.0),
    WEIGHT("weight_kg", "Weight", "kg", 40.0, 150.0),
    MUSCLE("muscle", "Muscle", "", 0.0, 1.0),
    BODY_FAT("body_fat_pct", "Body fat", "%", 5.0, 50.0),
    SURVIVAL_1Y("survival_1y", "Survival 1 y", "", 0.0, 1.0),
    SURVIVAL_5Y("survival_5y", "Survival 5 y", "", 0.0, 1.0),
    SURVIVAL_10Y("survival_10y", "Survival 10 y", "", 0.0, 1.0),
    SURVIVAL_20Y("survival_20y", "Survival 20 y", "", 0.0, 1.0);

    companion object {
        fun forKey(key: String): TwinTrait? = entries.firstOrNull { it.key == key }
        val SURVIVAL = listOf(SURVIVAL_1Y, SURVIVAL_5Y, SURVIVAL_10Y, SURVIVAL_20Y)
    }
}

/** Raw TEXT in storage: a source a later build adds stays readable. */
object TwinSource {
    const val MOCK = "mock"
    const val SERVER = "server"
}

/** [outputs] keyed by [TwinTrait.key]; unknown keys from a newer contract are kept, not dropped. */
data class TwinScan(
    val id: Long,
    val atMs: Long,
    val source: String,
    val outputs: Map<String, Double>,
) {
    operator fun get(trait: TwinTrait): Double? = outputs[trait.key]
}
