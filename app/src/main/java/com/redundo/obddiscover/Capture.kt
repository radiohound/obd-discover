package com.redundo.obddiscover

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import org.json.JSONObject

enum class CapPhase { IDLE, VIN, DISCOVER, DRIVE, DONE, FAILED }

/**
 * DISCOVER and DRIVE LOG as one action, keyed to the car actually plugged in.
 *
 * Two buttons meant deciding, per car, whether the map on disk applied to it. Nothing
 * checked. `DiscoverRunner.loadLast()` restored the NEWEST discover file regardless of
 * origin, so plugging into a second car offered to drive-log the first car's DIDs against
 * it -- every column NO DATA, and a wasted drive to find that out. This reads the VIN first
 * and decides from that, which makes the single button a correctness fix rather than a
 * convenience.
 *
 * IDENTITY IS A HASH, NOT THE VIN. `Discover.vinKey` is a truncated SHA-256: stable for one
 * car, useless for identifying it, and it cannot be reversed. WMI alone would not do -- every
 * Subaru shares one, so it cannot answer "have I mapped THIS car".
 *
 * A CACHED MAP IS REUSED ONLY IF it matches the VIN key, holds at least one block, and its
 * run was not aborted. That last condition matters: the first Subaru run was stopped early
 * while it swept phantom blocks, so its map is real but incomplete, and silently trusting it
 * would skip the discovery that fills the gap.
 */
/** Short buzz when a run ends. A ten-minute parked sweep is not watched, and a
 *  three-second one finishes before anyone looks up. */
private fun buzz(ctx: Context, ok: Boolean) = runCatching {
    val v = ctx.getSystemService(android.os.Vibrator::class.java) ?: return@runCatching
    val pattern = if (ok) longArrayOf(0, 120, 90, 120) else longArrayOf(0, 400)
    v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
}

