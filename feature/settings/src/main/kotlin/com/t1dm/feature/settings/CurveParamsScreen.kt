package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Read-only view of the carb-appearance / insulin-action curve defaults (PLAN.private.md Phase 7C
 * item 14 — "curve/PK params defaults"). These shape how a logged meal or dose is turned into the
 * appearance (Ra) / PK-action curve the model and calculator see (model-io-curves.md). They are shown
 * transparently here; live per-curve editing (the Bézier editors) is Phase 7D's deliverable, so this
 * screen deliberately does not mutate them. Populated by the app from `CurveEngine.Presets`.
 */
data class CurveParams(
    val bolusGammaK: Double,
    val bolusGammaTheta: Double,
    val bolusDiaBaseHours: Double,
    val basalKaPerHour: Double,
    val basalKePerHour: Double,
    val lantusDiaHours: Double,
    val tresibaDiaHours: Double,
    val carbHighGiK: Double,
    val carbHighGiTheta: Double,
    val carbLowGiK: Double,
    val carbLowGiTheta: Double,
)

@Composable
fun CurveParamsScreen(params: CurveParams) {
    SettingsScaffold("Curve & PK parameters") {
        SettingsNote(
            "How the app turns a logged meal or dose into the absorption / action curve the model and " +
                "calculator use. These are the compiled defaults; per-curve editing arrives with the " +
                "curve editors in a later update.",
        )

        SettingsSectionHeader("Rapid-acting bolus (dose-scaled gamma)")
        Kv("Shape k", "%.2f".format(params.bolusGammaK))
        Kv("Scale θ (min)", "%.1f".format(params.bolusGammaTheta))
        Kv("Base duration of action", "%.1f h".format(params.bolusDiaBaseHours))

        SettingsSectionHeader("Long-acting basal (Bateman)")
        Kv("Absorption kₐ", "%.2f /h".format(params.basalKaPerHour))
        Kv("Elimination kₑ", "%.2f /h".format(params.basalKePerHour))
        Kv("Lantus duration", "%.0f h".format(params.lantusDiaHours))
        Kv("Tresiba duration", "%.0f h".format(params.tresibaDiaHours))

        SettingsSectionHeader("Carb appearance (GI → gamma)")
        SettingsNote("High-GI carbs peak early and sharp; low-GI carbs spread out.")
        Kv("High GI k / θ", "%.1f / %.0f".format(params.carbHighGiK, params.carbHighGiTheta))
        Kv("Low GI k / θ", "%.1f / %.0f".format(params.carbLowGiK, params.carbLowGiTheta))
    }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
