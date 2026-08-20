package com.t1dm.core.model

/**
 * The adapter's four attachment sites as one stored integer, and back.
 *
 * ONE definition, because the bits are written into `lora.targets` by the fit and read back by every
 * surface that has to say what an adapter touches. Two hand-rolled packings that agree on the day
 * they are written is exactly how a stored adapter comes to describe sites it does not have.
 *
 * The order is the crate's own site order — hidden → l0 → l1 → l2 — the order `LoraWeights.params`
 * concatenates in, so a reader walking the bits walks the parameter blocks.
 */
object LoraSites {
    const val HIDDEN = 1 shl 0
    const val L0 = 1 shl 1
    const val L1 = 1 shl 2
    const val L2 = 1 shl 3

    /** Hidden and l0 off. The hidden bottleneck sits on the state the whole head reads, so a rank-1
     *  map there can null the dose direction outright — the collapse the attach guard exists to
     *  catch. l1 and l2 are late enough that the same rank cannot. */
    const val DEFAULT = L1 or L2

    fun labelOf(bits: Int): String = buildList {
        if (bits and HIDDEN != 0) add("hidden")
        if (bits and L0 != 0) add("l0")
        if (bits and L1 != 0) add("l1")
        if (bits and L2 != 0) add("l2")
    }.joinToString("+").ifEmpty { "none" }
}

/** The sites this config names, packed for storage. */
fun LoraConfig.targetBits(): Int =
    (if (targetHidden) LoraSites.HIDDEN else 0) or
        (if (targetL0) LoraSites.L0 else 0) or
        (if (targetL1) LoraSites.L1 else 0) or
        (if (targetL2) LoraSites.L2 else 0)
