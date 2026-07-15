package com.t1dm.app

// PUBLIC flavor: the fail-open DEATH override is compiled out. With SUPPORTED = false the persisted
// death.enabled key is never read or written, so deathMode/currentDeathMode are pinned false and the
// §3.6 rail/degeneracy-gate bypass, alarm silencing, and warning suppression cannot engage.
object DeathFlavor {
    const val SUPPORTED = false
}
