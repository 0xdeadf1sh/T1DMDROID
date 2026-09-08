package com.t1dm.app.widget

import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as material3ColorProviders
import com.t1dm.core.design.T1dmColorScheme

/** A get(), not a cached val: the FGS seeds T1dmColorScheme just before it pushes the tiles. */
internal val T1dmGlanceColors: ColorProviders get() = material3ColorProviders(T1dmColorScheme)
