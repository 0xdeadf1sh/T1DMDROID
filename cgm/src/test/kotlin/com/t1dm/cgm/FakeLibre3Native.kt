package com.t1dm.cgm

/**
 * Scripted [Libre3PairingMachineHandle]: a tiny state machine mirroring the real machine's
 * external contract (PLAN §4/§5.2/§5.3) — queued actions in the REAL [Libre3PairingAction]
 * shapes, per-character reassembly (§5.2 seq prefix stripped), early arrival buffered and
 * consumed by the next ask, fail-closed error replay. Tests script both sides: the steps here,
 * the sensor's notify bytes through [FakeLibre3GattTransport.notify].
 */
class ScriptedPairingMachine(vararg steps: Libre3PairingAction) : Libre3PairingMachineHandle {

    private val queue = ArrayDeque<Libre3PairingAction>().apply { steps.forEach { addLast(it) } }

    /** Reassembled bytes per char, seq prefix stripped like the real machine's reassembler. */
    private val buffers = HashMap<Libre3PairingChar, ByteArray>()

    override var error: String? = null
        private set

    /** Every raw chunk fed, in arrival order — the session's onNotify forwarding, asserted. */
    val fedChunks = mutableListOf<Pair<Libre3PairingChar, ByteArray>>()

    var closed = false
        private set

    private var established = false

    override fun nextAction(): Libre3PairingAction? {
        if (established) {
            error = "machine already established"
            return null
        }
        if (error != null) return null
        return advance()
    }

    override fun onNotify(char: Libre3PairingChar, chunk: ByteArray): Libre3PairingAction? {
        if (established || error != null) return null
        fedChunks += char to chunk.copyOf()
        val stripped = if (chunk.isEmpty()) ByteArray(0) else chunk.copyOfRange(1, chunk.size)
        buffers[char] = (buffers[char] ?: ByteArray(0)) + stripped
        return advance()
    }

    override fun close() {
        closed = true
    }

