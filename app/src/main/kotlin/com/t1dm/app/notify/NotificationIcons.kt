package com.t1dm.app.notify

import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import com.t1dm.app.R
import com.t1dm.core.design.IconStyle
import com.t1dm.core.design.resolvePalette

/**
 * The platform draws a small icon as a monochrome alpha silhouette and tints it itself, so a theme
 * can only change the glyph geometry: one vector drawable per style, `ic_notif_<glyph>_<style>.xml`.
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

    /** For `:alerts`, which cannot reach `:app`'s `R`. */
    fun icon(context: Context, glyph: Glyph, style: IconStyle): Icon =
        Icon.createWithResource(context, res(glyph, style))

    /** A caller needing the palette too should resolve once and take `.primary.toArgb()` itself:
     *  behind a custom theme each resolution is a fresh JSON parse. */
    fun accentArgb(themeId: String?, customThemeJson: String?): Int =
        resolvePalette(themeId, customThemeJson).primary.toArgb()
}
