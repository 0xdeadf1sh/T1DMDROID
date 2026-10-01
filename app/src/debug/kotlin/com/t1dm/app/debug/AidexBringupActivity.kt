package com.t1dm.app.debug

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

/** F001 CCCD write pairs the link, its 0x05 the trigger (CGM.md §4); createBond is the fallback. */
class AidexBringupActivity : Activity() {

    private enum class Option { B, A }
    private enum class Hs { IDLE, CCCD_F001, CCCD_F002, ASKKEY, MASTERKEY, BLOB, DONE }

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnActivate: Button
    private lateinit var btnRealtime: Button
    private lateinit var btnHistory: Button

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val ui = Handler(android.os.Looper.getMainLooper())

    private var t0Ms: Long = 0L
    private var serial: String = ""
    private var refused: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var bondReceiverRegistered = false

    @Volatile private var option: Option = Option.B
    @Volatile private var optionBFailed: Boolean = false
    @Volatile private var scanning = false
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var target: BluetoothDevice? = null
    @Volatile private var connectWasBonded = false
    @Volatile private var bondingWindow = false
    @Volatile private var createBondAttempted = false
    @Volatile private var keyMissingRecoveries = 0

    @Volatile private var f001Char: BluetoothGattCharacteristic? = null
    @Volatile private var f002Char: BluetoothGattCharacteristic? = null
    @Volatile private var f003Char: BluetoothGattCharacteristic? = null
    @Volatile private var hs: Hs = Hs.IDLE
    @Volatile private var iv: ByteArray? = null
    @Volatile private var masterkey: ByteArray? = null
    @Volatile private var sess: ByteArray? = null
    @Volatile private var sessionHeld = false
    @Volatile private var activated: Boolean? = null

    @Volatile private var pendingResponse: ((AidexResponse) -> Unit)? = null
    @Volatile private var lastCmdLabel = ""

    private var dvcInfoStr: String? = null
    private var startStr: String? = null
    private var glucStr: String? = null
    private var histLastId: Int? = null
    private var histStartHistory: Int? = null
    private var activationEpoch: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        t0Ms = SystemClock.elapsedRealtime()

        val s = intent?.getStringExtra("serial")
        when {
            s.isNullOrBlank() -> { refuse("REFUSED: no --es serial given"); return }
            s.equals(OLD_SERIAL, ignoreCase = true) -> { refuse("REFUSED: serial $OLD_SERIAL is the old sensor"); return }
        }
        serial = s!!
        setStatus("Bring-up: $serial\nSTARTING — Option B (unbonded, no createBond)")
        logi("BRINGUP ACTIVITY start serial=$serial — Option B first (unbonded handshake), auto-fallback to Option A on 0x05/drop")

        thread = HandlerThread("aidex-bringup").apply { start() }
        handler = Handler(thread.looper)
        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        bondReceiverRegistered = true

        btnActivate.setOnClickListener { onActivateClicked() }
        btnRealtime.setOnClickListener { onRealtimeClicked() }
        btnHistory.setOnClickListener { onHistoryClicked() }

