package com.t1dm.app.widget

import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as material3ColorProviders
import com.t1dm.core.design.T1dmColorScheme

/**
 * The Glance palette snapshot: the widgets render under the SAME [T1dmColorScheme] the Activity
 * uses, so the home/lock tiles read in the current app theme instead of the launcher's dynamic
 * Material default (PLAN Phase 7B/7D — "palette-snapshot theming"). glance-material3 maps the Compose
 * `ColorScheme` tokens onto Glance's `widgetBackground`/`onSurface`/… providers. Computed on each
 * widget refresh (a `get()` property, NOT a cached `val`) so a theme change picks up the newly-active
 * [T1dmColorScheme] the next time the FGS refreshes the surfaces — no widget-code change per theme.
 */
internal val T1dmGlanceColors: ColorProviders get() = material3ColorProviders(T1dmColorScheme)
