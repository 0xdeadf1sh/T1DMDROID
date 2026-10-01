package com.t1dm.cgm

/**
 * Recording [Libre3GattTransport] double: ready scripted, writes recorded, notifies driven by
 * the test (or by [replay]) — the pump side the sensor would own. Every PDU it sees lands in
 * [pdus] as a §12.5 fixture.
 */
class FakeLibre3GattTransport(
    /** §12.5: a recorded PDU list; [replay] feeds its NOTIFY side through the listeners. */
    val recorded: List<Libre3Pdu> = emptyList(),
    /** Healthy link: onReady fires inside connect; `false` parks until [fireReady]. */
    private val autoReady: Boolean = true,
) : Libre3GattTransport {

    var connectCalled = false
        private set
    var readyFired = false
        private set
    var closed = false
        private set

    /** (charUuid, chunk) per writeChunk, in order. */
    val writes = mutableListOf<Pair<String, ByteArray>>()

    /** What onDone received, index-aligned with [writes]. */
    val writeResults = mutableListOf<Boolean>()

    val gattErrors = mutableListOf<String>()

    /** enableDataChars was called; the §5.1 data chars recorded in enable order. */
    var enableDataCharsCalled = false
        private set
    val dataCharsEnabled = mutableListOf<Libre3DataChar>()

    /** Scripted §5.10 enable failure: enableDataChars routes to onFail instead of arming. */
    var dataEnableFailure: String? = null

    /** When false, enableDataChars parks until [fireDataReady] (the real transport waits). */
    private var dataOnReady: (() -> Unit)? = null
    private var dataOnFail: ((String) -> Unit)? = null

    private fun armDataChars() {
        if (dataCharsEnabled.isNotEmpty()) return
        for (c in Libre3DataChar.entries) dataCharsEnabled += c
    }

    private val listeners = HashMap<String, (ByteArray, Long?) -> Unit>()
    private var onReady: (() -> Unit)? = null
    private var onGattError: ((String) -> Unit)? = null

    /** Writes the stack would refuse outright (session sees onDone(false)). */
    var refuseWrites = false

    /** Refuse every write from this index on (the busy-link case, mid-round). */
    var refuseWritesAfter: Int? = null

    /** Writes that never confirm at all (session's 2 s chunk timeout). */
    var silentWrites = false

    /** Every confirmed patchControl write gets one reply notify, as the sensor answers. */
    var answerPatchControl = true

    private val pduLog = mutableListOf<Libre3Pdu>()
    private var pduClock = 0L

    /** Every PDU this fake saw (writes + test-driven notifies), monotonic ts. */
    val pdus: List<Libre3Pdu> get() = pduLog

    override fun connect(onReady: () -> Unit, onGattError: (reason: String) -> Unit) {
        connectCalled = true
        this.onReady = onReady
        this.onGattError = onGattError
        if (autoReady) fireReady()
    }

    /** For [autoReady] = false: the test decides when the characteristics are armed. */
    fun fireReady() {
        readyFired = true
        onReady?.invoke()
    }

    override fun writeChunk(charUuid: String, chunk: ByteArray, onDone: (Boolean) -> Unit) {
        val index = writes.size
        writes += charUuid to chunk.copyOf()
        record(Libre3Pdu.Direction.WRITE, charUuid, chunk)
        if (silentWrites) return
        val ok = !refuseWrites && (refuseWritesAfter?.let { index < it } ?: true)
        writeResults += ok
        onDone(ok)
        if (ok && answerPatchControl && charUuid == Libre3DataChar.PatchControl.uuid) {
            deliverDataNotify(Libre3DataChar.PatchControl, byteArrayOf(0x01, 0x00, 0x00, 0x00))
        }
    }

    override fun setNotifyListener(charUuid: String, listener: ((ByteArray, Long?) -> Unit)?) {
        if (listener == null) {
            listeners.remove(charUuid.lowercase())
        } else {
            listeners[charUuid.lowercase()] = listener
        }
    }

    /** How many times readRemoteRssi was called (the source polls; tests assert the cadence). */
    var readRemoteRssiCount = 0
        private set

    /** Scripted RSSI: readRemoteRssi() delivers this through the listener (healthy link). */
    var scriptedRssi: Int? = null

    private var rssiListener: ((Int) -> Unit)? = null

    override fun readRemoteRssi(): Boolean {
        readRemoteRssiCount++
        val value = scriptedRssi
        if (value != null) rssiListener?.invoke(value)
        return true
    }

    override fun setRssiListener(listener: ((Int) -> Unit)?) {
        rssiListener = listener
    }

    /**
     * §5.1 data phase, mirrored from the real transport: the data chars are recorded in
     * [Libre3DataChar.entries] order (the enable order the §5.1 set fixes), then onReady
     * fires — unless [dataEnableFailure] is scripted, which routes to onFail instead. Once
     * armed, [fail] prefers the data-phase onFail: the pairing session is gone by then.
     */
    override fun enableDataChars(onReady: () -> Unit, onFail: (String) -> Unit) {
        enableDataCharsCalled = true
        dataOnReady = onReady
        dataOnFail = onFail
        val failure = dataEnableFailure
        if (failure != null) {
            onFail(failure)
            return
        }
        armDataChars()
        onReady()
    }

    /** For [dataEnableFailure] scripted runs: the test decides when the data chars arm. */
    fun fireDataReady() {
        armDataChars()
        dataOnReady?.invoke()
    }

    override fun close() {
        closed = true
        onReady = null
        onGattError = null
        dataOnReady = null
        dataOnFail = null
        rssiListener = null
    }

    /** Test surface: one raw notify chunk off the binder, like the real callback. */
    fun notify(char: Libre3PairingChar, chunk: ByteArray) {
        record(Libre3Pdu.Direction.NOTIFY, char.uuid, chunk)
        listeners[char.uuid]?.invoke(chunk.copyOf(), null)
    }

    /** Test surface: one raw DATA notify chunk (§5.1 data phase), like the real callback. */
    fun deliverDataNotify(char: Libre3DataChar, bytes: ByteArray, rx: Long? = null) {
        record(Libre3Pdu.Direction.NOTIFY, char.uuid, bytes)
        listeners[char.uuid.lowercase()]?.invoke(bytes.copyOf(), rx)
    }

    /** Link death from the test; the data phase owns it once armed (the real transport's rule). */
    fun fail(reason: String) {
        gattErrors += reason
        val dataFail = dataOnFail
        if (dataFail != null) {
            dataFail(reason)
        } else {
            onGattError?.invoke(reason)
        }
    }

    /** §12.5: deliver the recorded NOTIFY side in order; the session re-creates the writes. */
    fun replay() {
        for (p in recorded) {
            if (p.direction == Libre3Pdu.Direction.NOTIFY) listeners[p.charUuid]?.invoke(p.bytes.copyOf(), null)
        }
    }

    private fun record(direction: Libre3Pdu.Direction, charUuid: String, bytes: ByteArray) {
        pduLog += Libre3Pdu(tsMs = pduClock++, direction = direction, charUuid = charUuid, bytes = bytes.copyOf())
    }
}
