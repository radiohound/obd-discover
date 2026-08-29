package com.redundo.obddiscover

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * OBD Discover — map an unlisted vehicle, from the driver's seat.
 *
 * WHY THIS EXISTS SEPARATELY. obd-gauge-cluster's `obd_scan` can characterise a vehicle
 * thoroughly, but `sweep` only walks blocks a preset already declares, so a make with no
 * preset (Subaru, Toyota, VW, Honda...) has nothing to sweep — and running it needs a laptop
 * in the passenger seat. This app carries no vehicle-specific knowledge at all: it finds the
 * blocks by probing, then logs what it found, and writes both files in the exact formats the
 * host tools already read.
 *
 * FIVE BUTTONS, in order: Scan, Connect, Init, DISCOVER, DRIVE LOG. The last appears only
 * once discovery has something to log.
 *
 * OUTPUT, both in obd_scan's own formats so nothing needs converting:
 *   discover-<ts>.json  — matches discover.json; feeds `sweep --blocks-from`
 *   discovered-<ts>.csv — matches drive.csv; feeds `correlate` directly
 *
 * SAFETY. Mode 22 reads only, powertrain headers only, never 7E3-7E7.
 * PRIVACY. Only the VIN's 3-character WMI is written; the other 14 characters are dropped.
 */
class MainActivity : ComponentActivity() {

    private val ble: ElmBle get() = Session.ble
    private val runner: ScanRunner get() = Session.runner
    private val discover: DiscoverRunner get() = Session.discover

    private val perms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.ensure(this)

        val want = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            want += Manifest.permission.BLUETOOTH_SCAN
            want += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            want += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(want.toTypedArray())

