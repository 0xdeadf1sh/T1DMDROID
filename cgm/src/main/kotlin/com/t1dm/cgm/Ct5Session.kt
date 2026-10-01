package com.t1dm.cgm

import android.util.Log
import uniffi.t1dm_core.CoreException
import uniffi.t1dm_core.Ct5Push
import uniffi.t1dm_core.ct5BuildCheckId
import uniffi.t1dm_core.ct5BuildInit
import uniffi.t1dm_core.ct5BuildPullHistory
import uniffi.t1dm_core.ct5BuildPushAck
import uniffi.t1dm_core.ct5BuildLowPower
import uniffi.t1dm_core.ct5BuildQuerySsn
import uniffi.t1dm_core.ct5BuildSelfCheck
import uniffi.t1dm_core.ct5BuildSetDate
import uniffi.t1dm_core.ct5BuildSetId
import uniffi.t1dm_core.ct5BuildSetParameters
import uniffi.t1dm_core.ct5BuildVersionRequest
import uniffi.t1dm_core.ct5CipherIdFromSetIdReply
import uniffi.t1dm_core.ct5FrameIsLegal
import uniffi.t1dm_core.ct5HistoryBatchSize
import uniffi.t1dm_core.ct5HistoryRecordSize
import uniffi.t1dm_core.ct5ParseAdvert
import uniffi.t1dm_core.ct5ParseCheckIdResponse
import uniffi.t1dm_core.ct5ParseHistory
import uniffi.t1dm_core.ct5ParseInitResponse
import uniffi.t1dm_core.ct5GlucoseFromCurrent
import uniffi.t1dm_core.ct5ParsePush
import uniffi.t1dm_core.ct5ParseSelfCheck
import uniffi.t1dm_core.ct5ParseSetDateResponse
import uniffi.t1dm_core.ct5ParseSsnResponse
import uniffi.t1dm_core.ct5ParseVersion
import uniffi.t1dm_core.ct5PushUnderEveryKey
import uniffi.t1dm_core.ct5VerifySetParametersEcho

/** One live `0x35` push. Every quantity an exact integer in the wire's own resolution; no float. */
data class Ct5PushSample(
    /** The sensor's own sample counter. Reading time is `bindTimeMs + glucoseId * 180_000`. */
    val glucoseId: Int,
    /** Null when the wire field is zero (warm-up); zero is ABSENT, never 0 mg/dL. */
    val glucoseMgdl: Int?,
    val trendCode: Int,
    val errorCode: Int,
    /** Centi-degrees Celsius. */
    val tempCx100: Int,
    /** Background current, hundredths. Reads 0 in practice. */
    val ibX100: Int,
    /** Working-electrode current, hundredths. */
    val iwX100: Int,
    /** `null` in the 11-byte short record. */
    val batteryRaw: Int?,
    /** BE/WE/RE/CE in mV, or `null` for the 11-byte short record. */
    val electrodesMv: List<Int>?,
)

/** One parsed `0x37` history batch. */
data class Ct5HistoryBatch(
    val startId: Int,
    /** Shorter than [slots] wherever the sensor held a gap. */
    val samples: List<Ct5PushSample>,
    /** A full record of `0xFC` was reached: the sensor holds nothing beyond this point. */
    val endOfHistory: Boolean,
    /** Slots this reply accounted for, terminator excluded; not samples.size. */
    val slots: Int,
)

/** `K` and `R` are exact hundredths. */
data class Ct5SensorIdentity(
    val ssn: String,
    val kX100: Int,
    val rX100: Int,
    /** `0` in the 17/18-character forms, which have no position for it. */
    val lifeTime: Int,
    val calibration: Int,
    val unitOrder: Int,
    val year: Int,
    val serialNo: String,
    val sensorNo: String,
)

data class Ct5Version(
    val year: Int,
    val month: Int,
    val day: Int,
    val version: String,
    val algorithm: String,
)

/** A CT5 advertisement before any key is held. Carries NO glucose: presence and liveness only. */
data class Ct5AdvertInfo(
    /** Flips only after `0x38`+`0x06`, never after `0x30` alone. */
    val bound: Boolean,
    /** The telemetry block is populated; all `0xFF` until the sensor is running. */
    val running: Boolean,
    val recordCount: Int,
    val startGlucoseId: Int,
    val checksumValid: Boolean,
)

/** The sensor's answer to `0x31` checkID. */
enum class Ct5BindVerdict {
    ACCEPTED,
    REJECTED,

    /** No verdict readable: at six bytes the verdict byte is also the checksum byte. */
    AMBIGUOUS,
}

/** Native surface for the CT5 connected session; fallible ops return null, never throw. */
interface Ct5Session {

    /** `0x03` setDate from a LOCAL wall clock. No timezone byte; cosmetic and freely repeatable. */
    fun buildSetDate(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): ByteArray?

    fun buildVersionRequest(): ByteArray
    fun buildSelfCheck(): ByteArray
    fun buildQuerySsn(): ByteArray

    /** `0x06` init — IRREVERSIBLE. Its reply is the activation success signal. */
    fun buildInit(): ByteArray

