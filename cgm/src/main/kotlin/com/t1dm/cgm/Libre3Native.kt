package com.t1dm.cgm

import android.util.Log
import java.security.SecureRandom
import uniffi.libre3_core.Libre3CapturedChunk as NativeCapturedChunk
import uniffi.libre3_core.Libre3ClinicalRecord as NativeClinicalRecord
import uniffi.libre3_core.Libre3DataChannel as NativeDataChannel
import uniffi.libre3_core.Libre3DataPlaneSession as NativeDataPlaneSession
import uniffi.libre3_core.Libre3DataPlaneUpdate as NativeDataPlaneUpdate
import uniffi.libre3_core.Libre3EntropySource as NativeEntropySource
import uniffi.libre3_core.Libre3Exception
import uniffi.libre3_core.Libre3FirstPair as NativeFirstPair
import uniffi.libre3_core.Libre3FirstPairNativeEphemeral as NativeFirstPairEphemeral
import uniffi.libre3_core.Libre3HistoricalPageRecord as NativeHistoricalPageRecord
import uniffi.libre3_core.Libre3LifecycleRecord as NativeLifecycleRecord
import uniffi.libre3_core.Libre3PairingAction as NativePairingAction
import uniffi.libre3_core.Libre3PairingChar as NativePairingChar
import uniffi.libre3_core.Libre3PairingException
import uniffi.libre3_core.Libre3PairingMachine as NativePairingMachine
import uniffi.libre3_core.Libre3PatchStatusRecord as NativePatchStatusRecord
import uniffi.libre3_core.Libre3RealtimeGlucoseRecord as NativeRealtimeGlucoseRecord
import uniffi.libre3_core.Libre3PatchInfo as NativePatchInfo
import uniffi.libre3_core.Libre3Region as NativeRegion
import uniffi.libre3_core.Libre3SwitchResponse as NativeSwitchResponse
import uniffi.libre3_core.ProvisionAction as NativeAction
import uniffi.libre3_core.ProvisionException
import uniffi.libre3_core.libre3ClinicalBackfillCmd
import uniffi.libre3_core.libre3EventLogCmd
import uniffi.libre3_core.libre3FactoryDataCmd
import uniffi.libre3_core.libre3ClinicalBackfillRangeCmd
import uniffi.libre3_core.libre3HistoricalBackfillCmd
import uniffi.libre3_core.libre3HistoricalBackfillRangeCmd
import uniffi.libre3_core.libre3NfcActivateCmd
import uniffi.libre3_core.libre3NfcParsePatchInfo
import uniffi.libre3_core.libre3NfcParseSwitchResponse
import uniffi.libre3_core.libre3NfcPatchInfoCmd
import uniffi.libre3_core.libre3NfcSwitchCmd
import uniffi.libre3_core.libre3Ping
import uniffi.libre3_core.libre3ProvisionAction
import uniffi.libre3_core.libre3ReceiverId

/** PLAN_T1DMDROID.md §5.8 derivation selector; the fold itself is Rust-owned. */
enum class Libre3Region { Eu, Us }

/** §5.9 state rule on patch-info: 0x01 activates fresh, anything else switches receiver. */
enum class Libre3ProvisionAction { Activate, SwitchReceiver }

/** Patch-info reply with the flags byte stripped; §5.9 layout. */
data class Libre3PatchInfo(
    val wearDurationMin: Int,
    val rawStatus: Int,
    /** Firmware bytes as `a.b.c.d`. */
    val fw: String,
    val sensorState: Int,
    /** Nine ASCII characters. */
    val serial: String,
)

/** Switch-receiver reply: the credentials this app then owns (§5.9). */
data class Libre3SwitchResponse(
    /** Display form `AA:BB:CC:DD:EE:FF`. */
    val bleAddress: String,
    /** Four bytes; the phase-5 tail, never logged. */
    val blePin: ByteArray,
    val activationTimeS: Long,
) {
    // ByteArray fields: identity-compare would make two equal replies unequal.
    override fun equals(other: Any?) = this === other || (other is Libre3SwitchResponse &&
        bleAddress == other.bleAddress &&
        blePin.contentEquals(other.blePin) &&
        activationTimeS == other.activationTimeS)

    override fun hashCode(): Int {
        var h = bleAddress.hashCode()
        h = 31 * h + blePin.contentHashCode()
        h = 31 * h + activationTimeS.hashCode()
        return h
    }

    override fun toString() = "Libre3SwitchResponse(bleAddress=$bleAddress, pin withheld, " +
        "activationTimeS=$activationTimeS)"
}

/** §5.1: the three security-service characteristics the handshake runs on. */
enum class Libre3PairingChar(val uuid: String) {
    /** `secCertData` — certs + ephemeral keys (phases 1–4); LibreSensorGATT.swift L44. */
    SecCertData("089823fa-ef89-11e9-81b4-2a2ae2dbcce4"),

    /** `secChallengeData` — phase 5 challenge, phase 6 response; LibreSensorGATT.swift L46. */
    SecChallengeData("089822ce-ef89-11e9-81b4-2a2ae2dbcce4"),

