package com.t1dm.core.model

/**
 * A logged event the patient deleted, kept as a row after the event itself is gone.
 *
 * The tombstone does three jobs, and each is a hole it closes:
 *
 *  - it is the **authority the push is rebuilt from**. `:data` deletes the event inside one
 *    transaction and cannot hand `:app` an entity that no longer exists, so everything the wire
 *    needs to express the deletion — the id, the grid slot, the offset and the ordering stamp —
 *    lives here.
 *  - it is the **filter hydration refuses against**. A server catch-up re-delivers events by
 *    `clientId`; without a local record of the deletion an id-keyed insert resurrects the row.
 *  - it is a **term in the event high-water mark**, so deleting the newest event does not walk the
 *    catch-up cursor backward into re-pulling the range it was in.
 *
 * [updatedAt] is authored strictly newer than the row it retires, so `SPEC/invariants.md` §7's
 * ordering guard resolves the deletion against the create whatever order the two arrive in.
 *
 * @param actingUntilMs for an insulin dose, the instant its action curve ends — read by the
 *   dose-history rail, which must keep blocking while a deleted dose could still be acting. Null
 *   for a meal.
 */
data class EventTombstone(
    val clientId: String,
    val kind: CurveKind,
    val tsMs: Long,
    val tzOffsetMin: Int,
    val updatedAt: Long,
    val actingUntilMs: Long? = null,
)