        setContent { Surface(Modifier.fillMaxSize()) { Screen() } }
    }

    /**
     * One step's light. Colour AND text, never colour alone: a phone on a sunlit dashboard
     * is a bad place to distinguish amber from green, and roughly one man in twelve cannot
     * separate red from green at all.
     */
    @Composable
    private fun Led(label: String, step: Step) {
        val (color, mark) = when (step) {
            Step.IDLE -> Color(0xFFBDBDBD) to "○"
            Step.RUNNING -> Color(0xFFFFA000) to "◐"
            Step.OK -> Color(0xFF2E7D32) to "●"
            Step.FAIL -> Color(0xFFC62828) to "✕"
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(mark, color = color, fontSize = 18.sp)
            Text(label, color = color, fontSize = 9.sp)
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        // Session state, not remember{}: a rebuilt Activity would otherwise forget the
        // adapter identity mid-capture and grey out the buttons. See Session's docstring.
        val ident = Session.ident
        val conn = Session.connector
        val cap = Session.capture
        var exportNote by remember { mutableStateOf("") }
        var showLog by rememberSaveable { mutableStateOf(false) }
        var showSources by rememberSaveable { mutableStateOf(false) }
        val sourcesText = remember {
            runCatching {
                assets.open("ATTRIBUTION.txt").bufferedReader().readText()
            }.getOrDefault("Adapted from OBDb (https://github.com/OBDb), CC BY-SA 4.0, " +
                "and NHTSA vPIC (public domain). Application source: MIT.")
        }

        Column(
            Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("OBD Discover", style = MaterialTheme.typography.titleMedium)
            Text("build ${BuildTag.ID}", fontSize = 10.sp)


            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = !conn.running && !runner.running && !discover.running,
                    onClick = { conn.start() },
                ) { Text(if (conn.done) "RECONNECT" else "START") }
                Led("scan", conn.scan)
                Led("link", conn.link)
                Led("ready", conn.ready)
            }
            if (conn.message.isNotEmpty()) Text(conn.message, fontSize = 11.sp)

            if (!ble.connected && ble.devices.isNotEmpty()) {
                // Every device is tappable, not just the ones whose name matches a hint.
                // START picks the best-named automatically, which handles the common case;
                // this is what makes an adapter sold under an unfamiliar name usable at all
                // rather than invisible.
                Text("Seen (tap to connect):", fontSize = 12.sp)
                // Compact and wrapping. Full-size stacked buttons pushed CAPTURE below the
                // fold on a phone that had seven BLE devices in range -- a thermostat, two
                // televisions and a solar controller, none of them an OBD adapter.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ble.devices.sortedByDescending { it.rssi }.take(8).forEach { d ->
                        val known = ble.looksLikeObd(d.name)
                        Button(
                            enabled = !runner.running && !discover.running,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            colors = if (known) ButtonDefaults.buttonColors()
                                     else ButtonDefaults.outlinedButtonColors(),
                            onClick = { ble.connect(d.address) },
                        ) {
                            Text((if (known) "★ " else "") +
                                (d.name?.take(18) ?: "(unnamed)") + "  ${d.rssi}",
                                fontSize = 10.sp)
                        }
                    }
                }
                Text("★ = a known adapter name. Unmarked devices work too if they expose an "
                    + "ELM327 serial profile — tap one to try it.", fontSize = 9.sp)
            }

            // ONE action: identify the car, map it if it is new, then log the drive.
            // Splitting these was not just extra taps -- nothing checked that the map on
            // disk belonged to the car plugged in.
            // FlowRow, not Row: these buttons carry long labels and kept running off the
            // right edge in portrait -- first the exports, then Re-map. A wrapping layout
            // fixes the whole class of it rather than the instance, and a button the user
            // cannot see is indistinguishable from one that does not exist.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    enabled = ble.connected && ident != null && !cap.running &&
                        !runner.running && !discover.running,
                    onClick = { cap.start() },
                ) { Text("CAPTURE") }
                // Disabled and relabelled the instant it is pressed. The worker is inside a
                // BLE round trip and cannot notice the flag until it returns, so without this
                // the button stayed live and unchanged for seconds -- which reads as a press
                // that did not register, and gets pressed again. It was, three times.
                Button(enabled = cap.running && !cap.stopping, onClick = { cap.stop() }) {
                    Text(if (cap.stopping) "Stopping…" else "Stop")
                }
            }
            // Exports on their OWN row. Five buttons abreast overflowed the width in
            // portrait, so EXPORT SCRUBBED and EXPORT RAW were reachable only by turning
            // the phone sideways -- which is not a thing anyone would guess to try, and
            // made the capture look like it had no way to get the data off the device.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (cap.phase == CapPhase.DONE || cap.phase == CapPhase.FAILED) {
                    // Colour AND wording carry the distinction. Green is the one that is
                    // safe to send anywhere; orange is the one that identifies the car.
                    // Colour alone would not be enough -- red/green confusion is common,
                    // and this is a mistake that cannot be taken back once posted.
                    Button(
                        enabled = !cap.running,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32), contentColor = Color.White),
                        onClick = {
                            val b = runCatching {
                                Export.build(this@MainActivity, cap.info, scrub = true, codes = cap.dtcs,
                                    vinKey = cap.vinKey,
                                    adapterLog = ble.connLog.toList(),
                                    names = cap.named, namesFrom = cap.namedFrom)
                            }.getOrNull()
                            if (b == null) exportNote = "nothing to export yet"
                            else {
                                exportNote = "shared ${b.contents.size} files — VIN and per-car key removed"
                                Export.share(this@MainActivity, b.file)
                            }
                        },
                    ) { Text("EXPORT SCRUBBED") }
                    Button(
                        enabled = !cap.running,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE65100), contentColor = Color.White),
                        onClick = {
                            val b = runCatching {
                                Export.build(this@MainActivity, cap.info, scrub = false, codes = cap.dtcs,
                                    vinKey = cap.vinKey,
                                    adapterLog = ble.connLog.toList(),
                                    names = cap.named, namesFrom = cap.namedFrom)
                            }.getOrNull()
                            if (b == null) exportNote = "nothing to export yet"
                            else {
                                exportNote = "RAW: includes the VIN — keep this one, do not post it"
                                Export.share(this@MainActivity, b.file)
                            }
                        },
                    ) { Text("EXPORT RAW") }
                    // A THIRD COLOUR, because this is a third thing. Green is safe to send
                    // anywhere, orange identifies the car, and this one is meant to be
                    // published -- to a public pull request, by someone who should be able
                    // to see at a glance that it is not either of the other two.
                    Button(
                        enabled = !cap.running,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1565C0), contentColor = Color.White),
                        onClick = {
                            val b = runCatching {
                                Export.contribute(this@MainActivity, cap.info,
                                    cap.modelName, cap.vinKey)
                            }.getOrNull()
                            exportNote = when {
                                b == null -> "nothing discovered yet to contribute"
                                cap.modelName.isEmpty() ->
                                    "${b.file.name} — no model resolved; name it before opening a PR"
                                else ->
                                    "${b.file.name} — VIN pattern only (8 chars), no serial, no payloads"
                            }
                            b?.let { Export.share(this@MainActivity, it.file, "application/json") }
                        },
                    ) { Text("CONTRIBUTE") }
                }
                // Always available, unlike the capture exports, which need a capture. The
                // case this serves is the adapter that will not connect or the car that
                // answers nothing -- where there is no discover.json and the evidence is
                // entirely in the log.
                Button(
                    onClick = {
                        val b = runCatching {
                            // The PERSISTED log, not just this session's -- the report is
                            // most needed after a restart, when the in-memory list is empty.
                            Export.report(
                                this@MainActivity,
                                ble.persistedLog().asReversed().ifEmpty { ble.connLog.toList() },
                                ident,
                                ble.boundProfile, ble.mtu, ble.connected,
                                cap.protocol, cap.phase.name, cap.status, cap.info,
                            )
                        }.getOrNull()
                        if (b == null) exportNote = "could not build a report"
                        else {
                            exportNote = "report: ${b.contents.size} files, no VIN or MAC"
                            Export.share(this@MainActivity, b.file)
                        }
                    },
                ) { Text("REPORT") }
                // Re-map sits OUTSIDE the DONE/FAILED gate above. It used to be inside it,
                // so the only route to the button that skips a scan was to start a scan and
                // stop it -- and on a non-CAN vehicle Stop did nothing, which made it
                // unreachable. It is an escape hatch for a stale map; it has to be reachable
                // whenever there is a vehicle and nothing running.
                if (cap.info != null && !cap.running) {
                    Button(
                        enabled = ble.connected && ident != null,
                        onClick = { cap.start(forceDiscover = true) },
                    ) { Text("Re-map") }
                }
            }
            cap.info?.let { v ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text(listOfNotNull(
                            v.year?.toString(),
                            v.make.ifEmpty { null } ?: "unknown make",
                        ).joinToString(" "), style = MaterialTheme.typography.titleMedium)
                        Text("VIN ${v.vin}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("WMI ${v.wmi}" + if (!v.known) "  (not in the bundled table)" else "",
                            fontSize = 10.sp)
                        if (cap.protocol.isNotEmpty()) {
                            // Name it, not just number it. The protocol is the single fact
                            // that explains what this app can and cannot do on this car, so
                            // it should be readable by someone who has never heard of AT DPN.
                            Text(ble.protocolName(cap.protocol),
                                style = MaterialTheme.typography.titleMedium)
                            Text("ELM protocol ${cap.protocol}", fontSize = 9.sp)
                        }
                        if (cap.hintNote.isNotEmpty()) Text(cap.hintNote, fontSize = 11.sp)
                        if (cap.info09.isNotEmpty()) {
                            Text("vehicle information", fontSize = 11.sp)
                            cap.info09.forEach { (name, value) ->
                                Text("  $name: $value",
                                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                        }
                        if (cap.dtcRead) {
                            if (cap.dtcs.isEmpty()) {
                                Text("no stored trouble codes", fontSize = 12.sp)
                            } else {
                                Text("${cap.dtcs.size} stored trouble code" +
                                    (if (cap.dtcs.size == 1) "" else "s"),
                                    style = MaterialTheme.typography.titleMedium)
                                cap.dtcs.forEach { d ->
                                    Text(d.code, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                    val unofficial = if (d.description == null) Dtc.unofficial(d.code) else null
                                    Text("   " + (d.description
                                        ?: unofficial?.first?.let { "$it  (not an SAE definition)" }
                                        ?: (d.subsystem.ifEmpty { d.system } +
                                            if (!d.generic) " — manufacturer-specific, no standard description" else "")),
                                        fontSize = 10.sp)
                                    unofficial?.let { Text("   ${it.second}", fontSize = 9.sp) }
                                }
                            }
                        }
                    }
                }
            }
            if (exportNote.isNotEmpty()) Text(exportNote, fontSize = 10.sp)

            // The adapter conversation, newest first. Without this a failure in the car is
            // undiagnosable without a laptop -- which is the situation this app exists to
            // avoid, so leaving it out was the wrong call.
            // CC BY-SA 4.0 requires attribution, and an unreferenced file in assets/ is
            // not attribution in any sense a licence would recognise. Reading it from the
            // asset rather than restating it keeps one copy: if the terms change, the text
            // the user sees changes with them.
            Button(onClick = { showSources = !showSources }) {
                Text(if (showSources) "hide sources" else "sources & licence")
            }
            if (showSources) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text(sourcesText, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    }
                }
            }

            if (ble.connLog.isNotEmpty()) {
                Button(onClick = { showLog = !showLog }) {
                    Text(if (showLog) "hide adapter log" else "show adapter log (${ble.connLog.size})")
                }
                if (showLog) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(6.dp)) {
                            ble.connLog.take(40).forEach {
                                Text(it, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
            // Offered only on a car where it would change anything: ISO 9141-2, where the
            // spec that justifies Mode 21 on KWP2000 does not apply. Stating what is being
            // agreed to is the point of the switch -- a bare toggle would be worse than no
            // toggle, because it would look like a considered default.
            if (cap.protocol.trimStart('A') == "3") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = Session.mode21OnIso9141,
                                onCheckedChange = { Session.mode21OnIso9141 = it })
                            Text("Also sweep Mode 21 on this car", fontSize = 12.sp)
                        }
                        Text("Mode 21 is a read, and every request sent is two bytes, which " +
                            "cannot ask an ECU to stream continuously. On KWP2000 that is " +
                            "guaranteed by ISO 14230-3. On this bus (ISO 9141-2) no standard " +
                            "we have read defines Mode 21 at all, so the guarantee is " +
                            "reasoning by analogy rather than by specification. It would add " +
                            "256 requests, about a minute, and might find enhanced data that " +
                            "is otherwise unreachable here.", fontSize = 9.sp)
                    }
                }
            }
            if (cap.phase == CapPhase.IDLE) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text("ready", fontSize = 11.sp)
                        Text("CAPTURE will identify this vehicle, map its blocks if it is new, "
                            + "then log a drive.", fontSize = 12.sp)
                        Text(when (cap.storedMaps) {
                            -1 -> "checking stored maps..."
                            0 -> "no vehicles mapped yet — the first run takes ~10 min parked"
                            1 -> "1 vehicle already mapped — a match skips straight to logging"
                            else -> "${cap.storedMaps} vehicles already mapped — a match skips straight to logging"
                        }, fontSize = 11.sp)
                    }
                }
            }
            if (cap.phase != CapPhase.IDLE) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        val mark = when (cap.phase) {
                            CapPhase.VIN -> "1/3  identifying"
                            CapPhase.DISCOVER -> "2/3  mapping blocks"
                            CapPhase.DRIVE -> "3/3  logging"
                            CapPhase.DONE -> "done"
                            CapPhase.FAILED -> "stopped"
                            else -> ""
                        }
                        // Completion needs to be unmistakable. It was rendered as small grey
                        // text identical to the in-progress labels, and on a car whose whole
                        // capture takes under three seconds there is no transition to notice
                        // either -- a 2006 Highlander finished before the operator could tell
                        // it had started.
                        val finished = cap.phase == CapPhase.DONE || cap.phase == CapPhase.FAILED
                        Text(if (cap.phase == CapPhase.DONE) "\u2714  DONE"
                             else if (cap.phase == CapPhase.FAILED) "\u2715  STOPPED" else mark,
                            style = if (finished) MaterialTheme.typography.titleMedium else LocalTextStyle.current,
                            fontSize = if (finished) 18.sp else 11.sp,
                            color = when (cap.phase) {
                                CapPhase.DONE -> Color(0xFF2E7D32)
                                CapPhase.FAILED -> Color(0xFFC62828)
                                else -> Color.Unspecified
                            })
                        if (cap.modelName.isNotEmpty())
                            Text(cap.modelName, style = MaterialTheme.typography.titleMedium)
                        Text(cap.status, style = MaterialTheme.typography.titleMedium)
                        if (cap.detail.isNotEmpty()) Text(cap.detail, fontSize = 11.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = Session.onlineVinLookup,
                                onCheckedChange = { Session.onlineVinLookup = it })
                            Text("Look up the model online", fontSize = 13.sp)
                        }
                        Text(
                            "Off by default, and the only thing in this app that uses the " +
                            "network. It sends the FIRST 10 CHARACTERS of the VIN to NHTSA's " +
                            "public vPIC database to get the model and year.\n\n" +
                            "Those ten are the manufacturer, the model/body/engine code and " +
                            "the model year. Characters 11-17 — the plant and the serial " +
                            "number that identify your specific vehicle — are never sent. " +
                            "Thousands of cars share the same first ten, so what leaves the " +
                            "phone describes a model, not a car.\n\n" +
                            "Answers are cached by those ten characters, so the same model is " +
                            "only ever looked up once. Everything else — scanning, decoding, " +
                            "export — works with no network at all.",
                            fontSize = 11.sp,
                        )
                        // The non-CAN scan runs in CapPhase.VIN and used to show no bar at
                        // all -- two minutes of a status line on a Highlander.
                        if (cap.running && cap.phase != CapPhase.DISCOVER &&
                            cap.phase != CapPhase.DRIVE && cap.capPct > 0) {
                            LinearProgressIndicator(
                                progress = { cap.capPct / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                            )
                            Text(
                                "${cap.capPct}%" +
                                    (if (cap.capEta.isEmpty()) "" else "  ·  ${cap.capEta}"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (cap.phase == CapPhase.DISCOVER) {
                            // An actual bar, not a percentage buried in an 11sp line with
                            // four other facts. A discovery run is 15-20 minutes on a phone
                            // propped in a footwell, and the question it has to answer at a
                            // glance -- from the driver's seat, without reading -- is "is
                            // this still going, and how much is left".
                            LinearProgressIndicator(
                                progress = { discover.pct / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                            )
                            Text(
                                "${discover.pct}%" +
                                    (if (discover.eta.isEmpty()) "" else "  ·  ${discover.eta}"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("${discover.blocksFound} blocks · ${discover.didsFound} DIDs",
                                fontSize = 13.sp)
                            Text(discover.progress, fontSize = 11.sp)
                        }
                        if (cap.phase == CapPhase.DRIVE) {
                            // The drive is the LONG half and had no target on screen.
                            // Measured on the BMW, 2026-08-27: 568 DIDs plus seven anchors
                            // is 575 requests per row, one row every 43 seconds, so
                            // correlate's 30-sample floor is 22 minutes away. The driver was
                            // shown "48 rows logged" in 11sp with nothing saying what number
                            // would be enough -- a 34-minute drive with no finish line.
                            val need = Triage.MIN_SAMPLES
                            val done = runner.rows >= need
                            LinearProgressIndicator(
                                progress = { (runner.rows.toFloat() / need).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = if (done) Color(0xFF2E7D32) else ProgressIndicatorDefaults.linearColor,
                            )
                            Text(
                                if (done) "${runner.rows} rows — enough to correlate, keep going for a better fit"
                                else "${runner.rows} of $need rows — correlate needs $need",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (done) Color(0xFF2E7D32) else Color.Unspecified,
                            )
                            if (runner.rowsAtMark > 0 && !done) {
                                Text("about ${((need - runner.rows) * 43) / 60 + 1} min at this rate",
                                    fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Provocation: only offered once a plan exists, because it logs the DIDs
            // discovery found. Kept OUT of the CAPTURE sequence on purpose -- it needs the
            // operator working the controls, so it cannot be part of an unattended chain.
            Session.activePlan?.let { (hdr, reqs) ->
                if (!cap.running) {
                    Text("Identify fields that a drive cannot reveal", fontSize = 12.sp)
                    Text(ControlsTest.SUMMARY, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = ble.connected && ident != null && !runner.running,
                            onClick = {
                                LogService.start(this@MainActivity, "Controls test")
                                runner.mark = ControlsTest.labels.first()
                                runner.start("controls", reqs, hdr, 1.0,
                                    Obd.ANCHORS, withMark = true) {
                                    LogService.stop(this@MainActivity)
                                }
                            },
                        ) { Text("CONTROLS TEST") }
                        Button(
                            enabled = runner.running && runner.mark.isNotEmpty(),
                            colors = if (runner.rowsAtMark >= 3) ButtonDefaults.buttonColors()
                                     else ButtonDefaults.outlinedButtonColors(),
                            onClick = {
                                runner.mark = ControlsTest.next(runner.mark)
                                if (runner.mark == ControlsTest.labels.last()) runner.stop()
                            },
                        ) { Text("NEXT") }
                    }
                    if (runner.running && runner.mark.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp)) {
                                val release = ControlsTest.isRelease(runner.mark)
                                Text((if (release) "CONTROL STEP — " else "NOW: ") + runner.mark,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (release) Color(0xFF2E7D32) else Color.Unspecified)
                                if (release) Text(
                                    "This is what makes the previous step evidence. Skip it and " +
                                    "a response cannot be told from a drift.", fontSize = 10.sp)
                                Text(ControlsTest.hint(runner.mark), fontSize = 11.sp)
                                val n = runner.rowsAtMark
                                val enough = n >= 3
                                Text("samples at this step: $n" +
                                    (if (enough) "  — enough, tap NEXT"
                                     else "  — hold until 3"),
                                    style = MaterialTheme.typography.titleMedium)
                                Text("step ${ControlsTest.labels.indexOf(runner.mark) + 1}" +
                                    "/${ControlsTest.labels.size}   " +
                                    "a step advanced after one sample cannot tell a response " +
                                    "from noise", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            Session.triage?.let { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Drive check — advisory only", fontSize = 12.sp)
                        Text(t.note, style = MaterialTheme.typography.titleMedium)
                        Text("${t.rows} rows · ${t.varying}/${t.didColumns} DIDs moved · " +
                            "${t.strong.size} strong, ${t.weak.size} weak", fontSize = 12.sp)
                        t.strong.forEach {
                            Text("  ${it.column}  ${it.interp}  vs ${it.anchor}  r=%+.3f".format(it.r),
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        if (t.strong.isEmpty() && t.weak.isNotEmpty()) {
                            t.weak.forEach {
                                Text("  ${it.column}  ${it.interp}  vs ${it.anchor}  r=%+.3f".format(it.r),
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                        Text("The real report is obd_scan correlate on the host — this is " +
                            "only here to say whether the drive is worth keeping.",
                            fontSize = 10.sp)
                        Session.continueFile?.let { prev ->
                            // Appending, not a second file: correlate reads ONE csv, so a
                            // new file would strand the rows already collected rather than
                            // add to them.
                            Button(
                                enabled = ble.connected && ident != null &&
                                    !runner.running && !discover.running,
                                onClick = {
                                    Session.activePlan?.let { (h2, r2) ->
                                        LogService.start(this@MainActivity, "Continue drive")
                                        Session.triage = null
                                        runner.start("discovered", r2, h2, 1.0, appendTo = prev) { f ->
                                            LogService.stop(this@MainActivity)
                                            if (f != null) Thread {
                                                Session.triage = runCatching { Triage.run(f) }.getOrNull()
                                                Session.continueFile =
                                                    if (Session.triage?.enoughSamples == false) f else null
                                            }.start()
                                        }
                                    }
                                },
                            ) { Text("KEEP DRIVING — add to ${prev.name.takeLast(18)}") }
                        }
                    }
                }
            }
            if (runner.running || runner.rows > 0) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text("log: ${runner.rows} rows", fontSize = 12.sp)
                        runner.lastError?.let { Text(it, fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}