    /** `secCommandResponse` — the single-byte command clock; LibreSensorGATT.swift L49. */
    SecCommandResponse("08982198-ef89-11e9-81b4-2a2ae2dbcce4"),
}

/**
 * PLAN_T1DMDROID.md §5.1: the seven data-service characteristics, declared in the
 * post-handshake CCCD subscribe order (protocol.md PoC: patchControl, eventLog, historicData,
 * clinicalData, factoryData, glucoseData, patchStatus) — [entries] order IS the enable order
 * the transport walks. [channel] maps to the Rust data-plane channel for
 * [Libre3DataPlaneHandle.feed].
 */
enum class Libre3DataChar(val uuid: String, val channel: NativeDataChannel) {
    /** `patchControl` — write; data-plane commands (phase-5 backfill request). */
    PatchControl("08981338-ef89-11e9-81b4-2a2ae2dbcce4", NativeDataChannel.PATCH_CONTROL),

    /** `eventLog` — notify. */
    EventLog("08981bee-ef89-11e9-81b4-2a2ae2dbcce4", NativeDataChannel.EVENT_LOG),

    /** `historicData` — notify; historical backfill pages. */
    HistoricData("0898195a-ef89-11e9-81b4-2a2ae2dbcce4", NativeDataChannel.HISTORIC_DATA),

    /** `clinicalData` — notify; clinical stream. */
    ClinicalData("08981ab8-ef89-11e9-81b4-2a2ae2dbcce4", NativeDataChannel.CLINICAL_DATA),

    /** `factoryData` — notify. */
    FactoryData("08981d24-ef89-11e9-81b4-2a2ae2dbcce4", NativeDataChannel.FACTORY_DATA),

    /** `glucoseData` — notify; realtime per-minute glucose (15+20 B split, §5.2). */
    GlucoseData("0898177a-ef89-11e9-81b4-2a2ae2dbcce4", NativeDataChannel.GLUCOSE_DATA),

    /** `patchStatus` — notify; status/lifecycle/disconnect. */
    PatchStatus("08981482-ef89-11e9-81b4-2a2ae2dbcce4", NativeDataChannel.PATCH_STATUS),
}

/** §4/§5.3/§5.4: one step of driver work. Write chunks arrive already §5.2-fragmented. */
sealed interface Libre3PairingAction {

    /** Write every chunk to [char]'s UUID in order, each awaited, then ask for the next action. */
    data class WriteChar(
        val char: Libre3PairingChar,
        val chunks: List<ByteArray>,
    ) : Libre3PairingAction {
        // ByteArray list: identity-compare would make two equal steps unequal.
        override fun equals(other: Any?) = this === other || (other is WriteChar &&
            char == other.char &&
            chunks.size == other.chunks.size &&
            chunks.withIndex().all { (i, c) -> other.chunks[i].contentEquals(c) })

        override fun hashCode(): Int {
            var h = char.hashCode()
            for (c in chunks) h = 31 * h + c.contentHashCode()
            return h
        }
    }

    /**
     * Park on [char] until [exactly] reassembled bytes hold. [drain] marks the command clock
     * (§5.3): [expectPrefix] must start the reassembled bytes; everything buffered drains with it.
     */
    data class AwaitNotify(
        val char: Libre3PairingChar,
        val exactly: Int,
        val expectPrefix: ByteArray?,
        val drain: Boolean,
        val label: String,
    ) : Libre3PairingAction {
        override fun equals(other: Any?) = this === other || (other is AwaitNotify &&
            char == other.char &&
            exactly == other.exactly &&
            (expectPrefix == null) == (other.expectPrefix == null) &&
            (expectPrefix == null || expectPrefix.contentEquals(other.expectPrefix)) &&
            drain == other.drain &&
            label == other.label)

        override fun hashCode(): Int {
            var h = char.hashCode()
            h = 31 * h + exactly
            h = 31 * h + (expectPrefix?.contentHashCode() ?: 0)
            h = 31 * h + drain.hashCode()
            h = 31 * h + label.hashCode()
            return h
        }

        override fun toString() = "AwaitNotify(char=$char, exactly=$exactly, " +
            "expectPrefix=${expectPrefix?.size ?: -1} B, drain=$drain, label=$label)"
    }

    /**
     * §5.6 terminal: the R1/R2 echo verified. kEnc/ivEnc are session-scoped data-plane keys
     * (phase 5 consumes them); kAuth (when non-null) is the driver's to persist.
     */
    data class Established(
        val kEnc: ByteArray,
        val ivEnc: ByteArray,
        val kAuth: ByteArray?,
    ) : Libre3PairingAction {
        override fun equals(other: Any?) = this === other || (other is Established &&
            kEnc.contentEquals(other.kEnc) &&
            ivEnc.contentEquals(other.ivEnc) &&
            (kAuth == null) == (other.kAuth == null) &&
            (kAuth == null || kAuth.contentEquals(other.kAuth)))

        override fun hashCode(): Int {
            var h = kEnc.contentHashCode()
            h = 31 * h + ivEnc.contentHashCode()
            h = 31 * h + (kAuth?.contentHashCode() ?: 0)
            return h
        }

        override fun toString() = "Established(kEnc=${kEnc.size} B, ivEnc=${ivEnc.size} B, " +
            "kAuth=${kAuth != null})"
    }
}

