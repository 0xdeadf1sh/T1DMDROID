package com.t1dm.cgm

/** Recovers a wrong CIPHER_ID; not automatic — checksum covers ciphertext, so many keys parse. */
object Ct5KeySearch {

    /** Two frames still left two candidates on the sensor this was written for. */
    const val MIN_FRAMES: Int = 3

    /** Skin temp over samples; a wrong key wanders across frames, the right one doesn't. */
    const val MAX_TEMP_SPREAD_CX100: Int = 200

    /** Ring cap. The search reads every held frame; [MAX_TEMP_SPREAD_CX100] spans all of them. */
    const val MAX_FRAMES: Int = 8

    /** One key decodes every frame, else null (0 or 2+ candidates). k and k^0xFF fold as one. */
    fun recover(perFrame: List<Map<Int, Ct5PushSample>>): Int? {
        if (perFrame.size < MIN_FRAMES) return null
        val survivors = (0..0xFF).filter { key ->
            var low = Int.MAX_VALUE
            var high = Int.MIN_VALUE
            for (byKey in perFrame) {
                val sample = byKey[key] ?: return@filter false
                if (!physical(sample)) return@filter false
                low = minOf(low, sample.tempCx100)
                high = maxOf(high, sample.tempCx100)
            }
            high - low <= MAX_TEMP_SPREAD_CX100
        }
        return survivors.mapTo(HashSet()) { minOf(it, it xor 0xFF) }.singleOrNull()
    }

    /** Background current reads zero on this family; that's what filters the one impostor key. */
    private fun physical(sample: Ct5PushSample): Boolean =
        sample.tempCx100 in Ct5Constants.PLAUSIBLE_TEMP_CX100 &&
            sample.errorCode in Ct5Constants.ACCEPTED_ERROR_CODES &&
            sample.ibX100 == 0
}