    /** `0x0F` lowPower, a go-idle hint. Optional; may draw no reply. */
    fun buildLowPower(): ByteArray

    /** 0x35 push-ack: no reply, NOT optional, sensor tears link after an un-acked push. */
    fun buildPushAck(): ByteArray

    /** `0x30` setID — IRREVERSIBLE. [a] never leaves this process. */
    fun buildSetId(b: ByteArray, a: ByteArray): ByteArray?

    /** `0x31` checkID with the persisted [b]. The reconnect frame. */
    fun buildCheckId(b: ByteArray): ByteArray?

    /** 0x38 setParameters, IRREVERSIBLE; plants RANDOM_ID, the ONLY unbind password. */
    fun buildSetParameters(
        kX100: Int,
        rX100: Int,
        intervalMin: Int,
        cycleDays: Int,
        randomId: String,
        cipherId: Int,
    ): ByteArray?

    /** Hold 0x38 reply against what was sent; reply is OBFUSCATED, deobfuscate [1..12] first. */
    fun verifySetParametersEcho(
        reply: ByteArray,
        kX100: Int,
        rX100: Int,
        intervalMin: Int,
        cycleDays: Int,
        randomId: String,
        cipherId: Int,
    ): Boolean

    /** Derive CIPHER_ID from 0x30 reply and both nonces; null on mismatch, 0x30 is unrepeatable. */
    fun cipherIdFromSetIdReply(reply: ByteArray, a: ByteArray, b: ByteArray): Int?

    /** Validate, deobfuscate and decode one live `0x35` push. */
    fun parsePush(frame: ByteArray, cipherId: Int): Ct5PushSample?

    /** Every key a frame decodes under, for Ct5KeySearch; empty if none, or unimplemented. */
    fun pushUnderEveryKey(frame: ByteArray): Map<Int, Ct5PushSample> = emptyMap()

    /** Reading from Iw/temp/K, not glucoseMgdl (discarded fixed-gain); null if unjustified. */
    fun glucoseMgdl(sample: Ct5PushSample, kX100: Int): Int?

    /** 0x37 pull-history: count records from startId, clamped to what one batch holds. */
    fun buildPullHistory(startId: Int, count: Int): ByteArray?

    /** Records to ask for at mtu/recordSize; never zero, under-asks by one to detect store end. */
    fun historyBatchSize(mtu: Int, recordSize: Int): Int

    /** Dialect of a single-record probe reply, from length; null if neither; a batch can't tell. */
    fun historyRecordSize(frameLen: Int): Int?

    /** Validate, split, deobfuscate and decode one `0x37` batch. */
    fun parseHistory(frame: ByteArray, cipherId: Int, recordSize: Int): Ct5HistoryBatch?

    /** Parse the `0x3F` reply. Pass [NO_CIPHER_ID] before a bind, when the answer is plaintext. */
    fun parseSsnResponse(frame: ByteArray, cipherId: Int): Ct5SensorIdentity?

    fun parseVersion(reply: ByteArray): Ct5Version?

    /** `false` aborts a bind: the reply must be exactly 20 bytes with a valid checksum. */
    fun parseSelfCheck(reply: ByteArray): Boolean

    fun parseSetDateResponse(reply: ByteArray): Boolean
    fun parseInitResponse(reply: ByteArray): Boolean
    fun parseCheckIdResponse(reply: ByteArray): Ct5BindVerdict
    fun parseAdvert(mfg: ByteArray): Ct5AdvertInfo?

    /** Trailing byte against the additive sum of everything before it. */
    fun frameIsLegal(frame: ByteArray): Boolean

    companion object {
        /** No key held yet — the state a bind's `0x3F` query runs in. */
        const val NO_CIPHER_ID: Int = -1
    }
}

/** The shipping Ct5Session; a CoreException becomes null so a bind aborts, not the process. */
class UniffiCt5Session : Ct5Session {

    override fun buildSetDate(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): ByteArray? =
        nullOnCoreError("setDate") { ct5BuildSetDate(year, month, day, hour, minute, second) }

    override fun buildVersionRequest(): ByteArray = ct5BuildVersionRequest()
    override fun buildSelfCheck(): ByteArray = ct5BuildSelfCheck()
    override fun buildQuerySsn(): ByteArray = ct5BuildQuerySsn()
    override fun buildInit(): ByteArray = ct5BuildInit()
    override fun buildLowPower(): ByteArray = ct5BuildLowPower()
    override fun buildPushAck(): ByteArray = ct5BuildPushAck()

    override fun buildSetId(b: ByteArray, a: ByteArray): ByteArray? =
        nullOnCoreError("setID") { ct5BuildSetId(b, a) }

    override fun buildCheckId(b: ByteArray): ByteArray? =
        nullOnCoreError("checkID") { ct5BuildCheckId(b) }

    override fun buildSetParameters(
        kX100: Int,
        rX100: Int,
        intervalMin: Int,
        cycleDays: Int,
        randomId: String,
        cipherId: Int,
    ): ByteArray? = nullOnCoreError("setParameters") {
        ct5BuildSetParameters(kX100, rX100, intervalMin, cycleDays, randomId, cipherId)
    }

