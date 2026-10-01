package com.t1dm.cgm

import android.util.Log
import uniffi.t1dm_core.AidexResponse as UniffiAidexResponse
import uniffi.t1dm_core.CoreException
import uniffi.t1dm_core.aidexAskkey
import uniffi.t1dm_core.aidexCmdDeviceInfo
import uniffi.t1dm_core.aidexCmdGetBroadcast
import uniffi.t1dm_core.aidexCmdGetHistory
import uniffi.t1dm_core.aidexCmdGetLastId
import uniffi.t1dm_core.aidexCmdGetStartTime
import uniffi.t1dm_core.aidexCmdSetAutoUpdate
import uniffi.t1dm_core.aidexCmdSetDynamicAdvMode
import uniffi.t1dm_core.aidexCmdSetNewSensor
import uniffi.t1dm_core.aidexDecryptFrame
import uniffi.t1dm_core.aidexDeriveSession
import uniffi.t1dm_core.aidexEncodeLocalStartTime
import uniffi.t1dm_core.aidexEncryptFrame
import uniffi.t1dm_core.aidexIv
import uniffi.t1dm_core.aidexParseRealtime
import uniffi.t1dm_core.aidexParseResponse

/** 0xF003; readingType 1=normal, 3=ended; isReal folds physiological validity. */
data class AidexRealtimeSample(
    val readingType: Int,
    val trendTenthsPerMin: Int,
    val minFromStart: Int,
    val glucoseMgdl: Int,
    val warmup: Boolean,
    val valid: Boolean,
    val isReal: Boolean,
)

/** One on-sensor history record (CGM.md §6); its instant is `activationEpoch + recordId*60`. */
data class AidexHistorySample(
    val recordId: Int,
    val glucoseMgdl: Int,
    val warmup: Boolean,
    val valid: Boolean,
    val isReal: Boolean,
)

/** Decoded 0xF002 responses (CGM.md §6); lean projection of AidexResponse. */
sealed interface AidexSessionResponse {
    /** `0x111` — a `LastPast` (CGM.md §6). */
    data class Current(
        val minFromStart: Int,
        val trendTenthsPerMin: Int,
        val glucoseMgdl: Int,
        val valid: Boolean,
        val quality: Int,
    ) : AidexSessionResponse

    /** 0x121 (CGM.md §7); year is activation discriminator, implausible needs activation. */
    data class StartTime(val year: Int, val epochSecs: Long) : AidexSessionResponse

    /** `0x110`. */
    data class DeviceInfo(val firmware: String, val name: String, val lifeDays: Int) : AidexSessionResponse

    /** `0x122` — newest absolute record id (CGM.md §6). */
    data class LastId(val lastId: Int) : AidexSessionResponse

    /** `0x123` — a history batch (CGM.md §6); [startId] is absolute, the request id is not. */
    data class History(val startId: Int, val samples: List<AidexHistorySample>) : AidexSessionResponse

    /** `0x120`/`0x131`/`0x134`/`0x135`. */
    data class Ack(val tag: Int) : AidexSessionResponse

    /** `0x1F2`/`0x0F2`: disconnect, bond kept (CGM.md §5). */
    data class Disconnect(val success: Boolean) : AidexSessionResponse

    data class Unknown(val tag: Int) : AidexSessionResponse
}

/** CGM.md §4-7; crypto is golden-gated in Rust, NOT re-verified here; fallible ops return null. */
interface AidexSession {
    /** 16 bytes, fixed per serial and reused for every message (CGM.md §4.3). */
    fun iv(serial: String): ByteArray

    /** 16 bytes, written to `0xF001` to open the handshake (CGM.md §4.3). */
    fun askKey(serial: String): ByteArray

    /** blob is the 17-byte 0xF002 read (CGM.md §4.4); null on bad size or failing crc8_maxim. */
    fun deriveSession(serial: String, masterKey: ByteArray, blob: ByteArray): ByteArray?

