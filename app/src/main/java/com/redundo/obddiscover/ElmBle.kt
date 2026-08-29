package com.redundo.obddiscover

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.ArrayDeque
import java.util.UUID
import kotlin.concurrent.thread

/**
 * A BLE ELM327 client: scan, bind a GATT serial profile, and run ELM327 commands.
 *
 * The platform mechanisms here are ported from BoschDecoderAndroid's BleManager -- the
 * serialized op queue, the 5-scans-per-30s throttle guard, LOW_LATENCY scan mode, and the
 * decoded GATT status codes. All four exist because their absence produces failures that
 * are INVISIBLE or that mimic a different failure. What is deliberately NOT ported is the
 * bonding path: the Bosch bike REJECTS unbonded links (status 147) and must be paired
 * first, whereas a BLE ELM327 takes a plain connection and pairing it is actively harmful.
 * That inversion is why this is a separate class and not a flag on that one.
 */
class ElmBle(private val ctx: Context) {

    // ---- GATT layouts, same table and same order as the dash firmware's bindChars()
    // (src/ble_obd_source.cpp). An adapter that works on the dash works here.
    data class Profile(val tag: String, val svc: UUID, val notify: UUID, val write: UUID)

    private fun u16(x: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", x))

    private val profiles by lazy {
        listOf(
            Profile("vlinker 18f0", u16(0x18F0), u16(0x2AF0), u16(0x2AF1)),
            Profile("clone fff0", u16(0xFFF0), u16(0xFFF1), u16(0xFFF2)),
            Profile("clone ffe0", u16(0xFFE0), u16(0xFFE1), u16(0xFFE1)),   // one char does both
            Profile(
                "nordic-uart",
                UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
            ),
        )
    }

    /** Advertised-name hints, verbatim from bleNameLooksLikeObd (src/ble_rank.cpp). */
    // Ranking hints, NOT a whitelist. A device that matches none of these can still be
    // chosen by hand from the scan list -- see MainActivity. Adapters ship under whatever
    // name the reseller chose, so treating this list as the set of allowable devices makes
    // an unlisted-but-working adapter indistinguishable from no adapter at all.
    private val nameHints = listOf(
        "obd", "vlink", "elm", "icar", "veepeak", "konnwei", "carista", "obdlink",
        "goliton", "vgate", "viecar", "ancel", "topdon", "thinkdiag", "scan",
    )

    private val handler = Handler(Looper.getMainLooper())
    private val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = btManager.adapter

    // ---- Compose-observable state
    var status by mutableStateOf("Idle"); private set
    var connected by mutableStateOf(false); private set
    var boundProfile by mutableStateOf<String?>(null); private set
    var mtu by mutableStateOf(23); private set
    val devices = mutableStateListOf<Found>()
    val connLog = mutableStateListOf<String>()

    data class Found(val name: String, val address: String, var rssi: Int)

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    fun log(msg: String) {
        android.util.Log.i("ELMBLE", msg)
        handler.post {
            connLog.add(0, msg)
            while (connLog.size > 120) connLog.removeAt(connLog.size - 1)
        }
    }

    /** Human meaning for the GATT status codes that actually show up in the field. */
    fun gattStatusName(code: Int) = when (code) {
        0 -> "SUCCESS"
        8 -> "8 CONN_TIMEOUT (adapter went away / out of range)"
        19 -> "19 REMOTE_DISCONNECT (adapter hung up - or another client took it)"
        22 -> "22 LOCAL_HOST_TERMINATED"
        62 -> "62 CONN_FAIL_ESTABLISH (never completed - usually not advertising)"
        133 -> "133 GATT_ERROR (catch-all: usually not advertising, or too many GATT clients)"
        147 -> "147 AUTH_FAIL (peer wants bonding)"
        else -> "$code"
    }

    // ---- serialized GATT op queue -------------------------------------------------------
    // Android permits exactly ONE outstanding GATT operation. Issuing a second before the
    // first completes silently drops it; there is no error. Every op goes through here.
    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false

    private fun enqueue(op: () -> Unit) {
        synchronized(opQueue) { opQueue.add(op) }
        drain()
    }

    private fun drain() {
        val op: (() -> Unit)?
        synchronized(opQueue) {
            if (opInFlight) return
            op = opQueue.pollFirst()
            if (op != null) opInFlight = true
        }
        op?.invoke()
    }

    private fun opDone() {
        synchronized(opQueue) { opInFlight = false }
        drain()
    }

    // ---- scanning -----------------------------------------------------------------------
    private val scanStarts = ArrayDeque<Long>()
    private var scanning = false

    /** How many scans started in the last 30 s. Android's hard limit is 5. */
    fun scansInWindow(): Int {
        val cutoff = System.currentTimeMillis() - 30_000
        while (scanStarts.isNotEmpty() && scanStarts.first() < cutoff) scanStarts.removeFirst()
        return scanStarts.size
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            handler.post {
                val existing = devices.firstOrNull { it.address == result.device.address }
                if (existing != null) existing.rssi = result.rssi
                else devices.add(Found(name, result.device.address, result.rssi))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            // Without this, a platform refusal is indistinguishable from "the adapter is
            // not here" -- and the natural response, retrying harder, is what keeps it
            // refused. Name it instead.
            val why = when (errorCode) {
                1 -> "ALREADY_STARTED"
                2 -> "APPLICATION_REGISTRATION_FAILED"
                3 -> "INTERNAL_ERROR"
                4 -> "FEATURE_UNSUPPORTED"
                5 -> "OUT_OF_HARDWARE_RESOURCES"
                6 -> "SCANNING_TOO_FREQUENTLY (the 5-per-30s limit)"
                else -> "code $errorCode"
            }
            handler.post { scanning = false; status = "Scan failed: $why" }
            log("scan FAILED: $why")
        }
    }

    @SuppressLint("MissingPermission")
    /**
     * @param onDone invoked when the scan window closes -- with false if the scan never
     *        started at all. Both refusal paths below report, rather than returning
     *        silently: a caller chaining scan -> connect -> init on the callback would
     *        otherwise wait forever for a scan that was declined in the first line.
     */
    fun startScan(onDone: ((ok: Boolean) -> Unit)? = null) {
        val scanner = adapter?.bluetoothLeScanner
            ?: run { status = "Bluetooth off"; onDone?.invoke(false); return }
        if (scansInWindow() >= 5) {
            val waitS = ((scanStarts.first() + 30_000 - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
            status = "Android scan limit (5 per 30 s) - wait ${waitS}s"
            log("scan REFUSED locally: ${scansInWindow()}/5 used; wait ${waitS}s")
            onDone?.invoke(false)
            return
        }
        scanStarts.addLast(System.currentTimeMillis())
        devices.clear()
        scanning = true
        status = "Scanning..."
        log("scan start (${scansInWindow()}/5 used in this window)")
        // Default is LOW_POWER, which batches and can miss an adapter that advertises
        // infrequently. LOW_LATENCY is what nRF Connect uses.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed({ stopScan(); onDone?.invoke(true) }, 8_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        val hinted = devices.count { looksLikeObd(it.name) }
        log("scan done: ${devices.size} device(s), $hinted look like an OBD adapter" +
            if (devices.isEmpty()) "  <- saw NOTHING: check Bluetooth/permissions" else "")
    }

    fun looksLikeObd(name: String?) = name != null && nameHints.any { name.lowercase().contains(it) }

    /** Name-hint first, RSSI second -- the dash's rankKey (src/ble_rank.cpp). */
    fun best(): Found? = devices.sortedWith(
        compareBy({ !looksLikeObd(it.name) }, { -it.rssi }),
    ).firstOrNull { looksLikeObd(it.name) }

    // ---- connect ------------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        stopScan()
        val dev: BluetoothDevice = adapter?.getRemoteDevice(address) ?: run {
            status = "Bad address"; return
        }
        // NOTE the contrast with the Bosch path: no createBond() here. A BLE ELM327 takes a
        // plain connection, and bonding one can leave the phone holding a stale bond that
        // blocks reconnection.
        synchronized(opQueue) { opQueue.clear(); opInFlight = false }
        gatt?.close()
        status = "Connecting..."
        log("connectGatt(autoConnect=true) -> $address")
        // autoConnect=true is a STANDING request: the stack links whenever the peer appears,
        // instead of "connect this instant or fail". Slower to fire when the adapter is
        // already advertising; never misses the window.
        gatt = dev.connectGatt(ctx, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        pending?.let { it.done("", false) }
        pending = null
        gatt?.disconnect(); gatt?.close(); gatt = null
        writeChar = null; notifyChar = null
        handler.post { connected = false; boundProfile = null; status = "Disconnected" }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            log("state=$newState status=${gattStatusName(statusCode)}")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                handler.post { status = "Connected - negotiating MTU" }
                g.requestMtu(247)
            } else {
                handler.post { connected = false; boundProfile = null; status = "Disconnected" }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtuValue: Int, statusCode: Int) {
            handler.post { mtu = mtuValue }
            log("mtu=$mtuValue")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            for (p in profiles) {
                val svc = g.getService(p.svc) ?: continue
                val nc = svc.getCharacteristic(p.notify) ?: continue
                val wc = svc.getCharacteristic(p.write) ?: continue
                notifyChar = nc; writeChar = wc
                g.setCharacteristicNotification(nc, true)
                val cccd = nc.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd, ENABLE_NOTIFY)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = ENABLE_NOTIFY
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
                handler.post { boundProfile = p.tag; connected = true; status = "Bound ${p.tag}" }
                log("bound GATT profile '${p.tag}'")
                return
            }
            // No layout matched. Dump EVERYTHING -- this is a real finding (a fifth layout,
            // or the OBDLink case where ELM327 is not carried over GATT at all), and the
            // only way to tell it from a broken adapter is the full table.
            val sb = StringBuilder("NO KNOWN GATT PROFILE. Everything this device exposes:")
            for (s in g.services) {
                sb.append("\n  service ${s.uuid}")
                for (c in s.characteristics) sb.append("\n    char ${c.uuid} props=0x%02X".format(c.properties))
            }
            log(sb.toString())
            handler.post { status = "No known GATT profile - see log" }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, s: Int) = opDone()

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) = opDone()

        // API 33+ delivers the value as a parameter; older devices read it off the characteristic.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onBytes(value)

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onBytes(c.value ?: ByteArray(0))
        }
    }

    // ---- ELM327 command layer -----------------------------------------------------------
    // An ELM327 reply is framed by a trailing '>' prompt, and over BLE it arrives in
    // MTU-sized fragments -- the prompt can land in its own notification. So a command
    // completes when the prompt is seen, not when a packet is received.
    private class Pending(val cb: (String, Boolean) -> Unit) {
        val sb = StringBuilder()
        var finished = false
        fun done(text: String, sawPrompt: Boolean) {
            if (finished) return
            finished = true
            cb(text, sawPrompt)
        }
    }

    private var pending: Pending? = null

    private fun onBytes(data: ByteArray) {
        val p = pending ?: return
        p.sb.append(String(data, Charsets.ISO_8859_1))
        if (p.sb.contains('>')) {
            pending = null
            p.done(p.sb.toString(), true)
        }
    }

    /**
     * Send one ELM command and wait for its prompt. BLOCKING -- call from a worker thread.
     * Returns the raw reply text, or "" if the prompt never arrived before [timeoutMs].
     *
     * `sawPrompt` matters more than the text: a promptless read means the LINK failed, which
     * must not be recorded as a clean "NO DATA" from the vehicle.
     */
    @SuppressLint("MissingPermission")
    fun cmd(text: String, timeoutMs: Long = 2_000): Pair<String, Boolean> {
        val g = gatt ?: return "" to false
        val wc = writeChar ?: return "" to false
        val lock = Object()
        var out = "" to false
        val p = Pending { s, ok ->
            synchronized(lock) { out = s to ok; lock.notifyAll() }
        }
        pending = p
        val payload = (text + "\r").toByteArray(Charsets.US_ASCII)
        enqueue {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(wc, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                wc.value = payload
                @Suppress("DEPRECATION")
                wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(wc)
            }
        }
        synchronized(lock) {
            val until = System.currentTimeMillis() + timeoutMs
            while (!p.finished && System.currentTimeMillis() < until) {
                lock.wait((until - System.currentTimeMillis()).coerceAtLeast(1))
            }
        }
        if (!p.finished) {
            pending = null
            p.done(p.sb.toString(), false)
            // Do not let one lost GATT callback wedge every subsequent command. The op is
            // abandoned, so the queue must be released or the rest of the drive silently
            // queues behind an operation that will never complete.
            opDone()
        }
        return out
    }

    /**
     * Reset and configure, mirroring obd_scan's ElmSession.init: echo off, linefeeds off,
     * spaces off, adaptive timing. Returns the adapter identity, or "" if ATZ drew no prompt.
     *
     * ATZ gets a long window on purpose. Protocol search is fragile: any host character
     * aborts the ELM's auto-search, and a short timeout latches a wrong protocol so every
     * subsequent PID returns NO DATA.
     */
    fun init(): String {
        var (ident, ok) = cmd("ATZ", 6_000)
        if (!ok) { val r = cmd("ATZ", 6_000); ident = r.first; ok = r.second }
        if (!ok) return ""
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATAT2")) cmd(c)
        return ident.replace("\r", " ").trim()
    }

    /**
     * Let the ADAPTER find the protocol, instead of us asserting one.
     *
     * Everything used to begin with AT SP6 -- 11-bit CAN at 500 kbaud. That is the common
     * case and it is what the F10 and the Ranger both use, so it worked and kept working
     * until it met a car that does not. A Toyota on 2026-08-26 answered every single request
     * with NO DATA: adapter alive and replying, vehicle silent, because the question was
     * being asked on a bus it does not speak. Nine probes, no headers, five runs, and
     * nothing in the result to say why.
     *
     * AT SP0 puts the ELM327 into its own protocol search, and 0100 -- legislated, and
     * present on every OBD-II vehicle -- is what triggers it. AT DPN then reports what it
     * settled on. The search can take several seconds, hence the long timeout.
     *
     * Returns the protocol number the adapter reports (e.g. "6", "A6", "8"), or "" if the
     * vehicle never answered at all -- which is a different fault and needs saying so.
     */
    /**
     * ELM327 protocol numbers and what they are, used both for the explicit sweep and to
     * name the result on screen. A bare "protocol 3" tells the owner nothing; "ISO 9141-2
     * (K-line, 10.4 kbaud)" tells them why enhanced discovery stopped and what their car is.
     */
    val protocols = listOf(
        "6" to "ISO 15765-4 CAN, 11-bit, 500 kbaud",
        "7" to "ISO 15765-4 CAN, 29-bit, 500 kbaud",
        "8" to "ISO 15765-4 CAN, 11-bit, 250 kbaud",
        "9" to "ISO 15765-4 CAN, 29-bit, 250 kbaud",
        "3" to "ISO 9141-2 (K-line, 10.4 kbaud)",
        "4" to "ISO 14230-4 KWP2000, 5-baud init (K-line)",
        "5" to "ISO 14230-4 KWP2000, fast init (K-line)",
        "1" to "SAE J1850 PWM (Ford, 41.6 kbaud)",
        "2" to "SAE J1850 VPW (GM, 10.4 kbaud)",
    )

    /**
     * Find the bus this vehicle speaks, rather than asserting one.
     *
     * Everything used to begin with AT SP6 -- 11-bit CAN at 500 kbaud. That is the common
     * case and it is what the F10 and the Ranger both use, so it worked until it met a car
     * that does not. A running Toyota answered every request with NO DATA across five runs:
     * adapter alive and replying, vehicle silent, because the question was being asked on a
     * bus it does not speak.
     *
     * AT SP0 hands the search to the adapter, and 0100 -- legislated, present on every
     * OBD-II vehicle -- is what triggers it. THE FIRST ATTEMPT OFTEN RETURNS "SEARCHING..."
     * and nothing else, which is why this retries rather than concluding from one reply.
     *
     * If the auto-search still finds nothing, each protocol is tried explicitly. That costs
     * nine probes and turns "no data" into an answer: either one of them responds and the
     * bus is known, or none does and the fault lies upstream of protocol entirely.
     */
    /** Human name for an ELM protocol number, tolerating the "A" prefix auto-search adds. */
    fun protocolName(p: String): String {
        val n = p.trimStart('A', 'a')
        val name = protocols.firstOrNull { it.first == n }?.second ?: return p
        return if (p.startsWith("A")) "$name — auto-detected" else name
    }

    fun detectProtocol(): String {
        cmd("ATSP0")
        for (attempt in 1..3) {
            val (r, ok) = cmd("0100", 12_000)
            val clean = r.replace("\r", " ").replace(">", "").trim()
            log("protocol: SP0 0100 try $attempt -> ok=$ok raw=$clean")
            if (ok && clean.contains("41")) {
                val dpn = cmd("ATDPN").first.replace("\r", " ").replace(">", "").trim()
                val desc = cmd("ATDP").first.replace("\r", " ").replace(">", "").trim()
                log("protocol: auto-detected $dpn ($desc)")
                return dpn.ifEmpty { "auto" }
            }
        }
        log("protocol: auto-search found nothing; trying each protocol explicitly")
        for ((n, desc) in protocols) {
            cmd("ATSP$n")
            // SLOW BUSES NEED SLOW TIMING, and the default is tuned for CAN.
            //
            // init() sets ATAT2 -- the most aggressive adaptive timing -- which suits a
            // 500 kbaud CAN bus and is wrong for a 10.4 kbaud K-line with mandated
            // inter-byte gaps. A 2006 Highlander showed this exactly: SP3 reported
            // "BUS INIT: ...OK" (the five-baud handshake succeeded, so the ECU is there and
            // the wiring is fine) and then NO DATA to 0100, because the adapter stopped
            // listening before a slow reply had finished arriving.
            //
            // AT1 is the gentler adaptive mode, and ST 64 gives 400 ms of patience instead
            // of the CAN-shaped default. Restored to AT2 afterwards so CAN keeps its speed.
            val slowBus = n in listOf("3", "4", "5")
            if (slowBus) { cmd("ATAT1"); cmd("ATST64") }
            val (r, ok) = cmd("0100", if (slowBus) 12_000 else 6_000)
            if (slowBus && !(ok && r.contains("41"))) {
                // One more try: after a successful BUS INIT the first request is the one
                // most likely to be lost, and a K-line session is already open by now.
                val retry = cmd("0100", 12_000)
                if (retry.second && retry.first.contains("41")) {
                    log("protocol: SP$n answered on the second attempt after BUS INIT")
                    log("protocol: SETTLED on $n -- $desc")
                    return n
                }
            }
            val clean = r.replace("\r", " ").replace(">", "").trim()
            log("protocol: SP$n ($desc) -> $clean")
            if (!slowBus) { /* CAN keeps the fast timing it was initialised with */ }
            else cmd("ATAT2")                       // restore before moving on
            if (ok && clean.contains("41")) {
                log("protocol: SETTLED on $n -- $desc")
                return n
            }
        }
        log("protocol: NO protocol answered 0100. The fault is not the protocol.")
        cmd("ATSP0")
        return ""
    }

    fun runOnWorker(body: () -> Unit) = thread(start = true) { body() }

    private companion object {
        val ENABLE_NOTIFY = byteArrayOf(0x01, 0x00)
    }
}
