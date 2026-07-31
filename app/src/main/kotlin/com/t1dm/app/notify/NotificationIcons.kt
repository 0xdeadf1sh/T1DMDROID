package com.t1dm.app.notify

import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import com.t1dm.app.R
import com.t1dm.core.design.IconStyle
import com.t1dm.core.design.resolvePalette

/**
 * Resolves the per-theme notification SMALL icons (issue I1). The platform draws a notification small
 * icon as a monochrome alpha SILHOUETTE and tints it itself, so "per-theme" cannot mean colour — it
 * means the GLYPH GEOMETRY changes with the theme, exactly as the nav/launcher icons do
 * ([com.t1dm.core.design.NavIcons]): Tron is thin/angular (stroked, mitred), Umbrella is blocky
 * (solid-filled), Hello Kitty is soft (thick stroke, rounded). Each glyph exists as one vector drawable
 * per style (`ic_notif_<glyph>_<style>.xml`); this picks the one for the active [IconStyle].
 *
 * The accent SWATCH — the small tinted band the system paints beside the icon in the shade — is set
 * from the active theme's `primary` role via `setColor(...)`, so the alarm/monitor notifications carry
 * the theme colour without disturbing the silhouette.
 */
object NotificationIcons {

    enum class Glyph { MONITOR, WARNING, ALARM, DOSE, SIGNAL_LOSS }

    @DrawableRes
    fun res(glyph: Glyph, style: IconStyle): Int = when (glyph) {
        Glyph.MONITOR -> when (style) {
            IconStyle.TRON -> R.drawable.ic_notif_monitor_tron
            IconStyle.UMBRELLA -> R.drawable.ic_notif_monitor_umbrella
            IconStyle.KITTY -> R.drawable.ic_notif_monitor_kitty
        }
        Glyph.WARNING -> when (style) {
            IconStyle.TRON -> R.drawable.ic_notif_warning_tron
            IconStyle.UMBRELLA -> R.drawable.ic_notif_warning_umbrella
            IconStyle.KITTY -> R.drawable.ic_notif_warning_kitty
        }
        Glyph.ALARM -> when (style) {
            IconStyle.TRON -> R.drawable.ic_notif_alarm_tron
            IconStyle.UMBRELLA -> R.drawable.ic_notif_alarm_umbrella
            IconStyle.KITTY -> R.drawable.ic_notif_alarm_kitty
        }
        Glyph.DOSE -> when (style) {
            IconStyle.TRON -> R.drawable.ic_notif_dose_tron
            IconStyle.UMBRELLA -> R.drawable.ic_notif_dose_umbrella
            IconStyle.KITTY -> R.drawable.ic_notif_dose_kitty
        }
        Glyph.SIGNAL_LOSS -> when (style) {
            IconStyle.TRON -> R.drawable.ic_notif_signal_loss_tron
            IconStyle.UMBRELLA -> R.drawable.ic_notif_signal_loss_umbrella
            IconStyle.KITTY -> R.drawable.ic_notif_signal_loss_kitty
        }
    }

    /** An [Icon] wrapping the themed drawable — for surfaces (`:alerts`) that cannot reach `:app`'s `R`. */
    fun icon(context: Context, glyph: Glyph, style: IconStyle): Icon =
        Icon.createWithResource(context, res(glyph, style))

    /**
     * The active theme's accent (`primary`) as an ARGB int for `Notification.Builder.setColor`. Reads a
     * bundled palette by id, or the persisted custom-theme JSON; any failure falls back to the default.
     *
     * The id→palette rule is [resolvePalette]'s and is no longer restated here: this was a verbatim
     * second copy of its branch — down to the same Tron fallback, `paletteForId(null)` being
     * `TronPalette` — in a codebase whose KDoc calls that function "the ONE place that decodes a custom
     * theme, so the Activity, the FGS notification, and the widget all agree".
     *
     * A caller needing both the accent and the palette should resolve ONCE and take `.primary.toArgb()`
     * itself rather than call this as well: behind a custom theme each resolution is a fresh JSON parse.
     */
    fun accentArgb(themeId: String?, customThemeJson: String?): Int =
        resolvePalette(themeId, customThemeJson).primary.toArgb()
}
