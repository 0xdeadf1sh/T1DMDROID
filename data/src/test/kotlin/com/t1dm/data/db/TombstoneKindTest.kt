package com.t1dm.data.db

import com.t1dm.core.model.CurveKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** `kind` is raw TEXT. */
class TombstoneKindTest {

    private fun tombstone(kind: String) = EventTombstoneEntity(
        clientId = "c", kind = kind, tsMs = 0L, tzOffsetMin = 0,
        updatedAt = 0L, createdAtMs = 0L,
    )

    @Test
    fun `each stored kind decodes to its own channel`() {
        assertEquals(CurveKind.CARB, tombstone(TOMBSTONE_KIND_MEAL).toModel().kind)
        assertEquals(CurveKind.INSULIN, tombstone(TOMBSTONE_KIND_DOSE).toModel().kind)
        assertEquals(CurveKind.EXERCISE, tombstone(TOMBSTONE_KIND_EXERCISE).toModel().kind)
    }

    @Test
    fun `an unknown kind reads as a meal rather than throwing`() {
        // A row a later build wrote must stay readable; the fallback is the pre-existing one.
        assertEquals(CurveKind.CARB, tombstone("something-else").toModel().kind)
    }

    @Test
    fun `the four kinds are distinct`() {
        assertEquals(
            4,
            setOf(TOMBSTONE_KIND_MEAL, TOMBSTONE_KIND_DOSE, TOMBSTONE_KIND_EXERCISE, TOMBSTONE_KIND_BG).size,
        )
    }

    /** Archives carry this id; a new spelling would stop an older file's cut applying. */
    @Test
    fun `a cut slot's id names its slot`() {
        assertEquals("bg:1700000100000", bgTombstoneId(1_700_000_100_000L))
    }
}
