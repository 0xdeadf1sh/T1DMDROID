package com.t1dm.cgm

import java.util.UUID

/** CT5 (Yuwell Anytime 5Pro) constants. GRID_MS/VALID_BG_RANGE live in CgmConstants, not here. */
object Ct5Constants {


    const val VENDOR_ID: String = "anytime"

    /** The sensor family; the scope displayed history spans. */
    const val MODEL_ID: String = "anytime:ct5"

    const val BRAND: String = "Anytime"

    /** Name = prefix + 10-digit BSN, 17 chars, no separator; taken by length/digit class. */
    const val NAME_PREFIX: String = "Anytime"

    /** The BSN is characters 2-11 of the printed 12-character code. */
    const val BSN_DIGITS: Int = 10

    /** ASCII digits of `RANDOM_ID` — the only unbind password. */
    const val RANDOM_ID_DIGITS: Int = 4

    /** In each of the two activation nonces `A` and `B`. */
    const val NONCE_BYTES: Int = 4

    /** Matching is by NAME, never by BLE address: the BSN is what the key and the user know. */
    fun bsnFrom(advertName: String): String? {
        if (!advertName.startsWith(NAME_PREFIX)) return null
        val bsn = advertName.substring(NAME_PREFIX.length)
        if (bsn.length != BSN_DIGITS || !bsn.all { it in '0'..'9' }) return null
        return bsn
    }


    /** 3 min — faster than the five-minute grid, which is why the sub-grid sample store exists. */
    const val SAMPLE_INTERVAL_MS: Long = 180_000L

    /** Written to the sensor at bind, in `0x38`'s minutes byte. */
    const val SAMPLE_INTERVAL_MIN: Int = 3

    /** Rated cycle, and the value written at bind (`0x38` byte 5 = `0x10`). */
    const val CYCLE_DAYS: Int = 16

    /** 7695*3min = 16.03 days, confirms units. Not enforced: observed a transmitter reach 8175. */
    const val RATED_SAMPLES: Int = 7695

    /** The `0x37` start id is a u16, so no sample above this can be asked for at all. */
    const val MAX_SAMPLE_ID: Int = 0xFFFF

    /** 15 * 3 min = 45 minutes. */
    const val WARMUP_SAMPLES: Int = 15

    /** For the two lifetime codes that extend it. */
    const val WARMUP_SAMPLES_EXTENDED: Int = 20

    /** Present only in the 21-character identity form. */
    private val EXTENDED_WARMUP_LIFETIMES = setOf(2, 3)

    /** Returns MINUTES. A lifetime the 17/18-char forms can't express reads 0, falls to normal. */
    fun warmupWindowMinFor(lifeTime: Int): Int =
        (if (lifeTime in EXTENDED_WARMUP_LIFETIMES) WARMUP_SAMPLES_EXTENDED else WARMUP_SAMPLES) *
            SAMPLE_INTERVAL_MIN

    /** Allowlist, unseen code fails closed. 2=index-order error; unused for value, only dating. */
    val ACCEPTED_ERROR_CODES: Set<Int> = setOf(0, 2, 4, 5, 105)

    /** Vendor's valid band, 12-48°C: the only independent check a key decoded right (checksum). */
    val PLAUSIBLE_TEMP_CX100: IntRange = 1200..4800

    /** Dropping alone isn't enough: link stays alive, readings go stale. Failing hands it back. */
    const val MAX_IMPLAUSIBLE_RECORDS: Int = 3


    /** Max BEHIND-skew before falling back to receive instant. 0x37 backfill's rule is inverse. */
    const val MAX_CLOCK_SKEW_MS: Long = SAMPLE_INTERVAL_MS

    /** Max AHEAD-skew before refusal; not [MAX_CLOCK_SKEW_MS]'s mirror. Falls back to delivery. */
    const val MAX_FORWARD_SKEW_MS: Long = 30_000L


    /** Vendor (Dialog) base UUID, not the Bluetooth base; uuid16() can't build any of these. */
    val SERVICE_UUID: UUID = UUID.fromString("00001000-1212-efde-1523-785feabcd123")

    /** WRITE-WITHOUT-RESPONSE ONLY. Nothing acknowledges a write; see [Ct5GattTransport]. */
    val WRITE_UUID: UUID = UUID.fromString("00001002-1212-efde-1523-785feabcd123")

    /** NOTIFY (not indicate), CCCD `0x0100`. */
    val NOTIFY_UUID: UUID = UUID.fromString("00001001-1212-efde-1523-785feabcd123")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** DFU service the transmitter also advertises; the only part a ScanFilter can express. */
    val DFU_SERVICE_UUID: UUID = UUID.fromString("0000FEF5-0000-1000-8000-00805F9B34FB")

    /** Function, not a ByteArray const: arrays are mutable. Matches by category, which varies. */
    fun hasCgmCategory(mfg: ByteArray): Boolean =
        mfg.size >= 3 && mfg[0] == 0x43.toByte() && mfg[1] == 0x47.toByte() && mfg[2] == 0x4D.toByte()