    override fun verifySetParametersEcho(
        reply: ByteArray,
        kX100: Int,
        rX100: Int,
        intervalMin: Int,
        cycleDays: Int,
        randomId: String,
        cipherId: Int,
    ): Boolean = nullOnCoreError("setParameters echo") {
        ct5VerifySetParametersEcho(reply, kX100, rX100, intervalMin, cycleDays, randomId, cipherId)
    } ?: false

    override fun cipherIdFromSetIdReply(reply: ByteArray, a: ByteArray, b: ByteArray): Int? =
        nullOnCoreError("cipher-id derivation") { ct5CipherIdFromSetIdReply(reply, a, b) }

    override fun pushUnderEveryKey(frame: ByteArray): Map<Int, Ct5PushSample> =
        nullOnCoreError("key search") {
            ct5PushUnderEveryKey(frame).associate { it.cipherId to pushSampleOf(it.push) }
        } ?: emptyMap()

    override fun parsePush(frame: ByteArray, cipherId: Int): Ct5PushSample? =
        nullOnCoreError("push") { pushSampleOf(ct5ParsePush(frame, cipherId)) }

    override fun glucoseMgdl(sample: Ct5PushSample, kX100: Int): Int? =
        ct5GlucoseFromCurrent(sample.iwX100, sample.tempCx100, kX100, sample.glucoseId)

    override fun buildPullHistory(startId: Int, count: Int): ByteArray? =
        nullOnCoreError("pull-history") { ct5BuildPullHistory(startId, count) }

    override fun historyBatchSize(mtu: Int, recordSize: Int): Int = ct5HistoryBatchSize(mtu, recordSize)

    /** Zero crosses the FFI for "neither dialect". */
    override fun historyRecordSize(frameLen: Int): Int? =
        ct5HistoryRecordSize(frameLen).takeIf { it > 0 }

    override fun parseHistory(frame: ByteArray, cipherId: Int, recordSize: Int): Ct5HistoryBatch? =
        nullOnCoreError("history") {
            val h = ct5ParseHistory(frame, cipherId, recordSize)
            Ct5HistoryBatch(
                startId = h.startId,
                samples = h.samples.map(::pushSampleOf),
                endOfHistory = h.endOfHistory,
                slots = h.slots,
            )
        }

    override fun parseSsnResponse(frame: ByteArray, cipherId: Int): Ct5SensorIdentity? =
        nullOnCoreError("SSN") {
            val id = ct5ParseSsnResponse(frame, cipherId)
            Ct5SensorIdentity(
                ssn = id.ssn,
                kX100 = id.kX100,
                rX100 = id.rX100,
                lifeTime = id.lifeTime,
                calibration = id.calibration,
                unitOrder = id.unitOrder,
                year = id.year,
                serialNo = id.serialNo,
                sensorNo = id.sensorNo,
            )
        }

    override fun parseVersion(reply: ByteArray): Ct5Version? =
        nullOnCoreError("version") {
            val v = ct5ParseVersion(reply)
            Ct5Version(v.year, v.month, v.day, v.version, v.algorithm)
        }

    override fun parseSelfCheck(reply: ByteArray): Boolean = ct5ParseSelfCheck(reply)
    override fun parseSetDateResponse(reply: ByteArray): Boolean = ct5ParseSetDateResponse(reply)
    override fun parseInitResponse(reply: ByteArray): Boolean = ct5ParseInitResponse(reply)

    override fun parseCheckIdResponse(reply: ByteArray): Ct5BindVerdict =
        when (ct5ParseCheckIdResponse(reply)) {
            1 -> Ct5BindVerdict.ACCEPTED
            0 -> Ct5BindVerdict.REJECTED
            else -> Ct5BindVerdict.AMBIGUOUS
        }

    override fun parseAdvert(mfg: ByteArray): Ct5AdvertInfo? =
        nullOnCoreError("advert") {
            val a = ct5ParseAdvert(mfg)
            Ct5AdvertInfo(
                bound = a.bound,
                running = a.running,
                recordCount = a.recordCount,
                startGlucoseId = a.startGlucoseId,
                checksumValid = a.checksumValid,
            )
        }

    override fun frameIsLegal(frame: ByteArray): Boolean = ct5FrameIsLegal(frame)

    private fun pushSampleOf(p: Ct5Push): Ct5PushSample = Ct5PushSample(
        glucoseId = p.glucoseId,
        glucoseMgdl = if (p.glucosePresent) p.glucoseMgdl else null,
        trendCode = p.trendCode,
        errorCode = p.errorCode,
        tempCx100 = p.tempCX100,
        ibX100 = p.ibX100,
        iwX100 = p.iwX100,
        batteryRaw = if (p.batteryPresent) p.batteryRaw else null,
        electrodesMv = p.electrodesMv.ifEmpty { null },
    )

    private inline fun <T> nullOnCoreError(what: String, body: () -> T): T? =
        try {
            body()
        } catch (e: CoreException) {
            Log.w(TAG, "$what rejected: ${e.message}")
            null
        }

    private companion object {
        const val TAG = "Ct5Session"
    }
}
