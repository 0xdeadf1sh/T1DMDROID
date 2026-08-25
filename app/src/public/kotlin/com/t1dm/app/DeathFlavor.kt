package com.t1dm.app

// Fail-closed: death.enabled is never read or written here, so the §3.6 rail bypass, alarm
// silencing and warning suppression cannot engage.
object DeathFlavor {
    const val SUPPORTED = false
}