/** §4: one pairing machine, driven stepwise by Libre3Session over the transport. */
interface Libre3PairingMachineHandle {

    /** Next driver action; null = the machine refused (see [error], logged) or is terminal. */
    fun nextAction(): Libre3PairingAction?

    /** §5.2 chunk, seq-prefixed except raw secCommandResponse; null = buffered or refused. */
    fun onNotify(char: Libre3PairingChar, chunk: ByteArray): Libre3PairingAction?

    /** Fail-closed error the machine died with; null while healthy. Set before the null return. */
    val error: String?

    /** Releases the native machine (it holds the §9 tables); idempotent. */
    fun close()
}

// MARK: - data plane (PLAN_T1DMDROID.md §5.7, phase 5)

/** §5.7: the warmup/active/expired lifecycle (kit `SensorLifecycle`), Kotlin mirror. */
data class Libre3Lifecycle(
    /** "warmup" | "active" | "expired" (kit `SensorLifecyclePhase` rawValue). */
    val phase: String,
    val isWarmingUp: Boolean,
    val isExpired: Boolean,
    val remainingWarmupMin: Int,
    val remainingWearMin: Int?,
)

/**
 * §5.7: one decoded data-plane update — Kotlin mirror of the native records, carrying exactly
 * the fields the stream dispatches. Fakes and tests never touch uniffi types.
 */
sealed interface Libre3DataUpdate {

    /** §5.7 12-B patchStatus plaintext plus the kit's user-facing inference. */
    data class PatchStatus(
        val lifeCount: Int,
        val currentLifeCount: Int,
        val patchState: Int,
        val errorData: Int,
        /** patchState 4 = active; 3/5/7 expired/error handling; 6/8 terminated (§5.7). */
        val isPatchStateActive: Boolean,
        val isPatchStateTerminated: Boolean,
        val isShutdownTerminated: Boolean,
        val attention: String,
        val shouldNotifyUser: Boolean,
        val shouldNotifyReplaceSensor: Boolean,
        val lifecycle: Libre3Lifecycle,
    ) : Libre3DataUpdate

    /** §5.7 29-B realtime glucose plus the kit's quality assessment. */
    data class RealtimeGlucose(
        val lifeCount: Int,
        /** Display-clamped mg/dL (§5.7 clamp); null = not displayable. */
        val mgdl: Int?,
        /** The §5.7 usability rule: displayable AND quality-good AND condition-ok AND not warmup/expiry. */
        val usable: Boolean,
        /** A warmup issue was among the blocking issues. */
        val warmup: Boolean,
        /** An expiry issue was among the blocking issues. */
        val expired: Boolean,
        /** All assessment issues, kit description strings, in kit order; logged on a drop. */
        val issues: List<String>,
        /** Signed rate of change, 1/100 mg/dL/min, NOT the model field's 1/10; null = none. */
        val rateRaw: Short?,
        val trendKind: String,
    ) : Libre3DataUpdate

    /** §5.7 one 14-B page: 6 samples at a 5-minute stride, values display-clamped. */
    data class HistoricalPage(
        val startLifeCount: Int,
        /** mg/dL per sample (§5.7 clamp); null = not displayable. */
        val valuesMgdl: List<Int?>,
        /** The sample life counts, +5 per index from [startLifeCount]. */
        val sampleLifeCounts: List<Int>,
    ) : Libre3DataUpdate

    /** §5.7 clinical stream record; logged only — §11 does not map it (parked gap-fill source). */
    data class Clinical(val lifeCount: Int, val currentMgdl: Int?, val historicMgdl: Int?) : Libre3DataUpdate

    /** Decrypted bytes of an unknown payload shape; visible in logs, never coerced (§5.7/§15). */
    data class Raw(val channel: Libre3DataChar, val plaintext: ByteArray) : Libre3DataUpdate {
        // ByteArray field: identity-compare would make two equal updates unequal.
        override fun equals(other: Any?) = this === other || (other is Raw &&
            channel == other.channel &&
            plaintext.contentEquals(other.plaintext))

        override fun hashCode(): Int {
            var h = channel.hashCode()
            h = 31 * h + plaintext.contentHashCode()
            return h
        }
    }
}

/** §5.7: one sensor's post-pairing data plane over the Rust session (mirrors its seam). */
interface Libre3DataPlaneHandle {

    /**
     * §5.2/§5.7: feed one raw notify chunk (the §5.2 glucose 15+20 B split reassembles inside
     * the Rust session, concatenated before CCM). Returns the decoded updates; an EMPTY list
     * means the frame is still assembling (the 15-B glucose prefix latched — keep waiting).
     * null = refused for THIS frame (per-frame CCM/decode failure, logged inside): the stream
     * keeps going — one bad frame is not a dead link (§5.7).
     */
    fun feed(char: Libre3DataChar, chunk: ByteArray): List<Libre3DataUpdate>?

