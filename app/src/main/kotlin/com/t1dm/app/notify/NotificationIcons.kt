package com.t1dm.app.notify

import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import com.t1dm.app.R
import com.t1dm.core.design.IconStyle
import com.t1dm.core.design.paletteForId
import com.t1dm.core.design.parseThemeJson

/**
 * Resolves the per-theme notification SMALL icons (issue I1). The platform draws a notification small
 * icon as a monochrome alpha SILHOUETTE and tints it itself, so "per-theme" cannot mean colour — it
 * means the GLYPH GEOMETRY changes with the theme, exactly as the nav/launcher icons do
 * ([com.t1dm.core.design.NavIcons]): Tron is thin/angular (stroked, mitred), Umbrella is blocky
 * (solid-filled), Hello Kitty is soft (thick stroke, rounded), Windows XP is a medium rounded-join
 * stroke, Teto is a chunky square-capped stroke. Each glyph exists as one vector drawable per style
 * (`ic_notif_<glyph>_<style>.xml`); this picks the one for the active [IconStyle].
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
            IconStyle.XP -> R.drawable.ic_notif_monitor_xp
            IconStyle.TETO -> R.drawable.ic_notif_monitor_teto
        }
        Glyph.WARNING -> when (style) {
            IconStyle.TRON -> R.drawable.ic_notif_warning_tron
            IconStyle.UMBRELLA -> R.drawable.ic_notif_warning_umbrella
            IconStyle.KITTY -> R.drawable.ic_notif_warning_kitty
            IconStyle.XP -> R.drawable.ic_notif_warning_xp
            IconStyle.TETO -> R.drawable.ic_notif_warning_teto
        }
        Glyph.ALARM -> when (style) {
            IconStyle.TRON -> R.drawable.ic_notif_alarm_tron
            IconStyle.UMBRELLA -> R.drawable.ic_notif_alarm_umbrella
            IconStyle.KITTY -> R.drawable.ic_notif_alarm_kitty
            IconStyle.XP -> R.drawable.ic_notif_alarm_xp
            IconStyle.TETO -> R.drawable.ic_notif_alarm_teto
        }
        Glyph.DOSE -> when (style) {
            IconStyle.TRON -> R.drawable.ic_notif_dose_tron
            IconStyle.UMBRELLA -> R.drawable.ic_notif_dose_umbrella
            IconStyle.KITTY -> R.drawable.ic_notif_dose_kitty
            IconStyle.XP -> R.drawable.ic_notif_dose_xp
            IconStyle.TETO -> R.drawable.ic_notif_dose_teto
        }
        Glyph.SIGNAL_LOSS -> when (style) {
            IconStyle.TRON -> R.drawable.ic_notif_signal_loss_tron
            IconStyle.UMBRELLA -> R.drawable.ic_notif_signal_loss_umbrella
            IconStyle.KITTY -> R.drawable.ic_notif_signal_loss_kitty
            IconStyle.XP -> R.drawable.ic_notif_signal_loss_xp
            IconStyle.TETO -> R.drawable.ic_notif_signal_loss_teto
        }
    }

    /** An [Icon] wrapping the themed drawable — for surfaces (`:alerts`) that cannot reach `:app`'s `R`. */
    fun icon(context: Context, glyph: Glyph, style: IconStyle): Icon =
        Icon.createWithResource(context, res(glyph, style))

    /** The active theme's accent (`primary`) as an ARGB int for `Notification.Builder.setColor`. Reads a
     *  bundled palette by id, or the persisted custom-theme JSON; any failure falls back to the default. */
    fun accentArgb(themeId: String?, customThemeJson: String?): Int {
        val palette = runCatching {
            if (themeId == com.t1dm.core.design.ThemeIds.CUSTOM && !customThemeJson.isNullOrBlank()) {
                parseThemeJson(customThemeJson)
            } else {
                paletteForId(themeId)
            }
        }.getOrElse { paletteForId(null) }
        return palette.primary.toArgb()
    }
}