class CaptureRunner(
    private val ctx: Context,
    private val ble: ElmBle,
    private val discover: DiscoverRunner,
    private val runner: ScanRunner,
) {
    var phase by mutableStateOf(CapPhase.IDLE); private set
    var status by mutableStateOf(""); private set
    var detail by mutableStateOf(""); private set
    var vinKey by mutableStateOf(""); private set
    var wmi by mutableStateOf(""); private set
    var info by mutableStateOf<VehicleId.Info?>(null); private set
    var vin by mutableStateOf(""); private set

    /** Model from the optional online lookup, or "" when it is off or found nothing. */
    var modelName by mutableStateOf(""); private set
    /**
     * The bare vPIC model, e.g. "Highlander". Separate from `modelName`, which is the
     * display label and carries the year and series with it ("2006 MCU23L/MCU28L/ACU20L/
     * ACU25L Highlander"). A contributed record must key on the bare name: the label would
     * split one model into a silo per model year, and no second Highlander would ever
     * match the first.
     */
    /** What this vehicle already has on disk, and whether this build wrote it. */
    var coverage by mutableStateOf(""); private set
    var modelClean by mutableStateOf(""); private set
    var modelSeries by mutableStateOf(""); private set

    /** OBDb repo the vPIC model resolved to, or "" — the route to signal naming. */
    private var vpicRepo = ""

    /**
     * DIDs this model's OBDb signalset could name: CSV column, signal name, unit.
     *
     * Names only. The decoded VALUE deliberately does not travel with them -- see
     * Export.namesCsv and Obd's contract note.
     */
    var named by mutableStateOf<List<Triple<String, String, String>>>(emptyList()); private set

    /** OBDb repo the names came from, for attribution in the export. */
    var namedFrom by mutableStateOf(""); private set
    var dtcs by mutableStateOf<List<Dtc.Code>>(emptyList()); private set
    var dtcRead by mutableStateOf(false); private set
    var hintNote by mutableStateOf(""); private set
    /** Protocol the adapter negotiated, e.g. "A6". Empty means the vehicle never answered. */
    var protocol by mutableStateOf(""); private set
    /** Legislated PIDs this vehicle answered the support bitmaps for. */
    var stdPids by mutableStateOf<List<String>>(emptyList()); private set
    /** Mode-21 local identifiers that answered, KWP2000 only. */
    var kwpIds by mutableStateOf<List<String>>(emptyList()); private set
    /** Mode-09 vehicle information: calibration IDs, CVNs, ECU name. */
    var info09 by mutableStateOf<List<Pair<String,String>>>(emptyList()); private set

    /**
     * How many distinct vehicles have a usable stored map.
     *
     * Shown before anything is pressed. The idle screen previously rendered nothing at all,
     * so there was no way to tell the feature was wired up until you committed to a run.
     */
    var storedMaps by mutableStateOf(-1); private set

    fun countStoredMaps() {
        val dir = File(ctx.getExternalFilesDir(null), "logs")
        val keys = HashSet<String>()
        dir.listFiles { f -> f.name.startsWith("discover-") && f.name.endsWith(".json") }
            ?.forEach { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    val k = o.optString("vin_key")
                    // Same three conditions findCached applies, so the count cannot promise
                    // a skip that the lookup will then refuse.
                    if (k.isNotEmpty() && !o.optBoolean("aborted", false) &&
                        (o.optJSONArray("blocks")?.length() ?: 0) > 0) keys.add(k)
                }
            }
        storedMaps = keys.size
    }

    val running: Boolean get() = phase == CapPhase.VIN || phase == CapPhase.DISCOVER || phase == CapPhase.DRIVE

    /**
     * Say what a reply WAS, not merely that the adapter answered.
     *
     * Every failed VIN attempt used to log `ok=true raw=NO DATA`. Both halves are true --
     * the adapter did reply, and `sawPrompt` is a transport fact -- but `ok=true` beside a
     * failure reads as success, and it is why four requests to the wrong address family
     * surfaced as a vague "VIN unreadable" instead of naming the cause.
     *
     * These outcomes point at different bugs and are worth keeping apart:
     *   NO DATA                  nobody answered -- wrong header, wrong protocol, absent PID
     *   ?                        the adapter rejected the command as malformed
     *   UNABLE TO CONNECT        protocol selection failed
     *   7F xx yy                 an ECU actively refused; the PID is genuinely unsupported
     * Only the last says the data is unavailable. The rest are transport problems.
     */
    private fun describe(r: Pair<String, Boolean>, redact: Boolean = false): String {
        val (raw, sawPrompt) = r
        if (!sawPrompt) return "NO REPLY (adapter never returned a prompt)"
        val t = raw.uppercase().replace("\r", " ").replace("\n", " ").trim()
        return when {
            t.contains("NO DATA") -> "NO DATA (nobody answered — wrong header, wrong protocol, or PID absent)"
            t.contains("UNABLE TO CONNECT") -> "UNABLE TO CONNECT (protocol selection failed)"
            t.contains("BUS INIT") && t.contains("ERROR") -> "BUS INIT: ERROR"
            t.replace(" ", "").contains("7F") -> "REFUSED by an ECU: ${t.take(40)}"
            t.isEmpty() || t == ">" -> "empty reply"
            // A VIN REPLY IS NEVER PRINTED. It carries the VIN as ASCII-in-hex, and this
            // log is written into the export bundle -- including the SCRUBBED one, which is
            // the file documented as safe to attach to a public issue. A capture-BMW.zip
            // produced on 2026-08-28 contained WBA00000000000000 for exactly this reason.
            //
            // The diagnostic value was never the content: what matters is whether anything
            // answered and roughly how much, which is what distinguishes a wrong address
            // family from an unsupported PID. That survives redaction.
            redact -> "ok: ${t.replace(" ", "").length / 2} bytes (content withheld — VIN)"
            else -> "ok: ${t.take(60)}"
        }
    }

    /** True between pressing Stop and the worker actually unwinding. */
    val stopping: Boolean get() = when (phase) {
        CapPhase.DISCOVER -> discover.stopping
        CapPhase.DRIVE -> runner.stopping
        else -> capStopping
    }

    /**
     * Set by stop(), checked by the non-CAN scan. WITHOUT THIS, STOP DID NOTHING THERE.
     *
     * The non-CAN branch never sets phase to DISCOVER -- that phase belongs to the CAN
     * DiscoverRunner -- so stop() fell through to `else -> {}` and returned. The branch also
     * had no stopFlag check of its own, so a Mode-21 sweep of 256 identifiers on a 10.4 kbaud
     * K-line ran to completion with the button doing literally nothing. Reported from a
     * Highlander on 2026-08-28: "the stop button was completely non responsive".
     */
    @Volatile private var capStop = false

    /**
     * Progress for the NON-CAN scan, which had none.
     *
     * The bar and the ETA are gated on CapPhase.DISCOVER, and that phase belongs to the CAN
     * DiscoverRunner -- the non-CAN branch runs start to finish in CapPhase.VIN. So a
     * Highlander scanned for 2 minutes 12 seconds showing a status line and nothing else,
     * the same root cause that made Stop inert there.
     *
     * Weighted by where the time actually goes rather than by step count: the Mode-21 sweep
     * is 256 of roughly 300 requests, so it owns most of the bar. Monotonic, for the reason
     * 9f1d1a8 gives -- a bar that goes backwards reads as broken.
     */
    var capPct by mutableStateOf(0); private set
    var capEta by mutableStateOf(""); private set
    private var capStart = 0L

    private fun capProgress(pct: Int) {
        capPct = maxOf(capPct, pct.coerceIn(0, 99))
        val ms = System.currentTimeMillis() - capStart
        capEta = if (capPct < 5 || ms < 8_000) "" else {
            val left = (ms.toDouble() * (100 - capPct) / capPct / 1000).toLong()
            when {
                left <= 0 -> ""
                left < 90 -> "about ${left}s left"
                else -> "about ${(left + 30) / 60} min left"
            }
        }
    }

    /** True once Stop has been pressed during a non-CAN scan. */
    private var capStopping by mutableStateOf(false)

    fun stop() {
        capStop = true
        capStopping = true
        when (phase) {
            CapPhase.DISCOVER -> discover.stop()
            CapPhase.DRIVE -> runner.stop()
            else -> {}
        }
    }

    fun start(forceDiscover: Boolean = false) {
        capStop = false
        capStopping = false
        capPct = 0; capEta = ""; capStart = System.currentTimeMillis()
        if (running) return
        // FOREGROUND SERVICE FOR THE WHOLE RUN, not just the drive.
        //
        // Samsung's power management freezes a backgrounded app -- logcat shows
        // "FreecessHandler: freeze com.redundo.obddiscover" every six seconds -- and a
        // frozen process cannot service its BLE connection, which then dies with
        // GATT status 8, CONN_TIMEOUT. That is exactly what happened on the first
        // on-car attempt: the VIN read looked like a parse failure and was really the
        // link being killed underneath it.
        //
        // The drive stage always had this protection because it started LogService.
        // Identification and the ten-minute discovery sweep did not, which is precisely
        // when the phone is most likely to be face-down on a seat with the screen off.
        LogService.start(ctx, "Capture")
        phase = CapPhase.VIN
        status = "identifying the vehicle..."
        detail = ""
        ble.runOnWorker {
            // Ask the adapter to find the bus before asking the car anything.
            status = "detecting the OBD protocol..."
            protocol = ble.detectProtocol()
            if (protocol.isEmpty()) {
                LogService.stop(ctx)
                phase = CapPhase.FAILED
                buzz(ctx, false)
                status = "the vehicle did not answer 0100 on any protocol"
                detail = "All nine OBD protocols were tried and none answered 0100, the PID " +
                    "every OBD-II vehicle must support. The adapter is replying, so this is " +
                    "not the phone or the link. Check the adapter is fully seated, and try " +
                    "another adapter if you have one — see the adapter log for each attempt."
                return@runOnWorker
            }
            // AT SH selects a CAN id, which means nothing on ISO 9141-2 or KWP2000. Skip it
            // there and let the adapter use that protocol's own addressing, so the VIN and
            // code reads below still work on a pre-2008 vehicle.
            val canBus = protocol.trimStart('A') in listOf("6", "7", "8", "9")
            // 29-BIT NEEDS 29-BIT ADDRESSES. 7DF and 7E0 do not exist on that bus.
            //
            // Measured on a Chevrolet Silverado 2500HD, 2026-08-28. The adapter had already
            // reported `auto-detected A7 (ISO 15765-4 CAN 29/500)` one step earlier, and the
            // VIN path then sent four requests to 7DF and 7E0 and got NO DATA from all four.
            // The bus was healthy -- the 0100 probe drew four ECU replies -- because with
            // AT SP 0 and no header the adapter uses the detected protocol's own functional
            // address. Sending AT SH 7DF is what broke it.
            //
            // DiscoverRunner already falls back to 29-bit; the VIN path had no equivalent.
            // The cost was total: no VIN, so no make, no hints, no vin_key, and therefore no
            // cached map -- a truck that takes an hour to sweep re-swept it on every plug-in.
            val is29 = protocol.trimStart('A') in listOf("7", "9")
            val vinBroadcast = if (is29) "DB33F1" else "7DF"
            val vinPhysical = if (is29) "DA10F1" else "7E0"
            if (canBus) {
                if (is29) ble.cmd("ATCP18")      // priority byte, 29-bit only
                ble.cmd("ATSH$vinBroadcast")
            }

            // WARM UP BEFORE ASKING FOR THE VIN.
            //
            // 0902 used to be the first real request after ATSP6, while the adapter was
            // still settling the protocol -- the classic SEARCHING... window. It timed out,
            // and because DiscoverRunner reads the VIN again later, AFTER hundreds of
            // successful probes, that second read succeeded. The result was a capture whose
            // discover.json carried wmi=WBA while the screen showed "unknown make" and the
            // export was named capture-vehicle.zip: the same car identified in one place and
            // not the other, from the same session.
            //
            // 0100 is the cheapest legislated request there is, and it forces protocol
            // selection to finish before anything that matters is asked.
            ble.cmd("0100", 6_000)

            var raw = ""; var ok = false
            for (attempt in 1..3) {
                val r = ble.cmd("0902", 6_000)
                raw = r.first; ok = r.second
                ble.log("VIN 0902 @$vinBroadcast try $attempt -> ${describe(r, redact = true)}")
                if (ok && Discover.vinFrom(raw).isNotEmpty()) break
            }

            // A VIN is a legislated Mode-09 read, but not every car answers it on the
            // functional broadcast: some only reply to the engine ECU directly. Trying 7E0
            // costs one request and is the difference between identifying the car and
            // falling back to a blind map.
            if (ok && Discover.vinFrom(raw).isEmpty()) {
                ble.cmd("ATSH$vinPhysical")
                val second = ble.cmd("0902", 6_000)
                ble.log("VIN 0902 @$vinPhysical -> ${describe(second, redact = true)}")
                if (second.second && Discover.vinFrom(second.first).isNotEmpty()) {
                    raw = second.first; ok = true
                }
                // ISO 14229 identification, not manufacturer-specific, so it is worth one
                // ask on any CAN bus when 0902 has failed twice.
                if (Discover.vinFrom(raw).isEmpty()) {
                    val third = ble.cmd("22F190", 6_000)
                    ble.log("VIN 22F190 @$vinPhysical -> ${describe(third, redact = true)}")
                    if (third.second && Discover.vinFrom(third.first).isNotEmpty()) {
                        raw = third.first; ok = true
                    }
                }
                ble.cmd("ATSH$vinBroadcast")
            }
            coverage = ""
            vin = if (ok) Discover.vinFrom(raw) else ""
            if (vin.isEmpty()) ble.log("VIN: no 17-char VIN parsed from either header")
            wmi = if (ok) Discover.wmiFrom(raw) else ""
            vinKey = if (ok) Discover.vinKey(raw) else ""
            info = if (vin.isNotEmpty()) VehicleId.identify(vin) else null

            // Optional, off by default, and never blocking the scan. Ten characters go out;
            // the six that identify this specific vehicle do not. See VinLookup.
            modelName = ""; modelClean = ""; modelSeries = ""; vpicRepo = ""
            // THE OFFLINE ANSWER FIRST, because we may already have it. vehicles/ ships a
            // pattern -> make/model/year table built from cars people scanned, and until
            // now nothing consulted it: a Subaru whose pattern JF2SJARC is IN the shipped
            // asset still came out of ADD VEHICLE as Subaru-MODEL.json with no model,
            // because the only route to a name was a network lookup that is off by default.
            //
            // That was the whole point of shipping the patterns. It costs no network and no
            // permission, and it is exactly as good as whoever contributed the record.
            if (vin.isNotEmpty()) {
                VehicleId.contributedId(vin)?.let { (_, model) ->
                    if (model.isNotEmpty()) {
                        modelClean = model
                        modelName = listOfNotNull(info?.year?.toString(), model)
                            .joinToString(" ")
                        ble.log("model from contributed records: $model")
                    }
                }
            }
            if (Session.onlineVinLookup && vin.isNotEmpty()) {
                status = "looking up the model (first 10 VIN characters)..."
                VinLookup.lookup(ctx, vin)?.let {
                    modelName = it.label
                    modelClean = it.model; modelSeries = it.series
                    vpicRepo = it.repo(ctx, info?.make ?: "")
                    ble.log("vPIC: ${VinLookup.abbreviate(vin)} -> ${it.label}")
                }
                if (modelName.isEmpty()) ble.log("vPIC: no model for ${VinLookup.abbreviate(vin)}")
            }

            // What the bundled tables know about this make, before any probing. Purely
            // informational here -- the scan still sweeps blind for what is not listed.
            val mk = info?.make ?: ""
            // Other makes this plant builds for. Extends the hint tail, never the head.
            val sib = info?.vin?.let { VehicleId.siblings(it) } ?: emptyList()
            // Looked up once: the screen reports this count and phase 0 sends exactly these.
            // Computing it twice invited the two to disagree.
            val knownReqs = if (mk.isEmpty()) emptyList() else VehicleId.supportedFor(mk)
            hintNote = if (mk.isEmpty()) "" else {
                val blocks = VehicleId.blockPrefixes(mk, also = sib)
                val hdrs = VehicleId.headers(mk, also = sib)
                // This used to read "which this tool cannot send", which stopped being true
                // when the Mode-21 sweep landed. A stale capability claim on the identity
                // card is worse than no claim: it tells the operator not to expect data the
                // scan is about to go and get.
                val m21 = if (!VehicleId.usesMode21(mk, sib)) "" else when {
                    Mode21.appliesTo(protocol) -> "  (also uses Mode 21, which this scan sweeps)"
                    Mode21.appliesToIso(protocol) ->
                        "  (also uses Mode 21 — sweeping it here is the opt-in below)"
                    else -> "  (also uses Mode 21, which applies to its non-CAN variants)"
                }
                // Header counts are a CAN fact. On K-line there is no 11-bit id to be
                // usable or unreachable, so quoting "6 usable headers, 6F1 unreachable" at
                // someone looking at an ISO 9141-2 car describes a bus they are not on.
                val oor = if (!canBus) emptyList() else VehicleId.unaddressable(mk, sib)
                val oorNote = if (oor.isEmpty()) "" else
                    "  ${oor.size} documented header(s) unreachable by this app: " +
                    oor.joinToString(", ") { "${it.first} — ${it.second}" }
                val hdrNote = if (canBus) ", ${hdrs.size} usable headers" else ""
                val known = knownReqs.size
                if (blocks.isEmpty() && known == 0) "no documented Mode-22 locations for $mk$m21$oorNote"
                else "$mk: $known known DIDs, ${blocks.size} documented blocks" +
                     "$hdrNote$m21$oorNote"
            }

            // Stored codes: one extra request, and it is the first thing anyone asks a
            // scanner for. Mode 03 is a read; codes are never cleared.
            status = "reading stored codes..."
            Dtc.load(ctx)          // lazy: only parsed when codes are actually being read
            val (dRaw, dOk) = ble.cmd("03", 4_000)
            dtcs = if (dOk) Dtc.parse(dRaw) else emptyList()
            dtcRead = dOk

            // CAN-ONLY BEYOND THIS POINT, and it is worth being explicit about why.
            //
            // Everything downstream addresses modules by CAN header -- AT SH 7DF, 7E0, 700.
            // On ISO 9141-2 or KWP2000 those commands are meaningless: addressing is by ISO
            // source and target bytes, not an 11-bit CAN id. A pre-2008 vehicle can connect
            // perfectly, answer Mode 01 and Mode 03, and still have nothing this discovery
            // can address -- a limit of the tool, not an absence of data in the car.
            //
            // The check sits HERE, after the VIN and the codes, not before them. It used to
            // return first and then tell the operator that "stored codes and standard PIDs
            // work on this vehicle" -- asserting something the run had skipped over. On a car
            // where discovery cannot run, identity and stored codes are the entire useful
            // output, so they are read and shown before stopping.
            if (!canBus) {
                // CHECK THE CACHE FIRST -- this branch returns long before the CAN path's
                // findCached call, so a non-CAN car re-scanned every single time.
                //
                // 877a6a0 taught findCached to read a non-CAN map and stopped there, which
                // fixed the lookup and not the caller: nothing on this path invoked it. A
                // 2006 Highlander proved it two minutes apart on 2026-08-28, running the
                // full Mode-01/09/21 scan twice for an identical result.
                val cachedNonCan = if (forceDiscover) null else findCached(vinKey)
                if (cachedNonCan != null) {
                    val (file, plan, _) = cachedNonCan
                    status = "known vehicle${if (wmi.isNotEmpty()) " ($wmi)" else ""} — " +
                        "${plan.second.size} parameters already mapped"
                    detail = "from ${file.name}  ·  Re-map to scan again"
                    driveStep(plan)
                    return@runOnWorker
                }

                // Not a dead end -- a different scan. The Mode-01 support bitmaps work on
                // every protocol and differ per vehicle, so this car still has a discoverable,
                // loggable parameter set even though its enhanced data is out of reach.
                status = "scanning supported standard PIDs..."
                stdPids = Mode01.supportedPids { req ->
                    if (capStop) return@supportedPids null
                    val (raw, ok) = ble.cmd(req, 4_000)
                    if (!ok) null else Obd.payloadsOf(req, raw).map { Obd.hex(it) }
                }
                ble.log("Mode-01 bitmap scan: ${stdPids.size} PIDs supported")
                capProgress(3)

                // Mode 09 describes itself the same way Mode 01 does, so this is a handful of
                // requests rather than a sweep. On a car where enhanced discovery cannot run
                // it is most of what distinguishes this vehicle's capture from another of the
                // same year and model: a calibration ID and CVN name the exact firmware.
                val nine = ArrayList<Pair<String,String>>()
                val nineProbe = Mode09.probe { req ->
                    if (capStop) return@probe null
                    val (raw, ok) = ble.cmd(req, 4_000)
                    if (!ok) null else Obd.payloadsOf(req, raw).map { Obd.hex(it) }
                }
                ble.log("Mode-09 bitmap 0900 -> ${nineProbe.bitmap ?: "no answer"}" +
                    (if (nineProbe.viaFallback) "  (probing the legislated PIDs directly)" else ""))
                for (pid in nineProbe.pids) {
                    if (capStop) break
                    val req = "09%02X".format(pid)
                    val (raw, ok) = ble.cmd(req, 4_000)
                    if (!ok) continue
                    // joinFrames, not payloadOf. A calibration ID does not fit one ISO 9141
                    // frame: this Highlander returned "3487" and stopped, because payloadOf
                    // takes the first matching line and a K-line frame holds four data
                    // bytes. Same assembler the VIN uses -- see Discover.joinFrames.
                    val pl = Discover.joinFrames(req, raw)
                    if (pl.isEmpty()) continue
                    nine.add((Mode09.NAMES[pid] ?: req) to Mode09.render(pid, pl))
                }
                info09 = nine
                ble.log("Mode-09 info: ${nine.size} items")
                capProgress(6)

                // Does Mode 22 answer here at all? Never measured before -- the app simply
                // asserted it could not. Block DISCOVERY needs CAN addressing; sending the
                // service to whoever the adapter is already talking to does not, and Mode
                // 22's identifier space is two bytes wide where Mode 21's is one. Cheap,
                // and a refusal settles it as firmly as data does. See Mode22.
                status = "checking whether this bus answers Mode 22..."
                val m22Probes = Mode22.BASE_PROBES +
                    // Bottom-anchored: measured on a BMW, a Subaru and a Ford, every
                    // populated block has a hit within its first three offsets.
                    VehicleId.blockPrefixes(mk, also = sib).flatMap { b ->
                        listOf("22%02X00".format(b), "22%02X01".format(b))
                    }
                val m22 = Mode22.probe(m22Probes) { req ->
                    if (capStop) return@probe null
                    val (raw, ok) = ble.cmd(req, 4_000)
                    if (ok) raw else null
                }
                ble.log("Mode-22 probe (${m22Probes.size} requests): ${m22.verdict} — ${m22.evidence}")
                capProgress(12)

                // KWP2000 keeps enhanced data behind Mode 21, where Mode 22 often does not
                // answer. The whole identifier space is one byte, so there is no sampling to
                // do -- 256 bare two-byte reads cover it. See Mode21 for why every request
                // is exactly two bytes and why that is a structural guarantee, not a habit.
                val kwp = ArrayList<Pair<String,String>>()
                val claimRefusals = ArrayList<Pair<String,String>>()
                if (Mode21.appliesTo(protocol) ||
                    (Session.mode21OnIso9141 && Mode21.appliesToIso(protocol))) {
                    // Was hardcoded "KWP2000", which is the wrong protocol name on the very
                    // car this opt-in was built for: the Highlander is ISO 9141-2 and the
                    // screen said KWP2000 while scanning it.
                    status = "${ble.protocolName(protocol)} — scanning Mode-21 local identifiers..."
                    var m21done = 0
                    for (req in Mode21.allRequests()) {
                        if (capStop) break
                        // 12% at entry to 92% at the end: 256 of ~300 requests live here.
                        if (m21done++ % 8 == 0) capProgress(12 + m21done * 80 / 256)
                        if (!Mode21.isSafeRequest(req)) continue      // belt and braces
                        val (raw, ok) = ble.cmd(req, 3_000)
                        if (!ok) continue
                        val pl = Obd.payloadOf(req, raw) ?: continue
                        if (pl.isNotEmpty()) kwp.add(req to Obd.hex(pl))
                    }
                    ble.log("Mode-21 scan: ${kwp.size} of 256 local identifiers answered")

                    // SECOND PASS, driven by what the first pass learned.
                    //
                    // The sweep above is exhaustive and stays that way -- see
                    // Mode21.bitmapClaims for why the bitmaps must never gate it. What they
                    // are good for is the opposite direction: an identifier the ECU's own
                    // bitmap CLAIMS and that answered nothing is the vehicle contradicting
                    // our measurement, and that deserves a second ask where a plain silent
                    // probe does not. On the Highlander that is 13 identifiers out of 256.
                    //
                    // Retried with a longer adapter timeout and the raw reply kept, so a
                    // refusal is recorded as a refusal. "conditionsNotCorrect" means come
                    // back with the engine running; "requestOutOfRange" means the bitmap
                    // overstates what is there. Both are findings. Discarding them, which
                    // is what payloadOf ?: continue did, records neither.
                    val answered = kwp.toMap()
                    val silent = Mode21.bitmapClaims(answered).filter { it !in answered }
                    if (silent.isNotEmpty()) {
                        status = "re-asking ${silent.size} identifiers the ECU claims..."
                        ble.log("Mode-21 bitmaps claim ${silent.size} identifiers that did " +
                            "not answer: ${silent.joinToString(" ")}")
                        ble.cmd("ATSTFF")                    // ~1s, up from the K-line 400ms
                        for (req in silent) {
                            if (capStop) break
                            if (!Mode21.isSafeRequest(req)) continue
                            var (raw, ok) = ble.cmd(req, 6_000)
                            // responsePending and busyRepeatRequest are the ECU asking for
                            // time, not refusing. One more ask is what they are for.
                            var nrc = if (ok) Mode21.negativeCode(raw) else null
                            if (nrc == 0x78 || nrc == 0x21) {
                                val r2 = ble.cmd(req, 6_000); raw = r2.first; ok = r2.second
                                nrc = if (ok) Mode21.negativeCode(raw) else null
                            }
                            val pl = if (ok) Obd.payloadOf(req, raw) else null
                            when {
                                pl != null && pl.isNotEmpty() -> {
                                    kwp.add(req to Obd.hex(pl))
                                    ble.log("  $req answered on retry: ${Obd.hex(pl)}")
                                }
                                nrc != null -> {
                                    claimRefusals.add(req to Mode21.negativeName(nrc))
                                    ble.log("  $req refused: ${Mode21.negativeName(nrc)}")
                                }
                                // Record what actually came back, not just that it was not
                                // data. "no reply" lumped a timeout together with a clean
                                // NO DATA, which is the same distinction the NRC pass was
                                // added to preserve -- lost one level down.
                                else -> claimRefusals.add(
                                    req to if (!ok) "timeout"
                                           else raw.replace('\r', ' ').replace('\n', ' ')
                                               .trim().take(40).ifEmpty { "empty reply" },
                                )
                            }
                        }
                        capProgress(97)
                        ble.cmd("ATST64")                    // back to the K-line setting
                        kwp.sortBy { it.first }
                    }
                }
                kwpIds = kwp.map { it.first }

                // Write BEFORE the DONE/FAILED split. What this car answered is worth
                // keeping either way, and the export path reads it off disk, not off screen.
                writeNonCanMap(stdPids, nine, kwp, nineProbe, claimRefusals, m22)

                val plan = stdPids + kwpIds
                if (plan.isNotEmpty()) {
                    Session.activePlan = "" to plan         // "" = no CAN header to set
                    phase = CapPhase.DONE
                    buzz(ctx, true)
                    LogService.stop(ctx)
                    // The overlap is stated on screen, not buried in the file. "63 Mode-21
                    // identifiers" reads as 63 new things found; on the Highlander 22 of
                    // them mirrored Mode 01, so the honest headline is the enhanced count.
                    val m22Note = when (m22.verdict) {
                        Mode22.Verdict.ANSWERED ->
                            "  Mode 22 ANSWERS on this bus (${m22.evidence}) — a two-byte " +
                            "identifier space is reachable here, which this app had assumed " +
                            "it was not. Worth a proper sweep."
                        Mode22.Verdict.SUPPORTED_EMPTY ->
                            "  Mode 22 is supported here but none of the probed identifiers " +
                            "hold data (${m22.evidence}). The service is reachable, so the " +
                            "right identifiers would answer."
                        Mode22.Verdict.UNSUPPORTED ->
                            "  Mode 22 is not supported on this bus (${m22.evidence}), so " +
                            "Mode 21 is the whole of the enhanced data here."
                        // Silence is not a no. This ECU answered 23 Mode-22 probes and 13
                        // re-asked Mode-21 identifiers without a single 7F, so it declines
                        // by saying nothing -- which means a silent probe cannot distinguish
                        // "no Mode 22 here" from "Mode 22, wrong identifiers". Saying so is
                        // the honest report; claiming either would be inventing a result.
                        Mode22.Verdict.SILENT ->
                            "  Mode 22 was probed here and nothing answered. This ECU sends " +
                            "no refusal codes at all, so that is genuinely undecided rather " +
                            "than a no — the service may be absent, or present at " +
                            "identifiers these probes did not guess."
                    }
                    val dup = kwp.count { "01" + it.first.substring(2) in stdPids }
                    status = "protocol $protocol (not CAN) — ${stdPids.size} standard PIDs" +
                        (if (kwp.isNotEmpty())
                            ", ${kwp.size - dup} enhanced Mode-21" +
                            (if (dup > 0) " (+$dup mirroring Mode 01)" else "")
                         else "")
                    // Careful about WHICH claim this makes. Mode-22 BLOCK DISCOVERY walks
                    // module by module and does need CAN ids, so that part is true. Whether
                    // the SERVICE answers at all on this bus is a separate question, and the
                    // old wording ran the two together into "this protocol does not have
                    // Mode 22" -- which the app had never tested. It is tested now; say what
                    // was measured and let m22Note carry the verdict.
                    detail = "Mode-22 block discovery walks module by module and needs CAN " +
                        "ids to do it, which this bus does not have. What IS readable is " +
                        "logged: DRIVE LOG below records these ${plan.size}, and correlate " +
                        "reads the result like any other log." +
                        (if (dup > 0)
                            "  $dup Mode-21 identifiers returned at the same low byte as a " +
                            "supported Mode-01 PID, so they are likely the same value twice. " +
                            "Both are logged rather than guessed at — on this slow bus that " +
                            "costs sample rate, so drop them if a look at the data agrees."
                         else "") + m22Note +
                        (if (Mode21.appliesTo(protocol) && kwp.isEmpty())
                            "  No Mode-21 identifier answered, so this ECU keeps nothing there."
                         else "")
                    return@runOnWorker
                }
                LogService.stop(ctx)
                phase = CapPhase.FAILED
                buzz(ctx, false)
                status = "connected on protocol $protocol — not CAN, so block discovery cannot run"
                detail = "The VIN and any stored codes are above, and they are the useful " +
                    "output here. Enhanced discovery addresses modules by CAN header, which " +
                    "does not exist on ISO 9141-2 or KWP2000. Pre-2008 vehicles also tend to " +
                    "keep enhanced data behind Mode 21, which this tool may not send."
                return@runOnWorker
            }

            // THE LEGISLATED SET, ON EVERY CAN CAPTURE -- cached or not.
            //
            // Mode01.supportedPids is protocol-agnostic by design, but only the non-CAN
            // branch called it, so a CAN capture recorded no Mode-01 data at all. Putting
            // it in the discovery branch was still wrong: every vehicle already mapped
            // skips discovery, so the cars most likely to be plugged in again were exactly
            // the ones that would never scan. Seven requests, about ten seconds, and it is
            // per-vehicle data whether or not the blocks need finding.
            status = "scanning supported standard PIDs..."
            ble.cmd("ATSH$vinBroadcast")
            stdPids = Mode01.supportedPids { req ->
                if (capStop) return@supportedPids null
                val (raw, ok) = ble.cmd(req, 4_000)
                if (!ok) null else Obd.payloadsOf(req, raw).map { Obd.hex(it) }
            }
            ble.log("Mode-01 bitmap scan: ${stdPids.size} PIDs supported")
            discover.stdPidsIn = stdPids

            val cached = if (forceDiscover) null else findCached(vinKey)
            if (cached != null) {
                val (file, plan, skipped) = cached
                // WHAT THIS CAR ALREADY HAS, on screen, so "do I need to scan this again?"
                // is answerable without pulling files off the phone.
                // WHAT THIS CAR HAS, computed after this scan folds in, so the line
                // reflects the truth as it now stands rather than what was on disk a moment
                // ago. Shows the transition: "2 → 45 standard PIDs" says the rescan was
                // worth doing, where a bare "45" leaves you wondering whether it did
                // anything.
                coverage = runCatching {
                    val o = JSONObject(file.readText())
                    val was = o.optJSONArray("mode01")?.length() ?: 0
                    val oldBuild = o.optString("build")
                    val blocks = o.optJSONArray("blocks")?.length() ?: 0
                    var now = was
                    if (stdPids.size > was) {
                        o.put("mode01", org.json.JSONArray(stdPids))
                        o.put("build", BuildTag.ID)
                        file.writeText(o.toString())
                        now = stdPids.size
                        ble.log("cached map updated: $now Mode-01 PIDs")
                    }
                    val pidText =
                        if (now != was) "$was \u2192 $now standard PIDs" else "$now standard PIDs"
                    val current = now != was || oldBuild == BuildTag.ID
                    "$blocks blocks \u00B7 $pidText" +
                        if (current) "  \u2713 current"
                        else "  \u26A0 mapped on ${oldBuild.ifEmpty { "an older build" }}"
                }.getOrDefault("")
                status = "known vehicle${if (wmi.isNotEmpty()) " ($wmi)" else ""} — " +
                    "${plan.second.size} DIDs already mapped, skipping discovery"
                detail = "from ${file.name}" +
                    (if (skipped > 0) "  (+$skipped DIDs on other headers)" else "")
                driveStep(plan)
            } else {
                // No VIN is NOT a reason to reuse someone else's map. Discovering again
                // costs ten minutes; logging the wrong car's DIDs costs the whole drive.
                status = when {
                    forceDiscover -> "re-mapping this vehicle..."
                    vinKey.isEmpty() -> "VIN unreadable — mapping from scratch to be safe"
                    else -> "new vehicle${if (wmi.isNotEmpty()) " ($wmi)" else ""} — mapping its blocks"
                }
                // Was a hardcoded "about 10 minutes" on runs measured at 19.5 -- roughly
                // half the truth, and shown beside a percentage that reset each phase. The
                // runner now estimates from its own probe rate; this line only has to be
                // true before there is anything to measure.
                detail = "stay parked, engine warm — this usually takes 15–20 minutes"
                phase = CapPhase.DISCOVER

                discover.hintedBlocks = if (mk.isEmpty()) emptyList() else VehicleId.blockPrefixes(mk, also = sib)
                discover.hintedHeaders = if (mk.isEmpty()) emptyList() else VehicleId.headers(mk, also = sib)
                discover.hinted29 = if (mk.isEmpty()) emptyList() else VehicleId.headers29(mk, also = sib)
                discover.hintedExt = mk.isNotEmpty() &&
                    VehicleId.unaddressable(mk, sib).any { it.first == "6F1" }
                discover.wmiIn = wmi
                discover.vinKeyIn = vinKey

                // Exact DIDs known to answer on this make, asked by name in phase 0. This is
                // the cheap, precise mechanism: on a Ford Ranger it reaches the six
                // documented DIDs that offset sampling misses in 181 probes, against 6656 to
                // find them by sweeping the hinted blocks. It also loses nothing, because a
                // phase-0 hit seeds its block into `found` and phase 2 then sweeps that block
                // in full -- so the whole-block coverage arrives anyway, for the blocks that
                // actually answer.
                discover.knownRequests = knownReqs
                discover.hintMake = mk

                // Block-level fallback, only where there is no census to ask from. 16 makes
                // have block hints and no supported-command data -- Fiat, Citroen, Lancia and
                // similar -- and for those this is the only mechanism that reaches a block
                // whose DIDs all sit off the seven recon offsets. Running it alongside a
                // census that already covers the same ground would just spend 6656 probes to
                // learn what 181 already established.
                discover.hintedPairs =
                    if (mk.isEmpty() || knownReqs.isNotEmpty()) emptyList()
                    else VehicleId.hintedPairs(mk, also = sib)
                discover.start {
                    // IDENTITY RECOVERED LATE IS STILL IDENTITY. When the VIN is unreadable
                    // at the addresses CaptureRunner can guess, Discover finds it once it
                    // knows which headers are live -- a 2025 Ioniq 5 has no engine ECU, so
                    // every pre-discovery attempt went to an address that does not exist.
                    // Too late for this run's hints; not too late for the record, which is
                    // written afterwards. Without this the car that most needs the fallback
                    // is the one whose contribution comes out as vehicle-MODEL.json with no
                    // make, model, year or pattern.
                    if (info == null && discover.recoveredVin.isNotEmpty()) {
                        vin = discover.recoveredVin
                        wmi = vin.take(3)
                        info = VehicleId.identify(vin)
                        ble.log("identity recovered after discovery: ${info?.make} " +
                            "${info?.year ?: ""}".trim())
                    }
                    val plan = discover.logPlan
                    // A stopped run does not roll on into a drive. The callback fires either
                    // way, so stopping DURING THE SWEEP -- once some blocks had yielded DIDs
                    // and logPlan was therefore set -- started a six-hour drive log instead
                    // of stopping. Stopping during recon happened to be safe only because no
                    // full sweep had run yet, so there was no plan to act on.
                    if (discover.aborted) {
                        LogService.stop(ctx)
                        phase = CapPhase.FAILED
                        buzz(ctx, false)
                        status = "stopped — ${discover.blocksFound} blocks found so far"
                        detail = if (plan == null || plan.second.isEmpty())
                            "The sweep had not reached them yet, so no DIDs were read. The " +
                            "discover JSON is saved with what recon found."
                        else
                            "${plan.second.size} DIDs were read before stopping and are saved. " +
                            "Run CAPTURE again to finish the sweep."
                    } else if (plan == null || plan.second.isEmpty()) {
                        LogService.stop(ctx)
                        phase = CapPhase.FAILED
                        buzz(ctx, false)
                        status = "no Mode-22 blocks answered — nothing to log"
                        detail = "this make may not use Mode 22 for enhanced data " +
                            "(Toyota's is largely Mode 21). The discover JSON is still saved."
                    } else {
                        // Phrased as a match, not an identification. The last attempt at
                        // naming the model in this app was abandoned for being wrong too
                        // often, and it was decoding VIN positions. This says what the car
                        // answered like, which is a claim the capture can be checked against.
                        val like = when (discover.matchedModels.size) {
                            0 -> ""
                            1 -> " — answers like a ${discover.matchedModels[0]}"
                            else -> " — answers like a ${discover.matchedModels.joinToString(" or ")}"
                        }
                        val logged = maxOf(discover.logPlanAll.size, plan.second.size)
                        // NAME WHAT WE CAN. Only with the toggle on, only for a model OBDb
                        // documents, and only a minority even then -- 55 signals on a
                        // Silverado against the 1,929 DIDs a sweep of one finds. Saying which
                        // of them we can read is still the first answer this app has given to
                        // "what does this DID mean" rather than "which DIDs answer".
                        named = emptyList(); namedFrom = ""
                        if (Session.onlineVinLookup) {
                            // vPIC first -- it is authoritative and it works. The signature
                            // match is kept only as a fallback and has never produced a hit.
                            val repo = vpicRepo.ifEmpty {
                                discover.matchedModels.firstOrNull()
                                    ?.let { VehicleId.repoFor(mk, it) } ?: ""
                            }
                            if (repo.isNotEmpty() && SignalSet.load(ctx, repo)) {
                                namedFrom = repo
                                named = discover.allHits.flatMap { (h, req, pl) ->
                                    SignalSet.decode(h, req, pl).map {
                                        Triple("$req@$h", it.name, it.unit)
                                    }
                                }.distinctBy { it.first + it.second }
                                ble.log("named ${named.size} of ${discover.allHits.size} " +
                                    "DIDs from OBDb/$repo")
                            }
                        }
                        val refused = discover.refusedButPresent
                        status = "mapped ${discover.blocksFound} blocks, $logged DIDs$like" +
                            (if (refused > 0) "  ($refused more refused, not absent)" else "") +
                            (if (named.isNotEmpty()) "  · ${named.size} named" else "")
                        driveStep(plan)
                    }
                }
            }
        }
    }

    private fun driveStep(plan: Pair<String, List<String>>) {
        Session.activePlan = plan          // whichever way it was obtained
        phase = CapPhase.DRIVE
        // Says what to do AND what the alternative is. A parked session -- mapping a car
        // to provoke it rather than drive it -- otherwise looks like the app is demanding a
        // drive, when stopping here is a perfectly good outcome and is what makes the
        // CONTROLS TEST button appear.
        detail = "DRIVE NOW — 10-15 min of varied load.\n" +
            "Only wanted the map? Tap Stop; CONTROLS TEST and EXPORT appear after."
        Session.triage = null
        // All headers when discovery produced them; the single-header plan otherwise (a
        // cached map, or a non-CAN car). Session.activePlan keeps the single-header form so
        // KEEP DRIVING and the UI are unaffected.
        val all = discover.logPlanAll
        runner.start("discovered", plan.second, plan.first, 1.0,
                     multi = if (all.size > plan.second.size) all else emptyList()) { f ->
            LogService.stop(ctx)
            if (f == null) {
                phase = CapPhase.FAILED
                buzz(ctx, false); status = "the drive log wrote no file"; return@start
            }
            val t = runCatching { Triage.run(f) }.getOrNull()
            Session.triage = t
            Session.continueFile = if (t?.enoughSamples == false) f else null
            phase = CapPhase.DONE
            buzz(ctx, true)
            // Say plainly whether this log is USABLE, not just that it finished. A drive
            // that stops short of correlate's floor produces a file that looks fine and
            // analyses to nothing, which is only discovered at a laptop an hour later.
            status = if (t == null) "drive saved (could not check it)"
            else if (!t.enoughSamples)
                "INCOMPLETE — ${t.rows} rows, correlate needs ${Triage.MIN_SAMPLES}. Tap KEEP DRIVING."
            else if (t.strong.isEmpty() && t.weak.isEmpty())
                "COMPLETE — ${t.rows} rows, but nothing correlated. More load variation would help."
            else
                "COMPLETE — ${t.rows} rows, ${t.strong.size} strong / ${t.weak.size} weak candidates"
            detail = "${t?.varying ?: 0}/${t?.didColumns ?: 0} DIDs moved · saved ${f.name}"
        }
    }

    /** Newest usable map for this car: right VIN key, has blocks, and finished cleanly. */
    private fun findCached(key: String): Triple<File, Pair<String, List<String>>, Int>? {
        if (key.isEmpty()) return null
        val dir = File(ctx.getExternalFilesDir(null), "logs")
        val files = dir.listFiles { f -> f.name.startsWith("discover-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() } ?: return null
        for (f in files) {
            try {
                val o = JSONObject(f.readText())
                if (o.optString("vin_key") != key) continue
                if (o.optBoolean("aborted", false)) continue      // incomplete: map again
                val det = o.optJSONArray("detail") ?: continue
                val byHeader = LinkedHashMap<String, MutableList<String>>()
                for (i in 0 until det.length()) {
                    val b = det.getJSONObject(i)
                    val hdr = b.optString("header", "")
                    val full = b.optJSONArray("full_hits") ?: continue
                    for (j in 0 until full.length()) {
                        val req = full.optJSONArray(j)?.optString(0, "") ?: ""
                        if (req.isNotEmpty()) byHeader.getOrPut(hdr) { mutableListOf() }.add(req)
                    }
                }
                if (byHeader.isEmpty()) {
                    // A NON-CAN MAP HAS NO full_hits, and used to fail this loop and fall
                    // through to "new vehicle". So a 2006 Highlander re-ran its entire
                    // Mode-01/09/21 scan on every plug-in even though the app already knew
                    // exactly what it found. Its plan lives in mode01 and mode21_ids instead,
                    // and the empty header is correct: on K-line AT SH selects nothing.
                    val plan = ArrayList<String>()
                    o.optJSONArray("mode01")?.let { a ->
                        for (j in 0 until a.length()) plan.add(a.optString(j))
                    }
                    o.optJSONArray("mode21_ids")?.let { a ->
                        for (j in 0 until a.length()) plan.add(a.optString(j))
                    }
                    val clean = plan.filter { it.length >= 4 && it.all { c -> c in "0123456789ABCDEF" } }
                    if (clean.isEmpty()) continue
                    return Triple(f, "" to clean, 0)
                }
                val best = byHeader.maxByOrNull { it.value.size } ?: continue
                val skipped = byHeader.entries.filter { it.key != best.key }.sumOf { it.value.size }
                return Triple(f, best.key to best.value, skipped)
            } catch (_: Exception) { /* unreadable file: try the next */ }
        }
        return null
    }

    /**
     * Persist what a non-CAN vehicle answered, in the same discover-*.json shape.
     *
     * WHY THIS EXISTS -- observed on a 2006 Highlander, 2026-08-27. The ISO 9141-2 branch
     * did all of its work in memory and returned. The screen said DONE and lit EXPORT, but
     * nothing had been written, so Export.build fell through to the newest discover-*.json
     * on disk -- which belonged to a DIFFERENT CAR scanned earlier that morning. The bundle
     * was not empty, it was wrong, and named after the Toyota. Silence would have been
     * better; this file is what makes the export honest.
     *
     * `blocks` is deliberately an empty list rather than an omitted key: obd_scan's
     * `sweep --blocks-from` reads this file, and "no Mode-22 blocks, and we looked" is a
     * true statement about this vehicle that the schema can already express.
     *
     * The payload split is a privacy boundary, not tidiness. `mode01` and `mode21_ids` are
     * WHICH identifiers answered -- the discovery, and the part worth sharing. `mode21` and
     * `mode09` carry what they returned, and a one-byte identifier on an unknown ECU may
     * hand back a VIN or a serial. Those two keys stay out of Export's scrub whitelist, so
     * they leave the phone only in a RAW export the owner asked for about their own car.
     */
    private fun writeNonCanMap(
        stdPids: List<String>,
        nine: List<Pair<String, String>>,
        kwp: List<Pair<String, String>>,
        nineProbe: Mode09.Probe,
        claimRefusals: List<Pair<String, String>>,
        m22: Mode22.Result,
    ): File? {
        if (stdPids.isEmpty() && nine.isEmpty() && kwp.isEmpty()) return null
        return try {
            val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val f = File(dir, "discover-${System.currentTimeMillis()}.json")
            val o = JSONObject()
            o.put("wmi", wmi)
            o.put("vin_key", vinKey)
            o.put("build", BuildTag.ID)
            o.put("preset", "generic")
            o.put("protocol", protocol)
            o.put("addressing", "none — $protocol is not CAN")
            o.put("aborted", false)
            o.put("blocks", org.json.JSONArray())
            o.put("detail", org.json.JSONArray())
            o.put("mode01", org.json.JSONArray(stdPids))
            o.put("mode21_ids", org.json.JSONArray(kwp.map { it.first }))

            // Mode-21 identifiers whose low byte is a supported Mode-01 PID.
            //
            // Measured on a 2006 Highlander: the set answering Mode 21 was EXACTLY the set
            // answering Mode 01, plus 00 and 20 -- and 2100 returned BF9FA891, which is bit
            // for bit the Mode-01 support bitmap that the independent 0100 scan produced.
            // So on that ECU Mode 21 mirrors Mode 01 over this range rather than adding to
            // it, and a count of "63 identifiers" over-reports what was actually found.
            //
            // Recorded, not removed. The bitmap identity is proof for 2100/2120; for the
            // data PIDs it is strong inference, and dropping a real reading on an inference
            // costs more than a duplicate column does. Whoever reads this file can see the
            // overlap and decide.
            val overlap = kwp.map { it.first }
                .filter { "01" + it.substring(2) in stdPids }
            o.put("mode21_overlaps_mode01", org.json.JSONArray(overlap))
            // Why an identifier the ECU claimed still gave nothing. A refusal is a finding
            // about the vehicle; recording it as absence loses the difference.
            o.put("mode21_claim_refusals", org.json.JSONArray().apply {
                claimRefusals.forEach { put(org.json.JSONArray(listOf(it.first, it.second))) }
            })
            // Whether this ECU refuses in words at all.
            //
            // The Highlander answered 23 Mode-22 probes and 13 re-asked Mode-21 identifiers
            // with 36 silences and not one 7F. An ECU that never sends a negative response
            // can only say no by saying nothing, which makes silence uninformative HERE in
            // a way it is not on a KWP2000 car. Worth stating in the file: it tells the next
            // reader how much a "silent" verdict below is actually worth.
            val sawNegative = claimRefusals.any { it.second.startsWith("7F") || it.second in
                setOf("conditionsNotCorrect", "requestOutOfRange", "serviceNotSupported") } ||
                m22.verdict == Mode22.Verdict.SUPPORTED_EMPTY ||
                m22.verdict == Mode22.Verdict.UNSUPPORTED
            o.put("ecu_uses_negative_responses", sawNegative)
            o.put("mode22_verdict", m22.verdict.name)
            o.put("mode22_evidence", m22.evidence)
            o.put("mode22_hits", org.json.JSONArray().apply {
                m22.hits.forEach { put(org.json.JSONArray(listOf(it.first, it.second))) }
            })
            o.put("mode09_bitmap", nineProbe.bitmap ?: JSONObject.NULL)
            o.put("mode09_via_fallback", nineProbe.viaFallback)
            o.put("mode21", org.json.JSONArray().apply {
                kwp.forEach { put(org.json.JSONArray(listOf(it.first, it.second))) }
            })
            o.put("mode09", org.json.JSONArray().apply {
                nine.forEach { put(org.json.JSONArray(listOf(it.first, it.second))) }
            })
            f.writeText(o.toString(1))
            ble.log("wrote ${f.name}: ${stdPids.size} PIDs, ${nine.size} Mode-09, ${kwp.size} Mode-21")
            f
        } catch (e: Exception) {
            // Not fatal to the capture -- the scan happened and is on screen. But the
            // operator must not be left thinking a file exists when it does not.
            ble.log("could not write non-CAN map: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