    /** CFB-encrypts `payload || crc16` (CGM.md §4.5). */
    fun encryptFrame(sess: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray

    /** Verifies and strips trailing CRC16 (CGM.md §4.5); null on a runt or CRC-failing frame. */
    fun decryptFrame(sess: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray?

    /** 15 bytes decrypted; `null` on a short payload. */
    fun parseRealtime(plaintext: ByteArray): AidexRealtimeSample?

    /** CGM.md §6; `null` on a short payload. */
    fun parseResponse(plaintext: ByteArray): AidexSessionResponse?

    fun cmdSetAutoUpdate(sess: ByteArray, iv: ByteArray): ByteArray
    fun cmdGetBroadcast(sess: ByteArray, iv: ByteArray): ByteArray
    fun cmdGetStartTime(sess: ByteArray, iv: ByteArray): ByteArray
    fun cmdDeviceInfo(sess: ByteArray, iv: ByteArray): ByteArray

    /** `0x22`: the newest ABSOLUTE record id the sensor holds (CGM.md §6). */
    fun cmdGetLastId(sess: ByteArray, iv: ByteArray): ByteArray

    /** `0x23`: [relId] is 1-based into the sensor's history RANGE, not an absolute record id. */
    fun cmdGetHistory(sess: ByteArray, iv: ByteArray, relId: Int): ByteArray

    /** 0x35 01. Step 2 of validated activation 0x20->0x35->0x34->0x21->0x11, no 0x31. */
    fun cmdSetDynamicAdvMode(sess: ByteArray, iv: ByteArray): ByteArray

    /** `0x20`, with the 9-byte [localStart] from [encodeLocalStartTime]. */
    fun cmdSetNewSensor(sess: ByteArray, iv: ByteArray, localStart: ByteArray): ByteArray

    /** 9-byte `LocalStartTime` (CGM.md §7). */
    fun encodeLocalStartTime(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        tzQuarterHours: Int,
        dstQuarterHours: Int,
    ): ByteArray
}

/** Rust t1dm-core over uniffi; a hostile blob/bad CRC/short payload maps CoreException to null. */
class UniffiAidexSession : AidexSession {

    override fun iv(serial: String): ByteArray = aidexIv(serial)

    override fun askKey(serial: String): ByteArray = aidexAskkey(serial)

    override fun deriveSession(serial: String, masterKey: ByteArray, blob: ByteArray): ByteArray? =
        try {
            aidexDeriveSession(serial, masterKey, blob)
        } catch (e: CoreException) {
            Log.w(TAG, "session derivation failed: ${e.message}")
            null
        }

    override fun encryptFrame(sess: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray =
        aidexEncryptFrame(sess, iv, payload)

    override fun decryptFrame(sess: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray? =
        try {
            aidexDecryptFrame(sess, iv, ct)
        } catch (e: CoreException) {
            Log.w(TAG, "frame decrypt/CRC failed: ${e.message}")
            null
        }

    override fun parseRealtime(plaintext: ByteArray): AidexRealtimeSample? =
        try {
            val r = aidexParseRealtime(plaintext)
            AidexRealtimeSample(
                readingType = r.readingType,
                trendTenthsPerMin = r.trendTenthsPerMin,
                minFromStart = r.minFromStart,
                glucoseMgdl = r.glucoseMgdl,
                warmup = r.warmup,
                valid = r.valid,
                isReal = r.isReal,
            )
        } catch (e: CoreException) {
            Log.w(TAG, "realtime parse failed: ${e.message}")
            null
        }

    override fun parseResponse(plaintext: ByteArray): AidexSessionResponse? =
        try {
            when (val r = aidexParseResponse(plaintext)) {
                is UniffiAidexResponse.Current -> AidexSessionResponse.Current(
                    minFromStart = r.reading.minFromStart,
                    trendTenthsPerMin = r.reading.trendTenthsPerMin,
                    glucoseMgdl = r.reading.glucoseMgdl,
                    valid = r.reading.valid,
                    quality = r.reading.quality,
                )
                is UniffiAidexResponse.StartTime -> AidexSessionResponse.StartTime(r.time.year, r.time.epochSecs)
                is UniffiAidexResponse.DeviceInfo ->
                    AidexSessionResponse.DeviceInfo(r.info.firmware, r.info.name, r.info.lifeDays)
                is UniffiAidexResponse.LastId -> AidexSessionResponse.LastId(r.lastId)
                is UniffiAidexResponse.History -> AidexSessionResponse.History(
                    startId = r.startId,
                    samples = r.entries.map {
                        AidexHistorySample(
                            recordId = it.recordId,
                            glucoseMgdl = it.glucoseMgdl,
                            warmup = it.warmup,
                            valid = it.valid,
                            isReal = it.isReal,
                        )
                    },
                )
                is UniffiAidexResponse.Ack -> AidexSessionResponse.Ack(r.tag)
                is UniffiAidexResponse.Disconnect -> AidexSessionResponse.Disconnect(r.success)
                is UniffiAidexResponse.Unknown -> AidexSessionResponse.Unknown(r.tag)
            }
        } catch (e: CoreException) {
            Log.w(TAG, "response parse failed: ${e.message}")
            null
        }

    override fun cmdSetAutoUpdate(sess: ByteArray, iv: ByteArray): ByteArray = aidexCmdSetAutoUpdate(sess, iv)
    override fun cmdGetBroadcast(sess: ByteArray, iv: ByteArray): ByteArray = aidexCmdGetBroadcast(sess, iv)
    override fun cmdGetStartTime(sess: ByteArray, iv: ByteArray): ByteArray = aidexCmdGetStartTime(sess, iv)
    override fun cmdDeviceInfo(sess: ByteArray, iv: ByteArray): ByteArray = aidexCmdDeviceInfo(sess, iv)

    override fun cmdGetLastId(sess: ByteArray, iv: ByteArray): ByteArray = aidexCmdGetLastId(sess, iv)

    override fun cmdGetHistory(sess: ByteArray, iv: ByteArray, relId: Int): ByteArray =
        aidexCmdGetHistory(sess, iv, relId)

    override fun cmdSetDynamicAdvMode(sess: ByteArray, iv: ByteArray): ByteArray = aidexCmdSetDynamicAdvMode(sess, iv)

    override fun cmdSetNewSensor(sess: ByteArray, iv: ByteArray, localStart: ByteArray): ByteArray =
        aidexCmdSetNewSensor(sess, iv, localStart)

    override fun encodeLocalStartTime(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        tzQuarterHours: Int,
        dstQuarterHours: Int,
    ): ByteArray = aidexEncodeLocalStartTime(year, month, day, hour, minute, second, tzQuarterHours, dstQuarterHours)

    private companion object {
        const val TAG = "AidexSession"
    }
}
