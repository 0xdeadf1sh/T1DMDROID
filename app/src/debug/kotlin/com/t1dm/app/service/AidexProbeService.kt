package com.t1dm.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import uniffi.t1dm_core.AidexDeviceInfo
import uniffi.t1dm_core.AidexResponse
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
import uniffi.t1dm_core.aidexIv
import uniffi.t1dm_core.aidexParseRealtime
import uniffi.t1dm_core.aidexParseResponse

/** ACTIVATE writes to the sensor; its sequence deliberately omits 0x31 prepareNewSensor. */
class AidexProbeService : Service() {

    private enum class Mode { SURVIVAL, BOND, HANDSHAKE, BRINGUP, ACTIVATE, REALTIME, HISTORY }

    private enum class Hs { IDLE, CCCD_F001, CCCD_F002, ASKKEY, MASTERKEY, BLOB, DONE }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    @Volatile private var t0Ms: Long = 0L
    @Volatile private var started: Boolean = false
    @Volatile private var stopping: Boolean = false
    @Volatile private var mode: Mode = Mode.SURVIVAL
    @Volatile private var scanning: Boolean = false
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var target: BluetoothDevice? = null
    @Volatile private var reconnectAttempts: Int = 0
    @Volatile private var f001Char: BluetoothGattCharacteristic? = null
    @Volatile private var f002Char: BluetoothGattCharacteristic? = null
    @Volatile private var f003Char: BluetoothGattCharacteristic? = null
    @Volatile private var bondReceiverRegistered: Boolean = false

    // Null in the legacy modes.
    @Volatile private var targetSerial: String? = null
    @Volatile private var serialForCrypto: String = SERIAL

    @Volatile private var hs: Hs = Hs.IDLE
    @Volatile private var iv: ByteArray? = null
    @Volatile private var masterkey: ByteArray? = null
    @Volatile private var sess: ByteArray? = null
    @Volatile private var sessionReady: Boolean = false
    @Volatile private var lastBootstrapSerial: String? = null
    @Volatile private var bondFirstForSession: Boolean = false
    @Volatile private var awaitingBondThenHandshake: Boolean = false
    @Volatile private var onSessionReady: (() -> Unit)? = null
    @Volatile private var verdictEmitted: Boolean = false

    // One op in flight at a time.
    @Volatile private var pendingResponse: ((AidexResponse) -> Unit)? = null
    @Volatile private var lastCmdLabel: String = ""

    @Volatile private var dvcInfoStr: String? = null
    @Volatile private var startStr: String? = null
    @Volatile private var glucStr: String? = null
    @Volatile private var activated: Boolean? = null
    @Volatile private var activateForce: Boolean = false

