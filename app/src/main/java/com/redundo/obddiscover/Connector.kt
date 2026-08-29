package com.redundo.obddiscover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Scan, connect and init as ONE action with three lights.
 *
 * They were three buttons, which put the burden of sequencing on the operator: each had to
 * be pressed in order, each was greyed until the one before it happened to succeed, and
 * nothing said which step had failed when the sequence stalled. In a parked car that is
 * three chances to press the wrong thing.
 *
 * The steps themselves are unchanged and still run in order -- this only removes the manual
 * hand-off between them, and surfaces WHICH one is in progress or has failed.
 *
 * Each step reports its own outcome, so a failure names itself: no adapter seen, GATT never
 * bound, or an adapter that connected but never answered ATZ. Those need different responses
 * from the driver and must not collapse into one "not connected".
 */
enum class Step { IDLE, RUNNING, OK, FAIL }

class Connector(private val ble: ElmBle) {

    var scan by mutableStateOf(Step.IDLE); private set
    var link by mutableStateOf(Step.IDLE); private set
    var ready by mutableStateOf(Step.IDLE); private set
    var message by mutableStateOf(""); private set

    val running: Boolean get() = scan == Step.RUNNING || link == Step.RUNNING || ready == Step.RUNNING
    val done: Boolean get() = ready == Step.OK

    fun start() {
        if (running) return
        scan = Step.RUNNING; link = Step.IDLE; ready = Step.IDLE
        message = "scanning for an adapter..."

        // Already linked from an earlier attempt: skip straight to the handshake rather
        // than burning one of Android's five scans per thirty seconds.
        if (ble.connected) {
            scan = Step.OK; link = Step.OK
            initStep()
            return
        }

        ble.startScan { ok ->
            if (!ok) {
                // ble.status carries the real reason -- Bluetooth off, or the scan quota.
                scan = Step.FAIL; message = ble.status
                return@startScan
            }
            val target = ble.best()
            if (target == null) {
                scan = Step.FAIL
                message = if (ble.devices.isEmpty())
                    "no BLE devices seen at all — check Bluetooth and that the dongle is powered"
                else
                    "saw ${ble.devices.size} device(s), none with a familiar adapter name — " +
                    "tap yours in the list below to connect anyway"
                return@startScan
            }
            scan = Step.OK
            link = Step.RUNNING
            message = "connecting to ${target.name}..."
            ble.connect(target.address)

            // connected goes true only once a GATT profile is BOUND (see the callback in
            // ElmBle), so this waits for a usable link rather than a mere TCP-ish connect.
            ble.runOnWorker {
                val deadline = 15_000L
                var waited = 0L
                while (!ble.connected && waited < deadline) {
                    Thread.sleep(200); waited += 200
                }
                if (!ble.connected) {
                    link = Step.FAIL
                    message = "found ${target.name} but no GATT profile bound — ${ble.status}"
                } else {
                    link = Step.OK
                    initStep()
                }
            }
        }
    }

    private fun initStep() {
        ready = Step.RUNNING
        message = "handshaking (ATZ)..."
        ble.runOnWorker {
            val id = ble.init()
            if (id.isEmpty()) {
                ready = Step.FAIL
                message = "connected, but no reply to ATZ — is the dongle seated in the port?"
                Session.ident = null
            } else {
                ready = Step.OK
                message = id
                Session.ident = id
            }
        }
    }
}