    /** §5.7: encrypt one 7-B patchControl plaintext; 13-B kind0 frame out; null = refused. */
    fun nextPatchControlFrame(plaintext: ByteArray): ByteArray?

    /** §5.7: the last accepted (usable, displayable) realtime glucose lifeCount, if any. */
    val lastAcceptedGlucoseLifeCount: Int?

    /** Console only: own assembler, copied key; the stream and the tx counter untouched. */
    fun openCaptured(chunks: List<Libre3CapturedChunk>): Libre3Call<List<Libre3OpenedFrame>>

    /** Console only: [kind] as in [Libre3OpenedFrame]; nothing sent, tx counter untouched. */
    fun sealFrame(kind: Int, sequence: Int, plaintext: ByteArray): Libre3Call<ByteArray>

    /** Releases the native session; idempotent. */
    fun close()
}

/** [char] null = unassembled, every packet kind tried. */
class Libre3CapturedChunk(val char: Libre3DataChar?, val bytes: ByteArray)

/** [kind]: Rust `DataPlanePacketKind` declaration index. Null: no tag verifies, or under 3 B. */
class Libre3OpenedFrame(
    /** Index of the chunk that completed the frame. */
    val chunk: Int,
    val sequence: Int?,
    val kind: Int?,
    val plaintext: ByteArray?,
)

/** §5.3 native first-pair ephemeral material (kit `FirstPairNativeEphemeralMaterial`). */
class Libre3FirstPairEphemeral(
    /** 32-byte big-endian private scalar; never logged. */
    val privateBe32: ByteArray,
    /** The `process2(5)` wire point `04 || X || Y` (65 B). */
    val publicKey65: ByteArray,
    /** The accepted 282-byte (0x11a) null-entropy block. */
    val nullEntropy11a: ByteArray,
    /** The native null-branch scalar window (70-byte LE). */
    val nullScalarWindow: ByteArray,
    val attempts: Int,
) {
    // ByteArray fields: identity-compare would make two equal materials unequal.
    override fun equals(other: Any?) = this === other || (other is Libre3FirstPairEphemeral &&
        privateBe32.contentEquals(other.privateBe32) &&
        publicKey65.contentEquals(other.publicKey65) &&
        nullEntropy11a.contentEquals(other.nullEntropy11a) &&
        nullScalarWindow.contentEquals(other.nullScalarWindow) &&
        attempts == other.attempts)

    override fun hashCode(): Int {
        var h = privateBe32.contentHashCode()
        h = 31 * h + publicKey65.contentHashCode()
        h = 31 * h + nullEntropy11a.contentHashCode()
        h = 31 * h + nullScalarWindow.contentHashCode()
        h = 31 * h + attempts
        return h
    }

    override fun toString() = "Libre3FirstPairEphemeral(attempts=$attempts, secrets withheld)"
}

/** Result of one native call; 0xB1 is its own case so the UI can name the account. */
sealed interface Libre3Call<out T> {
    data class Ok<T>(val value: T) : Libre3Call<T>

    /** The sensor stores another account's receiver fold (NFC error 0xB1). */
    data object AccountMismatch : Libre3Call<Nothing>

    data class Failed(val reason: String) : Libre3Call<Nothing>
}

/** Every byte-level and security-critical computation for the family lives behind this seam. */
interface Libre3Native {
    /** Scaffold seam check; the protocol ops land with the phases that own them. */
    fun ping(): String?

    /** §5.8 fold of the lowercased dashed account id for the region. */
    fun receiverId(accountId: String, region: Libre3Region): UInt?

    fun provisionAction(sensorState: Int): Libre3ProvisionAction?

    fun nfcPatchInfoCmd(): ByteArray?

    fun nfcActivateCmd(timeSeconds: Long, receiverId: UInt): ByteArray?

    fun nfcSwitchCmd(timeSeconds: Long, receiverId: UInt): ByteArray?

    /** Raw ISO 15693 reply; Rust skips the flags byte and the 0xa5 echo run (§5.9, live EU tap). */
    fun nfcParsePatchInfo(response: ByteArray): Libre3Call<Libre3PatchInfo>

    /** Raw reply; Rust skips the flags byte and the 0xa5 echo run. */
    fun nfcParseSwitchResponse(response: ByteArray): Libre3Call<Libre3SwitchResponse>

    /** §9 step 4: the pushed-dir manifest gate; a failure names the offending file. */
    fun verifyTables(tablesDir: String): Libre3Call<Unit>

    /**
     * §5.3: the native ephemeral material the whole first pair hangs on — the same entropy
     * derives the null scalar and the `process2(5)` wire point (a random ephemeral never
     * reaches a verified Phase 6). One-shot per call; the tables ctor costs a dir read.
     */
    fun makeFirstPairEphemeral(tablesDir: String, maxAttempts: Int): Libre3Call<Libre3FirstPairEphemeral>

