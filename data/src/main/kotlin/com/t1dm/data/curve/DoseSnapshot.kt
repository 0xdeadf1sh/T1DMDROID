package com.t1dm.data.curve

import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.CurveEvent

/** Rows read once; [at] drops rows logged after the instant. Later edits and deletes show. */
class DoseSnapshot internal constructor(
    private val meals: List<Logged>,
    private val boluses: List<Logged>,
    private val basalInjections: List<Logged>,
    /** Today's: a schedule keeps no history. */
    private val schedule: BasalSchedule?,
) {
    /** [loggedAtMs] 0 predates the column and counts as always known. */
    internal class Logged(val tsMs: Long, val loggedAtMs: Long, val event: CurveEvent)

    fun at(asOfMs: Long): DoseStore = object : DoseStore {
        override suspend fun carbEvents(fromMs: Long, toMs: Long) = meals.known(fromMs, toMs, asOfMs)

        override suspend fun insulinEvents(fromMs: Long, toMs: Long) = boluses.known(fromMs, toMs, asOfMs)

        override suspend fun basalInjectionEvents(fromMs: Long, toMs: Long) =
            basalInjections.known(fromMs, toMs, asOfMs)

        override suspend fun activeBasalSchedule() = schedule
    }
}

// Inclusive both ends: the DAO's `tsMs BETWEEN :fromMs AND :toMs`.
private fun List<DoseSnapshot.Logged>.known(fromMs: Long, toMs: Long, asOfMs: Long): List<CurveEvent> =
    filter { it.tsMs in fromMs..toMs && it.loggedAtMs <= asOfMs }.map { it.event }