    /** Rebuilds the wire AD field (company id 0x4743="CG"); without it every offset is two out. */
    fun adPayloadOf(companyId: Int, data: ByteArray): ByteArray =
        byteArrayOf((companyId and 0xFF).toByte(), ((companyId shr 8) and 0xFF).toByte()) + data

    /** Vendor-required: MTU 23 caps notify to 20 B; 0x3F reply is 22 B, so bind needs this. */
    const val REQUESTED_MTU: Int = 480


    /** ms offsets from queue; same bytes rewritten each time. Vendor force-closes after the 5th. */
    val DEFAULT_LADDER_MS: LongArray = longArrayOf(100, 900, 1700, 2500, 3300)

    /** `0x05` self-check: three writes, 2800 ms apart. */
    val SELF_CHECK_LADDER_MS: LongArray = longArrayOf(100, 2900, 5700)

    /** 0x37 pull is slow (~500 B/batch); ordinary ladder re-asks mid-answer, causing duplicates. */
    val HISTORY_LADDER_MS: LongArray = longArrayOf(100, 4_000, 8_000)

    /** No reply cancels this ladder; all rungs fire. 2 rungs, not 5 unanswered writes/3min. */
    val PUSH_ACK_LADDER_MS: LongArray = longArrayOf(100, 900)

    /** How long after the ladder's last write a reply is awaited before the step fails. */
    const val LADDER_GRACE_MS: Long = 2_800L

    fun ladderFor(opcode: Int): LongArray = when (opcode) {
        Opcode.SELF_CHECK -> SELF_CHECK_LADDER_MS
        Opcode.PULL_HISTORY -> HISTORY_LADDER_MS
        Opcode.PUSH -> PUSH_ACK_LADDER_MS
        else -> DEFAULT_LADDER_MS
    }

    /** In ms from the queue instant. */
    fun stepTimeoutMs(ladder: LongArray): Long = ladder.last() + LADDER_GRACE_MS


    /** Mandatory: a dead link showed no error for 35 min. 2.6 intervals: 2 missed + margin. */
    const val PUSH_STALE_MS: Long = SAMPLE_INTERVAL_MS * 13 / 5

    /** Pushes arriving, id unchanged this long: counter stopped (store full past rated wear). */
    const val COUNTER_STOPPED_MS: Long = 15 * 60_000L

    /** Re-pulls on idle timer. Reply proves the link, not sensor; doesn't feed PUSH_STALE_MS. */
    const val IDLE_PULL_MS: Long = 190_000L

    /** Covers connect/MTU/discovery/CCCD (not ladder-covered). 20s is the vendor's own figure. */
    const val HANDSHAKE_STEP_TIMEOUT_MS: Long = 20_000L


    /** Idle advert rate observed: 136 in 20s, falling to one in eight. */
    const val IDLE_ADVERT_INTERVAL_MS: Long = 8_000L

    /** Generous: 1 advert/8s, a short window finds nothing; a stale-handle connectGatt is slow. */
    const val SCAN_TIMEOUT_MS: Long = 60_000L

    /** Locked scan delay. Unbatched scan SUSPENDS silently (no onScanFailed); batched survives. */
    const val BATCH_FLUSH_MS: Long = 5 * 60_000L

    /** Ceiling on a targeted rescan while locked; ends on first hit. 2 flush periods, margin. */
    const val LOCKED_SCAN_TIMEOUT_MS: Long = BATCH_FLUSH_MS * 2

    /** Discovery sweep while locked; no early exit, bounded at one flush period plus a margin. */
    const val LOCKED_SWEEP_MS: Long = BATCH_FLUSH_MS + 30_000L

    /** Grace beyond SCAN_TIMEOUT_MS: a batched report lands up to one flush late. */
    const val BATCHED_HANDLE_GRACE_MS: Long = BATCH_FLUSH_MS


    /** Dialect settled by probe-reply length, never guessed: 165 B = 15 short or 11 voltage. */
    const val RECORD_SHORT_BYTES: Int = 11
    const val RECORD_VOLTAGE_BYTES: Int = 15

    /** Opcode, the u16 start id, and the checksum. */
    const val HISTORY_ENVELOPE_BYTES: Int = 4

    /** Vendor default, pessimistic: a too-large batch answers TRUNCATED, reads as end of store. */
    const val FALLBACK_MTU: Int = 211


    /** Between batches: a 16-day pull is ~250 round trips; keeps link free for the 3min push. */
    const val HISTORY_BATCH_GAP_MS: Long = 750L

    object Opcode {
        const val SET_DATE: Int = 0x03
        const val SET_DATE_REPLY: Int = 0x04
        const val VERSION: Int = 0x01
        const val SELF_CHECK: Int = 0x05
        const val INIT: Int = 0x06
        const val LOW_POWER: Int = 0x0F
        const val SET_ID: Int = 0x30
        const val CHECK_ID: Int = 0x31
        const val SET_PARAMETERS: Int = 0x38
        const val QUERY_SSN: Int = 0x3F
        const val PUSH: Int = 0x35

        /** A reply CANCELS the write ladder; an unsolicited `0x35` does not. */
        const val PULL_HISTORY: Int = 0x37
    }
}