    /** §5.3/§9: the command-gated first-pair machine; tables fail here, before any action. */
    fun startFirstPairMachine(
        tablesDir: String,
        ephemeral: Libre3FirstPairEphemeral,
        tail4: ByteArray,
        r2: ByteArray,
    ): Libre3Call<Libre3PairingMachineHandle>

    /**
     * §5.4: the cached direct-reconnect machine. [phase5RawKey] is
     * `Child23KAuthImport.phase5RawKey(forKAuthBlob:)`'s product; [kAuthBlob] rides on
     * `Established` unchanged.
     */
    fun startCachedReconnectMachine(
        tablesDir: String,
        tail4: ByteArray,
        r2: ByteArray,
        phase5RawKey: ByteArray,
        kAuthBlob: ByteArray?,
    ): Libre3Call<Libre3PairingMachineHandle>

    /**
     * §5.7: one sensor's data-plane session over kEnc/ivEnc (phase-6 material). warmup 60 min
     * is the kit default (SensorLifecycle.swift L10); wear comes from provisioning. Fails
     * closed on a wrong key size (Libre3Exception message kept).
     */
    fun startDataPlane(
        kEnc: ByteArray,
        ivEnc: ByteArray,
        warmupDurationMin: Int,
        wearDurationMin: Int?,
    ): Libre3Call<Libre3DataPlaneHandle>

    /**
     * §5.7 backfill commands: 7-B patchControl plaintexts (PatchControlCommand.swift); null on
     * a refused build. The historical default selector 1 = the greater-equal page command.
     */
    fun historicalBackfillCmd(lifeCount: Int, selector: Int = 1): ByteArray?

    fun clinicalBackfillCmd(lifeCount: Int, selector: Int = 1): ByteArray?

    /**
     * §5.7 Range probe (2026-09-24): the vendor's bounded backfill shape
     * (`libcrl_dp.so` PatchControlHistoricalDataBackfillRange; the kit's makeRangeCommand
     * `aux` field = the end life count). Closed window [startLifeCount, endLifeCount];
     * null on a refused build.
     */
    fun historicalBackfillRangeCmd(startLifeCount: Int, endLifeCount: Int, selector: Int = 1): ByteArray?

    fun clinicalBackfillRangeCmd(startLifeCount: Int, endLifeCount: Int, selector: Int = 1): ByteArray?

    fun eventLogCmd(index: Int): ByteArray?

    fun factoryDataCmd(): ByteArray?
}

/** Production impl over the uniffi bindings; unit tests substitute a scripted fake. */
class UniffiLibre3Native : Libre3Native {

    override fun ping(): String? = nullOnLibre3Error("ping") { libre3Ping() }

    override fun receiverId(accountId: String, region: Libre3Region): UInt? =
        nullOnLibre3Error("receiverId") { libre3ReceiverId(accountId, region.toNative()) }

    override fun provisionAction(sensorState: Int): Libre3ProvisionAction? =
        nullOnLibre3Error("provisionAction") {
            when (libre3ProvisionAction(sensorState.toUByte())) {
                NativeAction.ACTIVATE -> Libre3ProvisionAction.Activate
                NativeAction.SWITCH_RECEIVER -> Libre3ProvisionAction.SwitchReceiver
            }
        }

    override fun nfcPatchInfoCmd(): ByteArray? =
        nullOnLibre3Error("patchInfoCmd") { libre3NfcPatchInfoCmd() }

    override fun nfcActivateCmd(timeSeconds: Long, receiverId: UInt): ByteArray? =
        nullOnLibre3Error("activateCmd") { libre3NfcActivateCmd(timeSeconds.toUInt(), receiverId) }

    override fun nfcSwitchCmd(timeSeconds: Long, receiverId: UInt): ByteArray? =
        nullOnLibre3Error("switchCmd") { libre3NfcSwitchCmd(timeSeconds.toUInt(), receiverId) }

    override fun nfcParsePatchInfo(response: ByteArray): Libre3Call<Libre3PatchInfo> =
        onLibre3Call("patchInfo parse") {
            val n: NativePatchInfo = libre3NfcParsePatchInfo(response)
            Libre3PatchInfo(
                wearDurationMin = n.wearDurationMin.toInt(),
                rawStatus = n.rawStatus.toInt(),
                fw = n.fw.joinToString("."),
                sensorState = n.sensorState.toInt(),
                serial = n.serial,
            )
        }

    override fun nfcParseSwitchResponse(response: ByteArray): Libre3Call<Libre3SwitchResponse> =
        onLibre3Call("switchResponse parse") {
            val n: NativeSwitchResponse = libre3NfcParseSwitchResponse(response)
            Libre3SwitchResponse(
                bleAddress = n.bleAddress,
                blePin = n.blePin,
                activationTimeS = n.activationTimeS.toLong(),
            )
        }

    override fun verifyTables(tablesDir: String): Libre3Call<Unit> = Libre3Tables.verify(tablesDir)