        ensurePermissionsThenStart()
    }

    override fun onDestroy() {
        if (::handler.isInitialized) runCatching { handler.removeCallbacksAndMessages(null) }
        stopScan()
        closeGatt()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (bondReceiverRegistered) runCatching { unregisterReceiver(bondStateReceiver) }
        if (::thread.isInitialized) thread.quitSafely()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101014.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 17f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD); text = "Starting…"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        val bp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        btnActivate = Button(this).apply { text = "Activate"; isEnabled = false }
        btnRealtime = Button(this).apply { text = "Realtime"; isEnabled = false }
        btnHistory = Button(this).apply { text = "History"; isEnabled = false }
        row.addView(btnActivate, bp); row.addView(btnRealtime, bp); row.addView(btnHistory, bp)

        logView = TextView(this).apply {
            setTextColor(0xFF9CE39C.toInt()); textSize = 11f
            setTypeface(Typeface.MONOSPACE); setTextIsSelectable(true)
        }
        scrollView = ScrollView(this).apply { addView(logView) }

        root.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun setStatus(s: String) = ui.post { statusView.text = s }

    private fun setButtons(activate: Boolean, realtime: Boolean, history: Boolean) = ui.post {
        btnActivate.isEnabled = activate; btnRealtime.isEnabled = realtime; btnHistory.isEnabled = history
    }

    private fun logi(msg: String) {
        val line = "[t+${if (t0Ms == 0L) 0 else SystemClock.elapsedRealtime() - t0Ms}ms] $msg"
        Log.i(TAG, line)
        ui.post { logView.append(line + "\n"); scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) } }
    }

    private fun refuse(why: String) {
        refused = true
        logi(why)
        setStatus("REFUSED\n$why")
        ui.postDelayed({ finish() }, 6000)
    }

    private fun fail(reason: String) {
        setButtons(false, false, false)
        setStatus("FAILED\n$reason")
        logi("FAILURE — $reason")
    }

    private fun ensurePermissionsThenStart() {
        val need = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) handler.post { startScan() } else requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) handler.post { startScan() }
        else refuse("REFUSED: BLUETOOTH_SCAN/CONNECT not granted")
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (refused) return
        val scanner = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner }.getOrNull()
        if (scanner == null) { fail("no BluetoothLeScanner (adapter off?)"); return }
        scanning = true
        setStatus("Bring-up: $serial (Option $option)\nSCANNING…")
        logi("SCAN start (SCAN_MODE_LOW_LATENCY, mfr 0x0059 AND service 0x181F) [serial-locked to *$serial] option=$option")
        runCatching { scanner.startScan(scanFilters(), scanSettings(), scanCallback) }.onFailure { logi("SCAN start threw: $it") }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanning = false
        val scanner = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner }.getOrNull() ?: return
        runCatching { scanner.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
            if (name == null || !name.contains(serial, ignoreCase = true)) return
            logi("SCAN match: name=$name addr=${device.address} rssi=${result.rssi}dBm bondState=${bondName(device.bondState)}")
            scanning = false
            stopScan()
            target = device
            handler.post { beginConnect() }
        }

        override fun onScanFailed(errorCode: Int) { logi("SCAN FAILED errorCode=$errorCode"); scanning = false }
    }

    private fun beginConnect() {
        val dev = target ?: return
        connectWasBonded = dev.bondState == BluetoothDevice.BOND_BONDED
        if (option == Option.A) {
            setStatus("Option A — connecting (was bonded=$connectWasBonded)")
            logi("Option A: ~${PRECONNECT_DELAY_A_MS}ms pre-connect delay, connectWasBonded=$connectWasBonded")
            handler.postDelayed({ doConnect() }, PRECONNECT_DELAY_A_MS)
        } else {
            setStatus("Option B — connecting (unbonded)")
            doConnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun doConnect() {
        val dev = target ?: return
        logi("connectGatt(autoConnect=false, TRANSPORT_LE) option=$option connectWasBonded=$connectWasBonded → ${dev.address}")
        gatt = runCatching { dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }
            .getOrElse { logi("connectGatt threw: $it"); null }
        if (gatt == null) { fail("connectGatt returned null"); return }
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val connectTimeout = Runnable {
        if (!sessionHeld && hs == Hs.IDLE && !bondingWindow) {
            logi("connect timeout after ${CONNECT_TIMEOUT_MS / 1000}s (no services discovered)")
            if (option == Option.B && !optionBFailed) fallbackToOptionA("connect timeout") else fail("connect timeout")
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            logi("onConnectionStateChange newState=${stateName(newState)} status=$status (${gattStatusName(status)}) option=$option bondingWindow=$bondingWindow")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handler.removeCallbacks(connectTimeout)
                    onConnected(g)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    handler.removeCallbacks(connectTimeout)
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    onDisconnected(status)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            logi("onServicesDiscovered status=$status (${gattStatusName(status)}) services=${g.services.size}")
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
            doHandshake()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (characteristic.uuid == F002_UUID && hs == Hs.BLOB) onBlobRead(value, status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == F001_UUID && hs == Hs.MASTERKEY) onAskKeyWritten(status)
            else if (characteristic.uuid == F002_UUID && status != BluetoothGatt.GATT_SUCCESS) {
                logi("onCharacteristicWrite F002 (cmd) status=$status (${gattStatusName(status)})")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                F001_UUID -> handler.post { onMasterkeyNotify(value) }
                F002_UUID -> handler.post { onF002Response(value) }
                F003_UUID -> handler.post { onF003Realtime(value) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val chUuid = descriptor.characteristic?.uuid
            logi("onDescriptorWrite ${descriptor.uuid} (char $chUuid) status=$status (${gattStatusName(status)})")
            when {
                chUuid == F003_UUID -> if (status == 5) logi("F003 CCCD 0x05 — F003 is private-bonded (§4.1)") else logi("F003 realtime notify enabled")
                hs == Hs.CCCD_F001 || hs == Hs.CCCD_F002 -> handler.post { onCccdWritten(status) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onConnected(g: BluetoothGatt) {
        if (option == Option.B) {
            setStatus("Option B — CONNECTED, discovering (no bond, no MTU/priority)")
            logi("Option B CONNECTED: NOT createBond, NOT requestMtu, NOT requestConnectionPriority → discoverServices()")
            handler.post { runCatching { g.discoverServices() } }
            return
        }
        val bs = target?.bondState ?: BluetoothDevice.BOND_NONE
        logi("Option A CONNECTED bondState=${bondName(bs)}")
        if (bs == BluetoothDevice.BOND_BONDED) {
            logi("Option A: already BONDED on connect → discoverServices() (no createBond). (Watch for KEY_MISSING: status 22 pre-proof.)")
            setStatus("Option A — bonded, discovering")
            handler.post { runCatching { g.discoverServices() } }
        } else {
            bondingWindow = true
            setStatus("Option A — not bonded; ${BOND_RECHECK_MS}ms grace before createBond")
            logi("Option A: not bonded — scheduling ${BOND_RECHECK_MS}ms recheck; NOT creating bond yet (let the stack possibly auto-bond). NO GATT op while BONDING.")
            handler.postDelayed(bondRecheck, BOND_RECHECK_MS)
            handler.postDelayed(bondWatchdog, BOND_WATCHDOG_MS)
        }
    }

    private fun onDisconnected(status: Int) {
        if (sessionHeld) {
            setStatus("LINK DOWN after session held\nstatus=$status (${gattStatusName(status)})")
            logi("LINK DOWN while holding session — status=$status. If screen-off, the held link did not survive; a dropped bond will KEY_MISSING on reconnect.")
            setButtons(false, false, false)
            return
        }
        if (option == Option.A && bondingWindow) {
            logi("Option A: disconnect during the bonding window — IGNORED (no reconnect), per DashMax; awaiting the BOND_STATE event")
            return
        }
        if (option == Option.A && connectWasBonded && status == 22 && keyMissingRecoveries < MAX_KEY_MISSING_RECOVERIES) {
            keyMissingRecovery()
            return
        }
        if (option == Option.B && !optionBFailed) {
            fallbackToOptionA("link dropped during Option B steps 3-6 (status=$status ${gattStatusName(status)})")
            return
        }
        fail("link dropped pre-session (option=$option status=$status ${gattStatusName(status)})")
    }

    @SuppressLint("MissingPermission")
    private val bondRecheck = Runnable {
        if (sessionHeld) return@Runnable
        val dev = target ?: return@Runnable
        when {
            dev.bondState == BluetoothDevice.BOND_BONDED -> {
                bondingWindow = false
                handler.removeCallbacks(bondWatchdog)
                logi("Option A recheck: stabilized to BONDED (stack auto-bonded) → discoverServices() (no createBond)")
                gatt?.let { g -> handler.post { runCatching { g.discoverServices() } } }
            }
            !createBondAttempted -> {
                createBondAttempted = true
                logi("Option A recheck: still not bonded → device.createBond() ONCE (NOT resolving bonded on its return; awaiting BOND_STATE event)")
                val ok = runCatching { dev.createBond() }.getOrDefault(false)
                logi("createBond() returned $ok")
                if (!ok) { bondingWindow = false; handler.removeCallbacks(bondWatchdog); fail("Option A: createBond() refused to start (returned false) — no retry this session") }
            }
            else -> logi("Option A recheck: createBond already attempted — no retry; awaiting BOND_STATE event")
        }
    }

    private val bondWatchdog = Runnable {
        if (bondingWindow && !sessionHeld) { bondingWindow = false; fail("Option A: no BOND_BONDED within ${BOND_WATCHDOG_MS / 1000}s") }
    }

    /** Bonded is this event, not a callback; no GATT op runs while BOND_BONDING. */
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
            logi("BOND STATE ${bondName(prev)} → ${bondName(now)} reason=$reason (${unbondReasonName(reason)}) [option=$option]")
            if (option != Option.A) return
            when (now) {
                BluetoothDevice.BOND_BONDED -> if (bondingWindow) {
                    bondingWindow = false
                    handler.removeCallbacks(bondWatchdog)
                    handler.removeCallbacks(bondRecheck)
                    setStatus("Option A — BONDED, discovering")
                    logi("Option A: BOND_BONDED via receiver event → discoverServices(). (Necessary-not-sufficient; real proof is the handshake read.)")
                    gatt?.let { g -> handler.post { runCatching { g.discoverServices() } } }
                }
                BluetoothDevice.BOND_NONE -> if (prev == BluetoothDevice.BOND_BONDING && !sessionHeld) {
                    bondingWindow = false
                    fail("Option A bond failed (BONDING→NONE reason=$reason ${unbondReasonName(reason)})")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun keyMissingRecovery() {
        keyMissingRecoveries++
        logi("KEY_MISSING suspected — connected to a BONDED device and dropped with status 22 before any encrypted proof. Recovery #$keyMissingRecoveries/$MAX_KEY_MISSING_RECOVERIES")
        setStatus("KEY_MISSING — removing stale bond, retrying")
        val removed = reflectRemoveBond(target)
        logi("removeBond() (reflection) → $removed; waiting ${KEY_MISSING_WAIT_MS}ms then a FRESH connect + re-bond (GATT cache NOT cleared)")
        resetForReconnect()
        handler.postDelayed({ if (!refused) startScan() }, KEY_MISSING_WAIT_MS)
    }

    private fun reflectRemoveBond(device: BluetoothDevice?): Boolean {
        device ?: return false
        return runCatching {
            val m = device.javaClass.getMethod("removeBond")
            (m.invoke(device) as? Boolean) ?: false
        }.getOrElse {
            logi("removeBond() reflection blocked/failed: $it (HyperOS hidden-API enforcement?)")
            false
        }
    }

    private fun fallbackToOptionA(reason: String) {
        if (optionBFailed) return
        optionBFailed = true
        option = Option.A
        logi("OPTION B → $reason, trying A")
        setStatus("OPTION B → $reason\nfalling back to Option A (bond)")
        resetForReconnect()
        handler.postDelayed({ if (!refused) startScan() }, FALLBACK_DELAY_MS)
    }

    private fun resetForReconnect() {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(bondRecheck)
        handler.removeCallbacks(bondWatchdog)
        handler.removeCallbacks(cmdTimeout)
        closeGatt()
        hs = Hs.IDLE
        iv = null; masterkey = null; sess = null
        f001Char = null; f002Char = null; f003Char = null
        pendingResponse = null
        bondingWindow = false
        createBondAttempted = false
    }

    /** CGM.md §4.4. */
    @SuppressLint("MissingPermission")
    private fun doHandshake() {
        val g = gatt; val f1 = f001Char; val f2 = f002Char
        if (g == null || f1 == null || f2 == null) { fail("0x181F/F001/F002 not found (gatt=${g != null} f001=${f1 != null} f002=${f2 != null})"); return }
        runCatching { g.setCharacteristicNotification(f1, true) }
        runCatching { g.setCharacteristicNotification(f2, true) }
        setStatus("Option $option — handshaking (CCCD F001)")
        logi("handshake — enabling F001 notify (CCCD 0x2902); a 0x05 here ⇒ this unit mandates a bond")
        hs = Hs.CCCD_F001
        writeCccdEnable(f1)
    }

    @SuppressLint("MissingPermission")
    private fun writeCccdEnable(ch: BluetoothGattCharacteristic) {
        val g = gatt ?: return
        val cccd = ch.getDescriptor(CCCD_UUID) ?: run { fail("CCCD 0x2902 absent on ${ch.uuid}"); return }
        val res = runCatching { g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }.getOrDefault(-99)
        logi("writeDescriptor CCCD on ${ch.uuid} issued result=$res (0=SUCCESS)")
    }

    private fun onCccdWritten(status: Int) {
        if (status == 5) { on05OrFallback("F001/F002 CCCD"); return }
        if (status != BluetoothGatt.GATT_SUCCESS) { logi("CCCD write failed status=$status (${gattStatusName(status)})"); return }
        when (hs) {
            Hs.CCCD_F001 -> { logi("handshake — enabling F002 notify"); hs = Hs.CCCD_F002; f002Char?.let { writeCccdEnable(it) } }
            Hs.CCCD_F002 -> { logi("handshake — both CCCDs enabled; writing askKey to F001"); hs = Hs.ASKKEY; writeAskKey() }
            else -> logi("onCccdWritten unexpected phase=$hs")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeAskKey() {
        val g = gatt ?: return; val f1 = f001Char ?: return
        val ak = runCatching { aidexAskkey(serial) }.getOrElse { fail("aidexAskkey failed: $it"); return }
        iv = runCatching { aidexIv(serial) }.getOrElse { fail("aidexIv failed: $it"); return }
        logi("askKey=${ak.toHex()} iv=${iv?.toHex()} — writeCharacteristic(F001 WRITE_TYPE_DEFAULT)")
        hs = Hs.MASTERKEY
        val res = runCatching { g.writeCharacteristic(f1, ak, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }.getOrDefault(-99)
        logi("writeCharacteristic(F001 askKey) issued result=$res (0=SUCCESS)")
    }

    private fun onAskKeyWritten(status: Int) {
        logi("onCharacteristicWrite F001 (askKey) status=$status (${gattStatusName(status)})")
        if (status == 5) { on05OrFallback("F001 askKey write"); return }
        if (status != BluetoothGatt.GATT_SUCCESS) { fail("askKey write failed status=$status (${gattStatusName(status)})"); return }
        logi("askKey written — awaiting masterkey NOTIFY on F001")
    }

    @SuppressLint("MissingPermission")
    private fun onMasterkeyNotify(value: ByteArray) {
        if (hs != Hs.MASTERKEY) return
        logi("F001 NOTIFY masterkey len=${value.size} bytes=${value.toHex()}")
        masterkey = value.copyOf()
        val g = gatt; val f2 = f002Char
        if (g == null || f2 == null) { fail("no gatt/F002 for blob read"); return }
        hs = Hs.BLOB
        logi("handshake — reading F002 session blob")
        val ok = runCatching { g.readCharacteristic(f2) }.getOrDefault(false)
        logi("readCharacteristic(F002 blob) issued=$ok")
    }

    private fun onBlobRead(value: ByteArray, status: Int) {
        logi("F002 READ (blob) status=$status (${gattStatusName(status)}) len=${value.size} bytes=${value.toHex()}")
        if (status == 5) { on05OrFallback("F002 blob read"); return }
        if (status != BluetoothGatt.GATT_SUCCESS) { fail("blob read failed status=$status (${gattStatusName(status)})"); return }
        val mk = masterkey ?: return
        val s = runCatching { aidexDeriveSession(serial, mk, value) }.getOrElse { fail("aidexDeriveSession failed: $it"); return }
        sess = s
        hs = Hs.DONE
        logi("SESSION DERIVED (option=$option) — sess=${s.toHex()}; read-only survey")
        survey()
    }

    private fun on05OrFallback(where: String) {
        if (option == Option.B && !optionBFailed) fallbackToOptionA("$where returned 0x05 (Insufficient Auth)")
        else fail("$where returned 0x05 (Insufficient Authentication) despite bonded link")
    }

    private fun survey() {
        sendCommand("deviceInfo(0x10)", { aidexCmdDeviceInfo(sess!!, iv!!) }) { r ->
            if (r is AidexResponse.DeviceInfo) { dvcInfoStr = fmtDeviceInfo(r.info); logi("deviceInfo: $dvcInfoStr") } else logi("deviceInfo unexpected: $r")
            sendCommand("getStartTime(0x21)", { aidexCmdGetStartTime(sess!!, iv!!) }) { r2 ->
                activated = isActivated(r2); startStr = fmtStart(r2)
                logi("startTime: $startStr → ${if (activated == true) "ACTIVATED" else "UNACTIVATED"}")
                sendCommand("getBroadcast(0x11)", { aidexCmdGetBroadcast(sess!!, iv!!) }) { r3 ->
                    glucStr = fmtCurrent(r3)
                    logi("current: $glucStr")
                    holdSession()
                }
            }
        }
    }

    private fun holdSession() {
        sessionHeld = true
        val act = activated == true
        val verb = if (option == Option.B) "unbonded handshake worked" else "bonded+handshake"
        logi("OPTION $option OK — $verb; ACTIVATED=${if (act) "yes" else "no"}; glucose=[${glucStr ?: "n/a"}]")
        setStatus("OPTION $option OK — SESSION HELD\nACTIVATED=${if (act) "yes" else "no"}\ndeviceInfo: ${dvcInfoStr ?: "n/a"}\nstartTime: ${startStr ?: "n/a"}\nglucose: ${glucStr ?: "n/a"}")
        setButtons(activate = !act, realtime = true, history = true)
    }

    private fun onActivateClicked() { if (!sessionHeld) return; setButtons(false, false, false); setStatus("ACTIVATING…"); handler.post { runActivation() } }
    private fun onRealtimeClicked() { if (!sessionHeld) return; setButtons(activated != true, false, true); acquireWakeLock(); handler.post { startRealtime() } }
    private fun onHistoryClicked() { if (!sessionHeld) return; setButtons(false, false, false); setStatus("HISTORY…"); handler.post { startHistory() } }

    /** Destructive. */
    private fun runActivation() {
        val localStart = runCatching { buildLocalStartNow() }.getOrElse { logi("ACTIVATE ABORT — LocalStartTime build failed: $it"); reEnableAfterOp(); return }
        logi("ACTIVATE 1/5 — setNewSensor(0x20) localStart=${localStart.toHex()}")
        sendCommand("setNewSensor(0x20)", { aidexCmdSetNewSensor(sess!!, iv!!, localStart) }) { r1 ->
            logi("setNewSensor reply: $r1 (expect Ack 0x120)")
            logi("ACTIVATE 2/5 — setDynamicAdvMode(0x35 01)")
            sendCommand("setDynamicAdvMode(0x35)", { aidexCmdSetDynamicAdvMode(sess!!, iv!!) }) { r2 ->
                logi("setDynamicAdvMode reply: $r2 (expect Ack 0x135)")
                logi("ACTIVATE 3/5 — setAutoUpdate(0x34 01)")
                sendCommand("setAutoUpdate(0x34)", { aidexCmdSetAutoUpdate(sess!!, iv!!) }) { r3 ->
                    logi("setAutoUpdate reply: $r3 (expect Ack 0x134)")
                    logi("ACTIVATE 4/5 — getStartTime(0x21) confirm")
                    sendCommand("getStartTime(0x21) confirm", { aidexCmdGetStartTime(sess!!, iv!!) }) { r4 ->
                        val ok = isActivated(r4); activated = ok; startStr = fmtStart(r4)
                        logi("post-activation startTime: $startStr → ${if (ok) "ACTIVATED" else "STILL NOT ACTIVATED"}")
                        logi("ACTIVATE 5/5 — getBroadcast(0x11)")
                        sendCommand("getBroadcast(0x11)", { aidexCmdGetBroadcast(sess!!, iv!!) }) { r5 ->
                            glucStr = fmtCurrent(r5)
                            setStatus("ACTIVATE ${if (ok) "OK" else "INCONCLUSIVE"} — ACTIVATED=${if (ok) "yes" else "no"}\nstartTime: $startStr\nglucose: $glucStr")
                            logi("ACTIVATE ${if (ok) "OK" else "INCONCLUSIVE"} — 0x20/0x35/0x34 sent; startTime=[$startStr]; glucose=[$glucStr]")
                            reEnableAfterOp()
                        }
                    }
                }
            }
        }
    }

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
        val g = gatt; val f3 = f003Char
        if (g == null || f3 == null) { setStatus("REALTIME ABORT — no F003"); logi("REALTIME ABORT — F003 not found (F003 realtime is private-bonded §4.1; may need Option A)"); reEnableAfterOp(); return }
        runCatching { g.setCharacteristicNotification(f3, true) }
        setStatus("REALTIME — F003 notify on\n(lock the phone to test survival)")
        logi("REALTIME — enabling F003 notify (private-bonded §4.1). If Option B (unbonded), F003 may 0x05; lock the screen to watch survival.")
        writeCccdEnable(f3)
    }

    private fun onF003Realtime(value: ByteArray) {
        val s = sess ?: return; val v = iv ?: return
        val pt = runCatching { aidexDecryptFrame(s, v, value) }.getOrElse { logi("F003 decrypt failed: $it (ct=${value.toHex()})"); return }
        val rt = runCatching { aidexParseRealtime(pt) }.getOrElse { logi("F003 parseRealtime failed: $it (pt=${pt.toHex()})"); return }
        logi("F003 REALTIME — ${rt.glucoseMgdl} mg/dL trend=${rt.trendTenthsPerMin * 0.1}mg/dL/min minFromStart=${rt.minFromStart} warmup=${rt.warmup} valid=${rt.valid} type=${rt.readingType}")
    }

    private fun startHistory() {
        histLastId = null; histStartHistory = null; activationEpoch = null
        sendCommand("getStartTime(0x21)", { aidexCmdGetStartTime(sess!!, iv!!) }) { r ->
            if (r is AidexResponse.StartTime && isActivated(r)) { activationEpoch = r.time.epochSecs; logi("history: activationEpoch=${r.time.epochSecs}") }
            else logi("history: UNACTIVATED — ids logged without wall-clock ts")
            sendCommand("getLastId(0x22)", { aidexCmdGetLastId(sess!!, iv!!) }) { r2 ->
                val lastId = (r2 as? AidexResponse.LastId)?.lastId
                if (lastId == null) { setStatus("HISTORY ABORT — getLastId=$r2"); logi("HISTORY ABORT — getLastId=$r2"); reEnableAfterOp(); return@sendCommand }
                histLastId = lastId
                logi("history: lastId(absolute)=$lastId; paging from relId=1")
                requestHistoryPage(1)
            }
        }
    }

    private fun requestHistoryPage(relId: Int) {
        sendCommand("getHistory(0x23 relId=$relId)", { aidexCmdGetHistory(sess!!, iv!!, relId) }) { r ->
            if (r !is AidexResponse.History) { setStatus("HISTORY done/unexpected"); logi("HISTORY done/unexpected: $r"); reEnableAfterOp(); return@sendCommand }
            val startId = r.startId; val n = r.entries.size
            if (histStartHistory == null) histStartHistory = startId - 1
            for (e in r.entries) if (e.isReal) {
                val ts = activationEpoch?.let { it + e.recordId.toLong() * 60L }
                logi("HIST id=${e.recordId} bg=${e.glucoseMgdl}mg/dL warmup=${e.warmup} valid=${e.valid}${ts?.let { " ts=${it}s" } ?: ""}")
            }
            val lastInBatch = startId + n - 1
            logi("history batch: startId=$startId n=$n lastInBatch=$lastInBatch (target=$histLastId)")
            if (n == 0 || lastInBatch >= (histLastId ?: 0)) {
                setStatus("HISTORY OK — through id=$lastInBatch / ${histLastId}")
                logi("HISTORY OK — paged through id=$lastInBatch of lastId=$histLastId")
                reEnableAfterOp()
            } else requestHistoryPage((startId + n) - (histStartHistory ?: (startId - 1)))
        }
    }

    private fun reEnableAfterOp() = setButtons(activate = activated != true, realtime = true, history = true)

    @SuppressLint("MissingPermission")
    private fun sendCommand(label: String, build: () -> ByteArray, onResp: (AidexResponse) -> Unit) {
        val cipher = runCatching { build() }.getOrElse { logi("ABORT — build '$label' failed: $it"); reEnableAfterOp(); return }
        val g = gatt; val f2 = f002Char
        if (g == null || f2 == null) { logi("ABORT — no gatt/F002 for '$label'"); return }
        pendingResponse = onResp
        lastCmdLabel = label
        handler.removeCallbacks(cmdTimeout)
        handler.postDelayed(cmdTimeout, CMD_TIMEOUT_MS)
        logi("CMD $label → F002 (${cipher.toHex()})")
        val res = runCatching { g.writeCharacteristic(f2, cipher, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }.getOrDefault(-99)
        logi("writeCharacteristic(F002 '$label') issued result=$res (0=SUCCESS)")
    }

    private fun onF002Response(value: ByteArray) {
        val s = sess ?: return; val v = iv ?: return
        val pt = runCatching { aidexDecryptFrame(s, v, value) }.getOrElse { logi("F002 decrypt failed: $it (ct=${value.toHex()})"); return }
        val resp = runCatching { aidexParseResponse(pt) }.getOrElse { logi("F002 parse failed: $it (pt=${pt.toHex()})"); return }
        val h = pendingResponse
        pendingResponse = null
        handler.removeCallbacks(cmdTimeout)
        if (h != null) h(resp) else logi("unsolicited F002 response: $resp")
    }

    private val cmdTimeout = Runnable {
        if (pendingResponse != null) { logi("STALLED — no F002 reply to '$lastCmdLabel' within ${CMD_TIMEOUT_MS / 1000}s"); reEnableAfterOp() }
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "t1dm:aidex-bringup").apply { setReferenceCounted(false); acquire() }
    }

    private fun isActivated(r: AidexResponse): Boolean =
        r is AidexResponse.StartTime && r.time.year in 2000..2100 && r.time.epochSecs > 0

    private fun fmtDeviceInfo(d: AidexDeviceInfo): String =
        "fw=${d.firmware} model1=${d.model1} model2=${d.model2} name='${d.name}' life=${d.lifeDays}d"

    private fun fmtStart(r: AidexResponse): String = if (r is AidexResponse.StartTime) {
        val t = r.time
        "%04d-%02d-%02d %02d:%02d:%02d tz=%d dst=%d epoch=%d".format(t.year, t.month, t.day, t.hour, t.minute, t.second, t.tzQuarterHours, t.dstQuarterHours, t.epochSecs)
    } else "none/unactivated ($r)"

    private fun fmtCurrent(r: AidexResponse): String = if (r is AidexResponse.Current) {
        val g = r.reading
        "${g.glucoseMgdl} mg/dL trend=${g.trendTenthsPerMin * 0.1}mg/dL/min valid=${g.valid} minFromStart=${g.minFromStart}"
    } else "none ($r)"

    companion object {
        private const val TAG = "AIDEXPROBE"
        private const val OLD_SERIAL = "22222C74D9"
        private const val REQ_PERMS = 42
        private const val CMD_TIMEOUT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 35_000L
        private const val PRECONNECT_DELAY_A_MS = 400L
        private const val BOND_RECHECK_MS = 700L
        private const val BOND_WATCHDOG_MS = 30_000L
        private const val FALLBACK_DELAY_MS = 1_000L
        private const val KEY_MISSING_WAIT_MS = 1_200L
        private const val MAX_KEY_MISSING_RECOVERIES = 2

        private const val MANUFACTURER_ID = 0x0059
        private val CGM_SERVICE_PARCEL_UUID = ParcelUuid(UUID.fromString("0000181F-0000-1000-8000-00805F9B34FB"))
        private val F001_UUID = UUID.fromString("0000F001-0000-1000-8000-00805F9B34FB")
        private val F002_UUID = UUID.fromString("0000F002-0000-1000-8000-00805F9B34FB")
        private val F003_UUID = UUID.fromString("0000F003-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private fun scanFilters(): List<ScanFilter> = listOf(
            ScanFilter.Builder().setManufacturerData(MANUFACTURER_ID, byteArrayOf()).setServiceUuid(CGM_SERVICE_PARCEL_UUID).build(),
        )

        private fun scanSettings(): ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(true).setReportDelay(0L).build()

        private fun stateName(s: Int) = when (s) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"; BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"; BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"; else -> "UNKNOWN($s)"
        }

        private fun bondName(s: Int) = when (s) {
            BluetoothDevice.BOND_NONE -> "BOND_NONE"; BluetoothDevice.BOND_BONDING -> "BOND_BONDING"; BluetoothDevice.BOND_BONDED -> "BOND_BONDED"; else -> "BOND($s)"
        }

        private fun unbondReasonName(r: Int) = when (r) {
            -1 -> "none"; 0 -> "success"; 1 -> "AUTH_FAILED"; 2 -> "AUTH_REJECTED"; 3 -> "AUTH_CANCELED"; 4 -> "REMOTE_DEVICE_DOWN"
            5 -> "DISCOVERY_IN_PROGRESS"; 6 -> "AUTH_TIMEOUT"; 7 -> "REPEATED_ATTEMPTS"; 8 -> "REMOTE_AUTH_CANCELED"; 9 -> "REMOVED"; else -> "reason=$r"
        }

        private fun gattStatusName(s: Int) = when (s) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"; 5 -> "GATT_INSUFFICIENT_AUTHENTICATION"; 8 -> "GATT_CONN_TIMEOUT"
            15 -> "GATT_INSUFFICIENT_ENCRYPTION"; 19 -> "GATT_CONN_TERMINATE_PEER_USER"; 22 -> "GATT_CONN_TERMINATE_LOCAL_HOST (also KEY_MISSING teardown)"
            34 -> "GATT_CONN_LMP_TIMEOUT"; 62 -> "GATT_CONN_FAIL_ESTABLISH"; 133 -> "GATT_ERROR/133"; 137 -> "GATT_AUTH_FAIL"; else -> "status=$s"
        }

        private fun propsString(props: Int): String = buildList {
            if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
            if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
            if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        }.joinToString(",")

        private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
    }
}
