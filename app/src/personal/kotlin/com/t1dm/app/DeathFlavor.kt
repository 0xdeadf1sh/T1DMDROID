package com.t1dm.app

// PERSONAL flavor: the fail-open DEATH override (§3.6) is available.
// The public flavor stubs SUPPORTED to false so the override is structurally impossible there.
object DeathFlavor {
    const val SUPPORTED = true
}
