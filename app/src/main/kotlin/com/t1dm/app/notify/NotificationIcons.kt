package com.t1dm.app.notify

import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import com.t1dm.app.R
import com.t1dm.core.design.resolvePalette

object NotificationIcons {

    @DrawableRes
    fun res(): Int = R.drawable.ic_notif_curve

    /** For `:alerts`, which cannot reach `:app`'s `R`. */
    fun icon(context: Context): Icon = Icon.createWithResource(context, res())

    /** A caller needing the palette too should resolve once and take `.primary.toArgb()` itself:
     *  behind a custom theme each resolution is a fresh JSON parse. */
    fun accentArgb(themeId: String?, customThemeJson: String?): Int =
        resolvePalette(themeId, customThemeJson).primary.toArgb()
}
