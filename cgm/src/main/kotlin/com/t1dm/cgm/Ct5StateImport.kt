package com.t1dm.cgm

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Adopting a bound sensor: 0x30 can't be replayed, CIPHER_ID never re-mints; nothing repairs. */

interface Ct5ImportSource {
    /** The pending document, or `null` when there is none. Must not throw. */
    suspend fun read(): String?

    /** Called only after the secret is stored: a crash repeats the import, never overwrites. */
    suspend fun consume()
}

data class Ct5Import(val bsn: String, val state: Ct5SensorState)

object Ct5StateImport {

    /** null if the document isn't fully trustable; never throws, never logs key material. */
    fun parse(text: String): Ct5Import? = runCatching { parseOrThrow(text) }.getOrNull()

    private fun parseOrThrow(text: String): Ct5Import? {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return null

        // A dry run writes a full document for a bind that never reached the sensor; must be false.
        if (root.bool("dry_run") != false) return null

        val bsn = root.str("BSN") ?: return null
        if (bsn.length != Ct5Constants.BSN_DIGITS || !bsn.all { it in '0'..'9' }) return null

        val cipherId = root.int("CIPHER_ID") ?: return null
        if (cipherId !in 0..255) return null

        val a = root.nonce("A") ?: return null
        val b = root.nonce("B") ?: return null

        val randomId = root.str("RANDOM_ID") ?: return null
        if (randomId.length != Ct5Constants.RANDOM_ID_DIGITS || !randomId.all { it in '0'..'9' }) {
            return null
        }

        val kX100 = root.hundredths("K") ?: return null
        val rX100 = root.hundredths("R") ?: return null

        val ssn = root.str("SSN") ?: return null
        // encode writes the SSN as US-ASCII behind a length byte; anything else won't round-trip.
        if (ssn.isEmpty() || ssn.length > 255 || !ssn.all { it.code in 0..127 }) return null

        val bindTimeMs = root.str("bind_started_utc")
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return null
        // Origin of every reading ts: bindTimeMs+glucoseId*SAMPLE_INTERVAL_MS, not recomputed.
        if (bindTimeMs <= 0L) return null

        return Ct5Import(
            bsn = bsn,
            state = Ct5SensorState(
                cipherId = cipherId,
                a = a,
                b = b,
                randomId = randomId,
                kX100 = kX100,
                rX100 = rX100,
                ssn = ssn,
                bindTimeMs = bindTimeMs,
                // Imported sensor's bind FINISHED; unfinished rewrites 0x38 over the only password.
                initialised = true,
            ),
        )
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)

    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = prim(key)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.nonce(key: String): ByteArray? {
        val arr = (this[key] as? JsonArray) ?: return null
        if (arr.size != Ct5Constants.NONCE_BYTES) return null
        val out = ByteArray(Ct5Constants.NONCE_BYTES)
        arr.forEachIndexed { i, e ->
            val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    /** EXACT hundredths, or null if not representable; a bad value is refused, not rounded. */
    private fun JsonObject.hundredths(key: String): Int? {
        val v = prim(key)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        if (!v.isFinite() || v <= 0.0) return null
        val scaled = v * 100.0
        val rounded = Math.round(scaled).toInt()
        if (Math.abs(scaled - rounded) > 1e-6) return null
        if (rounded !in 1..0xFFFF) return null
        return rounded
    }
}