    override fun makeFirstPairEphemeral(tablesDir: String, maxAttempts: Int): Libre3Call<Libre3FirstPairEphemeral> =
        onLibre3Call("first-pair ephemeral") {
            NativeFirstPair.load(tablesDir).use { firstPair ->
                val n: NativeFirstPairEphemeral = firstPair.makeFirstPairNativeEphemeral(
                    maxAttempts.toUInt(),
                    SecureRandomEntropySource(),
                )
                Libre3FirstPairEphemeral(
                    privateBe32 = n.privateBe32,
                    publicKey65 = n.publicKey65,
                    nullEntropy11a = n.nullEntropy11a,
                    nullScalarWindow = n.nullScalarWindow,
                    attempts = n.attempts.toInt(),
                )
            }
        }

    override fun startFirstPairMachine(
        tablesDir: String,
        ephemeral: Libre3FirstPairEphemeral,
        tail4: ByteArray,
        r2: ByteArray,
    ): Libre3Call<Libre3PairingMachineHandle> = onPairingCall("first-pair machine") {
        Libre3PairingMachineNative(
            NativePairingMachine.startFirstPair(
                tablesDir = tablesDir,
                phonePrivateBe32 = ephemeral.privateBe32,
                phonePublicKey65 = ephemeral.publicKey65,
                nullEntropy11a = ephemeral.nullEntropy11a,
                tail4 = tail4,
                r2 = r2,
            ),
        )
    }

    override fun startCachedReconnectMachine(
        tablesDir: String,
        tail4: ByteArray,
        r2: ByteArray,
        phase5RawKey: ByteArray,
        kAuthBlob: ByteArray?,
    ): Libre3Call<Libre3PairingMachineHandle> = onPairingCall("cached-reconnect machine") {
        Libre3PairingMachineNative(
            NativePairingMachine.startCachedReconnect(
                tablesDir = tablesDir,
                tail4 = tail4,
                r2 = r2,
                phase5RawKey = phase5RawKey,
                kAuthBlob = kAuthBlob,
            ),
        )
    }

    override fun startDataPlane(
        kEnc: ByteArray,
        ivEnc: ByteArray,
        warmupDurationMin: Int,
        wearDurationMin: Int?,
    ): Libre3Call<Libre3DataPlaneHandle> = onDataPlaneCall("data plane session") {
        Libre3DataPlaneNative(
            NativeDataPlaneSession(kEnc, ivEnc, warmupDurationMin.toUInt(), wearDurationMin?.toUInt()),
        )
    }

    override fun historicalBackfillCmd(lifeCount: Int, selector: Int): ByteArray? =
        nullOnLibre3Error("historicalBackfillCmd") {
            libre3HistoricalBackfillCmd(lifeCount.toUShort(), selector.toUByte())
        }

    override fun clinicalBackfillCmd(lifeCount: Int, selector: Int): ByteArray? =
        nullOnLibre3Error("clinicalBackfillCmd") {
            libre3ClinicalBackfillCmd(lifeCount.toUShort(), selector.toUByte())
        }

    override fun historicalBackfillRangeCmd(startLifeCount: Int, endLifeCount: Int, selector: Int): ByteArray? =
        nullOnLibre3Error("historicalBackfillRangeCmd") {
            libre3HistoricalBackfillRangeCmd(
                startLifeCount.toUShort(),
                endLifeCount.toUShort(),
                selector.toUByte(),
            )
        }

    override fun clinicalBackfillRangeCmd(startLifeCount: Int, endLifeCount: Int, selector: Int): ByteArray? =
        nullOnLibre3Error("clinicalBackfillRangeCmd") {
            libre3ClinicalBackfillRangeCmd(
                startLifeCount.toUShort(),
                endLifeCount.toUShort(),
                selector.toUByte(),
            )
        }

    override fun eventLogCmd(index: Int): ByteArray? =
        nullOnLibre3Error("eventLogCmd") { libre3EventLogCmd(index.toUByte()) }

    override fun factoryDataCmd(): ByteArray? =
        nullOnLibre3Error("factoryDataCmd") { libre3FactoryDataCmd() }

    private fun Libre3Region.toNative(): NativeRegion = when (this) {
        Libre3Region.Eu -> NativeRegion.EU
        Libre3Region.Us -> NativeRegion.US
    }

    /** §5.3: native entropy per attempt; a SecureRandom failure is not a retry path. */
    private class SecureRandomEntropySource : NativeEntropySource {
        private val random = SecureRandom()

        override fun entropy(byteCount: UInt): ByteArray = try {
            ByteArray(byteCount.toInt()).also { random.nextBytes(it) }
        } catch (e: Exception) {
            // Thrown Libre3Exception crosses back as a native error (fail-closed).
            throw Libre3Exception.Internal("entropy source failed: ${e.javaClass.simpleName}")
        }
    }

    private inline fun <T> nullOnLibre3Error(what: String, body: () -> T): T? =
        try {
            body()
        } catch (e: Libre3Exception) {
            Log.w(TAG, "$what rejected: ${e.message}")
            null
        }

