package com.t1dm.cgm

import java.security.SecureRandom

/** Both nonces drawn by the CLIENT: this source's quality is the sensor's secret's quality. */
interface Ct5Nonces {
    /** [Ct5Constants.NONCE_BYTES] random bytes. */
    fun nonce(): ByteArray

    /** [Ct5Constants.RANDOM_ID_DIGITS] ASCII digits — the sensor's unbind password. */
    fun randomId(): String
}

object SecureRandomCt5Nonces : Ct5Nonces {
    private val rnd = SecureRandom()

    override fun nonce(): ByteArray = ByteArray(Ct5Constants.NONCE_BYTES).also(rnd::nextBytes)

    override fun randomId(): String {
        val digits = CharArray(Ct5Constants.RANDOM_ID_DIGITS) { ('0' + rnd.nextInt(10)) }
        return String(digits)
    }
}

/** Everything about ONE bound sensor unrecoverable from it: a lost byte strands the sensor. */
data class Ct5SensorState(
    /** Only ever known up to the pair `(k, k xor 0xFF)`. */
    val cipherId: Int,
    val a: ByteArray,
    /** The client nonce the sensor stores and `0x31` checkID presents on every reconnect. */
    val b: ByteArray,
    /** ASCII digits. The ONLY unbind password. */
    val randomId: String,
    val kX100: Int,
    val rX100: Int,
    /** The identity string `0x3F` answered, verbatim. */
    val ssn: String,
    /** Time = this+glucoseId*SAMPLE_INTERVAL_MS; a skewed push re-anchors, repair moves it back. */
    val bindTimeMs: Long,
    /** Whether the bind ever FINISHED; false resumes reconnect from 0x38 instead of waiting. */
    val initialised: Boolean,
) {
    init {
        // Wire-fixed widths: an invariant, not validation, so encode needs no bounds of its own.
        require(a.size == Ct5Constants.NONCE_BYTES) { "nonce A must be ${Ct5Constants.NONCE_BYTES} bytes" }
        require(b.size == Ct5Constants.NONCE_BYTES) { "nonce B must be ${Ct5Constants.NONCE_BYTES} bytes" }
        require(randomId.length == Ct5Constants.RANDOM_ID_DIGITS && randomId.all { it in '0'..'9' }) {
            "RANDOM_ID must be ${Ct5Constants.RANDOM_ID_DIGITS} ASCII digits"
        }
        require(cipherId in 0..255) { "CIPHER_ID is 8-bit" }
        require(ssn.length in 1..255) { "SSN must fit a length byte" }
    }

    /** Big-endian; the one variable field is length-prefixed. */
    fun encode(): ByteArray {
        val ssnBytes = ssn.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(HEADER + ssnBytes.size)
        out[0] = VERSION
        out[1] = cipherId.toByte()
        a.copyInto(out, 2)
        b.copyInto(out, 6)
        randomId.toByteArray(Charsets.US_ASCII).copyInto(out, 10)
        out.putBe16(14, kX100)
        out.putBe16(16, rX100)
        out.putBe64(18, bindTimeMs)
        out[26] = ssnBytes.size.toByte()
        out[27] = if (initialised) 1 else 0
        ssnBytes.copyInto(out, HEADER)
        return out
    }

    // ByteArrays: identity-compare would make two equal states unequal.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is Ct5SensorState &&
                cipherId == other.cipherId &&
                a.contentEquals(other.a) &&
                b.contentEquals(other.b) &&
                randomId == other.randomId &&
                kX100 == other.kX100 &&
                rX100 == other.rX100 &&
                ssn == other.ssn &&
                bindTimeMs == other.bindTimeMs &&
                initialised == other.initialised
            )

    override fun hashCode(): Int {
        var h = cipherId
        h = 31 * h + a.contentHashCode()
        h = 31 * h + b.contentHashCode()
        h = 31 * h + randomId.hashCode()
        h = 31 * h + kX100
        h = 31 * h + rX100
        h = 31 * h + ssn.hashCode()
        h = 31 * h + bindTimeMs.hashCode()
        h = 31 * h + if (initialised) 1 else 0
        return h
    }

    /** Never log the key material. */
    override fun toString(): String =
        "Ct5SensorState(ssn=$ssn, k=$kX100/100, r=$rX100/100, bindTimeMs=$bindTimeMs, " +
            "initialised=$initialised, secrets withheld)"

    companion object {
        private const val VERSION: Byte = 2

        /** Bytes before the SSN. */
        private const val HEADER = 28

        /** null unless this version wrote the blob; fail-closed: half a blob is wrong RANDOM_ID. */
        fun decode(blob: ByteArray): Ct5SensorState? {
            if (blob.size < HEADER || blob[0] != VERSION) return null
            val ssnLen = blob[26].toInt() and 0xFF
            // Every constructor gate is checked first; a malformed blob is null, not a throw.
            if (ssnLen == 0 || blob.size != HEADER + ssnLen) return null
            val randomId = String(blob, 10, Ct5Constants.RANDOM_ID_DIGITS, Charsets.US_ASCII)
            if (randomId.length != Ct5Constants.RANDOM_ID_DIGITS || !randomId.all { it in '0'..'9' }) {
                return null
            }
            return Ct5SensorState(
                cipherId = blob[1].toInt() and 0xFF,
                a = blob.copyOfRange(2, 6),
                b = blob.copyOfRange(6, 10),
                randomId = randomId,
                kX100 = blob.be16(14),
                rX100 = blob.be16(16),
                ssn = String(blob, HEADER, ssnLen, Charsets.US_ASCII),
                bindTimeMs = blob.be64(18),
                // Bad byte reads unfinished: doubt costs a frame, false trust strands the sensor.
                initialised = blob[27].toInt() == 1,
            )
        }

        private fun ByteArray.putBe16(at: Int, v: Int) {
            this[at] = (v ushr 8).toByte()
            this[at + 1] = v.toByte()
        }

        private fun ByteArray.putBe64(at: Int, v: Long) {
            for (i in 0 until 8) this[at + i] = (v ushr (56 - 8 * i)).toByte()
        }

        private fun ByteArray.be16(at: Int): Int =
            ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

        private fun ByteArray.be64(at: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (this[at + i].toLong() and 0xFF)
            return v
        }
    }
}
