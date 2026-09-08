package com.t1dm.core.model

/** Crate's own site order: hidden→l0→l1→l2, matching how [LoraWeights.params] concatenates. */
object LoraSites {
    const val HIDDEN = 1 shl 0
    const val L0 = 1 shl 1
    const val L1 = 1 shl 2
    const val L2 = 1 shl 3

    /** Hidden/l0 off (bottleneck a rank-1 map could null); l1/l2 are late enough to be safe. */
    const val DEFAULT = L1 or L2

    fun labelOf(bits: Int): String = buildList {
        if (bits and HIDDEN != 0) add("hidden")
        if (bits and L0 != 0) add("l0")
        if (bits and L1 != 0) add("l1")
        if (bits and L2 != 0) add("l2")
    }.joinToString("+").ifEmpty { "none" }
}

fun LoraConfig.targetBits(): Int =
    (if (targetHidden) LoraSites.HIDDEN else 0) or
        (if (targetL0) LoraSites.L0 else 0) or
        (if (targetL1) LoraSites.L1 else 0) or
        (if (targetL2) LoraSites.L2 else 0)