    private inline fun <T> onLibre3Call(what: String, body: () -> T): Libre3Call<T> =
        try {
            Libre3Call.Ok(body())
        } catch (e: ProvisionException.NfcB1) {
            Libre3Call.AccountMismatch
        } catch (e: ProvisionException) {
            Log.w(TAG, "$what refused: ${e.message}")
            Libre3Call.Failed(e.message ?: "$what refused")
        } catch (e: Libre3Exception) {
            Log.w(TAG, "$what failed: ${e.message}")
            Libre3Call.Failed(e.message ?: "$what failed")
        }

    /** The pairing machines fail with [Libre3PairingException], not the NFC/Libre3 hierarchy. */
    private inline fun <T> onPairingCall(what: String, body: () -> T): Libre3Call<T> =
        try {
            Libre3Call.Ok(body())
        } catch (e: Libre3PairingException) {
            Log.w(TAG, "$what refused: ${e.message}")
            Libre3Call.Failed(e.message?.takeIf { it.isNotEmpty() } ?: "$what refused: ${e.javaClass.simpleName}")
        } catch (e: Libre3Exception) {
            Log.w(TAG, "$what failed: ${e.message}")
            Libre3Call.Failed(e.message ?: "$what failed")
        }

    /** The data plane fails with [Libre3Exception] (wrong key size, CCM, plaintext sizes). */
    private inline fun <T> onDataPlaneCall(what: String, body: () -> T): Libre3Call<T> =
        try {
            Libre3Call.Ok(body())
        } catch (e: Libre3Exception) {
            Log.w(TAG, "$what refused: ${e.message}")
            Libre3Call.Failed(e.message?.takeIf { it.isNotEmpty() } ?: "$what refused: ${e.javaClass.simpleName}")
        }

    private companion object {
        const val TAG = "Libre3Native"
    }
}

/** Production handle over the uniffi machine; exceptions become `error` + a null return. */
private class Libre3PairingMachineNative(
    private val machine: NativePairingMachine,
) : Libre3PairingMachineHandle {

    @Volatile
    override var error: String? = null
        private set

    override fun nextAction(): Libre3PairingAction? = lift("nextAction") { machine.nextAction().toKotlin() }

    override fun onNotify(char: Libre3PairingChar, chunk: ByteArray): Libre3PairingAction? =
        lift("onNotify") { machine.onNotify(char.toNative(), chunk)?.toKotlin() }

    override fun close() {
        runCatching { machine.close() }
    }

    /** null = refused; the machine replays the error on every later call (fail-closed). */
    private inline fun <T> lift(what: String, body: () -> T): T? =
        try {
            body()
        } catch (e: Libre3PairingException) {
            error = e.message?.takeIf { it.isNotEmpty() } ?: e.javaClass.simpleName
            Log.w(TAG, "$what refused: ${e.message}")
            null
        }

    private fun NativePairingAction.toKotlin(): Libre3PairingAction = when (this) {
        is NativePairingAction.WriteChar -> Libre3PairingAction.WriteChar(char.toKotlin(), chunks)
        is NativePairingAction.AwaitNotify -> Libre3PairingAction.AwaitNotify(
            char = char.toKotlin(),
            exactly = exactly.toInt(),
            expectPrefix = expectPrefix,
            drain = drain,
            label = label,
        )
        is NativePairingAction.Established -> Libre3PairingAction.Established(kEnc, ivEnc, kAuth)
    }

    private fun NativePairingChar.toKotlin(): Libre3PairingChar = when (this) {
        NativePairingChar.SEC_CERT_DATA -> Libre3PairingChar.SecCertData
        NativePairingChar.SEC_CHALLENGE_DATA -> Libre3PairingChar.SecChallengeData
        NativePairingChar.SEC_COMMAND_RESPONSE -> Libre3PairingChar.SecCommandResponse
    }

    private fun Libre3PairingChar.toNative(): NativePairingChar = when (this) {
        Libre3PairingChar.SecCertData -> NativePairingChar.SEC_CERT_DATA
        Libre3PairingChar.SecChallengeData -> NativePairingChar.SEC_CHALLENGE_DATA
        Libre3PairingChar.SecCommandResponse -> NativePairingChar.SEC_COMMAND_RESPONSE
    }

    private companion object {
        const val TAG = "Libre3Native"
    }
}

