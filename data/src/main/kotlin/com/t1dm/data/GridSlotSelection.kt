package com.t1dm.data

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.CgmReadingEntity
import kotlin.math.abs

/**
 * Does [incoming] take the five-minute slot from [stored]? — the STORAGE-side decision, made once,
 * in [T1dmRepository.upsertReading].
 *
 * `cgm_reading` holds one row per `(source, slot)`, and a sensor that samples faster than the grid
 * (§1) offers several for the same slot. Until this existed the answer was whichever arrived last,
 * which was not a decision at all: it was `INSERT OR REPLACE`'s behaviour showing through. A
 * three-minute sensor puts five samples into three slots, so two of the five were dropped, and which
 * two depended only on arrival order rather than on which sample the slot was meant to hold.
 *
 * The order of preference, and why:
 *
 *  1. **A contest is only between two of the sensor's own measurements.** If either row is
 *     `INTERPOLATED`, [incoming] takes the slot exactly as it did before — a gap-fill and a
 *     measurement are not two candidates for the same instant, and re-deciding that here would
 *     change the stored series for sensors this is not about. A five-minute sensor never produces
 *     two `MEASURED` rows for DIFFERENT instants in one slot, so for such a sensor rules 2–4 are
 *     unreachable and the grid series is byte-identical to what plain replace-in-place produced.
 *  2. **`NORMAL` beats a suppressed reading.** A warm-up value nearer the slot instant must not
 *     displace a usable measurement: §3.6 keeps warm-up out of inference and out of alarm
 *     evaluation, and the stored series should not quietly prefer it either.
 *  3. **Then the sample nearest the slot instant** — `|rxWallMs − tsMs|`. This is the whole point:
 *     the slot claims to be the sensor's value at that instant, so the sample nearest it is the one
 *     that best supports the claim. `rxWallMs` is the instant the sample is FILED under
 *     ([com.t1dm.core.model.CgmReading]), so for a source that dates its own samples this measures
 *     nearness in SAMPLING rather than in delivery — which is what the rule wanted all along, and what
 *     makes the answer independent of how late a frame happened to arrive.
 *  4. **Then the earlier of the two instants.** Equidistant is reachable — a slot's own five minutes
 *     are symmetric about it, so a sample 60 s early and one 60 s late tie — and something has to
 *     break it. The earlier one wins, which means the incumbent normally keeps the slot and a settled
 *     record is not rewritten behind the reader.
 *  5. **The same instant twice is not a contest**, and replaces in place exactly as it always did.
 *     One source cannot receive two different samples in one millisecond, so this is only ever the
 *     same write arriving again — a retry, or a caller deliberately rewriting a slot — and the
 *     caller's newer row is the one it meant.
 *
 * Rules 2–4 are a total order on `(suppressed, distance, rxWallMs)` over samples with distinct filed
 * instants, so the surviving row is a function of the SET of samples for the slot and not of the order
 * they were persisted in — which is what makes an out-of-order or retried write land on the same
 * answer as a clean run.
 *
 * Distinct from [collapseByGridSlot], which is the DISPLAY-side decision and answers a different
 * question: which of SEVERAL SOURCES' rows to draw for one slot. This one runs first and within a
 * single source, so by the time the collapse sees the window each source offers it one row.
 */
internal fun supersedesGridSlot(stored: CgmReadingEntity?, incoming: CgmReadingEntity): Boolean {
    if (stored == null) return true
    // Rule 0: a reconstruction fills a HOLE and nothing else. Without this it would meet the
    // not-both-measured early-out below and take the slot from a real reading. The reverse
    // direction already works — a measured incoming meets `stored.provenance != MEASURED` and wins,
    // which is what lets a recovered sensor sample replace a promoted fill.
    if (incoming.provenance == ReadingProvenance.RECONSTRUCTED) {
        return stored.bgMgdl == null || stored.provenance == ReadingProvenance.RECONSTRUCTED
    }
    if (stored.provenance != ReadingProvenance.MEASURED ||
        incoming.provenance != ReadingProvenance.MEASURED
    ) {
        return true
    }
    val storedNormal = stored.flag == ReadingFlag.NORMAL
    val incomingNormal = incoming.flag == ReadingFlag.NORMAL
    if (storedNormal != incomingNormal) return incomingNormal

    val storedDistance = abs(stored.rxWallMs - stored.tsMs)
    val incomingDistance = abs(incoming.rxWallMs - incoming.tsMs)
    if (incomingDistance != storedDistance) return incomingDistance < storedDistance
    if (incoming.rxWallMs != stored.rxWallMs) return incoming.rxWallMs < stored.rxWallMs
    return true
}