    /** The real machine's rule: frontier writes pop, satisfied awaits consume, one action out. */
    private fun advance(): Libre3PairingAction? {
        while (true) {
            val head = queue.firstOrNull() ?: return null
            if (head is Libre3PairingAction.AwaitNotify) {
                val buf = buffers[head.char] ?: ByteArray(0)
                if (buf.size < head.exactly) return head // stays queued; the driver keeps waiting
                val prefix = head.expectPrefix
                if (prefix != null && !buf.startsWith(prefix)) {
                    error = "command rejected at ${head.label}: got ${hex(buf)}"
                    return null
                }
                if (!head.drain && buf.size != head.exactly) {
                    error = "notify wrong size: want ${head.exactly}, got ${buf.size}"
                    return null
                }
                buffers[head.char] = ByteArray(0)
                queue.removeFirst()
                continue
            }
            queue.removeFirst()
            if (head is Libre3PairingAction.Established) established = true
            return head
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Scripted [Libre3DataPlaneHandle]: the test maps fed chunks to updates (a null/throwing map
 * scripts a refused frame — one bad frame is not a dead link), hands out canned patchControl
 * frames, scripts the accepted life count; every call is recorded.
 */
class ScriptedDataPlane : Libre3DataPlaneHandle {

    /** Chunk → updates; null = refused frame (feed returns null, the stream continues). */
    var onFeed: ((Libre3DataChar, ByteArray) -> List<Libre3DataUpdate>?)? = null

    /** The canned 13-B kind0 frame; null = nextPatchControlFrame refuses. */
    var nextFrame: ByteArray? = null

    var acceptedLifeCount: Int? = null

    /** Every (char, chunk) fed, in arrival order. */
    val fedChunks = mutableListOf<Pair<Libre3DataChar, ByteArray>>()

    /** Every patchControl plaintext handed for encryption, in order. */
    val framePlaintexts = mutableListOf<ByteArray>()

    var closed = false
        private set

    override fun feed(char: Libre3DataChar, chunk: ByteArray): List<Libre3DataUpdate>? {
        fedChunks += char to chunk.copyOf()
        if (char == Libre3DataChar.PatchControl) return listOf(Libre3DataUpdate.Raw(char, chunk.copyOf()))
        return onFeed?.invoke(char, chunk) ?: emptyList()
    }

    /** Frame requests from this index on throw, as a session destroyed mid-walk does. */
    var throwFromFrame: Int? = null

    override fun nextPatchControlFrame(plaintext: ByteArray): ByteArray? {
        val destroyed = closed || throwFromFrame?.let { framePlaintexts.size >= it } == true
        check(!destroyed) { "Libre3DataPlaneSession object has already been destroyed" }
        framePlaintexts += plaintext.copyOf()
        return nextFrame?.copyOf()
    }

    override val lastAcceptedGlucoseLifeCount: Int?
        get() = acceptedLifeCount

    override fun openCaptured(chunks: List<Libre3CapturedChunk>): Libre3Call<List<Libre3OpenedFrame>> =
        Libre3Call.Failed("not scripted")

    override fun sealFrame(kind: Int, sequence: Int, plaintext: ByteArray): Libre3Call<ByteArray> =
        Libre3Call.Failed("not scripted")

    override fun close() {
        closed = true
    }
}

/** Scripted [Libre3Native] double: canned replies, recorded calls. */
open class FakeLibre3Native : Libre3Native {

    var nextReceiverId: UInt = 0x684FC53Fu
    var patchInfoCmd: ByteArray = byteArrayOf(0x02, 0xA1.toByte(), 0x7A)
    var activateCmd: ByteArray = byteArrayOf(0x02, 0xA0.toByte(), 0x7A)
    var switchCmd: ByteArray = byteArrayOf(0x02, 0xA8.toByte(), 0x7A)
    var patchInfoReply: Libre3Call<Libre3PatchInfo> = Libre3Call.Failed("no patch info scripted")
    var switchResponse: Libre3Call<Libre3SwitchResponse>? = null
    var actionByState: Map<Int, Libre3ProvisionAction> = emptyMap()

    // Phase-4 seam: tables gate, ephemeral and both machine starts, scriptable per test.
    var tablesVerify: Libre3Call<Unit> = Libre3Call.Ok(Unit)
    var ephemeral: Libre3Call<Libre3FirstPairEphemeral> = Libre3Call.Failed("no ephemeral scripted")
    var firstPairMachine: Libre3PairingMachineHandle? = null
    var cachedMachine: Libre3PairingMachineHandle? = null

    val verifyCalls = mutableListOf<String>()
    val ephemeralAttempts = mutableListOf<Int>()

    /** (tail4, r2) per startFirstPairMachine call. */
    val firstPairRequests = mutableListOf<Pair<ByteArray, ByteArray>>()

    /** (tail4, r2, rawKey, kAuthBlob) per startCachedReconnectMachine call. */
    val cachedRequests = mutableListOf<Array<ByteArray?>>()

    val foldCalls = mutableListOf<Pair<String, Libre3Region>>()
    val parsedPatchResponses = mutableListOf<ByteArray>()
    val switchTransceives = mutableListOf<Pair<Long, UInt>>()
    val parsedSwitchResponses = mutableListOf<ByteArray>()

    override fun ping(): String? = "ok"

    override fun receiverId(accountId: String, region: Libre3Region): UInt {
        foldCalls += accountId to region
        return nextReceiverId
    }

    override fun provisionAction(sensorState: Int): Libre3ProvisionAction? = actionByState[sensorState]

    override fun nfcPatchInfoCmd(): ByteArray = patchInfoCmd

    override fun nfcActivateCmd(timeSeconds: Long, receiverId: UInt): ByteArray {
        switchTransceives += timeSeconds to receiverId
        return activateCmd
    }

    override fun nfcSwitchCmd(timeSeconds: Long, receiverId: UInt): ByteArray {
        switchTransceives += timeSeconds to receiverId
        return switchCmd
    }

    override fun nfcParsePatchInfo(response: ByteArray): Libre3Call<Libre3PatchInfo> {
        parsedPatchResponses += response
        return patchInfoReply
    }

    override fun nfcParseSwitchResponse(response: ByteArray): Libre3Call<Libre3SwitchResponse> {
        parsedSwitchResponses += response
        return switchResponse ?: Libre3Call.Failed("no switch response scripted")
    }

    override fun verifyTables(tablesDir: String): Libre3Call<Unit> {
        verifyCalls += tablesDir
        return tablesVerify
    }

    override fun makeFirstPairEphemeral(tablesDir: String, maxAttempts: Int): Libre3Call<Libre3FirstPairEphemeral> {
        ephemeralAttempts += maxAttempts
        return ephemeral
    }

    override fun startFirstPairMachine(
        tablesDir: String,
        ephemeral: Libre3FirstPairEphemeral,
        tail4: ByteArray,
        r2: ByteArray,
    ): Libre3Call<Libre3PairingMachineHandle> {
        firstPairRequests += tail4.copyOf() to r2.copyOf()
        return firstPairMachine?.let { Libre3Call.Ok(it) } ?: Libre3Call.Failed("no first-pair machine scripted")
    }

    override fun startCachedReconnectMachine(
        tablesDir: String,
        tail4: ByteArray,
        r2: ByteArray,
        phase5RawKey: ByteArray,
        kAuthBlob: ByteArray?,
    ): Libre3Call<Libre3PairingMachineHandle> {
        cachedRequests += arrayOf(tail4.copyOf(), r2.copyOf(), phase5RawKey.copyOf(), kAuthBlob)
        return cachedMachine?.let { Libre3Call.Ok(it) } ?: Libre3Call.Failed("no cached machine scripted")
    }

    // Phase-5 seam: the data-plane session start + the backfill command builders.

    /** The scripted handle handed out on [startDataPlane]; also used by Libre3DataStreamTest. */
    var dataPlane: Libre3DataPlaneHandle? = null

    /** Overrides [dataPlane] when a test wants a refused/failed start (Fail/AccountMismatch). */
    var dataPlaneCall: Libre3Call<Libre3DataPlaneHandle>? = null

    /** (kEnc, ivEnc, warmupDurationMin, wearDurationMin) per startDataPlane call. */
    val dataPlaneRequests = mutableListOf<DataPlaneRequest>()

    data class DataPlaneRequest(
        val kEnc: ByteArray,
        val ivEnc: ByteArray,
        val warmupDurationMin: Int,
        val wearDurationMin: Int?,
    )

    val historicalRequests = mutableListOf<Pair<Int, Int>>()
    val clinicalRequests = mutableListOf<Pair<Int, Int>>()
    val historicalRangeRequests = mutableListOf<Pair<Int, Int>>()
    val clinicalRangeRequests = mutableListOf<Pair<Int, Int>>()
    val eventLogRequests = mutableListOf<Int>()
    var factoryCalls = 0
        private set

    /** Canned plaintexts; §5.7 shapes the tests pin (01 00 selector …). */
    var historicalCmd: ByteArray? = byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
    var clinicalCmd: ByteArray? = byteArrayOf(0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00)
    var historicalRangeCmd: ByteArray? = byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
    var clinicalRangeCmd: ByteArray? = byteArrayOf(0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00)
    var eventLogCmd: ByteArray? = byteArrayOf(0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    var factoryCmd: ByteArray? = byteArrayOf(0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    override fun startDataPlane(
        kEnc: ByteArray,
        ivEnc: ByteArray,
        warmupDurationMin: Int,
        wearDurationMin: Int?,
    ): Libre3Call<Libre3DataPlaneHandle> {
        dataPlaneRequests += DataPlaneRequest(kEnc.copyOf(), ivEnc.copyOf(), warmupDurationMin, wearDurationMin)
        return dataPlaneCall ?: dataPlane?.let { Libre3Call.Ok(it) }
            ?: Libre3Call.Failed("no data plane scripted")
    }

    override fun historicalBackfillCmd(lifeCount: Int, selector: Int): ByteArray? {
        historicalRequests += lifeCount to selector
        return historicalCmd
    }

    override fun clinicalBackfillCmd(lifeCount: Int, selector: Int): ByteArray? {
        clinicalRequests += lifeCount to selector
        return clinicalCmd
    }

    override fun historicalBackfillRangeCmd(startLifeCount: Int, endLifeCount: Int, selector: Int): ByteArray? {
        historicalRangeRequests += startLifeCount to endLifeCount
        return historicalRangeCmd
    }

    override fun clinicalBackfillRangeCmd(startLifeCount: Int, endLifeCount: Int, selector: Int): ByteArray? {
        clinicalRangeRequests += startLifeCount to endLifeCount
        return clinicalRangeCmd
    }

    override fun eventLogCmd(index: Int): ByteArray? {
        eventLogRequests += index
        return eventLogCmd
    }

    override fun factoryDataCmd(): ByteArray? {
        factoryCalls++
        return factoryCmd
    }
}
