package com.redundo.obddiscover

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The `log` stage, ported from obd_scan/stages.py run_log().
 *
 * THE CSV FORMAT IS A CONTRACT, not a choice. Alan's correlate stage parses these files, so
 * the columns must match run_log byte for byte:
 *
 *   iso_time, uptime_ms, <request>@<header> per hit..., then the anchors in ANCHORS order
 *
 * Hits are stored as RAW HEX and anchors as decoded numbers. That asymmetry is deliberate
 * and load-bearing (run_log's own comment): a wrong decode guess made in the field must not
 * destroy data that a better guess at home could still use. The anchors are decoded because
 * correlation needs numbers to correlate against.
 */
object Obd {

    /** Generic Mode-01 PIDs present on any OBD-II vehicle. Order matches cat.ANCHORS. */
    val ANCHORS = linkedMapOf(
        "rpm" to "010C",
        "speed" to "010D",
        "load" to "0104",
        "coolant" to "0105",
        "maf" to "0110",
        "baro" to "0133",
        "ambient" to "0146",
        // The two beyond catalog.ANCHORS, and why they earn their columns:
        //
        // fuel 012F -- resolution is 100/255 = 0.39%, about 0.072 gal on an 18.5 gal tank,
        // roughly 1.8 miles at 25 mpg. Without it NO fuel- or range-class DID has anything
        // to correlate against, which is exactly why the BMW's range could not be confirmed
        // from a 568-DID drive log even with a dash photo of it.
        //
        // distance 0131 -- distance since codes cleared, a second independent distance
        // reference. Would have confirmed the odometer DIDs 221700 / 22480A / 2258A1 without
        // needing a photograph at all.
        //
        // COMPATIBILITY: obd_scan's correlate takes the intersection of cat.ANCHORS and the
        // columns present, and treats everything else as a candidate. So these two appear as
        // two extra rows in its report until catalog.py gains them -- the file still parses
        // and every other column behaves exactly as before. That is a smaller cost than a
        // fuel-class DID being unidentifiable.
        "fuel" to "012F",
        "distance" to "0131",
    )

    /**
     * SAE J1979 re-serves the Mode-01 PIDs at DIDs F400-F4FF, so 22F410 carries the same
     * payload as 0110. Mirrors catalog.ANCHOR_MIRRORS.
     *
     * Not theoretical: obd_scan's own comment records a 2021 F-350 that answers 22F410 and
     * 22F446 while ignoring 0110 and 0146, so two of seven anchors logged 0 of 64 rows and
     * correlate declared them unusable for a whole drive. The failure is silent -- an empty
     * column looks exactly like a car that has no MAF sensor.
     */
    val ANCHOR_MIRRORS: Map<String, String> get() = ANCHORS.mapValues { "22F4" + it.value.substring(2) }

    /** Ported verbatim from stages._decode_anchor. Same formulas, same order of operations. */
    fun decodeAnchor(name: String, payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val a = payload[0].toInt() and 0xFF
        val b = if (payload.size > 1) payload[1].toInt() and 0xFF else 0
        return when (name) {
            "rpm" -> (((a shl 8) + b) / 4.0).toString()
            "speed" -> a.toDouble().toString()
            "load" -> (a * 100.0 / 255.0).toString()
            "coolant" -> (a - 40).toDouble().toString()
            "maf" -> (((a shl 8) + b) / 100.0).toString()
            "baro" -> a.toDouble().toString()
            "ambient" -> (a - 40).toDouble().toString()
            "fuel" -> (a * 100.0 / 255.0).toString()          // percent of tank
            "distance" -> ((a shl 8) + b).toDouble().toString() // km since codes cleared
            else -> ""
        }
    }

    /**
     * Pull the payload out of an ELM reply, or null if there wasn't a positive one.
     *
     * A positive response echoes the request with the mode raised by 0x40 (01->41, 22->62)
     * and the rest of the request bytes, then the data. Anything else -- NO DATA, a 7F
     * negative response, SEARCHING, an ELM error -- yields null.
     *
     * Multi-frame handling is not optional on a functional broadcast. Measured on a BMW F10:
     * 7DF broadcast a second module appends its own `7F2222` refusal on a SEPARATE line
     * after the good frame. Scanning line by line and taking the first positive one keeps
     * that NAK out of the data -- the exact trap that made oil pressure decode as one byte.
     */
    /**
     * The lines of a reply, with CAN multi-frame runs folded back into whole messages.
     *
     * A CAN frame holds eight bytes and a Mode-22 reply spends four of them on the PCI, the
     * 0x62 and the two identifier bytes -- so an answer of more than FOUR data bytes cannot
     * be one frame. ISO-TP splits it, and the ELM327 (auto-formatting is on by default and
     * this app never sends ATCAF0) hands back a length line and indexed frames:
     *
     *   00B
     *   0:620078012C01
     *   1:45015E0170AAAA
     *
     * Every one of those lines fails a startsWith("620078") test, and a reply carrying no
     * 0x7F is not a refusal either -- so the identifier was recorded as ABSENT, which in the
     * output is indistinguishable from one the vehicle does not implement. A missing name is
     * invisible; a missing IDENTIFIER is worse, because the sweep will not ask again.
     *
     * Measured across 5,859 hits from seven vehicles before this existed: 1-byte 3,377,
     * 2-byte 1,792, 3-byte 97, 4-byte 593, and NOTHING above four. That is not a taper, it
     * is a wall standing exactly at the single-frame ceiling. Every wide answer those cars
     * ever gave -- multi-sensor blocks, anything with flags packed beside a value -- was
     * dropped on the floor in silence. Found by cheeseprince against a GM profile that knew
     * what the truck was supposed to answer (#6); none of our own cars could have told us.
     *
     * K-LINE LINES ARE LEFT ALONE, DELIBERATELY. On ISO 9141 every line repeats the response
     * prefix and its own sequence byte, so two ECUs answering look exactly like one long
     * message -- joining them is what produced the phantom C0300. Only an explicit `N:` index
     * is treated as a continuation, because only it actually says so.
     */
    fun messages(raw: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        var declared = -1
        var nextIdx = -1
        fun flush() {
            if (buf.isNotEmpty()) {
                var hex = buf.toString()
                // The length line counts the message's own bytes, which is what trims the
                // padding the last frame carries. Without it a 20-byte answer decodes 21
                // bytes long and the tail is the ECU's pad byte. Trimming by the declared
                // length rather than stripping a known pad value is what lets a genuine
                // final 0x55 or 0xAA survive -- obd_scan's assemble_multiframe reaches the
                // same conclusion independently, and pads differ by ECU anyway.
                //
                // SHORT MEANS DROPPED, NOT SMALL. If the fragments do not add up to the
                // declared length a frame went missing, and the honest answer is silence:
                // a truncated payload decodes as a plausible wrong number, which is worse
                // than an identifier we ask about again. Also from obd_scan, which guards
                // the same case and states the reason better than the first draft here did.
                if (declared > 0) {
                    if (hex.length < declared * 2) { buf.setLength(0); declared = -1; nextIdx = -1; return }
                    if (hex.length > declared * 2) hex = hex.substring(0, declared * 2)
                }
                out.add(hex)
                buf.setLength(0)
            }
            declared = -1; nextIdx = -1
        }
        for (line in raw.split('\r', '\n', '>')) {
            val t = line.uppercase().replace(" ", "").trim()
            if (t.isEmpty()) continue
            val colon = t.indexOf(':')
            if (colon in 1..2 && t.take(colon).all { it in "0123456789ABCDEF" }) {
                val idx = t.take(colon).toInt(16) and 0xF
                // A frame index that does not follow the last one is a different message,
                // not a gap in this one. The counter is four bits, so F is followed by 0 --
                // comparing against the expected next index gets the wrap right and still
                // splits two replies that each start at 0.
                if (buf.isNotEmpty() && idx != nextIdx) flush()
                buf.append(t.substring(colon + 1))
                nextIdx = (idx + 1) and 0xF
                continue
            }
            flush()
            // A bare three-digit line is the ISO-TP length in bytes, belonging to the frames
            // that follow. It is never a payload: a payload is an even number of hex digits.
            if (t.length == 3 && t.all { it in "0123456789ABCDEF" }) { declared = t.toInt(16); continue }
            out.add(t)
        }
        flush()
        return out
    }

    /**
     * EVERY payload in a reply, not just the first.
     *
     * A functional broadcast is answered by every ECU on the bus, and they do not agree.
     * A Silverado answers 0100 with four lines -- three modules saying 80000001 ("PID 01
     * and nothing else") and the engine ECU saying BFDFB993 -- and payloadOf() returns
     * whichever arrived first. That made a supported-PID scan a race: the same truck
     * scored 2, 12, 6, 0 and 6 PIDs across five runs, and a 2025 Ioniq 5 scored zero
     * because all three of its first responders support nothing.
     *
     * What a VEHICLE supports is the union of what its modules support, so the caller
     * needs all of them.
     */
    fun payloadsOf(request: String, raw: String): List<ByteArray> {
        val req = request.uppercase().replace(" ", "")
        if (req.length < 2) return emptyList()
        val mode = req.substring(0, 2).toIntOrNull(16) ?: return emptyList()
        val expect = "%02X".format(mode + 0x40) + req.substring(2)
        val out = ArrayList<ByteArray>()
        for (t in messages(raw)) {
            if (!t.startsWith(expect)) continue
            val hex = t.substring(expect.length)
            if (hex.isEmpty() || hex.length % 2 != 0) continue
            if (!hex.all { it in "0123456789ABCDEF" }) continue
            out.add(ByteArray(hex.length / 2) {
                ((Character.digit(hex[it * 2], 16) shl 4) or
                    Character.digit(hex[it * 2 + 1], 16)).toByte()
            })
        }
        return out
    }

    fun payloadOf(request: String, raw: String): ByteArray? {
        val req = request.uppercase().replace(" ", "")
        if (req.length < 2) return null
        val mode = req.substring(0, 2).toIntOrNull(16) ?: return null
        val expect = "%02X".format(mode + 0x40) + req.substring(2)
        for (t in messages(raw)) {
            if (!t.startsWith(expect)) continue
            val hex = t.substring(expect.length)
            if (hex.isEmpty() || hex.length % 2 != 0) continue
            if (!hex.all { it in "0123456789ABCDEF" }) continue
            return ByteArray(hex.length / 2) {
                ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte()
            }
        }
        return null
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}

/** One DID to poll, and the CAN header to ask it under. */
data class Poll(val request: String, val header: String)

/**
 * Runs a logging session on a worker thread and writes the CSV incrementally.
 *
 * Every row is flushed the moment it is written. run_log does the same, for the same
 * reason: a lost logging session costs someone a drive, and that outweighs an fsync
 * per row. Here it outweighs it by more -- a cold start cannot be repeated for six hours.
 */
class ScanRunner(private val ctx: Context, private val ble: ElmBle) {

    var running by mutableStateOf(false); private set
    var rows by mutableStateOf(0); private set
    var lastError by mutableStateOf<String?>(null); private set
    var outFile by mutableStateOf<File?>(null); private set
    var noDataPolls by mutableStateOf(0); private set
    val latest = mutableStateMapOf<String, String>()      // column -> newest value, for the UI

    /**
     * Operator-set label written into a trailing `mark` column, for provocation runs.
     *
     * Without it a toggle is INVISIBLE in the data: A/C coming on looks identical to a signal
     * drifting, and the analysis would be reduced to guessing where the boundaries were. The
     * column only appears when [withMark] is set, so ordinary logs keep the exact seven-anchor
     * shape that obd_scan's correlate stage expects.
     */
    /**
     * Operator-set label. Setting it also resets [rowsAtMark], because the useful question
     * during a controls test is not how long the log is but how many samples THIS step has.
     */
    var mark: String = ""
        set(value) { field = value; markStartRow = rows; _markState = value }
        get() = _markState
    private var _markState by mutableStateOf("")
    private var markStartRow by mutableStateOf(0)

    /**
     * Rows logged since the current step began.
     *
     * A field that responds to a switch changes once and stays changed, so three samples are
     * enough to establish a level -- but the first controls test on a car produced about two
     * rows per step with no way for the operator to know that, because the screen showed only
     * a total. A step advanced after one sample cannot distinguish a response from noise.
     */
    val rowsAtMark: Int get() = (rows - markStartRow).coerceAtLeast(0)

    /**
     * Which form of each anchor this vehicle actually answers: the Mode-01 PID or its
     * Mode-22 mirror. Decided once per anchor, then reused.
     *
     * Deciding once matters. Trying both every row would double the anchor cost on every
     * car to protect the few that need it, and the anchors are already 9 of the requests in
     * a row that may hold hundreds.
     */
    private val anchorForm = HashMap<String, String>()

    /** Anchor value, falling back to the Mode-22 mirror the first time the PID is silent. */
    private fun readAnchor(name: String, pid: String): Double? {
        anchorForm[name]?.let { req ->
            val (raw, ok) = ble.cmd(req)
            val pl = if (ok) Obd.payloadOf(req, raw) else null
            return pl?.let { Obd.decodeAnchor(name, it).toDoubleOrNull() }
        }
        for (req in listOf(pid, Obd.ANCHOR_MIRRORS[name] ?: pid).distinct()) {
            val (raw, ok) = ble.cmd(req)
            val pl = if (ok) Obd.payloadOf(req, raw) else null
            val v = pl?.let { Obd.decodeAnchor(name, it).toDoubleOrNull() }
            if (v != null) {
                anchorForm[name] = req
                if (req != pid) ble.log("anchor $name: $pid silent, using mirror $req")
                return v
            }
        }
        return null
    }

    @Volatile private var stopFlag = false

    /** Set the instant Stop is pressed. See DiscoverRunner.stopping for why. */
    var stopping by mutableStateOf(false); private set

    fun stop() { stopFlag = true; stopping = true }

    /**
     * @param anchors decoded reference columns. Defaults to the full seven, which are the
     *               FORMAT CONTRACT that lets obd_scan's correlate stage read these files.
     *               Override it only for a run that will be analysed directly — a trimmed
     *               set makes the CSV faster but no longer correlate-compatible.
     * @param hits   DIDs to log as raw hex, in column order
     * @param hz     upper bound on cycle rate -- NOT a guarantee. Each cycle round-trips
     *               every hit plus all seven anchors sequentially over one BLE link, so the
     *               link, not this number, sets the real rate.
     * @param header CAN header every hit is asked under (7DF on this car)
     */
    fun start(
        name: String,
        hits: List<String>,
        header: String,
        hz: Double,
        anchors: Map<String, String> = Obd.ANCHORS,
        withMark: Boolean = false,
        appendTo: File? = null,
        multi: List<Poll> = emptyList(),
        onFinished: (File?) -> Unit,
    ) {
        if (running) return
        stopFlag = false
        running = true
        stopping = false
        anchorForm.clear()
        rows = 0
        noDataPolls = 0
        lastError = null
        latest.clear()

        ble.runOnWorker {
            var f: File? = null
            try {
                val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }

                // A plan may span headers now, so a poll carries its own. Grouped so ATSH is
                // sent once per header per row rather than once per request.
                val polls = if (multi.isNotEmpty()) multi
                            else hits.map { Poll(it, header.ifEmpty { "std" }) }
                val cols = polls.map { "${it.request}@${it.header}" } + anchors.keys.toList() +
                    (if (withMark) listOf("mark") else emptyList())
                val header0 = (listOf("iso_time", "uptime_ms") + cols).joinToString(",")

                // APPEND, when asked and only when the columns match exactly.
                //
                // A drive that stops short of correlate's 30-sample floor is not salvageable
                // by driving again into a SECOND file: correlate reads one CSV, so the first
                // 25 rows would simply be stranded. Appending is what makes "keep driving"
                // actually work.
                //
                // Column identity is checked rather than assumed. Appending rows with a
                // different DID set would produce a file that parses but silently misaligns
                // -- far worse than starting a new one.
                //
                // uptime_ms continues from the previous last row, so the column stays
                // monotonic across the join. correlate reads neither time column (it uses
                // only the DID and anchor columns), so this is honesty rather than
                // necessity: a reader should not see the clock jump backwards.
                var resumeMs = 0L
                val appending = appendTo != null && appendTo.exists() &&
                    appendTo.useLines { it.firstOrNull() }?.trim() == header0
                if (appending) {
                    f = appendTo
                    resumeMs = appendTo!!.useLines { seq ->
                        seq.lastOrNull { it.isNotBlank() }?.split(",")?.getOrNull(1)?.trim()?.toLongOrNull()
                    } ?: 0L
                } else {
                    f = File(dir, "$name-${System.currentTimeMillis()}.csv")
                }
                outFile = f
                if (appendTo != null && !appending) {
                    lastError = "columns differ from the previous log — started a new file"
                }

                val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }

                java.io.BufferedWriter(java.io.FileWriter(f, appending)).use { out ->
                    if (!appending) {
                        out.write(header0)
                        out.newLine()
                    }
                    out.flush()

                    // The header is selected ONCE, not per probe. That is most of the speed
                    // advantage -- re-sending ATSH before every request roughly doubles the
                    // command count for no benefit.
                    // Protocol already negotiated by CaptureRunner; see detectProtocol.
                    // An empty header means a non-CAN protocol, where AT SH selects nothing
                    // meaningful -- the adapter's own addressing for that bus is correct and
                    // sending a CAN id would only confuse it.
                    // Single-header plans still select once, outside the loop. Multi-header
                    // plans select per group inside it -- see the poll loop.
                    val multiHeader = polls.map { it.header }.distinct().size > 1
                    // applyHeader, not a bare ATSH: a plan may include BMW extended headers
                    // like "6F1@12", and a stale receive filter blocks Mode-01 afterwards.
                    var ext = false
                    if (!multiHeader && header.isNotEmpty()) ext = Discover.applyHeader(ble, header, ext)
                    var curHdr = if (multiHeader) "" else header

                    val t0 = System.currentTimeMillis() - resumeMs   // continue the clock
                    val cycleMs = (1000.0 / hz).toLong()

                    while (!stopFlag) {
                        val cycleStart = System.currentTimeMillis()
                        val cells = ArrayList<String>(cols.size)

                        // STOP IS CHECKED BETWEEN POLLS, not just between rows.
                        //
                        // A row is one request per DID plus the seven anchors -- 253 of them
                        // on a Subaru, 575 on the BMW -- and the loop used to run every one
                        // before looking at the flag again. At the end of a drive that is the
                        // worst possible moment to ask: the car is being parked, the ECU goes
                        // quiet, and requests stop being answered in tens of milliseconds and
                        // start costing the full 2 s timeout each. Measured in the field:
                        // Stop took about 45 seconds to take effect, and the button sat on
                        // "Stopping..." for all of it.
                        //
                        // Worst case is now one in-flight request.
                        var abandoned = false

                        // ANCHORS BRACKET THE ROW, and the stored value is the mean.
                        //
                        // A row is not a snapshot: 246 DIDs take 19 s to collect, and the
                        // anchors used to be read at the END of that. So a DID read early was
                        // being compared against an anchor measured 19 s later. Measured on
                        // the Subaru drive of 2026-08-28, speed moves 19.9 km/h per row --
                        // 27% of its whole range -- while coolant moves 4%.
                        //
                        // That biased correlate exactly backwards. Slow anchors matched
                        // cleanly (coolant reached r=0.99) and fast ones were structurally
                        // handicapped (speed topped out at 0.71), so a temperature-shaped
                        // artifact outranked anything dynamic.
                        //
                        // Interleaving the seven through the DIDs does NOT fix it -- it only
                        // moves each anchor's sample point, and a DID at position 200 is
                        // still far from an anchor read at position 20. One stored value has
                        // to stand for the whole window, so the value that minimises the
                        // mismatch is the window's average. Reading before and after and
                        // taking the mean halves the error, 10.4 km/h to 5.0, and unlike a
                        // single midpoint sample it survives a mid-row acceleration.
                        //
                        // Seven extra requests on a 253-request row: +2.8%.
                        val pre = HashMap<String, Double>()
                        if (multiHeader && header.isNotEmpty() && curHdr != header) {
                            ext = Discover.applyHeader(ble, header, ext); curHdr = header
                        }
                        for ((aName, aReq) in anchors) {
                            if (stopFlag) { abandoned = true; break }
                            readAnchor(aName, aReq)?.let { pre[aName] = it }
                        }

                        for (p in polls) {
                            if (stopFlag) { abandoned = true; break }
                            if (multiHeader && p.header != curHdr && p.header != "std") {
                                ext = Discover.applyHeader(ble, p.header, ext); curHdr = p.header
                            }
                            val (raw, sawPrompt) = ble.cmd(p.request)
                            if (!sawPrompt) { noDataPolls++; cells.add(""); continue }
                            val pl = Obd.payloadOf(p.request, raw)
                            val v = if (pl == null) "" else Obd.hex(pl)
                            if (pl == null) noDataPolls++
                            cells.add(v)
                            latest["${p.request}@${p.header}"] = if (v.isEmpty()) "NO DATA" else v
                        }
                        if (multiHeader && header.isNotEmpty() && curHdr != header) {
                            ext = Discover.applyHeader(ble, header, ext); curHdr = header
                        }
                        for ((aName, aReq) in anchors) {
                            if (stopFlag) { abandoned = true; break }
                            val post = readAnchor(aName, aReq)
                            val before = pre[aName]
                            // Either sample alone is still better than nothing; only when
                            // both are missing is the cell genuinely empty.
                            val v = when {
                                before != null && post != null -> ((before + post) / 2.0).toString()
                                post != null -> post.toString()
                                before != null -> before.toString()
                                else -> ""
                            }
                            cells.add(v)
                            // The screen shows the LATEST reading, not the row average --
                            // someone watching the dash wants the current number.
                            latest[aName] = (post ?: before)?.toString() ?: "-"
                        }
                        // A half-collected row is not a short row, it is a row whose later
                        // columns are blank for a reason that has nothing to do with the
                        // vehicle. correlate would read those blanks as data. Drop it.
                        if (abandoned) break

                        val now = System.currentTimeMillis()
                        if (withMark) cells.add(mark)
                        out.write(
                            (listOf(iso.format(Date(now)), (now - t0).toString()) + cells)
                                .joinToString(","),
                        )
                        out.newLine()
                        out.flush()
                        rows++

                        val spent = System.currentTimeMillis() - cycleStart
                        if (spent < cycleMs) Thread.sleep(cycleMs - spent)
                    }
                }
            } catch (e: Exception) {
                // Surfaced, never swallowed: rows already flushed are real and stay on disk,
                // but the operator has to know the log ended early rather than completed.
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                ble.log("LOG ERROR: ${lastError}")
            } finally {
                running = false
                onFinished(f)
            }
        }
    }
}