    @Volatile private var histLastId: Int? = null
    @Volatile private var histStartHistory: Int? = null
    @Volatile private var activationEpoch: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("aidex-probe").apply { start() }
        handler = Handler(thread.looper)
        createChannel()
        registerReceiver(
            bondStateReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        bondReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIF_ID,
            buildNotif("AiDEX probe: initialising"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val action = intent?.action
        if (action == ACTION_PROBE_STOP) {
            logi("STOP requested via receiver — tearing down")
            stopSelf()
            return START_NOT_STICKY
        }
        if (t0Ms == 0L) t0Ms = SystemClock.elapsedRealtime()
        when (action) {
            ACTION_PROBE_START -> startLegacy(Mode.SURVIVAL)
            ACTION_PROBE_BOND -> startLegacy(Mode.BOND)
            ACTION_PROBE_HANDSHAKE -> startLegacy(Mode.HANDSHAKE)
            ACTION_PROBE_BRINGUP -> startStaged(Mode.BRINGUP, intent)
            ACTION_PROBE_ACTIVATE -> startStaged(Mode.ACTIVATE, intent)
            ACTION_PROBE_REALTIME -> startStaged(Mode.REALTIME, intent)
            ACTION_PROBE_HISTORY -> startStaged(Mode.HISTORY, intent)
            else -> logi("unknown action: $action")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        logi("onDestroy — stopping scan, closing GATT")
        handler.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
        if (bondReceiverRegistered) runCatching { unregisterReceiver(bondStateReceiver) }
        thread.quitSafely()
        super.onDestroy()
    }

    private fun startLegacy(m: Mode) {
        if (started) {
            logi("$m ignored — probe already running mode=$mode")
            return
        }
        started = true
        mode = m
        serialForCrypto = SERIAL
        logi("PROBE START mode=$m pid=${android.os.Process.myPid()}${if (m == Mode.HANDSHAKE) " serial=$SERIAL (UNBONDED read-only; no createBond)" else ""}")
        handler.post { startScan() }
    }

    private fun startStaged(m: Mode, intent: Intent?) {
        val serial = validateStagedSerial(intent) ?: return
        started = true
        mode = m
        targetSerial = serial
        serialForCrypto = serial
        activateForce = intent?.getIntExtra("force", 0) == 1
        logi("STAGED $m serial=$serial (K90-as-master bring-up; BONDS)${if (activateForce) " force=1" else ""}")

        val phase: () -> Unit = { runStagedPhase(m) }
        if (sessionReady && gatt != null && lastBootstrapSerial == serial) {
            logi("$m — reusing the live bonded session for $serial")
            handler.post(phase)
        } else {
            onSessionReady = phase
            bondFirstForSession = true
            verdictEmitted = false
            if (gatt != null) {
                logi("closing stale GATT before re-bootstrap")
                closeGatt()
            }
            sessionReady = false
            hs = Hs.IDLE
            logi("$m — no live session for $serial; bootstrapping scan→connect→bond→handshake")
            handler.post { startScan() }
        }
    }

    /** Null when the caller must abort; the refusal is logged. */
    private fun validateStagedSerial(intent: Intent?): String? {
        val s = intent?.getStringExtra("serial")
        if (s.isNullOrBlank()) {
            logi("REFUSED: no --es serial given; staged bring-up requires an explicit fresh serial")
            return null
        }
        if (s.equals(OLD_SERIAL, ignoreCase = true)) {
            logi("REFUSED: serial $OLD_SERIAL is the old sensor")
            return null
        }
        return s
    }

    private fun runStagedPhase(m: Mode) {
        when (m) {
            Mode.BRINGUP -> bringupSurvey()
            Mode.ACTIVATE -> {
                if (activated == true && !activateForce) {
                    emitVerdict("ACTIVATE REFUSED — a prior BRINGUP found this sensor ALREADY ACTIVATED; pass --ei force 1 to override")
                } else {
                    activateFlow()
                }
            }
            Mode.REALTIME -> startRealtime()
            Mode.HISTORY -> startHistory()
            else -> {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (stopping) return
        val adapter = runCatching { getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            logi("ABORT scan: no BluetoothLeScanner (adapter off / no BLE / permission not granted)")
            return
        }
        scanning = true
        val lock = targetSerial?.let { " [serial-locked to *$it]" } ?: ""
        logi("SCAN start (SCAN_MODE_LOW_LATENCY, filter: mfr 0x0059 AND service 0x181F)$lock")
        runCatching { scanner.startScan(scanFilters(), scanSettings(), scanCallback) }
            .onFailure { logi("SCAN start threw: $it") }
        handler.postDelayed(scanHeartbeat, SCAN_HEARTBEAT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanning = false
        handler.removeCallbacks(scanHeartbeat)
        val scanner = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        }.getOrNull() ?: return
        runCatching { scanner.stopScan(scanCallback) }
    }

    private val scanHeartbeat = object : Runnable {
        override fun run() {
            if (scanning && !stopping) {
                val who = targetSerial?.let { "serial *$it" } ?: "any AiDEX"
                logi("SCANNING… still looking for $who (sensor must be advertising / not held by another central)")
                handler.postDelayed(this, SCAN_HEARTBEAT_MS)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning || stopping) return
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
            val ts = targetSerial
            if (ts != null && (name == null || !name.contains(ts, ignoreCase = true))) {
                return
            }
            logi("SCAN match: name=$name addr=${device.address} rssi=${result.rssi}dBm bondState=${bondName(device.bondState)}")
            scanning = false
            handler.removeCallbacks(scanHeartbeat)
            stopScan()
            target = device
            handler.post { connect(device) }
        }

        override fun onScanFailed(errorCode: Int) {
            logi("SCAN FAILED errorCode=$errorCode")
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (stopping) return
        logi("connectGatt(autoConnect=false, TRANSPORT_LE) → ${device.address}")
        gatt = runCatching {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrElse {
            logi("connectGatt threw: $it")
            null
        }
        if (gatt == null) logi("connectGatt returned null")
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            logi("onConnectionStateChange: newState=${stateName(newState)} status=$status (${gattStatusName(status)})")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempts = 0
                    updateNotif("CONNECTED — mode=$mode")
                    logi("LINK UP — discoverServices()")
                    handler.post { runCatching { g.discoverServices() } }
                    if (mode == Mode.SURVIVAL) startHeartbeat()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    stopHeartbeat()
                    logi("LINK DOWN — status=$status (${gattStatusName(status)}).")
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    sessionReady = false
                    when (mode) {
                        Mode.SURVIVAL -> {
                            logi("survival mode: THIS is the teardown signal; if it coincides with screen-off the OS is dropping the link")
                            scheduleReconnect()
                        }
                        Mode.BOND -> logi("bond mode: not auto-reconnecting — verdict arrives via ACTION_BOND_STATE_CHANGED")
                        Mode.REALTIME -> logi("REALTIME LINK DOWN status=$status — if this coincides with screen-off, the connection did NOT survive (survival FAIL); no auto-reconnect")
                        Mode.HANDSHAKE -> if (!verdictEmitted) emitVerdict("BONDING REQUIRED — link dropped mid-handshake (unbonded) at phase=$hs status=$status (${gattStatusName(status)}) — the current sensor needs a fresh/reset sensor")
                        Mode.BRINGUP, Mode.ACTIVATE, Mode.HISTORY -> if (!verdictEmitted) emitVerdict("$mode LINK DROP before verdict — status=$status (${gattStatusName(status)}) at phase=$hs")
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            logi("onServicesDiscovered: status=$status (${gattStatusName(status)}) services=${g.services.size}")
            for (svc in g.services) {
                logi("  service ${svc.uuid}")
                for (ch in svc.characteristics) {
                    logi("    char ${ch.uuid} props=[${propsString(ch.properties)}]")
                    when (ch.uuid) {
                        F001_UUID -> f001Char = ch
                        F002_UUID -> f002Char = ch
                        F003_UUID -> f003Char = ch
                    }
                }
            }
            when (mode) {
                Mode.SURVIVAL ->
                    logi("NON-DESTRUCTIVE: NOT reading/writing 0xF001/0xF002/0xF003 nor their CCCDs")
                Mode.BOND -> startBond()
                Mode.HANDSHAKE -> {
                    onSessionReady = { handshakeConversation() }
                    doHandshake()
                }
                Mode.BRINGUP, Mode.ACTIVATE, Mode.REALTIME, Mode.HISTORY -> beginBondedSession()
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (characteristic.uuid != F002_UUID) return
            if (mode == Mode.BOND) {
                onBondF002Read(value, status)
            } else if (hs == Hs.BLOB) {
                onHandshakeBlobRead(value, status)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == F001_UUID && hs == Hs.MASTERKEY) {
                onAskKeyWritten(status)
            } else if (characteristic.uuid == F002_UUID) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    logi("onCharacteristicWrite F002 (cmd) status=$status (${gattStatusName(status)})")
                }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            when (characteristic.uuid) {
                F001_UUID -> handler.post { onMasterkeyNotify(value) }
                F002_UUID -> handler.post { onF002Response(value) }
                F003_UUID -> handler.post { onF003Realtime(value) }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val chUuid = descriptor.characteristic?.uuid
            logi("onDescriptorWrite ${descriptor.uuid} (char $chUuid) status=$status (${gattStatusName(status)})")
            when {
                chUuid == F003_UUID -> {
                    if (status == 5) logi("F003 CCCD Insufficient-Auth (0x05) — F003 is private-bonded (§4.1); the bond is required")
                    else logi("F003 realtime notify enabled")
                }
                hs == Hs.CCCD_F001 || hs == Hs.CCCD_F002 -> handler.post { onCccdWritten(status) }
                status == 5 -> logi("CCCD write Insufficient-Auth (0x05)")
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) logi("HEARTBEAT OK rssi=${rssi}dBm — LINK ALIVE")
            else logi("HEARTBEAT FAILED readRemoteRssi status=$status (${gattStatusName(status)})")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBond() {
        val dev = target ?: run { logi("BOND: no target device"); return }
        logi("BOND mode → device.createBond() on ${dev.address} (current bondState=${bondName(dev.bondState)})")
        val ok = runCatching { dev.createBond() }.getOrDefault(false)
        logi("createBond() returned $ok — watching ACTION_BOND_STATE_CHANGED for the SMP outcome")
        if (!ok) logi("BOND RESULT: createBond() refused to start — stack rejected pairing outright")
    }

    private fun onBondF002Read(value: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            logi("F002 READ OK status=0 len=${value.size} bytes=${value.toHex()}")
            logi("BOND RESULT: PAIRED (link encrypted, F002 read status=0) — K90 is now a bonded central")
        } else {
            logi("F002 READ FAILED status=$status (${gattStatusName(status)})")
            if (status == 5) logi("BOND RESULT: bonded per OS but F002 STILL 0x05 — encrypted-link access unusable")
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginBondedSession() {
        val dev = target ?: run { emitVerdict("$mode ABORT — no target device"); return }
        if (dev.bondState == BluetoothDevice.BOND_BONDED) {
            logi("$mode: already bonded → handshake")
            doHandshake()
            return
        }
        awaitingBondThenHandshake = true
        logi("$mode: createBond() on ${dev.address} — a fresh/unbonded AiDEX should Just-Works-bond")
        val ok = runCatching { dev.createBond() }.getOrDefault(false)
        logi("createBond() returned $ok — awaiting BOND_BONDED")
        if (!ok) emitVerdict("$mode FAILED — createBond() refused to start (returned false)")
    }

    // CGM.md §4.4
    @SuppressLint("MissingPermission")
    private fun doHandshake() {
        val g = gatt
        val f1 = f001Char
        val f2 = f002Char
        if (g == null || f1 == null || f2 == null) {
            emitVerdict("$mode ABORT — 0x181F/F001/F002 not found (gatt=${g != null} f001=${f1 != null} f002=${f2 != null})")
            return
        }
        runCatching { g.setCharacteristicNotification(f1, true) }
        runCatching { g.setCharacteristicNotification(f2, true) }
        handler.postDelayed(sessionWatchdog, SESSION_WATCHDOG_MS)
        logi("handshake — enabling F001 notify (CCCD 0x2902); a 0x05 here means the link is not encrypted")
        hs = Hs.CCCD_F001
        writeCccdEnable(f1)
    }

    @SuppressLint("MissingPermission")
    private fun writeCccdEnable(ch: BluetoothGattCharacteristic) {
        val g = gatt ?: return
        val cccd = ch.getDescriptor(CCCD_UUID) ?: run {
            emitVerdict("$mode ABORT — CCCD 0x2902 absent on ${ch.uuid}")
            return
        }
        val res = runCatching {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }.getOrDefault(-99)
        logi("writeDescriptor CCCD on ${ch.uuid} issued result=$res (0=SUCCESS)")
    }

    private fun onCccdWritten(status: Int) {
        if (verdictEmitted) return
        if (status == 5) { handshakeAuthFail("F001/F002 CCCD"); return }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            emitVerdict("$mode INCONCLUSIVE — CCCD write failed status=$status (${gattStatusName(status)}) at phase=$hs")
            return
        }
        when (hs) {
            Hs.CCCD_F001 -> {
                val f2 = f002Char ?: return
                logi("handshake — enabling F002 notify")
                hs = Hs.CCCD_F002
                writeCccdEnable(f2)
            }
            Hs.CCCD_F002 -> {
                logi("handshake — both CCCDs enabled; writing serial-derived askKey to F001")
                hs = Hs.ASKKEY
                writeAskKey()
            }
            else -> logi("onCccdWritten: unexpected phase=$hs")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeAskKey() {
        val g = gatt ?: return
        val f1 = f001Char ?: return
        val ak = runCatching { aidexAskkey(serialForCrypto) }.getOrElse {
            emitVerdict("$mode ABORT — aidexAskkey($serialForCrypto) failed: $it"); return
        }
        iv = runCatching { aidexIv(serialForCrypto) }.getOrElse {
            emitVerdict("$mode ABORT — aidexIv($serialForCrypto) failed: $it"); return
        }
        logi("askKey=${ak.toHex()} iv=${iv?.toHex()} — writeCharacteristic(F001, WRITE_TYPE_DEFAULT)")
        hs = Hs.MASTERKEY
        val res = runCatching {
            g.writeCharacteristic(f1, ak, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }.getOrDefault(-99)
        logi("writeCharacteristic(F001 askKey) issued result=$res (0=SUCCESS)")
    }

    private fun onAskKeyWritten(status: Int) {
        if (verdictEmitted) return
        logi("onCharacteristicWrite F001 (askKey) status=$status (${gattStatusName(status)})")
        if (status == 5) { handshakeAuthFail("F001 askKey write"); return }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            emitVerdict("$mode INCONCLUSIVE — askKey write failed status=$status (${gattStatusName(status)})"); return
        }
        logi("askKey written — awaiting the 16-byte masterkey NOTIFY on F001")
    }

    @SuppressLint("MissingPermission")
    private fun onMasterkeyNotify(value: ByteArray) {
        if (verdictEmitted || hs != Hs.MASTERKEY) return
        logi("F001 NOTIFY masterkey len=${value.size} bytes=${value.toHex()}")
        masterkey = value.copyOf()
        val g = gatt
        val f2 = f002Char
        if (g == null || f2 == null) { emitVerdict("$mode ABORT — no gatt/F002 for blob read"); return }
        hs = Hs.BLOB
        logi("handshake — reading the 17-byte session blob from F002")
        val ok = runCatching { g.readCharacteristic(f2) }.getOrDefault(false)
        logi("readCharacteristic(F002 session blob) issued=$ok")
    }

    private fun onHandshakeBlobRead(value: ByteArray, status: Int) {
        if (verdictEmitted) return
        logi("F002 READ (session blob) status=$status (${gattStatusName(status)}) len=${value.size} bytes=${value.toHex()}")
        if (status == 5) { handshakeAuthFail("F002 blob read"); return }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            emitVerdict("$mode INCONCLUSIVE — F002 blob read failed status=$status (${gattStatusName(status)})"); return
        }
        val mk = masterkey ?: run { emitVerdict("$mode ABORT — masterkey missing"); return }
        val s = runCatching { aidexDeriveSession(serialForCrypto, mk, value) }.getOrElse {
            emitVerdict("$mode INCONCLUSIVE — aidexDeriveSession failed: $it (blob/masterkey/serial mismatch or CRC8)"); return
        }
        sess = s
        sessionReady = true
        lastBootstrapSerial = serialForCrypto
        hs = Hs.DONE
        handler.removeCallbacks(sessionWatchdog)
        logi("SESSION READY (${if (target?.bondState == BluetoothDevice.BOND_BONDED) "BONDED" else "UNBONDED"}) serial=$serialForCrypto sess=${s.toHex()}")
        val cb = onSessionReady
        onSessionReady = null
        (cb ?: { logi("session ready but no phase queued") }).invoke()
    }

    private fun handshakeAuthFail(where: String) {
        if (mode == Mode.HANDSHAKE) {
            emitVerdict("BONDING REQUIRED — $where refused UNBONDED (status 0x05 GATT_INSUFFICIENT_AUTHENTICATION) — the current sensor needs a fresh/reset sensor (CGM.md §4.2)")
        } else {
            emitVerdict("$mode ABORT — $where returned 0x05 despite a bonded link (unexpected)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(label: String, build: () -> ByteArray, onResp: (AidexResponse) -> Unit) {
        val cipher = runCatching { build() }.getOrElse {
            emitVerdict("$mode ABORT — build '$label' failed: $it"); return
        }
        val g = gatt
        val f2 = f002Char
        if (g == null || f2 == null) { emitVerdict("$mode ABORT — no gatt/F002 to send '$label'"); return }
        pendingResponse = onResp
        lastCmdLabel = label
        handler.removeCallbacks(cmdTimeout)
        handler.postDelayed(cmdTimeout, CMD_TIMEOUT_MS)
        logi("CMD $label → F002 (${cipher.toHex()})")
        val res = runCatching {
            g.writeCharacteristic(f2, cipher, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }.getOrDefault(-99)
        logi("writeCharacteristic(F002 '$label') issued result=$res (0=SUCCESS)")
    }

    private fun onF002Response(value: ByteArray) {
        if (verdictEmitted) return
        val s = sess ?: return
        val v = iv ?: return
        val pt = runCatching { aidexDecryptFrame(s, v, value) }.getOrElse {
            logi("F002 NOTIFY decryptFrame failed: $it (ct=${value.toHex()})"); return
        }
        val resp = runCatching { aidexParseResponse(pt) }.getOrElse {
            logi("F002 NOTIFY parseResponse failed: $it (pt=${pt.toHex()})"); return
        }
        val h = pendingResponse
        pendingResponse = null
        handler.removeCallbacks(cmdTimeout)
        if (h != null) h(resp) else logi("unsolicited F002 response: $resp")
    }

    private val cmdTimeout = Runnable {
        if (!verdictEmitted && pendingResponse != null) {
            emitVerdict("$mode STALLED — no F002 reply to '$lastCmdLabel' within ${CMD_TIMEOUT_MS / 1000}s")
        }
    }

    private fun bringupSurvey() {
        sendCommand("deviceInfo(0x10)", { aidexCmdDeviceInfo(sess!!, iv!!) }) { r ->
            if (r is AidexResponse.DeviceInfo) { dvcInfoStr = fmtDeviceInfo(r.info); logi("deviceInfo: $dvcInfoStr") }
            else logi("deviceInfo: unexpected $r")
            sendCommand("getStartTime(0x21)", { aidexCmdGetStartTime(sess!!, iv!!) }) { r2 ->
                activated = isActivated(r2)
                startStr = fmtStart(r2)
                logi("startTime: $startStr → ${if (activated == true) "ACTIVATED" else "UNACTIVATED"}")
                sendCommand("getBroadcast(0x11)", { aidexCmdGetBroadcast(sess!!, iv!!) }) { r3 ->
                    glucStr = fmtCurrent(r3)
                    logi("current: $glucStr")
                    emitVerdict("BRINGUP OK — bonded; ACTIVATED=${if (activated == true) "yes" else "no"}; deviceInfo=[${dvcInfoStr ?: "n/a"}]; glucose=[${glucStr ?: "n/a"}]")
                }
            }
        }
    }

    private fun activateFlow() {
        sendCommand("getStartTime(0x21) [pre-check]", { aidexCmdGetStartTime(sess!!, iv!!) }) { r ->
            val already = isActivated(r)
            if (already && !activateForce) {
                emitVerdict("ACTIVATE REFUSED — sensor already ACTIVATED (startTime=${fmtStart(r)}); pass --ei force 1 to override")
                return@sendCommand
            }
            if (already) logi("ACTIVATE: already activated but force=1 — proceeding (resets the session clock)")
            runActivationSteps()
        }
    }

    private fun runActivationSteps() {
        val localStart = runCatching { buildLocalStartNow() }.getOrElse {
            emitVerdict("ACTIVATE ABORT — building LocalStartTime failed: $it"); return
        }
        logi("ACTIVATE step 1/5 — setNewSensor(0x20) with LocalStartTime=${localStart.toHex()}")
        sendCommand("setNewSensor(0x20)", { aidexCmdSetNewSensor(sess!!, iv!!, localStart) }) { r1 ->
            logi("setNewSensor reply: $r1 (expect Ack 0x120)")
            logi("ACTIVATE step 2/5 — setDynamicAdvMode(0x35 01)")
            sendCommand("setDynamicAdvMode(0x35)", { aidexCmdSetDynamicAdvMode(sess!!, iv!!) }) { r2 ->
                logi("setDynamicAdvMode reply: $r2 (expect Ack 0x135)")
                logi("ACTIVATE step 3/5 — setAutoUpdate(0x34 01)")
                sendCommand("setAutoUpdate(0x34)", { aidexCmdSetAutoUpdate(sess!!, iv!!) }) { r3 ->
                    logi("setAutoUpdate reply: $r3 (expect Ack 0x134)")
                    logi("ACTIVATE step 4/5 — getStartTime(0x21) to confirm activation")
                    sendCommand("getStartTime(0x21) [confirm]", { aidexCmdGetStartTime(sess!!, iv!!) }) { r4 ->
                        val ok = isActivated(r4)
                        activated = ok
                        logi("post-activation startTime: ${fmtStart(r4)} → ${if (ok) "ACTIVATED" else "STILL NOT ACTIVATED"}")
                        logi("ACTIVATE step 5/5 — getBroadcast(0x11)")
                        sendCommand("getBroadcast(0x11)", { aidexCmdGetBroadcast(sess!!, iv!!) }) { r5 ->
                            val gs = fmtCurrent(r5)
                            emitVerdict("ACTIVATE ${if (ok) "OK" else "INCONCLUSIVE"} — 0x20/0x35/0x34 sent; post startTime=[${fmtStart(r4)}]; glucose=[$gs]")
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildLocalStartNow(): ByteArray {
        val nowMs = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val cal = Calendar.getInstance(tz)
        val dstMs = if (tz.inDaylightTime(Date(nowMs))) tz.dstSavings else 0
        val rawOffsetMs = tz.getOffset(nowMs) - dstMs
        val tzQ = rawOffsetMs / (15 * 60 * 1000)
        val dstQ = dstMs / (15 * 60 * 1000)
        val y = cal.get(Calendar.YEAR); val mo = cal.get(Calendar.MONTH) + 1; val d = cal.get(Calendar.DAY_OF_MONTH)
        val h = cal.get(Calendar.HOUR_OF_DAY); val mi = cal.get(Calendar.MINUTE); val se = cal.get(Calendar.SECOND)
        logi("LocalStartTime now=$y-$mo-$d $h:$mi:$se tzQ=$tzQ dstQ=$dstQ")
        return aidexEncodeLocalStartTime(y, mo, d, h, mi, se, tzQ, dstQ)
    }

    @SuppressLint("MissingPermission")
    private fun startRealtime() {
        val g = gatt
        val f3 = f003Char
        if (g == null || f3 == null) {
            emitVerdict("REALTIME ABORT — F003 characteristic not found (gatt=${g != null} f003=${f3 != null})")
            return
        }
        runCatching { g.setCharacteristicNotification(f3, true) }
        logi("REALTIME — enabling F003 notify (private-bonded, §4.1). Awaiting realtime pushes; runs until PROBE_STOP. Lock the screen to test survival.")
        writeCccdEnable(f3)
    }

    private fun onF003Realtime(value: ByteArray) {
        val s = sess ?: return
        val v = iv ?: return
        val pt = runCatching { aidexDecryptFrame(s, v, value) }.getOrElse {
            logi("F003 decryptFrame failed: $it (ct=${value.toHex()})"); return
        }
        val rt = runCatching { aidexParseRealtime(pt) }.getOrElse {
            logi("F003 parseRealtime failed: $it (pt=${pt.toHex()})"); return
        }
        logi("F003 REALTIME — ${rt.glucoseMgdl} mg/dL trend=${rt.trendTenthsPerMin * 0.1}mg/dL/min minFromStart=${rt.minFromStart} warmup=${rt.warmup} valid=${rt.valid} type=${rt.readingType} isReal=${rt.isReal}")
    }

    private fun startHistory() {
        histLastId = null
        histStartHistory = null
        activationEpoch = null
        sendCommand("getStartTime(0x21)", { aidexCmdGetStartTime(sess!!, iv!!) }) { r ->
            if (r is AidexResponse.StartTime && isActivated(r)) {
                activationEpoch = r.time.epochSecs
                logi("history: activationEpoch=${r.time.epochSecs} (${fmtStart(r)})")
            } else {
                logi("history: sensor UNACTIVATED (${fmtStart(r)}) — ids will be logged without wall-clock timestamps")
            }
            sendCommand("getLastId(0x22)", { aidexCmdGetLastId(sess!!, iv!!) }) { r2 ->
                val lastId = (r2 as? AidexResponse.LastId)?.lastId
                if (lastId == null) { emitVerdict("HISTORY ABORT — getLastId returned $r2"); return@sendCommand }
                histLastId = lastId
                logi("history: lastId(absolute)=$lastId; paging from relId=1")
                requestHistoryPage(1)
            }
        }
    }

    private fun requestHistoryPage(relId: Int) {
        sendCommand("getHistory(0x23 relId=$relId)", { aidexCmdGetHistory(sess!!, iv!!, relId) }) { r ->
            if (r !is AidexResponse.History) { emitVerdict("HISTORY done/unexpected reply: $r"); return@sendCommand }
            val startId = r.startId
            val n = r.entries.size
            if (histStartHistory == null) histStartHistory = startId - 1
            for (e in r.entries) {
                val ts = activationEpoch?.let { it + e.recordId.toLong() * 60L }
                if (e.isReal) {
                    logi("HIST id=${e.recordId} bg=${e.glucoseMgdl}mg/dL warmup=${e.warmup} valid=${e.valid}${ts?.let { " ts=${it}s" } ?: ""}")
                }
            }
            val lastInBatch = startId + n - 1
            logi("history batch: startId=$startId n=$n lastInBatch=$lastInBatch (target lastId=$histLastId)")
            val done = n == 0 || lastInBatch >= (histLastId ?: 0)
            if (done) {
                emitVerdict("HISTORY OK — paged through to id=$lastInBatch of lastId=${histLastId}")
            } else {
                val nextRel = (startId + n) - (histStartHistory ?: (startId - 1))
                requestHistoryPage(nextRel)
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val tgt = target
            if (device == null || tgt == null || device.address != tgt.address) return

            val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
            val now = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            val reason = intent.getIntExtra("android.bluetooth.device.extra.REASON", -1)
            logi("BOND STATE ${bondName(prev)} → ${bondName(now)} reason=$reason (${unbondReasonName(reason)})")

            when (mode) {
                Mode.BOND -> when (now) {
                    BluetoothDevice.BOND_BONDED -> {
                        updateNotif("BONDED — proving §4.2 access")
                        logi("BOND_BONDED — proving access with an F002 read")
                        handler.post { readF002ForBond() }
                    }
                    BluetoothDevice.BOND_NONE -> if (prev == BluetoothDevice.BOND_BONDING) {
                        logi("BOND RESULT: BOND REFUSED (BONDING→NONE reason=$reason ${unbondReasonName(reason)}) — sensor bonded elsewhere / Pairing-not-supported (§4.2); reset from the official app (§8) first")
                    }
                }
                Mode.BRINGUP, Mode.ACTIVATE, Mode.REALTIME, Mode.HISTORY -> when (now) {
                    BluetoothDevice.BOND_BONDED -> {
                        logi("BONDED (Just-Works) — staged bring-up; proceeding to handshake")
                        if (awaitingBondThenHandshake) {
                            awaitingBondThenHandshake = false
                            handler.post { doHandshake() }
                        }
                    }
                    BluetoothDevice.BOND_NONE -> if (prev == BluetoothDevice.BOND_BONDING && !verdictEmitted) {
                        emitVerdict("$mode FAILED — the fresh sensor refused to bond (reason=$reason ${unbondReasonName(reason)}) — not Just-Works pairable or already bonded elsewhere")
                    }
                }
                else -> {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readF002ForBond() {
        val g = gatt
        val ch = f002Char
        if (g == null || ch == null) { logi("F002 read skipped — gatt=${g != null} f002=${ch != null}"); return }
        val ok = runCatching { g.readCharacteristic(ch) }.getOrDefault(false)
        logi("readCharacteristic(0xF002) issued=$ok")
    }

    private fun handshakeConversation() {
        sendCommand("deviceInfo(0x10)", { aidexCmdDeviceInfo(sess!!, iv!!) }) { r ->
            if (r is AidexResponse.DeviceInfo) { dvcInfoStr = fmtDeviceInfo(r.info); logi("deviceInfo: $dvcInfoStr") }
            sendCommand("getStartTime(0x21)", { aidexCmdGetStartTime(sess!!, iv!!) }) { r2 ->
                startStr = fmtStart(r2); logi("startTime: $startStr")
                sendCommand("getBroadcast(0x11)", { aidexCmdGetBroadcast(sess!!, iv!!) }) { r3 ->
                    glucStr = fmtCurrent(r3); logi("current: $glucStr")
                    emitVerdict("UNBONDED HANDSHAKE OK — session derived; deviceInfo=[${dvcInfoStr ?: "n/a"}] startTime=[${startStr ?: "n/a"}] glucose=[${glucStr ?: "n/a"}] — the F001/F002 serial-AES handshake works WITHOUT an Android bond")
                }
            }
        }
    }

    private fun isActivated(r: AidexResponse): Boolean =
        r is AidexResponse.StartTime && r.time.year in 2000..2100 && r.time.epochSecs > 0

    private fun fmtDeviceInfo(d: AidexDeviceInfo): String =
        "fw=${d.firmware} model1=${d.model1} model2=${d.model2} name='${d.name}' life=${d.lifeDays}d"

    private fun fmtStart(r: AidexResponse): String = if (r is AidexResponse.StartTime) {
        val t = r.time
        "%04d-%02d-%02d %02d:%02d:%02d tz=%d dst=%d epoch=%d".format(
            t.year, t.month, t.day, t.hour, t.minute, t.second, t.tzQuarterHours, t.dstQuarterHours, t.epochSecs,
        )
    } else "none/unactivated ($r)"

    private fun fmtCurrent(r: AidexResponse): String = if (r is AidexResponse.Current) {
        val g = r.reading
        "${g.glucoseMgdl} mg/dL trend=${g.trendTenthsPerMin * 0.1}mg/dL/min valid=${g.valid} minFromStart=${g.minFromStart}"
    } else "none ($r)"

    private val sessionWatchdog = Runnable {
        if (!sessionReady && !verdictEmitted) {
            emitVerdict("$mode INCONCLUSIVE — no session within ${SESSION_WATCHDOG_MS / 1000}s (stalled at phase=$hs); check the sensor is advertising and not held elsewhere")
        }
    }

    private fun emitVerdict(line: String) {
        if (verdictEmitted) return
        verdictEmitted = true
        handler.removeCallbacks(sessionWatchdog)
        handler.removeCallbacks(cmdTimeout)
        updateNotif(line.take(80))
        logi(line)
    }

    private val heartbeat = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (stopping) return
            val g = gatt ?: run { logi("HEARTBEAT skipped — no live GATT"); return }
            val issued = runCatching { g.readRemoteRssi() }.getOrDefault(false)
            if (!issued) logi("HEARTBEAT readRemoteRssi() rejected (link busy/down?)")
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun startHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun stopHeartbeat() = handler.removeCallbacks(heartbeat)

    private fun scheduleReconnect() {
        if (stopping) return
        if (reconnectAttempts >= MAX_RECONNECT) {
            logi("RECONNECT giving up after $MAX_RECONNECT attempts")
            updateNotif("Link lost — gave up reconnecting")
            return
        }
        reconnectAttempts++
        logi("RECONNECT attempt $reconnectAttempts/$MAX_RECONNECT — re-scanning in ${RECONNECT_DELAY_MS}ms")
        handler.postDelayed({ if (!stopping) startScan() }, RECONNECT_DELAY_MS)
    }

    private fun buildNotif(text: String): Notification =
        Notification.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("AiDEX probe / bring-up")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    private fun updateNotif(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text)) }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, "AiDEX probe (debug)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Debug-only GATT probe / fresh-sensor bring-up."
                setShowBadge(false)
            },
        )
    }

    private fun logi(msg: String) {
        val t = if (t0Ms == 0L) 0L else SystemClock.elapsedRealtime() - t0Ms
        Log.i(TAG, "[t+${t}ms] $msg")
    }

    companion object {
        const val TAG = "AIDEXPROBE"
        const val ACTION_PROBE_START = "com.t1dm.app.PROBE_START"
        const val ACTION_PROBE_STOP = "com.t1dm.app.PROBE_STOP"
        const val ACTION_PROBE_BOND = "com.t1dm.app.PROBE_BOND"
        const val ACTION_PROBE_HANDSHAKE = "com.t1dm.app.PROBE_HANDSHAKE"
        const val ACTION_PROBE_BRINGUP = "com.t1dm.app.PROBE_BRINGUP"
        const val ACTION_PROBE_ACTIVATE = "com.t1dm.app.PROBE_ACTIVATE"
        const val ACTION_PROBE_REALTIME = "com.t1dm.app.PROBE_REALTIME"
        const val ACTION_PROBE_HISTORY = "com.t1dm.app.PROBE_HISTORY"

        /** The old sensor; the staged modes refuse this serial. */
        private const val SERIAL = "22222C74D9"
        private const val OLD_SERIAL = "22222C74D9"

        private const val CH_ID = "t1dm.debug.aidexprobe"
        private const val NOTIF_ID = 4210
        private const val HEARTBEAT_MS = 30_000L
        private const val SCAN_HEARTBEAT_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT = 6
        private const val SESSION_WATCHDOG_MS = 30_000L
        private const val CMD_TIMEOUT_MS = 12_000L

        private const val MANUFACTURER_ID = 0x0059
        private val CGM_SERVICE_PARCEL_UUID =
            ParcelUuid(UUID.fromString("0000181F-0000-1000-8000-00805F9B34FB"))
        private val F001_UUID = UUID.fromString("0000F001-0000-1000-8000-00805F9B34FB")
        private val F002_UUID = UUID.fromString("0000F002-0000-1000-8000-00805F9B34FB")
        private val F003_UUID = UUID.fromString("0000F003-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private fun scanFilters(): List<ScanFilter> = listOf(
            ScanFilter.Builder()
                .setManufacturerData(MANUFACTURER_ID, byteArrayOf())
                .setServiceUuid(CGM_SERVICE_PARCEL_UUID)
                .build(),
        )

        private fun scanSettings(): ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(true)
            .setReportDelay(0L)
            .build()

        private fun stateName(state: Int): String = when (state) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "UNKNOWN($state)"
        }

        private fun bondName(state: Int): String = when (state) {
            BluetoothDevice.BOND_NONE -> "BOND_NONE"
            BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
            BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
            else -> "BOND($state)"
        }

        private fun unbondReasonName(reason: Int): String = when (reason) {
            -1 -> "none"; 0 -> "success"; 1 -> "AUTH_FAILED"; 2 -> "AUTH_REJECTED"; 3 -> "AUTH_CANCELED"
            4 -> "REMOTE_DEVICE_DOWN"; 5 -> "DISCOVERY_IN_PROGRESS"; 6 -> "AUTH_TIMEOUT"
            7 -> "REPEATED_ATTEMPTS"; 8 -> "REMOTE_AUTH_CANCELED"; 9 -> "REMOVED"; else -> "reason=$reason"
        }

        private fun gattStatusName(status: Int): String = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
            5 -> "GATT_INSUFFICIENT_AUTHENTICATION"
            8 -> "GATT_CONN_TIMEOUT"
            15 -> "GATT_INSUFFICIENT_ENCRYPTION"
            19 -> "GATT_CONN_TERMINATE_PEER_USER"
            22 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
            34 -> "GATT_CONN_LMP_TIMEOUT"
            62 -> "GATT_CONN_FAIL_ESTABLISH"
            133 -> "GATT_ERROR/133 (generic — often transient)"
            137 -> "GATT_AUTH_FAIL"
            else -> "status=$status"
        }

        private fun propsString(props: Int): String = buildList {
            if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
            if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
            if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
            if (props and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("SIGNED_WRITE")
            if (props and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
            if (props and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) add("EXT")
        }.joinToString(",")

        private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
    }
}
