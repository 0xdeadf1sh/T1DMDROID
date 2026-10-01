package com.t1dm.cgm

import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Ct5ConnectedSourceTest {

    @Test
    fun `the link is armed connect then MTU then discovery then notify`() = runTest {
        val rig = rig()
        rig.arm()
        assertTrue(rig.transport.connectCalled)
        assertEquals(
            "MTU must be negotiated before discovery — the identity reply does not fit the default",
            listOf(Ct5Constants.REQUESTED_MTU),
            rig.transport.mtuRequests,
        )
        assertTrue(rig.transport.discoverCalled)
        assertEquals(listOf(true), rig.transport.notifyEnables)
    }

    @Test
    fun `an unbound sensor holds the link and writes nothing at all`() = runTest {
        val rig = rig()
        rig.arm()
        assertEquals("AWAITING_BIND", rig.source.phaseName)
        assertEquals("nothing may be written to an unbound sensor without the user", emptyList<Int>(), rig.transport.opcodes)
        assertEquals(CgmSourceStatus.Scanning, rig.source.status.value)
        assertTrue("the secret store must be untouched", rig.repo.secretWrites.isEmpty())
    }

    @Test
    fun `bind is offered only while an unclaimed sensor is armed`() = runTest {
        val rig = rig()
        assertFalse("nothing is offered before the link is up", rig.source.bindable.value)
        rig.arm()
        assertTrue(rig.source.bindable.value)

        pressBind(rig)
        assertFalse("the offer is withdrawn the instant the bind starts", rig.source.bindable.value)
        rig.completeBind()
        assertFalse("a claimed sensor cannot be claimed again", rig.source.bindable.value)
    }

    @Test
    fun `a bind offer is withdrawn when the link drops`() = runTest {
        val rig = rig()
        rig.arm()
        assertTrue(rig.source.bindable.value)
        rig.source.close()
        assertFalse(rig.source.bindable.value)
    }

    @Test
    fun `an unbound sensor is never failed for being silent while its link answers`() = runTest {
        val rig = rig()
        rig.arm()
        // The link is watched here; an unbound sensor pushes nothing, RSSI poll answers for it.
        repeat(40) {
            advanceTimeBy(Ct5ConnectedSource.RSSI_POLL_MS)
            rig.feed(Ct5GattEvent.Rssi(dbm = -62, ok = true))
        }
        runCurrent()
        assertEquals("AWAITING_BIND", rig.source.phaseName)
        assertEquals(CgmSourceStatus.Scanning, rig.source.status.value)
    }

    @Test
    fun `an unbound sensor whose link stops answering reaches SignalLost`() = runTest {
        val rig = rig()
        rig.arm()
        assertTrue(rig.source.bindable.value)
        advanceTimeBy(Ct5ConnectedSource.LINK_STALE_MS - 1)
        runCurrent()
        assertEquals("AWAITING_BIND", rig.source.phaseName)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
        assertFalse("a dead link cannot be bound", rig.source.bindable.value)
    }

    // ── The bind ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the bind writes the seven frames in the order that keeps setID and setParameters adjacent`() =
        runTest {
            val rig = rig()
            rig.arm()
            pressBind(rig)
            rig.completeBind()

            assertEquals(
                listOf(
                    Ct5Constants.Opcode.SET_DATE,
                    Ct5Constants.Opcode.VERSION,
                    Ct5Constants.Opcode.SELF_CHECK,
                    // querySSN pulled ahead of setID so the two irreversible frames are adjacent.
                    Ct5Constants.Opcode.QUERY_SSN,
                    Ct5Constants.Opcode.SET_ID,
                    Ct5Constants.Opcode.SET_PARAMETERS,
                    Ct5Constants.Opcode.INIT,
                    // Last, and cosmetic: the RTC is rewritten only once the session is usable.
                    Ct5Constants.Opcode.SET_DATE,
                ),
                rig.transport.opcodes,
            )
            assertEquals("LIVE", rig.source.phaseName)
        }

    @Test
    fun `the self-check gets the vendor's slower ladder and everything else the default one`() = runTest {
        val rig = rig()
        rig.arm()
        pressBind(rig)
        rig.completeBind()
        val byOpcode = rig.transport.opcodes.zip(rig.transport.ladders).toMap()
        assertTrue(
            byOpcode.getValue(Ct5Constants.Opcode.SELF_CHECK).contentEquals(Ct5Constants.SELF_CHECK_LADDER_MS),
        )
        assertTrue(
            byOpcode.getValue(Ct5Constants.Opcode.SET_ID).contentEquals(Ct5Constants.DEFAULT_LADDER_MS),
        )
    }

    @Test
    fun `the setDate frame carries the local wall clock and no timezone byte`() = runTest {
        val rig = rig()
        rig.arm()
        pressBind(rig)
        val setDate = rig.transport.writes.single()
        assertEquals(8, setDate.size)
        assertTrue(Ct5SessionCodec.frameIsLegal(setDate))
        assertEquals(Ct5Constants.Opcode.SET_DATE, setDate[0].toInt() and 0xFF)
    }

    @Test
    fun `a self-check reply of any length but twenty aborts before anything irreversible`() = runTest {
        for (badLength in listOf(4, 14, 19, 21, 24)) {
            val rig = rig()
            rig.arm()
            pressBind(rig)
            rig.feed(Ct5SessionCodec.setDateReply())
            rig.feed(Ct5SessionCodec.versionReply())
            rig.feed(Ct5SessionCodec.selfCheckReply(badLength))

            assertEquals("length $badLength", CgmSourceStatus.SignalLost, rig.source.status.value)
            assertFalse("length $badLength", rig.transport.wrote(Ct5Constants.Opcode.QUERY_SSN))
            assertFalse("length $badLength", rig.transport.wrote(Ct5Constants.Opcode.SET_ID))
            assertTrue("length $badLength", rig.repo.secretWrites.isEmpty())
        }
    }

    @Test
    fun `an identity string that decodes to a zero K is refused rather than planted`() = runTest {
        val rig = rig()
        rig.arm()
        pressBind(rig)
        rig.feed(Ct5SessionCodec.setDateReply())
        rig.feed(Ct5SessionCodec.versionReply())
        rig.feed(Ct5SessionCodec.selfCheckReply())
        // A legal identity string whose K digits are zero: K = 0 decodes fine and is as unusable as a refusal.
        rig.feed(Ct5SessionCodec.ssnReply("005522280008000000AA5"))

        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
        assertFalse(rig.transport.wrote(Ct5Constants.Opcode.SET_ID))
        assertTrue(rig.repo.secretWrites.isEmpty())
    }

    @Test
    fun `a setID reply of any length but ten derives no key and persists nothing`() = runTest {
        for (badLength in listOf(6, 7, 8, 9, 11, 12)) {
            val rig = rig()
            rig.arm()
            pressBind(rig)
            rig.bindToSetId()
            rig.feed(Ct5SessionCodec.setIdReply(NONCE_B, SENSOR_TAIL, length = badLength))

            // The vendor validates opcode and checksum but NOT length, and keys off a tail the length
            // sizes — so each of these yields a key it accepts and no later frame can use.
            assertEquals("length $badLength", CgmSourceStatus.SignalLost, rig.source.status.value)
            assertTrue("length $badLength: a key was persisted", rig.repo.secretWrites.isEmpty())
            assertFalse("length $badLength", rig.transport.wrote(Ct5Constants.Opcode.SET_PARAMETERS))
        }
    }

    @Test
    fun `the secret is on disk before setParameters is written`() = runTest {
        val rig = rig()
        var framesWrittenWhenPersisted = -1
        rig.repo.onSecretWrite = { framesWrittenWhenPersisted = rig.transport.writes.size }
        rig.arm()
        pressBind(rig)
        rig.bindToSetId()
        rig.feed(Ct5SessionCodec.setIdReply(NONCE_B, SENSOR_TAIL))

        // setDate, version, selfCheck, querySSN, setID had gone out; setParameters had not.
        assertEquals(5, framesWrittenWhenPersisted)
        assertEquals(Ct5Constants.Opcode.SET_PARAMETERS, rig.transport.opcodes.last())
        assertEquals(1, rig.repo.secretWrites.size)
    }

    @Test
    fun `a store that refuses the secret stops the bind before setParameters`() = runTest {
        val rig = rig()
        rig.repo.failSecretWrite = IllegalStateException("the keystore refused")
        rig.arm()
        pressBind(rig)
        rig.bindToSetId()
        rig.feed(Ct5SessionCodec.setIdReply(NONCE_B, SENSOR_TAIL))

        assertFalse(
            "no password may be planted that could not be stored",
            rig.transport.wrote(Ct5Constants.Opcode.SET_PARAMETERS),
        )
        assertTrue(rig.repo.secretWrites.isEmpty())
        assertEquals(
            "the session must fail on its own terms, not by the collector dying",
            CgmSourceStatus.SignalLost,
            rig.source.status.value,
        )
    }

    @Test
    fun `a store that refuses after init still goes live`() = runTest {
        val rig = rig()
        rig.arm()
        pressBind(rig)
        rig.bindToSetId()
        rig.feed(Ct5SessionCodec.setIdReply(NONCE_B, SENSOR_TAIL))
        val sent = rig.transport.writes.last()
        rig.repo.failSecretWrite = IllegalStateException("the disk is full")
        rig.feed(sent.copyOf())
        rig.feed(Ct5SessionCodec.initReply())

        assertEquals("LIVE", rig.source.phaseName)
        // Unfinished on disk, so the next connect resumes at 0x38.
        assertFalse(Ct5SensorState.decode(rig.repo.secretWrites.single().second)!!.initialised)
    }

    @Test
    fun `the persisted secret carries everything the sensor cannot be asked for again`() = runTest {
        val rig = rig()
        rig.arm()
        pressBind(rig)
        rig.completeBind()

        val stored = Ct5SensorState.decode(rig.repo.secretWrites.last().second)
        assertNotNull(stored)
        stored!!
        assertEquals(EXPECTED_CIPHER_ID, stored.cipherId)
        assertTrue(stored.a.contentEquals(NONCE_A))
        assertTrue(stored.b.contentEquals(NONCE_B))
        assertEquals(RANDOM_ID, stored.randomId)
        assertEquals(125, stored.kX100)
        assertEquals(100, stored.rX100)
        assertEquals(ANCHOR_SSN, stored.ssn)
    }

    @Test
    fun `the setParameters frame plants the K the sensor's own identity string spells`() = runTest {
        val rig = rig()
        rig.arm()
        pressBind(rig)
        rig.completeBind()

        val sent = rig.transport.writes[rig.transport.opcodes.indexOf(Ct5Constants.Opcode.SET_PARAMETERS)]
        val plain = Ct5SessionCodec.deobfuscate(sent.copyOfRange(1, 13), EXPECTED_CIPHER_ID)
        assertEquals("int(K)", 1, plain[0].toInt())
        assertEquals("K hundredths", 25, plain[1].toInt())
        assertEquals("int(R)", 1, plain[2].toInt())
        assertEquals("R hundredths", 0, plain[3].toInt())
        assertEquals("3-minute interval", Ct5Constants.SAMPLE_INTERVAL_MIN, plain[4].toInt())
        assertEquals("16-day cycle", Ct5Constants.CYCLE_DAYS, plain[5].toInt())
        assertEquals(RANDOM_ID, String(plain, 8, 4, Charsets.US_ASCII))
    }

    @Test
    fun `a setParameters echo that does not match stops before init`() = runTest {
        val rig = rig()
        rig.arm()
        pressBind(rig)
        rig.bindToSetId()
        rig.feed(Ct5SessionCodec.setIdReply(NONCE_B, SENSOR_TAIL))
        // A wrong K errors nowhere else in this protocol: silently wrong glucose for the wear.
        val corrupt = rig.transport.writes.last().copyOf()
        corrupt[3] = (corrupt[3].toInt() xor 0x01).toByte()
        corrupt[corrupt.size - 1] = Ct5SessionCodec.checksum(corrupt, corrupt.size - 1).toByte()
        rig.feed(corrupt)

        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
        assertFalse("init must not follow a failed echo", rig.transport.wrote(Ct5Constants.Opcode.INIT))
    }

    @Test
    fun `a duplicate reply from the write ladder changes nothing`() = runTest {
        val rig = rig()
        rig.arm()
        pressBind(rig)
        rig.bindToSetId()
        val reply = Ct5SessionCodec.setIdReply(NONCE_B, SENSOR_TAIL)
        // The ladder writes each frame up to five times; only the FIRST reply may be acted on.
        repeat(4) { rig.feed(reply) }

        assertEquals("the key was derived and persisted more than once", 1, rig.repo.secretWrites.size)
        assertEquals(
            "a duplicate must not re-send setParameters",
            1,
            rig.transport.opcodes.count { it == Ct5Constants.Opcode.SET_PARAMETERS },
        )
    }

    @Test
    fun `the reply a step wanted acknowledges the ladder and an interleaved push does not`() = runTest {
        val rig = rig()
        rig.arm()
        pressBind(rig)
        rig.feed(Ct5SessionCodec.setDateReply())
        assertEquals(1, rig.transport.acknowledgements)

        // Sensor interleaves unsolicited pushes into a bind; one must not cancel a step's ladder.
        rig.feed(Ct5SessionCodec.push(glucoseId = 1, cipherId = EXPECTED_CIPHER_ID))
        assertEquals("a push must never acknowledge a step", 1, rig.transport.acknowledgements)

        rig.feed(Ct5SessionCodec.initReply())
        assertEquals(1, rig.transport.acknowledgements)
    }

    @Test
    fun `a step whose ladder runs out fails the session`() = runTest {
        val rig = rig()
        rig.arm()
        pressBind(rig)
        rig.feed(Ct5GattEvent.LadderExhausted(Ct5Constants.Opcode.SET_DATE))
        // Nothing acknowledges a write here; pacer running out is the only signal.
        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
    }

    @Test
    fun `a bind is refused unless the link is armed and the sensor unbound`() = runTest {
        val fresh = rig()
        pressBind(fresh)
        assertEquals("nothing may be written before the link is armed", emptyList<Int>(), fresh.transport.writes.map { it[0].toInt() })

        val bound = rig(state = STORED_STATE)
        bound.arm()
        pressBind(bound)
        assertFalse(
            "an already-bound sensor must never be re-bound — 0x30 cannot be replayed",
            bound.transport.wrote(Ct5Constants.Opcode.SET_ID),
        )
    }

    /** Unreadable here (CIPHER_ID from a 0x30 never sent); every bind frame is irreversible. */
    @Test
    fun `a sensor bound elsewhere is never offered a bind and holds no link`() = runTest {
        val rig = rig(advertisedBound = true)
        rig.arm()

        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
        assertFalse("a claimed sensor must never be offered a bind", rig.source.bindable.value)
        assertEquals("nothing whatsoever may be written to it", emptyList<Int>(), rig.transport.opcodes)
        assertTrue(rig.repo.secretWrites.isEmpty())
    }

    @Test
    fun `a bind is refused on a sensor bound elsewhere even when asked for directly`() = runTest {
        val rig = rig(advertisedBound = true)
        rig.arm()
        pressBind(rig)
        assertEquals(emptyList<Int>(), rig.transport.opcodes)
        assertTrue(rig.repo.secretWrites.isEmpty())
    }

    /** No manufacturer block means no flag; rides scan response, silence isn't permission. */
    @Test
    fun `a sensor whose bind flag was never advertised is treated as claimed`() = runTest {
        val rig = rig(advertisedBound = null)
        rig.arm()

        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
        assertFalse("silence is not permission", rig.source.bindable.value)
        pressBind(rig)
        assertEquals("nothing whatsoever may be written to it", emptyList<Int>(), rig.transport.opcodes)
        assertTrue(rig.repo.secretWrites.isEmpty())
    }

    /** A bound sensor answers nothing until checkID authenticates; nothing may precede it. */
    @Test
    fun `a sensor with a stored secret rejoins with checkID and nothing before it`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.arm()
        assertEquals(listOf(Ct5Constants.Opcode.CHECK_ID), rig.transport.opcodes)
        val checkId = rig.transport.writes.last()
        assertTrue(checkId.copyOfRange(1, 5).contentEquals(NONCE_B))

        rig.feed(Ct5SessionCodec.checkIdReply(accepted = true))
        assertEquals("LIVE", rig.source.phaseName)
        assertEquals(
            listOf(Ct5Constants.Opcode.CHECK_ID, Ct5Constants.Opcode.SET_DATE),
            rig.transport.opcodes,
        )
        assertFalse(rig.transport.wrote(Ct5Constants.Opcode.SET_ID))
        assertFalse(rig.transport.wrote(Ct5Constants.Opcode.SET_PARAMETERS))
        assertFalse(rig.transport.wrote(Ct5Constants.Opcode.INIT))
        assertTrue(rig.repo.secretWrites.isEmpty())
    }

    @Test
    fun `a positively refused checkID fails the session rather than looping`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.arm()
        rig.feed(Ct5SessionCodec.checkIdReply(accepted = false))
        // The sensor does not know this B; reconnecting cannot change that.
        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
    }

    /** Secret on disk before 0x38; interrupted bind resumes same params, no 2nd 0x30. */
    @Test
    fun `an unfinished bind is resumed from setParameters on the next connect`() = runTest {
        val rig = rig(state = STORED_STATE.copy(initialised = false))
        rig.arm()
        rig.feed(Ct5SessionCodec.checkIdReply(accepted = true))

        assertEquals(
            listOf(
                Ct5Constants.Opcode.CHECK_ID,
                Ct5Constants.Opcode.SET_PARAMETERS,
            ),
            rig.transport.opcodes,
        )
        assertFalse("a resume never re-derives a key", rig.transport.wrote(Ct5Constants.Opcode.SET_ID))
        val sent = rig.transport.writes.last()
        val plain = Ct5SessionCodec.deobfuscate(sent.copyOfRange(1, 13), EXPECTED_CIPHER_ID)
        assertEquals(RANDOM_ID, String(plain, 8, 4, Charsets.US_ASCII))

        rig.feed(sent.copyOf())
        rig.feed(Ct5SessionCodec.initReply())
        assertEquals("LIVE", rig.source.phaseName)
        val stored = Ct5SensorState.decode(rig.repo.secretWrites.single().second)!!
        assertTrue(stored.initialised)
        assertEquals(RANDOM_ID, stored.randomId)
    }

    /** 0x31 refused means 0x30 never landed; blob unlocks nothing, would block requestBind. */
    @Test
    fun `a refused unfinished bind releases the key and offers a bind again`() = runTest {
        val rig = rig(state = STORED_STATE.copy(initialised = false))
        rig.arm()
        rig.feed(Ct5SessionCodec.checkIdReply(accepted = false))

        assertEquals(listOf(Ct5ConnectedSource.descriptorFor(BSN).id), rig.repo.secretsCleared)
        assertEquals("AWAITING_BIND", rig.source.phaseName)
        assertTrue("the sensor is claimable again", rig.source.bindable.value)
    }

    /** A finished bind's key is the only unbind password; unreachable is no reason to destroy. */
    @Test
    fun `a refused finished bind keeps the key`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.arm()
        rig.feed(Ct5SessionCodec.checkIdReply(accepted = false))

        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
        assertTrue("the only unbind password must survive", rig.repo.secretsCleared.isEmpty())
    }

    @Test
    fun `an unreadable checkID verdict listens anyway`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.arm()
        // At six bytes the verdict byte is the checksum byte; an ambiguous answer isn't a refusal.
        rig.feed(Ct5SessionCodec.buildCheckId(NONCE_B)!!)
        assertEquals("LIVE", rig.source.phaseName)
    }

    @Test
    fun `the two records the real sensor sent become warm-up readings with no value`() = runTest {
        val rig = rig(state = STORED_STATE.copy(cipherId = VECTOR_CIPHER_ID))
        rig.goLive()
        rig.feed(CAPTURED_PUSH_A)
        rig.feed(CAPTURED_PUSH_B)

        assertEquals(2, rig.repo.upsertedReadings.size)
        for (r in rig.repo.upsertedReadings) {
            assertEquals(ReadingFlag.WARMUP, r.flag)
            assertNull("a zero wire field is an ABSENT value, never 0 mg/dL", r.bgMgdl)
        }
        // Sample 5 of a 3-minute sensor is 15 minutes in, inside the 45-minute warm-up.
        assertEquals(15, rig.repo.upsertedReadings.first().minFromStart)
        assertEquals(18, rig.repo.upsertedReadings.last().minFromStart)
        assertEquals(CgmSourceStatus.Warmup, rig.source.status.value)
    }

    @Test
    fun `a zero glucose past the warm-up window is dropped rather than stored`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        // 30 samples = 90min, past the 45min window; dropping keeps the staleness clock running.
        rig.feed(Ct5SessionCodec.push(glucoseId = 30, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 0))
        assertTrue(rig.repo.upsertedReadings.isEmpty())
        assertTrue(rig.repo.rawAdverts.isEmpty())
    }

    @Test
    fun `an error code outside the accepted set drops the reading whatever the value beside it`() =
        runTest {
            val rig = rig(state = STORED_STATE)
            rig.goLive()
            for (code in listOf(1, 3, 6, 11, 104, 106, 255)) {
                rig.feed(
                    Ct5SessionCodec.push(
                        glucoseId = 40 + code,
                        cipherId = EXPECTED_CIPHER_ID,
                        glucoseMgdl = 120,
                        errorCode = code,
                    ),
                )
            }
            assertTrue("error code is a fault, not a footnote", rig.repo.upsertedReadings.isEmpty())

            for (code in Ct5Constants.ACCEPTED_ERROR_CODES) {
                rig.feed(
                    Ct5SessionCodec.push(
                        glucoseId = 200 + code,
                        cipherId = EXPECTED_CIPHER_ID,
                        glucoseMgdl = 120,
                        errorCode = code,
                    ),
                )
            }
            assertEquals(
                Ct5Constants.ACCEPTED_ERROR_CODES.size,
                rig.repo.upsertedReadings.size,
            )
        }

    @Test
    fun `the physiological rail judges the number stored, and the sensor's own field decides nothing`() =
        runTest {
            val rig = rig(state = STORED_STATE)
            rig.goLive()
            val any = requireNotNull(
                Ct5SessionCodec.parsePush(
                    Ct5SessionCodec.push(glucoseId = 120, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 91),
                    EXPECTED_CIPHER_ID,
                ),
            )

            // 18..800 mg/dL, on the value this reader will store.
            for (bg in listOf(null, 0, 17, 801, 4000)) {
                assertEquals("bg $bg", ReadingFlag.INVALID, rig.source.flagFor(any, 400, bg))
            }
            assertEquals(ReadingFlag.NORMAL, rig.source.flagFor(any, 400, 18))
            assertEquals(ReadingFlag.NORMAL, rig.source.flagFor(any, 400, 800))

            // A record whose OWN glucose field is zero still reads: the current is what carries it.
            rig.feed(Ct5SessionCodec.push(glucoseId = 121, cipherId = EXPECTED_CIPHER_ID, iwX100 = 686))
            assertEquals(1, rig.repo.upsertedReadings.size)
            assertNotNull(rig.repo.upsertedReadings.single().bgMgdl)

            // And a current below the conversion's own floor is no reading at all.
            rig.feed(Ct5SessionCodec.push(glucoseId = 122, cipherId = EXPECTED_CIPHER_ID, iwX100 = 50))
            assertEquals(1, rig.repo.upsertedReadings.size)
        }

    @Test
    fun `a re-delivered sample index is committed once`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        val frame = Ct5SessionCodec.push(glucoseId = 20, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 88)
        repeat(5) { rig.feed(frame) }
        assertEquals("the write ladder can deliver five copies", 1, rig.repo.upsertedReadings.size)
    }

    @Test
    fun `a push that does not decode is dropped and never becomes a reading`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        val good = Ct5SessionCodec.push(glucoseId = 21, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 90)
        val corrupt = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        rig.feed(corrupt)
        assertTrue(rig.repo.upsertedReadings.isEmpty())
        // A short frame, and one with another family's opcode.
        rig.feed(good.copyOfRange(0, 16))
        rig.feed(byteArrayOf(0x37, 0x00, 0x00, 0x37))
        assertTrue(rig.repo.upsertedReadings.isEmpty())
    }

    // Wrong key yields a well-formed record of invented numbers; temperature is the discriminator.

    @Test
    fun `a record that decodes to an impossible temperature is neither stored nor shown`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        rig.feed(
            Ct5SessionCodec.push(
                glucoseId = 50,
                cipherId = EXPECTED_CIPHER_ID,
                glucoseMgdl = 120,
                tempIntC = -30,
            ),
        )
        assertTrue(rig.repo.upsertedReadings.isEmpty())
        assertTrue("nor kept as wire evidence of a measurement", rig.repo.rawAdverts.isEmpty())
        // A garbage temperature reads exactly like a real one, so telemetry is withheld too.
        assertNull(rig.source.telemetry.value)
    }

    @Test
    fun `a run of records that decode to nothing physical fails the session`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        for (i in 0 until Ct5Constants.MAX_IMPLAUSIBLE_RECORDS - 1) {
            rig.feed(Ct5SessionCodec.push(glucoseId = 50 + i, cipherId = EXPECTED_CIPHER_ID, tempIntC = 120))
            assertEquals("record $i", CgmSourceStatus.Scanning, rig.source.status.value)
        }
        rig.feed(Ct5SessionCodec.push(glucoseId = 60, cipherId = EXPECTED_CIPHER_ID, tempIntC = 120))
        // Failing hands the link back; dropping alone would feed the watchdog and store nothing.
        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
    }

    @Test
    fun `one bad record among good ones does not end the session`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        for (i in 0 until 6) {
            val bad = i % 2 == 0
            rig.feed(
                Ct5SessionCodec.push(
                    glucoseId = 70 + i,
                    cipherId = EXPECTED_CIPHER_ID,
                    glucoseMgdl = 100,
                    tempIntC = if (bad) 120 else 31,
                ),
            )
        }
        assertEquals("the run must reset on a good record", 3, rig.repo.upsertedReadings.size)
        assertEquals(CgmSourceStatus.Live, rig.source.status.value)
    }

    @Test
    fun `the plausible band is the vendor's own and includes its edges`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        // The band says whether a record DECODED, not whether the wearer is warm; 12 C is the vendor's floor.
        rig.feed(
            Ct5SessionCodec.push(
                glucoseId = 90,
                cipherId = EXPECTED_CIPHER_ID,
                glucoseMgdl = 100,
                tempIntC = 12,
                tempHundredths = 0,
            ),
        )
        assertEquals(1, rig.repo.upsertedReadings.size)
        assertEquals(1200, rig.source.telemetry.value?.tempCx100)
    }

    @Test
    fun `the raw frame is kept for forensics beside the reading`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        val frame = Ct5SessionCodec.push(glucoseId = 22, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 95)
        rig.feed(frame)
        val raw = rig.repo.rawAdverts.single()
        assertTrue("the frame is stored as received", raw.payload.contentEquals(frame))
        assertEquals(66, raw.minFromStart)
    }

    @Test
    fun `a push before any key is held is ignored`() = runTest {
        val rig = rig()
        rig.arm()
        rig.feed(Ct5SessionCodec.push(glucoseId = 1, cipherId = EXPECTED_CIPHER_ID))
        assertTrue(rig.repo.upsertedReadings.isEmpty())
        assertEquals("AWAITING_BIND", rig.source.phaseName)
    }

    @Test
    fun `the bind instant is learned from a sample taken in the opening minutes`() = runTest {
        val rig = rig(state = STORED_STATE.copy(bindTimeMs = 0))
        rig.goLive()
        advanceTimeBy(60 * 60_000L)
        val now = currentTime
        // Sample index 1 means the sensor started 3 minutes ago, whatever the stored anchor claims.
        rig.feed(Ct5SessionCodec.push(glucoseId = 1, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 0))

        val corrected = Ct5SensorState.decode(rig.repo.secretWrites.single().second)!!
        assertEquals(now - Ct5Constants.SAMPLE_INTERVAL_MS, corrected.bindTimeMs)
    }

    @Test
    fun `a running sensor's anchor is re-anchored once it has drifted out of the band`() = runTest {
        // Anchor 6 minutes early: accumulated drift past the opening index ceiling.
        advanceTimeBy(WEAR_CLOCK_START)
        val drift = 2 * Ct5Constants.SAMPLE_INTERVAL_MS
        val rig = rig(
            state = STORED_STATE.copy(
                bindTimeMs = currentTime - 1468 * Ct5Constants.SAMPLE_INTERVAL_MS - drift,
            ),
        )
        rig.goLive()
        rig.feed(Ct5SessionCodec.push(glucoseId = 1468, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))

        val corrected = Ct5SensorState.decode(rig.repo.secretWrites.single().second)!!
        assertEquals(
            "the delivery pins the origin: a sample cannot be taken after it is delivered",
            currentTime - 1468 * Ct5Constants.SAMPLE_INTERVAL_MS,
            corrected.bindTimeMs,
        )
        assertEquals("the published start follows the anchor", corrected.bindTimeMs, rig.source.sensorStartMs.value)
        assertEquals(
            "and the reading goes to the slot the repaired clock names, not to its delivery",
            currentTime,
            rig.repo.upsertedReadings.first().rxWallMs,
        )
        assertEquals("nothing was filed under receive time", 0, rig.source.skewFallbackCount)
    }

    @Test
    fun `an anchor inside the band is left alone under a running sensor`() = runTest {
        val rig = rigMidWear(samplesTaken = 900)
        rig.goLive()
        // This runs on the push path, so agreement must cost no Keystore write.
        for (id in listOf(900, 901, 902)) {
            advanceTimeBy(Ct5Constants.SAMPLE_INTERVAL_MS)
            rig.feed(Ct5SessionCodec.push(glucoseId = id, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        }
        assertTrue("the anchor must not move under a sensor that agrees", rig.repo.secretWrites.isEmpty())
    }

    // Transmitter force-closes two sample intervals after an unanswered push (firmware timer).

    @Test
    fun `a live push is acknowledged, on its own ladder and expecting no reply`() = runTest {
        val rig = rigMidWear(samplesTaken = 900)
        rig.goLive()
        val before = rig.transport.writes.size
        rig.feed(Ct5SessionCodec.push(glucoseId = 900, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))

        val acks = rig.transport.writes.drop(before)
        assertEquals(1, acks.size)
        assertEquals(Ct5Constants.Opcode.PUSH, acks.single()[0].toInt() and 0xFF)
        // Two rungs not the default five; nothing answers this frame, so every rung gets written.
        assertArrayEquals(Ct5Constants.PUSH_ACK_LADDER_MS, rig.transport.ladders.last())
        assertFalse("nothing answers it, so nothing may wait for an answer", rig.transport.expectsReply.last())
    }

    @Test
    fun `a push the dedup ring swallows is acknowledged all the same`() = runTest {
        // What is acknowledged is the FRAME, not the reading.
        val rig = rigMidWear(samplesTaken = 900)
        rig.goLive()
        val before = rig.transport.writes.size
        repeat(2) {
            rig.feed(Ct5SessionCodec.push(glucoseId = 900, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        }

        assertEquals(2, rig.transport.writes.drop(before).size)
        assertEquals("and the duplicate is still not stored twice", 1, rig.repo.upsertedReadings.size)
    }

    @Test
    fun `acknowledging a push does not make a history reply unmatchable`() = runTest {
        // Ack goes straight at the pacer, not through send, which would record it in outstanding.
        val rig = rigMidWear(samplesTaken = 900, cursor = FIRST_CONVERTIBLE_ID - 1)
        goLiveAndBackfillOn(rig)
        rig.feed(Ct5SessionCodec.push(glucoseId = 900, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        rig.answerProbe(glucoseMgdl = 111, startId = FIRST_CONVERTIBLE_ID)

        assertTrue(
            "the probe reply must still be the frame the pull is waiting for",
            rig.repo.upsertedReadings.any { it.bgMgdl == 111 },
        )
    }

    @Test
    fun `an anchor already right is not rewritten`() = runTest {
        // Virtual clock starts at 0; an anchor one interval back is what sample index 1 implies.
        val rig = rig(state = STORED_STATE.copy(bindTimeMs = -Ct5Constants.SAMPLE_INTERVAL_MS))
        rig.goLive()
        rig.feed(Ct5SessionCodec.push(glucoseId = 1, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 0))
        assertTrue(rig.repo.secretWrites.isEmpty())
    }

    // Filed under bindTimeMs + glucoseId*180s, not delivery; 5 samples fall into 3 grid slots.

    @Test
    fun `a reading is filed under the instant it was sampled, not the one it was delivered`() = runTest {
        val anchor = currentTime
        val rig = rig(state = stateAnchoredFor(glucoseId = 100, atMs = anchor))
        rig.goLive()
        rig.feed(Ct5SessionCodec.push(glucoseId = 100, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        // Delivered two minutes late; its instant does not move for that.
        advanceTimeBy(2 * Ct5Constants.SAMPLE_INTERVAL_MS)
        rig.feed(Ct5SessionCodec.push(glucoseId = 101, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 104))

        assertEquals(
            listOf(anchor, anchor + Ct5Constants.SAMPLE_INTERVAL_MS),
            rig.repo.upsertedReadings.map { it.rxWallMs },
        )
        assertEquals(0, rig.source.skewFallbackCount)
    }

    @Test
    fun `five three-minute samples land in the three slots they were sampled in`() = runTest {
        val anchor = currentTime
        val rig = rig(state = stateAnchoredFor(glucoseId = 200, atMs = anchor))
        rig.goLive()
        for (i in 0 until 5) {
            if (i > 0) advanceTimeBy(Ct5Constants.SAMPLE_INTERVAL_MS)
            rig.feed(
                Ct5SessionCodec.push(glucoseId = 200 + i, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100 + i),
            )
        }
        // 5 samples, 3 slots; which of a contending pair the grid series keeps is the store's call.
        assertEquals(
            listOf(0L, 300_000L, 300_000L, 600_000L, 600_000L).map { anchor + it },
            rig.repo.upsertedReadings.map { it.tsMs },
        )
        // Nothing fabricated between them; consecutive 3-minute samples never leave an empty slot.
        assertEquals(5, rig.repo.upsertedReadings.count { it.provenance == ReadingProvenance.MEASURED })
    }

    @Test
    fun `a sample instant too far from its delivery re-anchors the clock in either direction`() = runTest {
        // Both bounds, both directions; generous behind, tight ahead (forward relaxes windows).
        for (skewMs in listOf(Ct5Constants.MAX_CLOCK_SKEW_MS + 1, -Ct5Constants.MAX_CLOCK_SKEW_MS - 1)) {
            val rig = rig(state = stateAnchoredFor(glucoseId = 300, atMs = currentTime + skewMs))
            rig.goLive()
            rig.feed(Ct5SessionCodec.push(glucoseId = 300, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 110))
            val reading = rig.repo.upsertedReadings.single()
            assertEquals("skew $skewMs", currentTime, reading.rxWallMs)
            assertEquals("skew $skewMs — the repair means nothing had to fall back", 0, rig.source.skewFallbackCount)
            val corrected = Ct5SensorState.decode(rig.repo.secretWrites.single().second)!!
            assertEquals(
                "skew $skewMs — and the anchor now derives this sample at its own delivery",
                currentTime,
                corrected.bindTimeMs + 300 * Ct5Constants.SAMPLE_INTERVAL_MS,
            )
            // Still a reading either way; the guard moves the clock, it doesn't withhold a value.
            assertEquals("skew $skewMs", 110, reading.bgMgdl)
        }
    }

    @Test
    fun `a sample exactly at the backward bound is still filed on the sensor clock`() = runTest {
        val derived = currentTime - Ct5Constants.MAX_CLOCK_SKEW_MS
        val rig = rig(state = stateAnchoredFor(glucoseId = 300, atMs = derived))
        rig.goLive()
        rig.feed(Ct5SessionCodec.push(glucoseId = 300, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 110))
        assertEquals(derived, rig.repo.upsertedReadings.single().rxWallMs)
        assertEquals(0, rig.source.skewFallbackCount)
    }

    /** A future stamp shortens apparent age (§3.6-A/§3.6-D), so the forward bound is tighter. */
    @Test
    fun `a derived instant ahead of the delivery is never used`() = runTest {
        for (aheadMs in listOf(1_000L, Ct5Constants.MAX_FORWARD_SKEW_MS, 10 * 60_000L)) {
            val rig = rig(state = stateAnchoredFor(glucoseId = 300, atMs = currentTime + aheadMs))
            rig.goLive()
            rig.feed(Ct5SessionCodec.push(glucoseId = 300, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 110))
            val reading = rig.repo.upsertedReadings.single()
            assertEquals("ahead $aheadMs", currentTime, reading.rxWallMs)
            assertEquals("a reading is still stored; only its instant is corrected", 110, reading.bgMgdl)
        }
    }

    @Test
    fun `the same sample index is filed under the same instant by a later session`() = runTest {
        // Sub-grid store keys on (source, filed instant), insert IGNORE; instant keys on sample.
        val state = stateAnchoredFor(glucoseId = 400, atMs = currentTime)
        val first = rig(state = state)
        first.goLive()
        first.feed(Ct5SessionCodec.push(glucoseId = 400, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 99))

        advanceTimeBy(Ct5Constants.MAX_CLOCK_SKEW_MS)
        val second = rig(state = state)
        second.goLive()
        second.feed(Ct5SessionCodec.push(glucoseId = 400, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 99))

        assertEquals(
            first.repo.upsertedReadings.single().rxWallMs,
            second.repo.upsertedReadings.single().rxWallMs,
        )
    }

    /** Unrepairable anchor falls back to delivery; the derivation's error would else reach §3.6. */
    @Test
    fun `an unrepairable clock files the sample under its delivery instead`() = runTest {
        val state = stateAnchoredFor(glucoseId = 400, atMs = currentTime)
        val rig = rig(state = state)
        rig.repo.failSecretWrite = IllegalStateException("keystore unavailable")
        rig.goLive()
        advanceTimeBy(Ct5Constants.MAX_CLOCK_SKEW_MS + 1)
        rig.feed(Ct5SessionCodec.push(glucoseId = 400, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 99))

        assertEquals(currentTime, rig.repo.upsertedReadings.single().rxWallMs)
        assertEquals(1, rig.source.skewFallbackCount)
    }

    @Test
    fun `the timezone offset is resolved at the sample instant`() = runTest {
        // §2: offset is the client's at the time of the event, and the event is the sample.
        val anchor = currentTime
        val rig = rig(state = stateAnchoredFor(600, anchor), tz = { ms -> (ms / 60_000).toInt() })
        rig.goLive()
        advanceTimeBy(4 * 60_000)
        rig.feed(Ct5SessionCodec.push(glucoseId = 601, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 105))

        val reading = rig.repo.upsertedReadings.single()
        val sampledAt = anchor + Ct5Constants.SAMPLE_INTERVAL_MS
        assertEquals(sampledAt, reading.rxWallMs)
        assertEquals((sampledAt / 60_000).toInt(), reading.tzOffsetMin)
    }

    @Test
    fun `the frame is dated when it arrived and the reading when it was sampled`() = runTest {
        val anchor = currentTime
        val rig = rig(state = stateAnchoredFor(700, anchor))
        rig.goLive()
        advanceTimeBy(90_000)
        rig.feed(Ct5SessionCodec.push(glucoseId = 700, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 105))
        // Raw store is wire evidence; when these bytes reached the phone is all it can say.
        assertEquals(anchor + 90_000, rig.repo.rawAdverts.single().rxWallMs)
        assertEquals(anchor, rig.repo.upsertedReadings.single().rxWallMs)
    }

    @Test
    fun `nothing is claimed before the first record`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        assertNull(rig.source.telemetry.value)
    }

    @Test
    fun `a record the value gate drops still publishes its telemetry`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        // 9 is outside the accepted set; reading withheld, but its diagnostic is worth seeing.
        rig.feed(
            Ct5SessionCodec.push(glucoseId = 70, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 120, errorCode = 9),
        )
        assertTrue(rig.repo.upsertedReadings.isEmpty())
        assertEquals(9, rig.source.telemetry.value?.errorCode)
    }

    @Test
    fun `telemetry carries the channels a real record held`() = runTest {
        val rig = rig(
            state = STORED_STATE.copy(
                cipherId = VECTOR_CIPHER_ID,
                bindTimeMs = currentTime - 5 * Ct5Constants.SAMPLE_INTERVAL_MS,
            ),
        )
        rig.goLive()
        rig.feed(CAPTURED_PUSH_A)

        val t = rig.source.telemetry.value!!
        assertEquals("31.04 C, integer-first", 3104, t.tempCx100)
        assertEquals(893, t.iwX100)
        assertEquals(0, t.ibX100)
        assertEquals(1603, t.batteryRaw)
        assertEquals(listOf(1038, 1038, 996, 648), t.electrodesMv)
        assertEquals("zero is a reported no-error, not an absence", 0, t.errorCode)
        assertEquals(0, t.trendCode)
        val reading = rig.repo.upsertedReadings.single()
        assertEquals(reading.rxWallMs, t.sampledAtMs)
        assertNull(reading.bgMgdl)
    }

    @Test
    fun `telemetry follows the newest record`() = runTest {
        val rig = rig(
            state = STORED_STATE.copy(
                cipherId = VECTOR_CIPHER_ID,
                bindTimeMs = currentTime - 5 * Ct5Constants.SAMPLE_INTERVAL_MS,
            ),
        )
        rig.goLive()
        rig.feed(CAPTURED_PUSH_A)
        rig.feed(CAPTURED_PUSH_B)
        assertEquals(3075, rig.source.telemetry.value?.tempCx100)
        assertEquals(1604, rig.source.telemetry.value?.batteryRaw)
    }

    @Test
    fun `a push that does not decode publishes no telemetry`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        val good = Ct5SessionCodec.push(glucoseId = 80, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 90)
        rig.feed(good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        // A corrupted battery/temperature reads exactly like a real one.
        assertNull(rig.source.telemetry.value)
    }

    @Test
    fun `a live session with no pushes reaches SignalLost`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        rig.source.armWatchdogForTest()

        advanceTimeBy(Ct5Constants.PUSH_STALE_MS - 1)
        runCurrent()
        assertEquals("not yet", CgmSourceStatus.Scanning, rig.source.status.value)

        advanceTimeBy(2)
        runCurrent()
        // Coordinator blocks on this transition; without it the session hangs for good.
        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
    }

    @Test
    fun `each push postpones the watchdog`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        rig.source.armWatchdogForTest()

        // Past the 45min warm-up, so these are NORMAL readings and status is Live.
        for (id in 20..25) {
            advanceTimeBy(Ct5Constants.SAMPLE_INTERVAL_MS)
            runCurrent()
            rig.feed(Ct5SessionCodec.push(glucoseId = id, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
            assertEquals("push $id", CgmSourceStatus.Live, rig.source.status.value)
        }
        advanceTimeBy(Ct5Constants.PUSH_STALE_MS + 1)
        runCurrent()
        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
    }

    @Test
    fun `a push that fails the value gate still postpones the watchdog`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        rig.source.armWatchdogForTest()
        advanceTimeBy(Ct5Constants.PUSH_STALE_MS - 1_000)
        runCurrent()
        // A checksum-valid frame proves the link is alive whatever the gate does with it.
        rig.feed(
            Ct5SessionCodec.push(glucoseId = 60, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 120, errorCode = 9),
        )
        assertTrue(rig.repo.upsertedReadings.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("the watchdog was postponed", CgmSourceStatus.Faulted, rig.source.status.value)
    }

    /** Link up, sensor answering, only records refused; connecting would contradict the error. */
    @Test
    fun `a sensor whose every record is refused reads as a fault, not as connecting`() = runTest {
        val rig = rig(state = STORED_STATE)
        rig.goLive()
        assertEquals(CgmSourceStatus.Scanning, rig.source.status.value)

        rig.feed(
            Ct5SessionCodec.push(glucoseId = 60, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 120, errorCode = 11),
        )

        assertTrue("nothing was stored", rig.repo.upsertedReadings.isEmpty())
        assertEquals(CgmSourceStatus.Faulted, rig.source.status.value)

        // And it goes back on a record the app can use.
        rig.feed(Ct5SessionCodec.push(glucoseId = 61, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 120))
        assertEquals(CgmSourceStatus.Live, rig.source.status.value)
    }

    /** A refused record still settles its slot; all-refused would never reconcile the cursor. */
    @Test
    fun `a refused record still reconciles a cursor left ahead of the sensor`() = runTest {
        val rig = rigMidWear(samplesTaken = 8175, cursor = 8184)
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()

        rig.feed(
            Ct5SessionCodec.push(glucoseId = 8175, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100, errorCode = 11),
        )
        runCurrent()

        assertEquals(8174, rig.repo.loadSourceCursor(rig.source.descriptor.id))
    }

    /** §3.6-A: a replay isn't a sensor still reading; repeats as fresh held the clock open. */
    @Test
    fun `a byte-identical repeat from a stopped counter is not a new measurement`() = runTest {
        val stuck = 8175
        val rig = rigMidWear(samplesTaken = stuck, cursor = stuck)
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()
        val replay = Ct5SessionCodec.push(
            glucoseId = stuck,
            cipherId = EXPECTED_CIPHER_ID,
            errorCode = 2,
            iwX100 = 686,
        )

        repeat(4) {
            advanceTimeBy(Ct5Constants.SAMPLE_INTERVAL_MS)
            rig.feed(replay)
            runCurrent()
        }

        val measured = rig.repo.upsertedReadings.filter { it.provenance == ReadingProvenance.MEASURED }
        assertEquals("the same bytes are one measurement, not four", 1, measured.size)
    }

    /** Point of ERROR_ALGORITHM_DATA: an ended session repeats one id, current/temp live on. */
    @Test
    fun `a stopped counter still yields readings, one per delivery`() = runTest {
        val stuck = 8175
        val rig = rigMidWear(samplesTaken = stuck, cursor = stuck)
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()

        val currents = listOf(686, 640, 705)
        for (iw in currents) {
            advanceTimeBy(Ct5Constants.SAMPLE_INTERVAL_MS)
            rig.feed(
                Ct5SessionCodec.push(
                    glucoseId = stuck,
                    cipherId = EXPECTED_CIPHER_ID,
                    errorCode = 2,
                    iwX100 = iw,
                ),
            )
            runCurrent()
        }

        val measured = rig.repo.upsertedReadings.filter { it.provenance == ReadingProvenance.MEASURED }
        assertEquals("one reading per delivery, not one per sample id", currents.size, measured.size)
        assertTrue("every one carries a value", measured.all { it.bgMgdl != null })
        assertEquals(
            "each filed under its own delivery instant",
            measured.size,
            measured.map { it.rxWallMs }.distinct().size,
        )
        assertTrue("a bigger current reads higher", measured[1].bgMgdl!! < measured[2].bgMgdl!!)
    }

    /** minFromStart freezes with the id; the start a sensor's age is read from must not. */
    @Test
    fun `a stopped counter leaves the sensor's start at the bind anchor`() = runTest {
        val stuck = 8175
        val rig = rigMidWear(samplesTaken = stuck, cursor = stuck)
        val bind = requireNotNull(rig.state).bindTimeMs
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()

        val day = 24 * 60 * 60_000L
        advanceTimeBy(day)
        rig.feed(Ct5SessionCodec.push(glucoseId = stuck, cipherId = EXPECTED_CIPHER_ID, iwX100 = 686))
        runCurrent()

        val reading = rig.repo.upsertedReadings.last { it.provenance == ReadingProvenance.MEASURED }
        assertEquals(stuck * Ct5Constants.SAMPLE_INTERVAL_MIN, reading.minFromStart)
        assertEquals(bind, rig.source.sensorStartMs.value)
        assertTrue(
            "a day on, the reading is a day past what its minFromStart says",
            reading.tsMs - bind >= stuck * Ct5Constants.SAMPLE_INTERVAL_MS + day - CgmConstants.GRID_MS / 2,
        )
    }

    /** Clock runs on while a stopped transmitter repeats; never-existing slots aren't settled. */
    @Test
    fun `the stored cursor never passes the highest id the sensor has delivered`() = runTest {
        val rig = rigMidWear(samplesTaken = 8175, cursor = 8174)
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()
        rig.feed(Ct5SessionCodec.push(glucoseId = 8175, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        runCurrent()

        // A day of wall clock, no new id: pull may look, but nothing above 8175 is settled.
        advanceTimeBy(24 * 60 * 60_000L)
        rig.source.requestBackfill()
        runCurrent()

        assertTrue(
            "cursor ran past the sensor",
            rig.repo.loadSourceCursor(rig.source.descriptor.id) <= 8175,
        )
    }

    /** Rewinding on a stopped counter's repeat re-walked thousands of empty slots every session. */
    @Test
    fun `a stopped counter's repeat does not pull the cursor back`() = runTest {
        val stuck = 8175
        val walked = 13_023
        val rig = rigMidWear(samplesTaken = walked, cursor = walked)
        rig.repo.saveHighestDeliveredId(rig.source.descriptor.id, stuck)
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()

        for (iw in listOf(686, 640)) {
            rig.feed(
                Ct5SessionCodec.push(glucoseId = stuck, cipherId = EXPECTED_CIPHER_ID, errorCode = 2, iwX100 = iw),
            )
            runCurrent()
            advanceTimeBy(Ct5Constants.SAMPLE_INTERVAL_MS)
        }

        assertEquals(walked, rig.repo.loadSourceCursor(rig.source.descriptor.id))
        assertTrue("no pull re-walks the store", rig.pulls().isEmpty())
    }

    /** Past rated wear the store ends at the stopped id; the bind clock's slots were all blank. */
    @Test
    fun `a blank slot past the last delivered id ends the pull`() = runTest {
        val stuck = 8175
        val rig = rigMidWear(samplesTaken = stuck + 3_339, cursor = stuck)
        rig.repo.saveHighestDeliveredId(rig.source.descriptor.id, stuck)
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()
        rig.feed(Ct5SessionCodec.push(glucoseId = stuck, cipherId = EXPECTED_CIPHER_ID, iwX100 = 686))
        runCurrent()
        assertEquals(stuck + 1, startIdOf(rig.pulls().single()))

        rig.feed(
            Ct5SessionCodec.historyReply(
                startId = stuck + 1,
                cipherId = EXPECTED_CIPHER_ID,
                records = listOf(Ct5SessionCodec.EMPTY_SLOT),
            ),
        )
        runCurrent()
        nextRequestGoesOut()

        assertEquals("one probe, no walk over blank slots", 1, rig.pulls().size)
        assertFalse(rig.source.backfillInFlight.value)
        assertEquals(stuck, rig.repo.loadSourceCursor(rig.source.descriptor.id))
    }

    @Test
    fun `a counter that holds one id for fifteen minutes marks the history exhausted`() = runTest {
        val stuck = 8175
        val rig = rigMidWear(samplesTaken = stuck, cursor = stuck)
        rig.repo.saveHighestDeliveredId(rig.source.descriptor.id, stuck)
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()

        val intervals = (Ct5Constants.COUNTER_STOPPED_MS / Ct5Constants.SAMPLE_INTERVAL_MS).toInt()
        for (n in 0..intervals) {
            assertFalse("flagged after $n intervals", rig.source.historyExhausted.value)
            advanceTimeBy(Ct5Constants.SAMPLE_INTERVAL_MS)
            rig.feed(
                Ct5SessionCodec.push(glucoseId = stuck, cipherId = EXPECTED_CIPHER_ID, iwX100 = 600 + n),
            )
            runCurrent()
        }
        assertTrue(rig.source.historyExhausted.value)

        advanceTimeBy(Ct5Constants.SAMPLE_INTERVAL_MS)
        rig.feed(Ct5SessionCodec.push(glucoseId = stuck + 1, cipherId = EXPECTED_CIPHER_ID, iwX100 = 700))
        runCurrent()
        assertFalse("a new id clears it", rig.source.historyExhausted.value)
    }

    @Test
    fun `a counter that advances never marks the history exhausted`() = runTest {
        val rig = rigMidWear(samplesTaken = 100, cursor = 100)
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()
        for (id in 101..110) {
            advanceTimeBy(Ct5Constants.SAMPLE_INTERVAL_MS)
            rig.feed(Ct5SessionCodec.push(glucoseId = id, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
            runCurrent()
        }
        assertFalse(rig.source.historyExhausted.value)
    }

    @Test
    fun `a handshake step that never answers reaches SignalLost`() = runTest {
        val rig = rig()
        rig.source.start()
        runCurrent()
        // No frame written, so the pacer cannot time this out; only the phase deadline can.
        advanceTimeBy(Ct5Constants.HANDSHAKE_STEP_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(CgmSourceStatus.SignalLost, rig.source.status.value)
    }

    @Test
    fun `a disconnect at any point drives SignalLost so the coordinator wakes`() = runTest {
        for (armFirst in listOf(false, true)) {
            val rig = rig(state = STORED_STATE)
            if (armFirst) rig.arm() else rig.source.start()
            runCurrent()
            rig.feed(Ct5GattEvent.Connection(connected = false, statusOk = true, statusCode = 19))
            // Unconditionally, even with status still Scanning; the coordinator waits on this.
            assertEquals("armFirst=$armFirst", CgmSourceStatus.SignalLost, rig.source.status.value)
        }
    }

    @Test
    fun `the descriptor is keyed on the BSN and seeds the warm-up window from the lifetime code`() {
        val plain = Ct5ConnectedSource.descriptorFor(BSN, "Anytime$BSN")
        assertEquals("anytime:$BSN", plain.id.value)
        assertEquals(Ct5Constants.VENDOR_ID, plain.vendorId)
        assertEquals(Ct5Constants.MODEL_ID, plain.sensorModelId)
        assertEquals(BSN, plain.serialSuffix)
        assertFalse(plain.passiveOnly)
        assertEquals(45, plain.warmupWindowMin)
        assertEquals(60, Ct5ConnectedSource.descriptorFor(BSN, lifeTime = 2).warmupWindowMin)
        assertEquals(60, Ct5ConnectedSource.descriptorFor(BSN, lifeTime = 3).warmupWindowMin)
        assertEquals(45, Ct5ConnectedSource.descriptorFor(BSN, lifeTime = 4).warmupWindowMin)
    }

    private fun TestScope.rig(
        state: Ct5SensorState? = null,
        tz: (Long) -> Int = { 0 },
        advertisedBound: Boolean? = false,
    ) = Rig(backgroundScope, { currentTime }, state, tz, advertisedBound)

    /** requestBind is queued onto the source's own collector; a turn there is part of the press. */
    private fun TestScope.pressBind(rig: Rig) {
        rig.source.requestBind()
        runCurrent()
    }

    // No bond on this family; a locked-screen drop leaves samples taken meanwhile on the sensor.

    @Test
    fun `a rejoin probes for one record before asking for a batch`() = runTest {
        val rig = rigMidWear(samplesTaken = 500)
        goLiveAndBackfillOn(rig)

        val pull = rig.transport.writes.single { (it[0].toInt() and 0xFF) == Ct5Constants.Opcode.PULL_HISTORY }
        assertEquals("the request is little-endian: cursor 0 means resume at sample 1", 1, pull[1].toInt())
        assertEquals(0, pull[2].toInt())
        // One record: the reply's length says which dialect this sensor speaks.
        assertEquals(1, pull[3].toInt())
    }

    @Test
    fun `recovered samples are filed on the sensor clock, not on the instant they were recovered`() = runTest {
        val rig = rigMidWear(samplesTaken = 500, cursor = FIRST_CONVERTIBLE_ID - 1)
        val anchor = currentTime - 500 * Ct5Constants.SAMPLE_INTERVAL_MS
        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 120, startId = FIRST_CONVERTIBLE_ID)
        nextRequestGoesOut()
        rig.feed(
            Ct5SessionCodec.historyReply(
                startId = FIRST_CONVERTIBLE_ID + 1,
                cipherId = EXPECTED_CIPHER_ID,
                records = listOf(
                    Ct5SessionCodec.record(glucoseMgdl = 130),
                    Ct5SessionCodec.record(glucoseMgdl = 140),
                    Ct5SessionCodec.END_OF_HISTORY,
                ),
            ),
        )

        val measured = rig.repo.upsertedReadings.filter { it.provenance == ReadingProvenance.MEASURED }
        assertEquals(listOf(120, 130, 140), measured.map { it.bgMgdl })
        assertEquals(
            "each sample keeps its own three-minute slot on the sensor's own clock",
            (0L..2L).map { anchor + (FIRST_CONVERTIBLE_ID + it) * Ct5Constants.SAMPLE_INTERVAL_MS },
            measured.map { it.rxWallMs },
        )
        assertEquals("nothing was filed under the recovery instant", 0, rig.source.skewFallbackCount)
    }

    @Test
    fun `a recovered reading never reaches the live readings flow`() = runTest {
        // This feeds the alarm engine; a measured reading clears a loss-of-signal (§3.6-A).
        val rig = rigMidWear(samplesTaken = 500)
        val published = mutableListOf<Int?>()
        backgroundScope.launch { rig.source.readings().collect { published += it.bgMgdl } }
        runCurrent()

        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 120)
        nextRequestGoesOut()
        rig.feed(
            Ct5SessionCodec.historyReply(
                startId = 2,
                cipherId = EXPECTED_CIPHER_ID,
                records = listOf(Ct5SessionCodec.record(glucoseMgdl = 130), Ct5SessionCodec.END_OF_HISTORY),
            ),
        )
        runCurrent()

        assertTrue("no recovered sample may be published", published.isEmpty())
        assertEquals(
            "and yet every one of them is stored",
            2,
            rig.repo.upsertedReadings.count { it.provenance == ReadingProvenance.MEASURED },
        )
    }

    @Test
    fun `the cursor advances over an empty slot rather than asking for it for ever`() = runTest {
        val rig = rigMidWear(samplesTaken = 900, cursor = FIRST_CONVERTIBLE_ID - 1)
        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 100, startId = FIRST_CONVERTIBLE_ID)
        nextRequestGoesOut()
        rig.feed(
            Ct5SessionCodec.historyReply(
                startId = FIRST_CONVERTIBLE_ID + 1,
                cipherId = EXPECTED_CIPHER_ID,
                records = listOf(
                    Ct5SessionCodec.record(glucoseMgdl = 110),
                    Ct5SessionCodec.EMPTY_SLOT,
                    Ct5SessionCodec.record(glucoseMgdl = 130),
                ),
            ),
        )
        nextRequestGoesOut()

        // Slots not samples: 3 answered, 2 decoded, next request starts past all four.
        val pulls = rig.transport.writes.filter { (it[0].toInt() and 0xFF) == Ct5Constants.Opcode.PULL_HISTORY }
        val last = pulls.last()
        assertEquals(
            FIRST_CONVERTIBLE_ID + 4,
            (last[1].toInt() and 0xFF) or ((last[2].toInt() and 0xFF) shl 8),
        )

        // Sample behind the hole keeps its own slot; vendor parser compacts and renumbers.
        val measured = rig.repo.upsertedReadings.filter { it.provenance == ReadingProvenance.MEASURED }
        assertEquals(130, measured.last().bgMgdl)
        assertEquals(
            (FIRST_CONVERTIBLE_ID + 3) * Ct5Constants.SAMPLE_INTERVAL_MIN,
            measured.last().minFromStart,
        )
    }

    @Test
    fun `the cursor is persisted per batch, so a dropped link resumes instead of restarting`() = runTest {
        val rig = rigMidWear(samplesTaken = 900)
        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 100)
        nextRequestGoesOut()
        rig.feed(
            Ct5SessionCodec.historyReply(
                startId = 2,
                cipherId = EXPECTED_CIPHER_ID,
                records = List(3) { Ct5SessionCodec.record(glucoseMgdl = 110 + it) },
            ),
        )

        assertEquals(
            "everything through sample 4 is accounted for",
            4,
            rig.repo.loadSourceCursor(rig.source.descriptor.id),
        )
        assertTrue(
            "and the sealed secret — the one thing a sensor cannot survive losing — is untouched",
            rig.repo.secretWrites.isEmpty(),
        )
    }

    @Test
    fun `the sensor's own end-of-store terminator stops the pull`() = runTest {
        val rig = rigMidWear(samplesTaken = 900)
        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 100)
        nextRequestGoesOut()
        rig.feed(
            Ct5SessionCodec.historyReply(
                startId = 2,
                cipherId = EXPECTED_CIPHER_ID,
                records = listOf(Ct5SessionCodec.record(glucoseMgdl = 110), Ct5SessionCodec.END_OF_HISTORY),
            ),
        )
        val before = rig.transport.writes.size
        advanceTimeBy(10 * Ct5Constants.HISTORY_BATCH_GAP_MS)
        runCurrent()
        assertEquals("nothing more is asked for past the end of the store", before, rig.transport.writes.size)
    }

    @Test
    fun `a history request that draws no reply ends the pull and not the session`() = runTest {
        val rig = rigMidWear(samplesTaken = 900)
        goLiveAndBackfillOn(rig)
        rig.feed(Ct5GattEvent.LadderExhausted(Ct5Constants.Opcode.PULL_HISTORY))

        assertEquals("LIVE", rig.source.phaseName)
        val before = rig.transport.writes.size
        advanceTimeBy(10 * Ct5Constants.HISTORY_BATCH_GAP_MS)
        runCurrent()
        assertEquals(before, rig.transport.writes.size)
    }

    @Test
    fun `a batch answering a range other than the one asked for is refused outright`() = runTest {
        // Every instant on this family is derived from the id.
        val rig = rigMidWear(samplesTaken = 900)
        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 100)
        nextRequestGoesOut()
        val stored = rig.repo.upsertedReadings.size
        rig.feed(
            Ct5SessionCodec.historyReply(
                startId = 700,
                cipherId = EXPECTED_CIPHER_ID,
                records = List(3) { Ct5SessionCodec.record(glucoseMgdl = 200 + it) },
            ),
        )
        assertEquals("not one record of a mis-addressed batch is filed", stored, rig.repo.upsertedReadings.size)
        assertEquals("LIVE", rig.source.phaseName)
    }

    @Test
    fun `a live push is still taken while a pull is running`() = runTest {
        val anchor = 1_000_000_000L
        val rig = rig(state = stateAnchoredFor(glucoseId = 0, atMs = anchor))
        goLiveAndBackfillOn(rig)
        val published = mutableListOf<Int?>()
        backgroundScope.launch { rig.source.readings().collect { published += it.bgMgdl } }
        runCurrent()

        // Sample 500 is the one being taken now on this wear, so it needs no clock advance.
        rig.feed(Ct5SessionCodec.push(glucoseId = 500, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 155))
        runCurrent()
        assertEquals(listOf(155), published)
        assertEquals("LIVE", rig.source.phaseName)
    }

    @Test
    fun `a recovered sample is never allowed to land in the freshness window`() = runTest {
        // A recovered sample is by construction older than the live stream, so one deriving to within a
        // sample interval of NOW is a wrong anchor, not a recovery — and that row is what the
        // latest-reading query, the panel and the dose calculator read as fresh.
        val rig = rigMidWear(samplesTaken = 10)
        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 120)
        nextRequestGoesOut()
        rig.feed(
            Ct5SessionCodec.historyReply(
                startId = 2,
                cipherId = EXPECTED_CIPHER_ID,
                records = (2..10).map { Ct5SessionCodec.record(glucoseMgdl = 100 + it) },
            ),
        )

        val filed = rig.repo.upsertedReadings
        // The property, not a fixed list: the cut moves with how far the clock ran during the exchange.
        assertTrue("the older samples are still recovered", filed.size >= 7)
        assertTrue(
            "nothing recovered may sit inside the window a fresh reading owns",
            filed.all { currentTime - it.rxWallMs > Ct5Constants.MAX_CLOCK_SKEW_MS },
        )
        assertTrue(
            "and the newest slots of the wear, which the live stream owns, are refused outright",
            filed.none { it.bgMgdl == 110 },
        )
        assertEquals(
            "the refused slot is left for a later pull, not consumed",
            9,
            rig.repo.loadSourceCursor(rig.source.descriptor.id),
        )
    }

    @Test
    fun `an idle pull leaves a sample too fresh to file for the re-pull that files it`() = runTest {
        val rig = rigMidWear(samplesTaken = 500, cursor = 500)
        val bind = requireNotNull(rig.state).bindTimeMs
        val id = rig.source.descriptor.id
        fun at(glucoseId: Int) = bind + glucoseId * Ct5Constants.SAMPLE_INTERVAL_MS
        goLiveAndPushOn(rig, liveId = 500)

        // Push 501 is lost; the idle pull asks for it while it still derives inside the window.
        advanceTimeBy(Ct5Constants.IDLE_PULL_MS + 1)
        runCurrent()
        assertEquals(501, startIdOf(rig.pulls().single()))
        rig.answerProbe(glucoseMgdl = 120, startId = 501)
        runCurrent()
        assertTrue("too fresh to file", rig.repo.upsertedReadings.none { it.bgMgdl == 120 })
        assertEquals("and not consumed", 500, rig.repo.loadSourceCursor(id))

        advanceTimeBy(at(502) - currentTime)
        rig.feed(Ct5SessionCodec.push(glucoseId = 502, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 140))
        runCurrent()
        assertEquals("502 does not step over the hole", 500, rig.repo.loadSourceCursor(id))

        advanceTimeBy(at(501) + Ct5Constants.MAX_CLOCK_SKEW_MS + 1 - currentTime)
        runCurrent()
        assertEquals("re-pulled once 501 leaves the window", 2, rig.pulls().size)
        assertEquals(501, startIdOf(rig.pulls().last()))
        rig.answerProbe(glucoseMgdl = 130, startId = 501)
        nextRequestGoesOut()
        rig.feed(
            Ct5SessionCodec.historyReply(
                startId = 502,
                cipherId = EXPECTED_CIPHER_ID,
                records = listOf(Ct5SessionCodec.record(glucoseMgdl = 140)),
            ),
        )
        runCurrent()

        assertEquals(at(501), rig.repo.upsertedReadings.single { it.bgMgdl == 130 }.rxWallMs)
        assertEquals("the cursor reaches the live stream", 502, rig.repo.loadSourceCursor(id))

        // Cursor write rationed to 1/10min; 506 is the first push past it.
        for (next in 503..506) {
            advanceTimeBy(at(next) - currentTime)
            rig.feed(Ct5SessionCodec.push(glucoseId = next, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 150))
        }
        assertEquals("and the live path advances it from there", 506, rig.repo.loadSourceCursor(id))
    }

    @Test
    fun `a backfill is refused when the anchor disagrees and could not be repaired`() = runTest {
        // A pull has no receive time to fall back on, so a wrong anchor files a stretch of the wear at
        // the wrong hour.
        val rig = rigMidWear(samplesTaken = 500)
        rig.repo.failSecretWrite = IllegalStateException("keystore unavailable")
        rig.source.start()
        runBlocking { rig.source.goLiveForTest(requireNotNull(rig.state)) }
        runCurrent()
        // A push whose id puts it far from its delivery. The correction it triggers cannot land.
        rig.feed(Ct5SessionCodec.push(glucoseId = 100, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        runCurrent()

        assertTrue("the live path must have noticed", rig.source.skewFallbackCount > 0)
        assertTrue(
            "no pull may run off an anchor known to be wrong",
            rig.pulls().isEmpty(),
        )
        assertEquals("and the live stream is untouched", "LIVE", rig.source.phaseName)
    }

    @Test
    fun `the pull opens on the session's first push, not when the session goes live`() = runTest {
        // The first push is what holds `bindTimeMs` against this session's clock, and repairs it.
        val rig = rigMidWear(samplesTaken = 900, cursor = 800)
        rig.source.start()
        runBlocking { rig.source.goLiveForTest(requireNotNull(rig.state)) }
        runCurrent()
        assertTrue("nothing may be pulled off an unchecked anchor", rig.pulls().isEmpty())

        rig.feed(Ct5SessionCodec.push(glucoseId = 900, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        runCurrent()
        assertEquals("and the first push opens it", 1, rig.pulls().size)
    }

    @Test
    fun `a pull that overshoots the present is trimmed back to it, so the live path can resume`() = runTest {
        // Pull walks in fixed-size runs; left claimed, overshoot slots strand cursor+1 forever.
        val rig = rigMidWear(samplesTaken = 20)
        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 100)
        // A batch whose slots run past sample 20, which is the newest the wear can have reached.
        nextRequestGoesOut()
        // Delivered live, so the pull may pass it inside the freshness window.
        rig.feed(Ct5SessionCodec.push(glucoseId = 20, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        rig.feed(
            Ct5SessionCodec.historyReply(
                startId = 2,
                cipherId = EXPECTED_CIPHER_ID,
                records = List(30) { Ct5SessionCodec.record(glucoseMgdl = 100) },
            ),
        )
        runCurrent()

        assertEquals(
            "the cursor stops at the newest slot that can exist",
            20,
            rig.repo.loadSourceCursor(rig.source.descriptor.id),
        )
        // Cursor write rationed to 1/10min; trim restarts window, pushes show the advance.
        for (id in 21..24) {
            advanceTimeBy(Ct5Constants.SAMPLE_INTERVAL_MS)
            rig.feed(Ct5SessionCodec.push(glucoseId = id, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 105))
        }
        assertEquals(
            "and the live path takes it from there rather than being locked out",
            24,
            rig.repo.loadSourceCursor(rig.source.descriptor.id),
        )
    }

    @Test
    fun `a cursor left ahead of the live stream is rewound so the pull can resume`() = runTest {
        // Live path advances only on cursor+1; an ahead cursor never sees it, pull sees nothing.
        val rig = rigMidWear(samplesTaken = 900, cursor = 902)
        rig.source.start()
        runBlocking { rig.source.goLiveForTest(requireNotNull(rig.state)) }
        runCurrent()
        rig.feed(Ct5SessionCodec.push(glucoseId = 900, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        runCurrent()

        assertEquals(
            "rewound to the slot before the live sample, so the gap in front of it is re-askable",
            899,
            rig.repo.loadSourceCursor(rig.source.descriptor.id),
        )
        assertEquals("and the pull runs again", 1, rig.pulls().size)
    }

    @Test
    fun `a recovered sample never fabricates an interpolated fill`() = runTest {
        // Backfill has no bracketing measurements; any non-MEASURED row wins the slot.
        val rig = rigMidWear(samplesTaken = 300, cursor = FIRST_CONVERTIBLE_ID - 1)
        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 120, startId = FIRST_CONVERTIBLE_ID)
        nextRequestGoesOut()
        rig.feed(
            Ct5SessionCodec.historyReply(
                startId = FIRST_CONVERTIBLE_ID + 1,
                cipherId = EXPECTED_CIPHER_ID,
                records = listOf(
                    Ct5SessionCodec.record(glucoseMgdl = 130),
                    // Off-body: below the plausible temperature band, so it is not a measurement.
                    Ct5SessionCodec.record(glucoseMgdl = 200, tempIntC = 5),
                    Ct5SessionCodec.record(glucoseMgdl = 140),
                ),
            ),
        )

        assertTrue(
            "a backfill writes measurements or nothing",
            rig.repo.upsertedReadings.all { it.provenance == ReadingProvenance.MEASURED },
        )
        assertEquals(listOf(120, 130, 140), rig.repo.upsertedReadings.map { it.bgMgdl })
    }

    /** Rated wear is a statement not a stop; capping there made later samples unreachable. */
    @Test
    fun `a pull reaches samples past the rated wear`() = runTest {
        val past = Ct5Constants.RATED_SAMPLES + 480
        val rig = rigMidWear(samplesTaken = past, cursor = past - 2)
        goLiveAndPushOn(rig, liveId = past)

        rig.source.requestBackfill()
        runCurrent()

        val pull = rig.pulls().single()
        assertEquals(past - 1, startIdOf(pull))
    }

    @Test
    fun `nothing above the wire's own id space is ever asked for`() = runTest {
        val rig = rigMidWear(samplesTaken = Ct5Constants.MAX_SAMPLE_ID + 5_000, cursor = 0)
        goLiveAndPushOn(rig, liveId = 100)
        rig.source.requestBackfill()
        runCurrent()
        for (pull in rig.pulls()) {
            assertTrue("asked for ${startIdOf(pull)}", startIdOf(pull) <= Ct5Constants.MAX_SAMPLE_ID)
        }
    }

    @Test
    fun `a sensor already level with the live stream is not pulled at all`() = runTest {
        val rig = rigMidWear(samplesTaken = 40, cursor = 40)
        goLiveAndBackfillOn(rig)
        assertTrue(
            "there is nothing behind the live stream to fetch",
            rig.transport.writes.none { (it[0].toInt() and 0xFF) == Ct5Constants.Opcode.PULL_HISTORY },
        )
    }

    private fun stateAnchoredFor(glucoseId: Int, atMs: Long) =
        STORED_STATE.copy(bindTimeMs = atMs - glucoseId * Ct5Constants.SAMPLE_INTERVAL_MS)



    @Test
    fun `a history request is not rewritten fast enough to provoke a duplicate reply`() {
        // Every other frame draws a handful of bytes; a batch draws ~500 across several ATT packets.
        val history = Ct5Constants.ladderFor(Ct5Constants.Opcode.PULL_HISTORY)
        assertTrue(
            "a batch must be given longer to arrive than an ordinary reply",
            Ct5Constants.stepTimeoutMs(history) >
                Ct5Constants.stepTimeoutMs(Ct5Constants.ladderFor(Ct5Constants.Opcode.CHECK_ID)),
        )
    }

    @Test
    fun `a duplicate history reply cannot cancel the ladder of the request that followed it`() = runTest {
        // Cancelling a ladder is irreversible; a late dup would ack the step queued meanwhile.
        val rig = rigMidWear(samplesTaken = 900)
        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 100)
        nextRequestGoesOut()
        val pullsBefore = rig.pulls().size

        // The probe's reply a second time, from the ladder, while the request for sample 2 is in flight.
        rig.answerProbe(glucoseMgdl = 100)
        runCurrent()

        val pulls = rig.pulls()
        assertEquals("the outstanding range must be asked again", pullsBefore + 1, pulls.size)
        assertEquals(
            "and it must be the SAME range, not the next one",
            2,
            (pulls.last()[1].toInt() and 0xFF) or ((pulls.last()[2].toInt() and 0xFF) shl 8),
        )
        assertEquals("LIVE", rig.source.phaseName)
    }

    @Test
    fun `a truncated batch drops to one record at a time rather than ending the pull`() = runTest {
        // Reported MTU can't be believed downward; reports 23-byte default, sends 499 bytes.
        val rig = rigMidWear(samplesTaken = 900)
        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 100)
        nextRequestGoesOut()
        val before = rig.pulls().size

        // A reply whose body isn't a whole number of records; the tail was cut off in transit.
        val whole = Ct5SessionCodec.historyReply(
            startId = 2,
            cipherId = EXPECTED_CIPHER_ID,
            records = List(3) { Ct5SessionCodec.record(glucoseMgdl = 110 + it) },
        )
        rig.feed(Ct5SessionCodec.retruncate(whole, 20))
        runCurrent()

        val pulls = rig.pulls()
        assertEquals("the range is asked again", before + 1, pulls.size)
        assertEquals("for the same range", 2, (pulls.last()[1].toInt() and 0xFF) or ((pulls.last()[2].toInt() and 0xFF) shl 8))
        assertEquals("one record at a time", 1, pulls.last()[3].toInt())
        assertEquals("LIVE", rig.source.phaseName)
    }

    @Test
    fun `a sensor that duplicates for ever is left to the next rejoin rather than asked in a loop`() = runTest {
        val rig = rigMidWear(samplesTaken = 900)
        goLiveAndBackfillOn(rig)
        rig.answerProbe(glucoseMgdl = 100)
        nextRequestGoesOut()
        repeat(6) {
            rig.answerProbe(glucoseMgdl = 100)
            runCurrent()
        }
        val settled = rig.pulls().size
        repeat(3) {
            rig.answerProbe(glucoseMgdl = 100)
            runCurrent()
        }
        assertEquals("the pull gives up rather than re-asking without bound", settled, rig.pulls().size)
        assertEquals("and the session it shares a link with is untouched", "LIVE", rig.source.phaseName)
    }


    /** A batch reply is only accepted while the request it answers is in flight. */
    private fun TestScope.nextRequestGoesOut() {
        advanceTimeBy(Ct5Constants.HISTORY_BATCH_GAP_MS + 1)
        runCurrent()
    }

    /** A stopped transmitter repeats one id forever, later each time; every instant moves. */
    @Test
    fun `a sample already accounted for never moves the clock, however late it arrives`() = runTest {
        val stuck = 8175
        val rig = rigMidWear(samplesTaken = stuck, cursor = stuck)
        val anchorBefore = requireNotNull(rig.state).bindTimeMs
        // No start(): a failed session (watchdog, in the wait below) would ignore this push.
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()

        // Half an hour past where this id derives to; what the repair exists to correct.
        advanceTimeBy(30 * 60_000L)
        rig.feed(Ct5SessionCodec.push(glucoseId = stuck, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        runCurrent()

        assertTrue("nothing may be re-anchored on a repeated id", rig.repo.secretWrites.isEmpty())
        assertNull("and nothing was written over the wear's anchor", rig.storedAnchor())
    }

    /** Repair resets cursor to re-pull; the anchor guard must survive or a push undoes it. */
    @Test
    fun `the anchor guard survives a cursor reset, because the delivered id is remembered`() = runTest {
        val stuck = 8175
        val rig = rigMidWear(samplesTaken = stuck, cursor = 0)
        runBlocking { rig.repo.saveHighestDeliveredId(rig.source.descriptor.id, stuck) }
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()

        advanceTimeBy(30 * 60_000L)
        rig.feed(Ct5SessionCodec.push(glucoseId = stuck, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        runCurrent()

        assertTrue("the repair was undone by the first push", rig.repo.secretWrites.isEmpty())
    }

    /** Stopped counter mustn't cost history; earlier samples derive from the guarded anchor. */
    @Test
    fun `a stopped counter still lets the sensor's own store be pulled`() = runTest {
        val stuck = 8175
        val rig = rigMidWear(samplesTaken = stuck, cursor = stuck)
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()
        advanceTimeBy(30 * 60_000L)
        rig.feed(Ct5SessionCodec.push(glucoseId = stuck, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        runCurrent()

        assertTrue("the anchor was kept, not repaired", rig.repo.secretWrites.isEmpty())
        assertEquals("and the pull opened on it", 1, rig.pulls().size)
    }

    /** An anchor that could not be repaired is a different thing, and still refuses the pull. */
    @Test
    fun `an anchor the store refused to keep still refuses the pull`() = runTest {
        val rig = rigMidWear(samplesTaken = 500, cursor = 100)
        rig.repo.failSecretWrite = IllegalStateException("sealed store is unavailable")
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()
        advanceTimeBy(30 * 60_000L)
        rig.feed(Ct5SessionCodec.push(glucoseId = 501, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        runCurrent()

        assertTrue("no pull may run off an anchor known to be wrong", rig.pulls().isEmpty())
    }

    /** The repair still has to work: the interval is not exactly 180.000 s and drift is one-directional. */
    @Test
    fun `a sample past everything accounted for still repairs the clock`() = runTest {
        val rig = rigMidWear(samplesTaken = 500, cursor = 500)
        val anchorBefore = requireNotNull(rig.state).bindTimeMs
        // No start(): a failed session (watchdog, in the wait below) would ignore this push.
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()

        advanceTimeBy(30 * 60_000L)
        rig.feed(Ct5SessionCodec.push(glucoseId = 501, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        runCurrent()

        assertTrue("a genuinely new sample must still repair the clock", rig.repo.secretWrites.isNotEmpty())
        assertNotNull(rig.storedAnchor())
        assertTrue("and move it", rig.storedAnchor() != anchorBefore)
    }

    private fun Rig.storedAnchor(): Long? =
        repo.sensorSecrets[source.descriptor.id]?.let { Ct5SensorState.decode(it)?.bindTimeMs }

    // The vendor's reader re-drives its pull after 190 s of silence rather than waiting the link out, and
    // pulls from the lowest sample its store lacks when asked.

    @Test
    fun `a live link silent for the idle period is asked for its store, once per silence`() = runTest {
        val rig = rigMidWear(samplesTaken = 500, cursor = 500)
        goLiveAndPushOn(rig, liveId = 500)
        rig.source.armWatchdogForTest()
        assertTrue("level with the wear: nothing to pull yet", rig.pulls().isEmpty())

        advanceTimeBy(Ct5Constants.IDLE_PULL_MS + 1)
        runCurrent()

        val pull = rig.pulls().single()
        assertEquals("the sample the silence covers", 501, startIdOf(pull))
        assertEquals("LIVE", rig.source.phaseName)

        advanceTimeBy(Ct5Constants.PUSH_STALE_MS - Ct5Constants.IDLE_PULL_MS - 2)
        runCurrent()
        assertEquals("one silence, one request", 1, rig.pulls().size)
        assertEquals("LIVE", rig.source.phaseName)

        advanceTimeBy(2)
        runCurrent()
        assertEquals("the reply proves the link, not the sensor", CgmSourceStatus.SignalLost, rig.source.status.value)
    }

    @Test
    fun `a fetch-history request pulls from the lowest sample the raw store lacks`() = runTest {
        val rig = rigMidWear(samplesTaken = 500, cursor = 500)
        val bind = requireNotNull(rig.state).bindTimeMs
        // Everything since 300 is on record except 350; behind 300 the store vouches for nothing.
        rig.repo.rawSamplesCompleteSinceMs = bind + 300 * Ct5Constants.SAMPLE_INTERVAL_MS
        for (id in 300..499) if (id != 350) rig.repo.upsertedReadings += rig.storedReading(id)
        goLiveAndPushOn(rig, liveId = 500)
        assertTrue(rig.pulls().isEmpty())

        rig.source.requestBackfill()
        runCurrent()

        val pull = rig.pulls().single()
        assertEquals(350, startIdOf(pull))
        assertEquals("a one-record probe first", 1, pull[3].toInt())
    }

    @Test
    fun `a fetch-history request off a store lacking nothing pulls nothing`() = runTest {
        val rig = rigMidWear(samplesTaken = 500, cursor = 500)
        val bind = requireNotNull(rig.state).bindTimeMs
        rig.repo.rawSamplesCompleteSinceMs = bind + 300 * Ct5Constants.SAMPLE_INTERVAL_MS
        for (id in 300..499) rig.repo.upsertedReadings += rig.storedReading(id)
        goLiveAndPushOn(rig, liveId = 500)

        rig.source.requestBackfill()
        runCurrent()

        assertTrue(rig.pulls().isEmpty())
    }

    @Test
    fun `a fetch-history request is refused before the session's first push`() = runTest {
        // Nothing has held the anchor against a delivery, so nothing recovered could be dated.
        val rig = rigMidWear(samplesTaken = 500, cursor = 100)
        rig.source.start()
        runBlocking { rig.source.goLiveForTest(requireNotNull(rig.state)) }
        runCurrent()

        rig.source.requestBackfill()
        runCurrent()

        assertTrue(rig.pulls().isEmpty())
    }

    private fun startIdOf(pull: ByteArray) = (pull[1].toInt() and 0xFF) or ((pull[2].toInt() and 0xFF) shl 8)

    /** Through the real [Ct5ConnectedSource.goLive] and a real push at [liveId], which is what checks the
     *  anchor and opens the automatic pull. */
    private suspend fun TestScope.goLiveAndPushOn(rig: Rig, liveId: Int) {
        rig.source.start()
        rig.source.goLiveForTest(requireNotNull(rig.state))
        runCurrent()
        rig.feed(Ct5SessionCodec.push(glucoseId = liveId, cipherId = EXPECTED_CIPHER_ID, glucoseMgdl = 100))
        runCurrent()
    }

    /** A MEASURED reading as the raw store would hold it for sample [id]. */
    private fun Rig.storedReading(id: Int): com.t1dm.core.model.CgmReading {
        val instant = requireNotNull(state).bindTimeMs + id * Ct5Constants.SAMPLE_INTERVAL_MS
        return com.t1dm.core.model.CgmReading(
            sourceId = source.descriptor.id,
            tsMs = instant,
            bgMgdl = 100,
            trendTenthsPerMin = null,
            minFromStart = id * Ct5Constants.SAMPLE_INTERVAL_MIN,
            quality = null,
            provenance = ReadingProvenance.MEASURED,
            flag = ReadingFlag.NORMAL,
            tzOffsetMin = 0,
            rxWallMs = instant,
            rssi = null,
        )
    }

    private fun TestScope.rigMidWear(samplesTaken: Int, cursor: Int = 0): Rig {
        advanceTimeBy(WEAR_CLOCK_START)
        val rig = rig(
            state = STORED_STATE.copy(
                bindTimeMs = currentTime - samplesTaken * Ct5Constants.SAMPLE_INTERVAL_MS,
            ),
        )
        rig.liveId = samplesTaken
        if (cursor > 0) runBlocking { rig.repo.saveSourceCursor(rig.source.descriptor.id, cursor) }
        return rig
    }

    /** [start] first: the pull is a loop with a pause in it, and its next batch arrives as a tick on the
     *  source's own collector. */
    private fun TestScope.goLiveAndBackfillOn(rig: Rig) {
        rig.source.start()
        runBlocking { rig.source.goLiveForTest(requireNotNull(rig.state) { "rig needs a state" }) }
        runCurrent()
        runBlocking { rig.source.openBackfillForTest(rig.liveId) }
        runCurrent()
    }

    private fun Rig.pulls() =
        transport.writes.filter { (it[0].toInt() and 0xFF) == Ct5Constants.Opcode.PULL_HISTORY }

    /** Answers the probe; recovered-value tests need FIRST_CONVERTIBLE_ID or beyond. */
    private suspend fun Rig.answerProbe(glucoseMgdl: Int, startId: Int = 1) = feed(
        Ct5SessionCodec.historyReply(
            startId = startId,
            cipherId = EXPECTED_CIPHER_ID,
            records = listOf(Ct5SessionCodec.record(glucoseMgdl = glucoseMgdl)),
        ),
    )

    private class Rig(
        scope: CoroutineScope,
        clock: () -> Long,
        val state: Ct5SensorState?,
        tz: (Long) -> Int = { 0 },
        advertisedBound: Boolean? = false,
    ) {
        /** The newest sample the wear has reached. Set by `rigMidWear`. */
        var liveId: Int = 0

        val transport = FakeCt5GattTransport()
        val repo = FakeCgmRepository()
        val source = Ct5ConnectedSource(
            descriptor = Ct5ConnectedSource.descriptorFor(BSN, "Anytime$BSN"),
            bsn = BSN,
            transport = transport,
            session = Ct5SessionCodec,
            repository = repo,
            scope = scope,
            initialState = state,
            advertisedBound = advertisedBound,
            // Fresh per rig; nonces hand out in order, a shared instance would hand B before A.
            nonces = FixedNonces(),
            tzOffsetMinFor = tz,
            nowMs = clock,
        )
    }

    private class FixedNonces : Ct5Nonces {
        private var handed = 0
        override fun nonce(): ByteArray = if (handed++ == 0) NONCE_A.copyOf() else NONCE_B.copyOf()
        override fun randomId(): String = RANDOM_ID
    }

    private companion object {
        /** The vendor's conversion emits nothing before this slot, whatever the record carries. */
        const val FIRST_CONVERTIBLE_ID = 14

        /** Every identifier and secret here is INVENTED; none of it may come from a live wear. */
        const val BSN = "0123456789"
        const val ANCHOR_SSN = "001734456789012510B2C"
        const val RANDOM_ID = "1234"
        val NONCE_A = byteArrayOf(0x3C, 0x71, 0xA2.toByte(), 0x0E)
        val NONCE_B = byteArrayOf(0x5D, 0x08, 0xE4.toByte(), 0x93.toByte())

        /** The four bytes a `0x30` reply carries after echoing B. */
        val SENSOR_TAIL = byteArrayOf(0x11, 0x22, 0x33, 0x44)

        /** [SENSOR_TAIL] and [NONCE_A] folded by ct5_session.rs; a literal, not the codec. */
        const val EXPECTED_CIPHER_ID = 216

        /** Anchor on a virtual zero-clock; a push against this state files at delivery. */
        val STORED_STATE = Ct5SensorState(
            cipherId = EXPECTED_CIPHER_ID,
            a = NONCE_A,
            b = NONCE_B,
            randomId = RANDOM_ID,
            kX100 = 125,
            rX100 = 100,
            ssn = ANCHOR_SSN,
            bindTimeMs = 1_755_000_000_000L,
            initialised = true,
        )

        /** Warm-up records, re-sealed under a synthetic key; only the wrapper is invented. */
        val CAPTURED_PUSH_A = byteArrayOf(
            0x35, 0x05, 0x00, 0xF0.toByte(), 0xF0.toByte(), 0xF1.toByte(), 0xDB.toByte(),
            0xCD.toByte(), 0xF3.toByte(), 0x0F, 0x0F, 0x0F, 0x6B, 0x94.toByte(), 0x6D, 0x2B, 0x0D,
            0x31, 0xA8.toByte(),
        )
        val CAPTURED_PUSH_B = byteArrayOf(
            0x35, 0x06, 0x00, 0x0F, 0x0F, 0x0E, 0x33, 0x32, 0xC9.toByte(), 0xF0.toByte(),
            0xF0.toByte(), 0xF0.toByte(), 0x94.toByte(), 0x6B, 0x92.toByte(), 0xD4.toByte(),
            0xF2.toByte(), 0xCC.toByte(), 0x88.toByte(),
        )
        const val VECTOR_CIPHER_ID = 0x5A

        /** Somewhere off zero for the backfill tests to anchor a wear against. */
        const val WEAR_CLOCK_START = 1_000_000_000L
    }

    private suspend fun Rig.arm() {
        source.start()
        source.onGattEvent(Ct5GattEvent.Connection(connected = true, statusOk = true, statusCode = 0))
        source.onGattEvent(Ct5GattEvent.MtuChanged(Ct5Constants.REQUESTED_MTU, ok = true))
        source.onGattEvent(Ct5GattEvent.ServicesDiscovered(ok = true, hasCt5Service = true))
        source.onGattEvent(Ct5GattEvent.NotifyEnabled(ok = true))
    }

    private fun Rig.goLive() = source.installLiveForTest(requireNotNull(state) { "rig needs a state" })

    private suspend fun Rig.feed(frame: ByteArray) = source.onGattEvent(Ct5GattEvent.Notify(frame))
    private suspend fun Rig.feed(event: Ct5GattEvent) = source.onGattEvent(event)

    /** Bind as far as the setID reply, exclusive. */
    private suspend fun Rig.bindToSetId() {
        feed(Ct5SessionCodec.setDateReply())
        feed(Ct5SessionCodec.versionReply())
        feed(Ct5SessionCodec.selfCheckReply())
        feed(Ct5SessionCodec.ssnReply(ANCHOR_SSN))
    }

    private suspend fun Rig.completeBind() {
        bindToSetId()
        feed(Ct5SessionCodec.setIdReply(NONCE_B, SENSOR_TAIL))
        // The sensor echoes the frame it received, byte for byte.
        feed(transport.writes.last().copyOf())
        feed(Ct5SessionCodec.initReply())
    }
}
