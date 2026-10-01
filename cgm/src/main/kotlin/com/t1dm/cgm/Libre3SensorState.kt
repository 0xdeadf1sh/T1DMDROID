package com.t1dm.cgm

/** Everything about ONE provisioned sensor, unrecoverable from it (PLAN_T1DMDROID.md §4). */
data class Libre3SensorState(
    /** Folded receiver id on record at the sensor; wire order little-endian. */
    val receiverId: UInt,
    /** Nine ASCII characters from patch-info. */
    val serial: String,
    /** Display form `AA:BB:CC:DD:EE:FF`. */
    val bleAddress: String,
    /** Four bytes; the phase-5 tail and the cached-reconnect credential. */
    val blePin: ByteArray,
    /** From the switch/activate response, epoch seconds. */
    val activationTimeS: Long,
    /** Patch-info wear duration; 14 or 15 d rated. */
    val wearDurationMin: Int,
    val region: Libre3Region,
    /** When this app provisioned the sensor. */
    val provisionedAtMs: Long,
    /** Persisted after the first pairing (phase 4); null before. */
    val kAuth: ByteArray?,
    /** Highest accepted realtime life count; null before the first reading. */
    val lastRealtimeLifeCount: Int? = null,
    /** Highest accepted historical life count; null before the first page. */
    val lastHistoricalLifeCount: Int? = null,
    /**
     * Oldest life count fully covered by backfill (the walk cursor, §14 phase 6); null before
     * the first walk. Everything below [lastHistoricalLifeCount] and above this is a gap.
     */
    val lastHistoricalFloorLifeCount: Int? = null,
) {
    init {
        require(serial.length == SERIAL_LEN && serial.all { it.code in 0x20..0x7E }) {
            "serial must be $SERIAL_LEN printable ASCII characters"
        }
        require(bleAddress.filter { it != ':' }.length == ADDRESS_HEX_LEN) {
            "BLE address must be $ADDRESS_HEX_LEN hex digits"
        }
        require(blePin.size == PIN_LEN) { "BLE PIN must be $PIN_LEN bytes" }
        require(wearDurationMin in 0..0xFFFF) { "wear duration must fit u16" }
    }

    /** Zero states no wear. */
    val statedLifetimeMin: Int?
        get() = wearDurationMin.takeIf { it > 0 }

    /** Colonized display form, tolerating a reply that arrives bare. */
    val bleAddressDisplay: String
        get() = bleAddress.filter { it != ':' }
            .uppercase()
            .chunked(2)
            .joinToString(":")

    /** LE little-endian wire, our own blob so versioned like [Ct5SensorState] (§4). */
    fun encode(): ByteArray {
        val kAuthLen = kAuth?.size ?: 0
        require(kAuthLen <= 0xFFFF) { "kAuth blob must fit u16" }
        val out = ByteArray(FIXED + kAuthLen + LIFE_COUNT_TAIL_V3)
        out[0] = VERSION
        out.putLe32(1, receiverId.toLong())
        out.putLe16(5, wearDurationMin)
        out[7] = when (region) {
            Libre3Region.Eu -> REGION_EU
            Libre3Region.Us -> REGION_US
        }
        out.putLe64(8, provisionedAtMs)
        out.putLe32(16, activationTimeS)
        blePin.copyInto(out, 20)
        serial.toByteArray(Charsets.US_ASCII).copyInto(out, 24)
        bleAddress.filter { it != ':' }.uppercase().toByteArray(Charsets.US_ASCII).copyInto(out, 33)
        out.putLe16(45, kAuthLen)
        kAuth?.copyInto(out, FIXED)
        out.putLe16(FIXED + kAuthLen, lastRealtimeLifeCount ?: LIFE_COUNT_NONE)
        out.putLe16(FIXED + kAuthLen + 2, lastHistoricalLifeCount ?: LIFE_COUNT_NONE)
        out.putLe16(FIXED + kAuthLen + 4, lastHistoricalFloorLifeCount ?: LIFE_COUNT_NONE)
        out[FIXED + kAuthLen + 6] = WALK_EPOCH.toByte()
        return out
    }

    // ByteArrays: identity-compare would make two equal states unequal.
    override fun equals(other: Any?) = this === other || (other is Libre3SensorState &&
        receiverId == other.receiverId &&
        serial == other.serial &&
        bleAddress == other.bleAddress &&
        blePin.contentEquals(other.blePin) &&
        activationTimeS == other.activationTimeS &&
        wearDurationMin == other.wearDurationMin &&
        region == other.region &&
        provisionedAtMs == other.provisionedAtMs &&
        (kAuth == null) == (other.kAuth == null) &&
        (kAuth == null || kAuth.contentEquals(other.kAuth)) &&
        lastRealtimeLifeCount == other.lastRealtimeLifeCount &&
        lastHistoricalLifeCount == other.lastHistoricalLifeCount &&
        lastHistoricalFloorLifeCount == other.lastHistoricalFloorLifeCount)

    override fun hashCode(): Int {
        var h = receiverId.hashCode()
        h = 31 * h + serial.hashCode()
        h = 31 * h + bleAddress.hashCode()
        h = 31 * h + blePin.contentHashCode()
        h = 31 * h + activationTimeS.hashCode()
        h = 31 * h + wearDurationMin
        h = 31 * h + region.hashCode()
        h = 31 * h + provisionedAtMs.hashCode()
        h = 31 * h + (kAuth?.contentHashCode() ?: 0)
        h = 31 * h + (lastRealtimeLifeCount ?: -1)
        h = 31 * h + (lastHistoricalLifeCount ?: -1)
        h = 31 * h + (lastHistoricalFloorLifeCount ?: -1)
        return h
    }

    /** Never log the PIN or kAuth. */
    override fun toString() = "Libre3SensorState(serial=$serial, bleAddress=$bleAddress, " +
        "region=$region, wearDurationMin=$wearDurationMin, activationTimeS=$activationTimeS, " +
        "secrets withheld)"

    companion object {
        /** Fresh sensor (patch-info 0x01): 0xa0 with this app's fold, same response layout. */
        fun provisioned(
            patch: Libre3PatchInfo,
            reply: Libre3SwitchResponse,
            receiverId: UInt,
            region: Libre3Region,
            provisionedAtMs: Long,
        ) = Libre3SensorState(
            receiverId = receiverId,
            serial = patch.serial,
            bleAddress = reply.bleAddress.filter { it != ':' }.uppercase()
                .chunked(2).joinToString(":"),
            blePin = reply.blePin,
            activationTimeS = reply.activationTimeS,
            wearDurationMin = patch.wearDurationMin,
            region = region,
            provisionedAtMs = provisionedAtMs,
            kAuth = null,
        )

        private const val VERSION: Byte = 3

        private const val REGION_EU: Byte = 0
        private const val REGION_US: Byte = 1

        private const val SERIAL_LEN = 9
        private const val ADDRESS_HEX_LEN = 12
        private const val PIN_LEN = 4

        /** Bytes through the address; kAuthLen, blob and the life-count tail follow. */
        private const val FIXED = 47

        /** v1: realtime + historical. v2 adds the walk floor. v3 adds the walk epoch. */
        private const val LIFE_COUNT_TAIL_V1 = 4
        private const val LIFE_COUNT_TAIL_V2 = 6
        private const val LIFE_COUNT_TAIL_V3 = 7

        /**
         * The walk-logic epoch. Bump when the walk's floor-advance rule changes materially:
         * a stored floor from an older epoch is DISTRUSTED (decoded as null) so the walk
         * re-covers the gap — the upsert dedup makes the redundancy free. v2's floor advanced
         * even on writes the GATT rejected (live 2026-09-24), so v3 distrusts it once.
         */
        private const val WALK_EPOCH: Int = 1

        /** u16 sentinel for "never seen"; life counts stay far below wear minutes. */
        private const val LIFE_COUNT_NONE = 0xFFFF

        /** Fail-closed. v3 is written; v2 decodes floor distrusted, v1 floorless (the only PIN). */
        fun decode(blob: ByteArray): Libre3SensorState? {
            val version = blob.getOrNull(0) ?: return null
            val tail = when (version) {
                VERSION -> LIFE_COUNT_TAIL_V3
                2.toByte() -> LIFE_COUNT_TAIL_V2
                1.toByte() -> LIFE_COUNT_TAIL_V1
                else -> return null
            }
            if (blob.size < FIXED + tail) return null
            val region = when (blob[7]) {
                REGION_EU -> Libre3Region.Eu
                REGION_US -> Libre3Region.Us
                else -> return null
            }
            val kAuthLen = blob.le16(45)
            val lifeAt = FIXED + kAuthLen
            if (blob.size != lifeAt + tail) return null
            val serial = String(blob, 24, SERIAL_LEN, Charsets.US_ASCII)
            val address = String(blob, 33, ADDRESS_HEX_LEN, Charsets.US_ASCII)
            val realtime = blob.le16(lifeAt)
            val historical = blob.le16(lifeAt + 2)
            // A floor from an older walk epoch is distrusted: the rule that wrote it may have
            // advanced it over windows that were never delivered (v2 did, live 2026-09-24).
            val epoch = if (tail >= LIFE_COUNT_TAIL_V3) blob[lifeAt + 6].toInt() and 0xFF else 0
            val rawFloor = if (tail >= LIFE_COUNT_TAIL_V2) blob.le16(lifeAt + 4) else LIFE_COUNT_NONE
            val floor = if (epoch < WALK_EPOCH) LIFE_COUNT_NONE else rawFloor
            return try {
                Libre3SensorState(
                    receiverId = blob.le32u(1),
                    serial = serial,
                    bleAddress = address.chunked(2).joinToString(":"),
                    blePin = blob.copyOfRange(20, 24),
                    activationTimeS = blob.le32l(16),
                    wearDurationMin = blob.le16(5),
                    region = region,
                    provisionedAtMs = blob.le64(8),
                    kAuth = if (kAuthLen == 0) null else blob.copyOfRange(FIXED, lifeAt),
                    lastRealtimeLifeCount = if (realtime == LIFE_COUNT_NONE) null else realtime,
                    lastHistoricalLifeCount = if (historical == LIFE_COUNT_NONE) null else historical,
                    lastHistoricalFloorLifeCount = if (floor == LIFE_COUNT_NONE) null else floor,
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun ByteArray.putLe16(at: Int, v: Int) {
            this[at] = (v and 0xFF).toByte()
            this[at + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        private fun ByteArray.putLe32(at: Int, v: Long) {
            for (i in 0 until 4) this[at + i] = ((v ushr (8 * i)) and 0xFF).toByte()
        }

        private fun ByteArray.putLe64(at: Int, v: Long) {
            for (i in 0 until 8) this[at + i] = ((v ushr (8 * i)) and 0xFF).toByte()
        }

        private fun ByteArray.le16(at: Int): Int =
            (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

        private fun ByteArray.le32u(at: Int): UInt {
            var v = 0L
            for (i in 0 until 4) v = v or ((this[at + i].toLong() and 0xFF) shl (8 * i))
            return v.toUInt()
        }

        private fun ByteArray.le32l(at: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = v or ((this[at + i].toLong() and 0xFF) shl (8 * i))
            return v
        }

        private fun ByteArray.le64(at: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((this[at + i].toLong() and 0xFF) shl (8 * i))
            return v
        }
    }
}