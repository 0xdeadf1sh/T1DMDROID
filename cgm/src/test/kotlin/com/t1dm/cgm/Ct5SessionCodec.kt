package com.t1dm.cgm

/** Pure-JVM reference for host tests; agreement with Rust pinned by [Ct5SessionCodecTest]. */
object Ct5SessionCodec : Ct5Session {


    fun checksum(bytes: ByteArray, upTo: Int = bytes.size): Int {
        var sum = 0
        for (i in 0 until upTo) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFF
    }

    fun frame(opcode: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 2)
        out[0] = opcode.toByte()
        payload.copyInto(out, 1)
        out[out.size - 1] = checksum(out, out.size - 1).toByte()
        return out
    }

    fun filler(opcode: Int): ByteArray = frame(opcode, byteArrayOf(0x55, 0xAA.toByte()))

    override fun frameIsLegal(frame: ByteArray): Boolean =
        frame.size > 1 && (frame[frame.size - 1].toInt() and 0xFF) == checksum(frame, frame.size - 1)


    private fun toBits(bytes: ByteArray): IntArray {
        val bits = IntArray(bytes.size * 8)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            for (s in 0 until 8) bits[i * 8 + s] = (b shr (7 - s)) and 1
        }
        return bits
    }

    private fun fromBits(bits: IntArray): ByteArray =
        ByteArray(bits.size / 8) { i ->
            var v = 0
            for (s in 0 until 8) v = (v shl 1) or bits[i * 8 + s]
            v.toByte()
        }

    /** For a payload as RECEIVED. */
    fun deobfuscate(input: ByteArray, k: Int): ByteArray {
        if (input.isEmpty()) return input
        val bits = toBits(ByteArray(input.size) { (input[it].toInt() xor k).toByte() })
        val out = IntArray(bits.size)
        for (i in 0 until bits.size - 1) out[i] = if (bits[i] == bits[i + 1]) 1 else 0
        out[bits.size - 1] = bits[bits.size - 1]
        return fromBits(out)
    }

    /** Exact inverse of [deobfuscate]. */
    fun obfuscate(input: ByteArray, k: Int): ByteArray {
        if (input.isEmpty()) return input
        val bits = toBits(input)
        for (i in bits.size - 2 downTo 0) if (bits[i + 1] == 0) bits[i] = bits[i] xor 1
        val bytes = fromBits(bits)
        return ByteArray(bytes.size) { (bytes[it].toInt() xor k).toByte() }
    }


    override fun buildSetDate(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): ByteArray? {
        if (year < 1900 || year > 2155 || month !in 1..12 || day !in 1..31 ||
            hour !in 0..23 || minute !in 0..59 || second !in 0..60
        ) {
            return null
        }
        return frame(
            Ct5Constants.Opcode.SET_DATE,
            byteArrayOf(
                (year - 1900).toByte(), month.toByte(), day.toByte(),
                hour.toByte(), minute.toByte(), second.toByte(),
            ),
        )
    }

    override fun buildVersionRequest(): ByteArray = byteArrayOf(Ct5Constants.Opcode.VERSION.toByte())
    override fun buildSelfCheck(): ByteArray = filler(Ct5Constants.Opcode.SELF_CHECK)
    override fun buildQuerySsn(): ByteArray = filler(Ct5Constants.Opcode.QUERY_SSN)
    override fun buildInit(): ByteArray = filler(Ct5Constants.Opcode.INIT)
    override fun buildLowPower(): ByteArray = filler(Ct5Constants.Opcode.LOW_POWER)
    override fun buildPushAck(): ByteArray = filler(Ct5Constants.Opcode.PUSH)

    /** b.size terms, as ct5_session.rs; products unreduced, the caller narrows the wire bytes. */
    fun convolve(b: ByteArray, a: ByteArray): IntArray {
        if (a.isEmpty()) return IntArray(0)
        val out = IntArray(b.size)
        for (i in b.indices) {
            for (j in 0 until minOf(a.size, b.size - i)) out[i + j] += (b[i].toInt() and 0xFF) * (a[j].toInt() and 0xFF)
        }
        return out
    }

    override fun buildSetId(b: ByteArray, a: ByteArray): ByteArray? {
        if (b.size != Ct5Constants.NONCE_BYTES || a.size != Ct5Constants.NONCE_BYTES) return null
        val conv = convolve(b, a)
        val payload = ByteArray(Ct5Constants.NONCE_BYTES * 2)
        b.copyInto(payload)
        for (i in 0 until Ct5Constants.NONCE_BYTES) payload[Ct5Constants.NONCE_BYTES + i] = conv[i].toByte()
        return frame(Ct5Constants.Opcode.SET_ID, payload)
    }

    override fun buildCheckId(b: ByteArray): ByteArray? =
        if (b.size != Ct5Constants.NONCE_BYTES) null else frame(Ct5Constants.Opcode.CHECK_ID, b)

    fun setParametersPayload(
        kX100: Int,
        rX100: Int,
        intervalMin: Int,
        cycleDays: Int,
        randomId: String,
    ): ByteArray? {
        if (kX100 !in 0..25599 || rX100 !in 0..25599 || intervalMin !in 1..255 || cycleDays !in 1..255) return null
        if (randomId.length != Ct5Constants.RANDOM_ID_DIGITS || !randomId.all { it in '0'..'9' }) return null
        return byteArrayOf(
            (kX100 / 100).toByte(), (kX100 % 100).toByte(),
            (rX100 / 100).toByte(), (rX100 % 100).toByte(),
            intervalMin.toByte(), cycleDays.toByte(),
            0x55, 0x00,
            randomId[0].code.toByte(), randomId[1].code.toByte(),
            randomId[2].code.toByte(), randomId[3].code.toByte(),
        )
    }

    override fun buildSetParameters(
        kX100: Int,
        rX100: Int,
        intervalMin: Int,
        cycleDays: Int,
        randomId: String,
        cipherId: Int,
    ): ByteArray? {
        if (cipherId !in 0..255) return null
        val payload = setParametersPayload(kX100, rX100, intervalMin, cycleDays, randomId) ?: return null
        return frame(Ct5Constants.Opcode.SET_PARAMETERS, obfuscate(payload, cipherId))
    }

    override fun verifySetParametersEcho(
        reply: ByteArray,
        kX100: Int,
        rX100: Int,
        intervalMin: Int,
        cycleDays: Int,
        randomId: String,
        cipherId: Int,
    ): Boolean {
        if (reply.size != 14 || !frameIsLegal(reply) || cipherId !in 0..255) return false
        val want = setParametersPayload(kX100, rX100, intervalMin, cycleDays, randomId) ?: return false
        return deobfuscate(reply.copyOfRange(1, 13), cipherId).contentEquals(want)
    }

    override fun cipherIdFromSetIdReply(reply: ByteArray, a: ByteArray, b: ByteArray): Int? {
        if (reply.size != 10 || (reply[0].toInt() and 0xFF) != Ct5Constants.Opcode.SET_ID) return null
        if (!frameIsLegal(reply) || a.size != Ct5Constants.NONCE_BYTES) return null
        if (b.size != Ct5Constants.NONCE_BYTES) return null
        if (!reply.copyOfRange(1, 1 + Ct5Constants.NONCE_BYTES).contentEquals(b)) return null
        var acc = 0
        for (v in convolve(reply.copyOfRange(5, 9), a)) acc = acc xor v
        return acc and 0xFF
    }


    override fun parsePush(frame: ByteArray, cipherId: Int): Ct5PushSample? {
        if (frame.size != 19 && frame.size != 15) return null
        if ((frame[0].toInt() and 0xFF) != Ct5Constants.Opcode.PUSH || !frameIsLegal(frame)) return null
        if (cipherId !in 0..255) return null
        val id = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)
        return decodeRecord(deobfuscate(frame.copyOfRange(3, frame.size - 1), cipherId), id)
    }

    /** Mirrors `glucose_from_current` in the core; [Ct5SessionCodecTest] holds the two together. */
    override fun glucoseMgdl(sample: Ct5PushSample, kX100: Int): Int? {
        if (kX100 <= 0 || sample.glucoseId < 14 || sample.glucoseId >= 10_095) return null
        val iw = sample.iwX100 / 100f
        val k0 = kX100 / 100f
        val normalised = iw / k0
        if (iw <= 1f || normalised <= 0.6f || normalised > 57.6f) return null

        val factor = if (iw > 50f) {
            1f
        } else {
            val tc = (sample.tempCx100 / 100f).coerceIn(12f, 48f)
            val g = when {
                tc > 39.4f -> 0.55f
                tc > 36.4f -> 4.35f / (tc - 32f)
                tc > 32f -> 0.2f * (tc - 32f) / 4.4f + 0.8f
                else -> 1f
            }
            1f + (tc - 32f) * -0.045929998159408569f * g
        }

        var sensitivity = 1.2f * k0
        if (sample.glucoseId <= 479) sensitivity *= 0.9f + sample.glucoseId * 0.1f / 480f
        val mmol = iw * factor / sensitivity.coerceIn(0.5f * k0, 2.5f * k0)

        return when {
            mmol > 27.8f -> 500
            mmol < 1.7f -> 31
            else -> (mmol * 18f + 0.5f).toInt()
        }
    }

    /** [rec] is already deobfuscated. */
    private fun decodeRecord(rec: ByteArray, glucoseId: Int): Ct5PushSample {
        val at = { i: Int -> rec[i].toInt() and 0xFF }
        val be = { hi: Int, lo: Int -> (at(hi) shl 8) or at(lo) }
        val glucose = ((at(6) and 0x0F) shl 8) or at(7)
        val voltage = rec.size == 15
        return Ct5PushSample(
            glucoseId = glucoseId,
            glucoseMgdl = if (glucose != 0) glucose else null,
            trendCode = at(6) shr 4,
            errorCode = at(8),
            // INTEGER-FIRST, pinned in the Rust golden.
            tempCx100 = at(5) + (at(4) - 40) * 100,
            ibX100 = be(0, 1),
            iwX100 = be(2, 3),
            batteryRaw = if (voltage) be(13, 14) else null,
            electrodesMv = if (voltage) listOf(at(9) * 6, at(10) * 6, at(11) * 6, at(12) * 6) else null,
        )
    }

    override fun buildPullHistory(startId: Int, count: Int): ByteArray? {
        if (startId !in 0..0xFFFF) return null
        val n = count.coerceIn(1, 45)
        return frame(
            Ct5Constants.Opcode.PULL_HISTORY,
            byteArrayOf((startId and 0xFF).toByte(), (startId shr 8).toByte(), n.toByte()),
        )
    }

    override fun historyBatchSize(mtu: Int, recordSize: Int): Int {
        if (recordSize <= 0 || mtu <= Ct5Constants.HISTORY_ENVELOPE_BYTES) return 1
        return (((mtu - Ct5Constants.HISTORY_ENVELOPE_BYTES) / recordSize) - 1).coerceIn(1, 45)
    }

    override fun historyRecordSize(frameLen: Int): Int? = when (frameLen) {
        Ct5Constants.HISTORY_ENVELOPE_BYTES + Ct5Constants.RECORD_SHORT_BYTES ->
            Ct5Constants.RECORD_SHORT_BYTES
        Ct5Constants.HISTORY_ENVELOPE_BYTES + Ct5Constants.RECORD_VOLTAGE_BYTES ->
            Ct5Constants.RECORD_VOLTAGE_BYTES
        else -> null
    }

    override fun parseHistory(frame: ByteArray, cipherId: Int, recordSize: Int): Ct5HistoryBatch? {
        if (frame.size < Ct5Constants.HISTORY_ENVELOPE_BYTES) return null
        if ((frame[0].toInt() and 0xFF) != Ct5Constants.Opcode.PULL_HISTORY || !frameIsLegal(frame)) return null
        if (cipherId !in 0..255) return null
        if (recordSize != Ct5Constants.RECORD_SHORT_BYTES && recordSize != Ct5Constants.RECORD_VOLTAGE_BYTES) {
            return null
        }
        val body = frame.copyOfRange(3, frame.size - 1)
        if (body.size % recordSize != 0) return null
        val startId = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)

        val count = body.size / recordSize
        fun marker(position: Int, byte: Byte) =
            (0 until recordSize).all { body[position * recordSize + it] == byte }

        // Terminators are LITERAL WIRE bytes, matched ahead of any deobfuscation.
        var slots = count
        var endOfHistory = false
        for (position in 0 until count) {
            if (marker(position, 0xFC.toByte())) {
                slots = position
                endOfHistory = true
                break
            }
        }

        // Each maximal marker-free run is ONE obfuscated stream; see [historyReply].
        val samples = ArrayList<Ct5PushSample>(slots)
        var runStart = 0
        var position = 0
        while (position <= slots) {
            if (position < slots && !marker(position, 0xFF.toByte())) {
                position++
                continue
            }
            if (position > runStart) {
                val plain = deobfuscate(
                    body.copyOfRange(runStart * recordSize, position * recordSize),
                    cipherId,
                )
                for (n in 0 until (position - runStart)) {
                    val rec = decodeRecord(
                        plain.copyOfRange(n * recordSize, (n + 1) * recordSize),
                        (runStart + n + startId).coerceAtMost(0xFFFF),
                    )
                    // The all-`0xFF` slot as it reads after deobfuscation under some other key.
                    if (rec.iwX100 > 65500 && rec.ibX100 > 65500 && rec.tempCx100 > 21500) continue
                    samples += rec
                }
            }
            position++
            runStart = position
        }
        return Ct5HistoryBatch(
            startId = startId,
            samples = samples,
            endOfHistory = endOfHistory,
            slots = slots,
        )
    }

    override fun parseSsnResponse(frame: ByteArray, cipherId: Int): Ct5SensorIdentity? {
        if (frame.size < 2 || (frame[0].toInt() and 0xFF) != Ct5Constants.Opcode.QUERY_SSN) return null
        val body = frame.copyOfRange(1, frame.size)
        val candidates = if (cipherId in 0..255) listOf(deobfuscate(body, cipherId), body) else listOf(body)
        for (c in candidates) {
            if (c.size !in setOf(17, 18, 21)) continue
            if (!c.all { (it.toInt() and 0xFF) in 0x20..0x7E }) continue
            identityOf(String(c, Charsets.US_ASCII))?.let { return it }
        }
        return null
    }

    /** The 21-character form only; the full three-grammar decode is the Rust side's. */
    private fun identityOf(s: String): Ct5SensorIdentity? {
        if (s.length != 21 || !s.all { it.isDigit() || it in 'A'..'Z' || it in 'a'..'z' }) return null
        val digits = { from: Int, to: Int -> s.substring(from, to).toIntOrNull() }
        val k = digits(13, 16) ?: return null
        val r = digits(16, 18) ?: return null
        val unit = digits(6, 9) ?: return null
        if (unit == 0) return null
        return Ct5SensorIdentity(
            ssn = s,
            kX100 = k,
            rX100 = r * 10,
            lifeTime = digits(1, 2) ?: 0,
            calibration = digits(12, 13) ?: 0,
            unitOrder = unit,
            year = digits(3, 4) ?: 0,
            serialNo = s.substring(4, 6),
            sensorNo = s.substring(9, 12),
        )
    }

    override fun parseVersion(reply: ByteArray): Ct5Version? {
        if (reply.size != 14 || (reply[0].toInt() and 0xFF) != Ct5Constants.Opcode.VERSION) return null
        val ascii = { from: Int, to: Int ->
            String(CharArray(to - from) { i ->
                val c = reply[from + i].toInt() and 0xFF
                if (c in 0x20..0x7E) c.toChar() else '?'
            })
        }
        return Ct5Version(
            year = (reply[1].toInt() and 0xFF) * 100 + (reply[2].toInt() and 0xFF),
            month = reply[3].toInt() and 0xFF,
            day = reply[4].toInt() and 0xFF,
            version = "V" + ascii(6, 10),
            algorithm = ascii(10, 14),
        )
    }

    override fun parseSelfCheck(reply: ByteArray): Boolean =
        reply.size == 20 && (reply[0].toInt() and 0xFF) == Ct5Constants.Opcode.SELF_CHECK && frameIsLegal(reply)

    override fun parseSetDateResponse(reply: ByteArray): Boolean =
        frameIsLegal(reply) &&
            (reply[0].toInt() and 0xFF).let {
                it == Ct5Constants.Opcode.SET_DATE || it == Ct5Constants.Opcode.SET_DATE_REPLY
            }

    override fun parseInitResponse(reply: ByteArray): Boolean =
        frameIsLegal(reply) && (reply[0].toInt() and 0xFF) == Ct5Constants.Opcode.INIT

    override fun parseCheckIdResponse(reply: ByteArray): Ct5BindVerdict {
        if (!frameIsLegal(reply) || (reply[0].toInt() and 0xFF) != Ct5Constants.Opcode.CHECK_ID) {
            return Ct5BindVerdict.AMBIGUOUS
        }
        if (reply.size <= 6) return Ct5BindVerdict.AMBIGUOUS
        return if ((reply[5].toInt() and 0xFF) == 1) Ct5BindVerdict.ACCEPTED else Ct5BindVerdict.REJECTED
    }

    override fun parseAdvert(mfg: ByteArray): Ct5AdvertInfo? {
        if (mfg.size < 4) return null
        if (!(mfg[0] == 'C'.code.toByte() && mfg[1] == 'G'.code.toByte() && mfg[2] == 'M'.code.toByte())) {
            return null
        }
        val bound = mfg[3].toInt() == 1
        if (mfg.size < 26) return Ct5AdvertInfo(bound, running = false, recordCount = 0, startGlucoseId = 0, checksumValid = false)
        var sum = 0
        for (i in 4..24) sum += mfg[i].toInt() and 0xFF
        return Ct5AdvertInfo(
            bound = bound,
            running = (7 until 25).any { mfg[it] != 0xFF.toByte() },
            recordCount = minOf(mfg[4].toInt() and 0x0F, 6),
            startGlucoseId = (mfg[5].toInt() and 0xFF) or ((mfg[6].toInt() and 0xFF) shl 8),
            checksumValid = (mfg[25].toInt() and 0xFF) == (sum and 0xFF),
        )
    }


    /** A `0x35` push carrying the 15-byte voltage record. */
    fun push(
        glucoseId: Int,
        cipherId: Int,
        glucoseMgdl: Int = 0,
        errorCode: Int = 0,
        tempIntC: Int = 31,
        tempHundredths: Int = 4,
        trendCode: Int = 0,
        battery: Int = 1603,
        iwX100: Int = inverseIwX100(glucoseMgdl, tempIntC * 100 + tempHundredths, glucoseId),
    ): ByteArray {
        val rec = recordBytes(glucoseMgdl, errorCode, tempIntC, tempHundredths, trendCode, battery, iwX100)
        val payload = ByteArray(2 + 15)
        payload[0] = (glucoseId and 0xFF).toByte()
        payload[1] = ((glucoseId shr 8) and 0xFF).toByte()
        obfuscate(rec, cipherId).copyInto(payload, 2)
        return frame(Ct5Constants.Opcode.PUSH, payload)
    }

    /** One 15-byte record's PLAINTEXT, the same layout [push] builds. */
    fun record(
        glucoseMgdl: Int = 0,
        errorCode: Int = 0,
        tempIntC: Int = 31,
        tempHundredths: Int = 4,
        trendCode: Int = 0,
        battery: Int = 1603,
        iwX100: Int = 0,
    ): ByteArray = recordBytes(glucoseMgdl, errorCode, tempIntC, tempHundredths, trendCode, battery, iwX100)

    /** Record must carry the CURRENT converting back to the wire value; [historyReply] sets it. */
    private fun recordBytes(
        glucoseMgdl: Int,
        errorCode: Int,
        tempIntC: Int,
        tempHundredths: Int,
        trendCode: Int,
        battery: Int,
        iwX100: Int,
    ): ByteArray {
        val rec = ByteArray(15)
        rec[2] = ((iwX100 shr 8) and 0xFF).toByte()
        rec[3] = (iwX100 and 0xFF).toByte()
        rec[4] = (tempIntC + 40).toByte()
        rec[5] = tempHundredths.toByte()
        rec[6] = (((trendCode and 0x0F) shl 4) or ((glucoseMgdl shr 8) and 0x0F)).toByte()
        rec[7] = (glucoseMgdl and 0xFF).toByte()
        rec[8] = errorCode.toByte()
        rec[9] = 173.toByte(); rec[10] = 173.toByte(); rec[11] = 166.toByte(); rec[12] = 108
        rec[13] = ((battery shr 8) and 0xFF).toByte()
        rec[14] = (battery and 0xFF).toByte()
        return rec
    }

    /** End-of-store marker; LITERAL on the wire. */
    val END_OF_HISTORY: ByteArray = ByteArray(15) { 0xFC.toByte() }

    /** Empty-slot marker; LITERAL on the wire. */
    val EMPTY_SLOT: ByteArray = ByteArray(15) { 0xFF.toByte() }

    /** Each marker-free run is one obfuscated stream, not per record; markers pass through raw. */
    fun historyReply(startId: Int, cipherId: Int, records: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(startId and 0xFF)
        out.write((startId shr 8) and 0xFF)
        val run = java.io.ByteArrayOutputStream()
        fun flush() {
            if (run.size() > 0) out.write(obfuscate(run.toByteArray(), cipherId))
            run.reset()
        }
        for ((slot, rec) in records.withIndex()) {
            if (rec === END_OF_HISTORY || rec === EMPTY_SLOT) {
                flush()
                out.write(rec)
            } else {
                run.write(withCurrentFor(rec, startId + slot))
            }
        }
        flush()
        return frame(Ct5Constants.Opcode.PULL_HISTORY, out.toByteArray())
    }

    /** Every fixture sensor decodes to this; the conversion divides by it. */
    const val FIXTURE_K_X100: Int = 125

    /** A record already carrying a current is left alone. */
    private fun withCurrentFor(rec: ByteArray, glucoseId: Int): ByteArray {
        if (rec[2].toInt() != 0 || rec[3].toInt() != 0) return rec
        val glucoseMgdl = ((rec[6].toInt() and 0x0F) shl 8) or (rec[7].toInt() and 0xFF)
        val tempCx100 = (rec[4].toInt() and 0xFF) - 40
        val iwX100 = inverseIwX100(glucoseMgdl, tempCx100 * 100 + (rec[5].toInt() and 0xFF), glucoseId)
        val out = rec.copyOf()
        out[2] = ((iwX100 shr 8) and 0xFF).toByte()
        out[3] = (iwX100 and 0xFF).toByte()
        return out
    }

    /** Inverts [glucoseMgdl] at [FIXTURE_K_X100]: a fixture asking for reading N gets one. */
    private fun inverseIwX100(glucoseMgdl: Int, tempCx100: Int, glucoseId: Int): Int {
        if (glucoseMgdl == 0) return 0
        val k0 = FIXTURE_K_X100 / 100f
        val tc = (tempCx100 / 100f).coerceIn(12f, 48f)
        val g = when {
            tc > 39.4f -> 0.55f
            tc > 36.4f -> 4.35f / (tc - 32f)
            tc > 32f -> 0.2f * (tc - 32f) / 4.4f + 0.8f
            else -> 1f
        }
        val factor = 1f + (tc - 32f) * -0.045929998159408569f * g
        var sensitivity = 1.2f * k0
        if (glucoseId <= 479) sensitivity *= 0.9f + glucoseId * 0.1f / 480f
        return Math.round(glucoseMgdl / 18f * sensitivity.coerceIn(0.5f * k0, 2.5f * k0) / factor * 100f)
    }

    /** Cut short at [size] with the checksum re-applied: truncated, not corrupt. */
    fun retruncate(frame: ByteArray, size: Int): ByteArray {
        val cut = frame.copyOfRange(0, size)
        cut[cut.size - 1] = checksum(cut, cut.size - 1).toByte()
        return cut
    }

    fun setDateReply(): ByteArray = frame(Ct5Constants.Opcode.SET_DATE_REPLY, byteArrayOf(0x55, 0xAA.toByte()))

    /** Reproduces the real transmitter's `V1130_20250618`. */
    fun versionReply(): ByteArray = byteArrayOf(
        0x01, 20, 25, 6, 18, 'C'.code.toByte(),
        '1'.code.toByte(), '1'.code.toByte(), '3'.code.toByte(), '0'.code.toByte(),
        '1'.code.toByte(), '1'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(),
    )

    /** Only length 20 is legal. */
    fun selfCheckReply(length: Int = 20): ByteArray =
        frame(Ct5Constants.Opcode.SELF_CHECK, ByteArray(length - 2) { (it * 7 + 3).toByte() })

    /** The reply an UNBOUND sensor gives: plaintext, final byte is data. */
    fun ssnReply(ssn: String): ByteArray =
        byteArrayOf(Ct5Constants.Opcode.QUERY_SSN.toByte()) + ssn.toByteArray(Charsets.US_ASCII)

    /** Only length 10 may yield a key. */
    fun setIdReply(b: ByteArray, tail: ByteArray, length: Int = 10): ByteArray {
        val payload = ByteArray(length - 2)
        b.copyInto(payload, 0, 0, minOf(b.size, payload.size))
        if (payload.size > 4) tail.copyInto(payload, 4, 0, minOf(tail.size, payload.size - 4))
        return frame(Ct5Constants.Opcode.SET_ID, payload)
    }

    fun checkIdReply(accepted: Boolean): ByteArray =
        frame(Ct5Constants.Opcode.CHECK_ID, byteArrayOf(0, 0, 0, 0, if (accepted) 1 else 0, 0))

    /** The activation success signal. */
    fun initReply(): ByteArray = filler(Ct5Constants.Opcode.INIT)
}
