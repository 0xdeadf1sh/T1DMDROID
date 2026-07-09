package com.t1dm.app.widget

import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as material3ColorProviders
import com.t1dm.core.design.T1dmColorScheme

/**
 * The Glance palette snapshot: the widgets render under the SAME [T1dmColorScheme] the Activity
 * uses, so the home/lock tiles read in the current app theme instead of the launcher's dynamic
 * Material default (PLAN Phase 7B — "palette-snapshot theming"). glance-material3 maps the Compose
 * `ColorScheme` tokens onto Glance's `widgetBackground`/`onSurface`/… providers. The app is a single
 * dark Tron scheme today, so day and night resolve to the same providers; when Phase 7D adds the
 * three-theme selector this constant follows [T1dmColorScheme] and every widget re-themes for free.
 */
internal val T1dmGlanceColors: ColorProviders = material3ColorProviders(T1dmColorScheme)