/** Production handle over the uniffi data-plane session; per-frame failures become null. */
private class Libre3DataPlaneNative(
    private val session: NativeDataPlaneSession,
) : Libre3DataPlaneHandle {

    override fun feed(char: Libre3DataChar, chunk: ByteArray): List<Libre3DataUpdate>? =
        lift("feed ${char.name}") { session.feed(char.channel, chunk).map { it.toKotlin() } }

    override fun nextPatchControlFrame(plaintext: ByteArray): ByteArray? =
        lift("nextPatchControlFrame") { session.nextPatchControlFrame(plaintext) }

    override val lastAcceptedGlucoseLifeCount: Int?
        get() = lift("lastAcceptedGlucoseLifeCount") { session.lastAcceptedGlucoseLifeCount() }?.toInt()

    override fun openCaptured(chunks: List<Libre3CapturedChunk>): Libre3Call<List<Libre3OpenedFrame>> =
        console {
            session.openCaptured(chunks.map { NativeCapturedChunk(it.char?.channel, it.bytes) }).map {
                Libre3OpenedFrame(it.chunk.toInt(), it.sequence?.toInt(), it.kind?.toInt(), it.plaintext)
            }
        }

    override fun sealFrame(kind: Int, sequence: Int, plaintext: ByteArray): Libre3Call<ByteArray> {
        if (kind !in 0..UByte.MAX_VALUE.toInt()) return Libre3Call.Failed("no kind $kind")
        if (sequence !in 0..UShort.MAX_VALUE.toInt()) return Libre3Call.Failed("seq 0–${UShort.MAX_VALUE}")
        return console { session.sealFrame(kind.toUByte(), sequence.toUShort(), plaintext) }
    }

    /** A closed session throws IllegalStateException; the console can race the teardown. */
    private inline fun <T> console(body: () -> T): Libre3Call<T> =
        try {
            Libre3Call.Ok(body())
        } catch (e: Libre3Exception) {
            Libre3Call.Failed(
                when (e) {
                    is Libre3Exception.Crypto -> e.reason
                    is Libre3Exception.Internal -> e.reason
                },
            )
        } catch (e: IllegalStateException) {
            Libre3Call.Failed("data plane closed")
        }

    override fun close() {
        runCatching { session.close() }
    }

    /**
     * Per-frame refusal → log + null, NO latch: the Rust session stays healthy across one bad
     * frame (§5.7 CCM failure is per-frame; the stream keeps streaming past the null).
     */
    private inline fun <T> lift(what: String, body: () -> T): T? =
        try {
            body()
        } catch (e: Libre3Exception) {
            Log.w(TAG, "$what refused: ${e.message}")
            null
        }

    private companion object {
        const val TAG = "Libre3Native"
    }
}

/** Native records → Kotlin mirrors (§5.7); the fakes/tests never see a uniffi type. */
private fun NativeLifecycleRecord.toKotlin(): Libre3Lifecycle = Libre3Lifecycle(
    phase = phase,
    isWarmingUp = isWarmingUp,
    isExpired = isExpired,
    remainingWarmupMin = remainingWarmupMin.toInt(),
    remainingWearMin = remainingWearMin?.toInt(),
)

private fun NativePatchStatusRecord.toKotlin(): Libre3DataUpdate.PatchStatus = Libre3DataUpdate.PatchStatus(
    lifeCount = lifeCount,
    currentLifeCount = currentLifeCount,
    patchState = patchState.toInt(),
    errorData = errorData.toInt(),
    isPatchStateActive = isPatchStateActive,
    isPatchStateTerminated = isPatchStateTerminated,
    isShutdownTerminated = isShutdownTerminated,
    attention = attention,
    shouldNotifyUser = shouldNotifyUser,
    shouldNotifyReplaceSensor = shouldNotifyReplaceSensor,
    lifecycle = lifecycle.toKotlin(),
)

private fun NativeRealtimeGlucoseRecord.toKotlin(): Libre3DataUpdate.RealtimeGlucose =
    Libre3DataUpdate.RealtimeGlucose(
        lifeCount = lifeCount.toInt(),
        mgdl = currentMgdl?.toInt(),
        usable = usable,
        warmup = warmup,
        expired = expired,
        issues = issues,
        rateRaw = rateRaw,
        trendKind = trendKind,
    )

private fun NativeHistoricalPageRecord.toKotlin(): Libre3DataUpdate.HistoricalPage =
    Libre3DataUpdate.HistoricalPage(
        startLifeCount = startLifeCount.toInt(),
        valuesMgdl = values.map { it.displayMgdl() },
        sampleLifeCounts = sampleLifeCounts.map { it.toInt() },
    )

private fun NativeClinicalRecord.toKotlin(): Libre3DataUpdate.Clinical = Libre3DataUpdate.Clinical(
    lifeCount = lifeCount.toInt(),
    currentMgdl = currentMgdl?.toInt(),
    historicMgdl = historicMgdl?.toInt(),
)

private fun NativeDataPlaneUpdate.toKotlin(): Libre3DataUpdate = when (this) {
    is NativeDataPlaneUpdate.PatchStatus -> status.toKotlin()
    is NativeDataPlaneUpdate.RealtimeGlucose -> reading.toKotlin()
    is NativeDataPlaneUpdate.HistoricalPage -> page.toKotlin()
    is NativeDataPlaneUpdate.Clinical -> record.toKotlin()
    is NativeDataPlaneUpdate.Raw -> Libre3DataUpdate.Raw(
        channel = Libre3DataChar.entries.first { it.channel == channel },
        plaintext = plaintext,
    )
}

/** §5.7 display clamp (glucose.rs `Libre3GlucoseValueStatus`): 1–38 → 39; 39–501 → value;
 *  502–999 → 501; else unavailable. Golden-tested in Rust; restated here for the page values. */
private fun UShort.displayMgdl(): Int? = when (val raw = toInt()) {
    in 1..38 -> 39
    in 39..501 -> raw
    in 502..999 -> 501
    else -> null
}