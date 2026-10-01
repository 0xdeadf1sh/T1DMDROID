package com.t1dm.cgm

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Pure-JVM reference AidexSession for host tests: mints CGM.md §4-7 frames sans native .so. */
object AidexSessionCodec : AidexSession {

    override fun iv(serial: String): ByteArray =
        md5(ByteArray(serial.length) { i -> ((snval(serial[i]) * 17 + 0x13) and 0xFF).toByte() })

    override fun askKey(serial: String): ByteArray =
        md5(ByteArray(serial.length) { i -> ((snval(serial[i]) * 13 + 61) and 0xFF).toByte() })

    override fun deriveSession(serial: String, masterKey: ByteArray, blob: ByteArray): ByteArray? {
        if (masterKey.size != 16 || blob.size != 17) return null
        val sk = cfb(masterKey, iv(serial), blob, encrypt = false)
        val sess = sk.copyOfRange(0, 16)
        if (crc8Maxim(sess) != (sk[16].toInt() and 0xFF)) return null
        return sess
    }

    override fun encryptFrame(sess: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray {
        val crc = crc16CcittFalse(payload)
        val pt = payload + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
        return cfb(sess, iv, pt, encrypt = true)
    }

    override fun decryptFrame(sess: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray? {
        if (ct.size < 2) return null
        val pt = cfb(sess, iv, ct, encrypt = false)
        val payload = pt.copyOfRange(0, pt.size - 2)
        val got = (pt[pt.size - 2].toInt() and 0xFF) or ((pt[pt.size - 1].toInt() and 0xFF) shl 8)
        return if (crc16CcittFalse(payload) == got) payload else null
    }

    override fun parseRealtime(plaintext: ByteArray): AidexRealtimeSample? {
        if (plaintext.size < 15) return null
        val bitfield = u16(plaintext, 6)
        val glucose = bitfield and 0x3FF
        val valid = (bitfield ushr 15) and 1 == 1
        val warmup = (bitfield ushr 10) and 1 == 1
        return AidexRealtimeSample(
            readingType = plaintext[0].toInt() and 0xFF,
            trendTenthsPerMin = plaintext[3].toInt(), // signed i8
            minFromStart = u16(plaintext, 4),
            glucoseMgdl = glucose,
            warmup = warmup,
            valid = valid,
            isReal = valid && glucose in 18..800,
        )
    }

    override fun parseResponse(plaintext: ByteArray): AidexSessionResponse? {
        if (plaintext.size < 2) return null
        val tag = u16(plaintext, 0)
        val body = plaintext.copyOfRange(2, plaintext.size)
        return when (tag) {
            0x110 -> {
                if (plaintext.size < 10) return null
                val name = plaintext.copyOfRange(10, minOf(20, plaintext.size))
                    .takeWhile { it.toInt() != 0 }.toByteArray().toString(Charsets.UTF_8).trimEnd()
                AidexSessionResponse.DeviceInfo(
                    firmware = "${plaintext[4].toInt() and 0xFF}.${plaintext[5].toInt() and 0xFF}." +
                        "${plaintext[6].toInt() and 0xFF}.${plaintext[7].toInt() and 0xFF}",
                    name = name,
                    lifeDays = plaintext[8].toInt() and 0xFF,
                )
            }
            0x111 -> {
                if (body.size < 8) return null
                val bitfield = u16(body, 5)
                AidexSessionResponse.Current(
                    minFromStart = u16(body, 0),
                    trendTenthsPerMin = body[4].toInt(),
                    glucoseMgdl = bitfield and 0x3FF,
                    valid = (bitfield ushr 15) and 1 == 1,
                    quality = body[7].toInt() and 0xFF,
                )
            }
            0x121 -> {
                if (body.size < 9) return null
                val year = u16(body, 0)
                val tz = body[7].toInt() // signed i8 quarter-hours
                val dst = body[8].toInt() and 0xFF
                val epoch = timegm(
                    year, body[2].toInt() and 0xFF, body[3].toInt() and 0xFF,
                    body[4].toInt() and 0xFF, body[5].toInt() and 0xFF, body[6].toInt() and 0xFF,
                ) - (tz + dst) * 15L * 60L
                AidexSessionResponse.StartTime(year, epoch)
            }
            0x122 -> {
                if (plaintext.size < 4) return null
                AidexSessionResponse.LastId(u16(plaintext, plaintext.size - 2))
            }
            0x123 -> {
                if (body.size < 2) return null
                val startId = u16(body, 0)
                AidexSessionResponse.History(
                    startId = startId,
                    samples = (0 until (body.size - 2) / 2).map { i ->
                        val bf = u16(body, 2 + i * 2)
                        val glucose = bf and 0x3FF
                        val valid = (bf ushr 15) and 1 == 1
                        AidexHistorySample(
                            recordId = startId + i,
                            glucoseMgdl = glucose,
                            warmup = (bf ushr 10) and 1 == 1,
                            valid = valid,
                            isReal = valid && glucose in 18..800,
                        )
                    },
                )
            }
            0x120, 0x131, 0x134, 0x135 -> AidexSessionResponse.Ack(tag)
            0x1F2 -> AidexSessionResponse.Disconnect(true)
            0x0F2 -> AidexSessionResponse.Disconnect(false)
            else -> AidexSessionResponse.Unknown(tag)
        }
    }

    override fun cmdSetAutoUpdate(sess: ByteArray, iv: ByteArray): ByteArray =
        encryptFrame(sess, iv, byteArrayOf(0x34, 0x01))

    override fun cmdGetBroadcast(sess: ByteArray, iv: ByteArray): ByteArray =
        encryptFrame(sess, iv, byteArrayOf(0x11))

    override fun cmdGetStartTime(sess: ByteArray, iv: ByteArray): ByteArray =
        encryptFrame(sess, iv, byteArrayOf(0x21))

    override fun cmdDeviceInfo(sess: ByteArray, iv: ByteArray): ByteArray =
        encryptFrame(sess, iv, byteArrayOf(0x10))

    override fun cmdGetLastId(sess: ByteArray, iv: ByteArray): ByteArray =
        encryptFrame(sess, iv, byteArrayOf(0x22))

    override fun cmdGetHistory(sess: ByteArray, iv: ByteArray, relId: Int): ByteArray =
        encryptFrame(sess, iv, byteArrayOf(0x23, (relId and 0xFF).toByte(), ((relId ushr 8) and 0xFF).toByte()))

    override fun cmdSetDynamicAdvMode(sess: ByteArray, iv: ByteArray): ByteArray =
        encryptFrame(sess, iv, byteArrayOf(0x35, 0x01))

    override fun cmdSetNewSensor(sess: ByteArray, iv: ByteArray, localStart: ByteArray): ByteArray =
        encryptFrame(sess, iv, byteArrayOf(0x20) + localStart)

    override fun encodeLocalStartTime(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        tzQuarterHours: Int,
        dstQuarterHours: Int,
    ): ByteArray = byteArrayOf(
        (year and 0xFF).toByte(), ((year ushr 8) and 0xFF).toByte(),
        month.toByte(), day.toByte(), hour.toByte(), minute.toByte(), second.toByte(),
        tzQuarterHours.toByte(), dstQuarterHours.toByte(),
    )

    // Mirrors of the Rust reference, CGM.md §9.

    private fun snval(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c.code - 0x37
        in 'a'..'z' -> c.code - 0x57
        else -> throw IllegalArgumentException("invalid serial char: $c")
    }

    private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** AES-128-CFB128, IV reused per call; ENCRYPT mode both directions (CGM.md §4.5). */
    private fun cfb(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = ByteArray(data.size)
        var feedback = iv.copyOf()
        var off = 0
        while (off < data.size) {
            val ks = cipher.doFinal(feedback)
            val n = minOf(16, data.size - off)
            val next = ByteArray(16)
            for (i in 0 until n) {
                val inb = data[off + i]
                val o = (inb.toInt() xor ks[i].toInt()).toByte()
                out[off + i] = o
                next[i] = if (encrypt) o else inb
            }
            if (n == 16) feedback = next
            off += n
        }
        return out
    }

    private fun crc16CcittFalse(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    private fun crc8Maxim(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1 }
        }
        return crc and 0xFF
    }

    // Howard Hinnant days_from_civil + timegm (mirror of the Rust reference, CGM.md §7).
    private fun daysFromCivil(y0: Long, m: Long, d: Long): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun timegm(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        daysFromCivil(y.toLong(), mo.toLong(), d.toLong()) * 86400L + h * 3600L + mi * 60L + s
}
