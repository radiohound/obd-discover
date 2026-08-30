package com.redundo.obddiscover

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import org.json.JSONObject

/**
 * Find which Mode-22 blocks a vehicle implements when NOTHING is known about it.
 *
 * Sweep.kt walks a hand-built list of BMW blocks. That is useless on a car nobody has
 * mapped: obd_scan's `sweep` can only target blocks a preset already declares, so a make
 * with no preset (Subaru, Toyota, VW, Honda...) has nothing to sweep at all. This is the
 * stage that produces that missing list, and it is the app's first vehicle-agnostic piece --
 * no BMW DIDs, no hardcoded 7DF.
 *
 * It is the Kotlin twin of obd_scan's `discover` stage (tools/obd_scan/stages.py). The
 * output JSON is deliberately the SAME schema the Python `sweep --blocks-from` reads, so a
 * capture from this phone feeds the host chain with no editing.
 *
 * TWO PHASES, because they answer different questions.
 *
 *   1. RECON -- a few offsets in each of the 256 candidate blocks. Answers "which blocks
 *      exist". A blind full sweep is 65536 requests per header, ~1.5 h at the ~12 probes/s
 *      this BLE link actually sustains (measured across three F10 logs), so this is the
 *      only tractable way in.
 *   2. FULL -- every one of the 256 offsets in each block recon found, at the header that
 *      answered. Answers "where do the hits sit INSIDE a block", which recon cannot: the
 *      offset choice below rests on the claim that blocks are bottom-anchored, and that
 *      claim is currently n=1 (one BMW). Phase 2 is what tests it on a second make.
 *
 * WHY THESE OFFSETS. Measured on the F10 sweep of 2026-08-24 (462 answering DIDs in 6
 * blocks): every populated block had a hit below 0x08, 2242xx was contiguous 0x00-0x07, and
 * 2244xx/2245xx held ALL their hits below 0x40. Probing 0x00-0x03 alone found 6 blocks of 6.
 * 0x40/0x80/0xC0 are insurance for a make whose layout is not bottom-anchored -- which is
 * exactly what this run is trying to find out, so they stay until a second car reports.
 *
 * WHY EVERY LIVE HEADER, not just the broadcast. Whether enhanced Mode 22 answers a
 * functional broadcast is vehicle-specific: the F10 answered 462 DIDs on 7DF, while the Jeep
 * WS's confirmed DIDs are physical-only. Broadcast-only would find everything on one and
 * nothing on the other, and "0 blocks" would read as "this car has no enhanced data" -- a
 * false negative worth avoiding on a car we are trying to characterise.
 *
 * SAFETY. Mode 22 is a READ service (ReadDataByIdentifier) and this emits nothing else.
 * Headers are the powertrain set only -- 7DF/7E0/7E1/7E2 -- never 7E3-7E7, which can reach
 * ADAS modules on some platforms.
 *
 * PRIVACY. The VIN is read to identify the MAKE, and only the 3-character WMI is written
 * out. The remaining 14 characters identify the individual vehicle and are dropped before
 * anything reaches a file.
 */
object Discover {

    /** Recon offsets. Matches catalog.DISCOVER_OFFSETS in the Python exactly. */
    /**
     * Recon offsets. ALL SEVEN STAY -- this was measured, decided, and is not to be
     * re-proposed as an optimisation.
     *
     * The case for dropping 0x40/0x80/0xC0 is real and was checked: across 40 blocks found
     * on a BMW, a Ford and a Subaru, not one was discovered by a high offset alone -- every
     * block had a hit somewhere in 0x00-0x03. Dropping the three would cut a Ford run 27%
     * (11,200 probes to 8,128) and a BMW run 20%, with no observed loss.
     *
     * Rejected anyway, on the owner's call: completeness over time. Forty blocks across
     * three makes is evidence, not proof, and the failure it would buy is the silent kind
     * -- a block that exists, is never recon'd, and is therefore reported absent. The
     * Subaru already shows how close it runs: 2211xx and 2212xx answered at 0x00 and then
     * only at 0x40/0x80/0xC0. A make whose blocks start one offset higher loses everything.
     *
     * This is the same trade as the hint tables (see VehicleId.kt:70-74) and it is settled
     * the same way. A scan that is 20% faster and quietly incomplete is worth less than one
     * that takes the extra five minutes.
     */
    val OFFSETS = listOf(0x00, 0x01, 0x02, 0x03, 0x40, 0x80, 0xC0)

    /**
     * Floor for the assumed block count before recon finishes, for sizing the estimate.
     *
     * Measured: 9 on a Subaru, 15 on a Ford, 16-17 on a BMW. Set above all of them so the
     * estimate rarely has to grow mid-run, which is the direction that makes a bar reverse.
     *
     * IT WAS CALIBRATED ON THE WRONG CARS. A GM Global B truck answers with 38 blocks and
     * our own Silverado with 40 -- double this. On that truck the sweep was sized at
     * 20 x 256 = 5,120 probes when the truth was 9,728, so the estimate ran about a quarter
     * low for the whole of recon and then jumped a third when recon ended and the real count
     * landed. That is the underestimate reported in #7: the count DOES enter the arithmetic,
     * but until recon finishes it enters as this constant.
     */
    const val BLOCK_PRIOR = 20


    /**
     * The block count to size the sweep with before recon has counted them.
     *
     * The hint table already knows roughly how many blocks a make has -- 32 for GM against
     * a measured 38-40, 7 for Subaru against 9 -- and that is strictly better information
     * than one constant for every vehicle. Taking the larger of the two keeps the existing
     * bias: over-estimating means the denominator DROPS when recon ends and the bar jumps
     * forward, which is the direction that does not feel broken.
     */
    fun blockPrior(hintedBlocks: Int): Int = maxOf(BLOCK_PRIOR, hintedBlocks)

    /**
     * BMW extended addressing. Written "6F1@12": tester 6F1, target byte 12, RX filter 612.
     *
     * BMW F-series enhanced data is not at 7DF or 7E1. The tester is 0x6F1 and the module is
     * chosen by an ISO-TP target byte (AT CEA) with a matching receive filter at 0x600+target
     * (AT CRA). Without it a BMW is read through the generic powertrain pool, which reaches
     * the DME and little else -- which is why BMW captures find two responders where a Ford
     * finds four. That is a property of the addressing, not the car.
     *
     * Evidence it matters: a 568-DID drive log of an F10 could not produce range-to-empty
     * even with a dash photograph of it (33 miles) to match against. Range is computed by the
     * instrument cluster, and the cluster is not reachable at 7DF or 7E1 -- it never could be.
     *
     * TARGETS ARE CURATED, NOT SWEPT. Sweeping all 256 would reach chassis and ADAS modules,
     * and obd_scan records a 2018 Audi Q5 where reading a driver-assist controller's DIDs
     * tripped pre-sense warnings on the dash. These two are the powertrain targets its
     * BMW_HEADERS documents: 12 is the DME (oil pressure 586F), 18 carries oil temp DA25 and
     * ATF DA12.
     */
    val BMW_TARGETS = listOf("12", "18")

    /**
     * Apply a header, including extended addressing when the name carries a target.
     *
     * Clearing matters as much as setting: a stale 618 receive filter left in place blocks
     * ordinary Mode-01 afterwards, so leaving an extended header sends bare ATCEA/ATCRA to
     * turn it off. Mirrors elm.set_header.
     */
    fun applyHeader(ble: ElmBle, header: String, extActive: Boolean): Boolean {
        val at = header.substringBefore('@')
        val target = header.substringAfter('@', "")
        ble.cmd("ATSH$at")
        return if (target.isNotEmpty()) {
            ble.cmd("ATCEA$target")
            ble.cmd("ATCRA6$target")
            true
        } else {
            if (extActive) { ble.cmd("ATCEA"); ble.cmd("ATCRA") }
            false
        }
    }

    /** Weight of the newest ETA sample. Low enough to steady it, high enough to react. */
    const val ETA_SMOOTH = 0.2

    /** Extra attempts for a probe the adapter never answered. gallia uses 3 total. */
    const val MAX_RETRY = 2

    /** Consecutive unanswered probes after which retrying is futile: the link is gone. */
    const val DEAD_LINK = 20

    /**
     * Retries wait far less than the first attempt, because the first attempt is what the
     * 2 s budget is for.
     *
     * Measured on a Subaru: an answered probe costs 61 ms, a timeout costs the full 2 s. So
     * 51 timeouts were 1.2% of the probes and TWENTY-EIGHT PERCENT of the elapsed time.
     * Retrying twice at the full timeout would have added up to 57% to that run -- a fix for
     * a 1.2% data loss, paid for with half the scan again.
     *
     * A retry is chasing a dropped notification or a missed write. If the adapter is going
     * to answer it does so in tens of milliseconds; 600 ms is ten times the measured figure
     * and caps the worst case near 17% while leaving the common case free.
     *
     * Mode-22 discovery is CAN-only, so this does not touch the K-line paths, which are
     * slow on purpose (see ElmBle's ATAT1/ATST64 handling).
     */
    const val RETRY_TIMEOUT_MS = 600L

    /** One probe's outcome: the reply, whether a prompt came back, and attempts spent. */
    data class Attempt(val raw: String, val sawPrompt: Boolean, val sent: Int)

    /**
     * Send until the adapter answers, or the attempts run out.
     *
     * Extracted so the property the cost depends on is testable rather than asserted in a
     * comment: ANY reply that comes with a prompt ends the loop immediately. A refusal, a
     * positive and a NO DATA all cost exactly one send. Only a stalled link costs more, and
     * that was 1.2% of probes on a real car.
     *
     * @param consecutiveDead unanswered probes immediately before this one. Past [DEAD_LINK]
     *        the link is gone rather than flaky, and retrying only triples a lost run.
     */
    fun sendWithRetry(
        consecutiveDead: Int,
        maxRetry: Int = MAX_RETRY,
        deadLink: Int = DEAD_LINK,
        abandon: () -> Boolean = { false },
        send: (attempt: Int) -> Pair<String, Boolean>,
    ): Attempt {
        var dead = consecutiveDead
        var sent = 0
        while (true) {
            val (raw, sawPrompt) = send(sent)
            sent++
            if (sawPrompt) return Attempt(raw, true, sent)
            dead++
            // Stop has to be honoured HERE, not merely between probes. A stalled probe
            // already costs 2 s; retrying it twice more made Stop take up to 3.2 s to be
            // noticed, which is the wrong direction for the one control that has to feel
            // immediate.
            if (abandon() || sent > maxRetry || dead >= deadLink) return Attempt(raw, false, sent)
        }
    }

    /**
     * ISO 14229 reserves F180-F1FF for identification, and recon cannot reach the useful
     * part of it: 0xF190 sits between the probed 0x80 and 0xC0, so a vehicle whose F1 block
     * answers ONLY there is recorded as not having one. A Subaru's 22F1xx was found because
     * it happened to answer at 0x00; a BMW's and a Ford's were not found at all.
     *
     * Asked directly rather than by widening OFFSETS. Adding these four to the recon list
     * would cost four more probes on all 256 blocks at every live header -- 4,096 on a
     * four-header Ford. Asking for the five identifiers themselves costs five per header,
     * about 20, and reaches the same data. Completeness, at a rounding error.
     */
    val IDENT_DIDS = listOf(
        "22F187" to "manufacturer spare part number",
        "22F18C" to "ECU serial number",
        "22F190" to "VIN",
        "22F191" to "ECU hardware number",
        "22F195" to "supplier software version",
    )

    /**
     * Powertrain headers to try, in order, as (AT setup commands, header name).
     *
     * BOTH addressing modes, because an unlisted car's is unknown and guessing wrong looks
     * exactly like a car with no enhanced data. 11-bit is tried first since it is far more
     * common; the 29-bit set is the fallback and is not merely theoretical -- the Jeep WS
     * in this project's catalog has an entirely dead 11-bit path and answers only on
     * 18DA18F1. A run that reported "0 blocks" purely because it assumed ATSP6 would be a
     * false negative recorded as a finding.
     *
     * Deliberately never 7E3-7E7, which can reach ADAS modules on some platforms.
     */
    val HEADERS_11BIT = listOf("7DF", "7E0", "7E1", "7E2")
    val HEADERS_29BIT = listOf("DB33F1", "DA10F1", "DA18F1", "DA1AF1", "DA28F1")

    const val SERVICE = 0x22

    /**
     * The 3-character WMI from a Mode-09 PID-02 reply, or "" if it cannot be read.
     *
     * A VIN reply is MULTI-FRAME, and that is the whole difficulty. With headers off an
     * ELM327 prints an ISO-TP length line then indexed continuation frames:
     *
     *     014
     *     0: 49 02 01 57 42 41
     *     1: 46 52 37 43 35 58 42
     *     2: 43 31 32 33 34 35 36
     *
     * The first Subaru run matched a bare 17-character pattern against that TEXT and
     * returned "014" -- the length line -- as the WMI. So: strip the frame indices, join
     * the hex, find the 4902 response, and read the ASCII out of the BYTES. The VIN is
     * ASCII inside the payload; it is never the hex digits that represent it.
     *
     * Only the WMI is returned. The remaining 14 characters identify the individual
     * vehicle and are deliberately never handed back to a caller that writes files.
     */
    /**
     * A stable per-vehicle key derived from the VIN, WITHOUT storing the VIN.
     *
     * WMI alone is the manufacturer -- every Subaru shares one -- so it cannot answer "have
     * I already mapped THIS car". The full VIN could, but it identifies the vehicle and its
     * owner and has no business sitting in a file that gets shared with a project. A
     * truncated SHA-256 is stable for one car, useless for identifying it, and cannot be
     * reversed into a VIN.
     */
    fun vinKey(raw: String): String {
        val vin = vinFrom(raw)
        if (vin.isEmpty()) return ""
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(vin.toByteArray())
        return d.take(4).joinToString("") { "%02x".format(it) }
    }

    /** The WMI (first 3 chars) only. */
    fun wmiFrom(raw: String): String = vinFrom(raw).take(3)

    /**
     * The full VIN from a Mode-09 PID-02 reply, or "" if unreadable.
     *
     * Shown on screen, never written to a file. Those are different acts: the owner reading
     * their own VIN off their own dashboard is unremarkable, while a VIN inside a capture
     * that gets attached to a pull request identifies a specific car and its owner to
     * everyone who reads the thread. Files carry wmiFrom() and vinKey() only.
     */
    /**
     * Join every frame of a multi-frame reply into one payload, prefix stripped.
     *
     * STRIP EACH LINE'S OWN PREFIX BEFORE JOINING. The two reply shapes differ, and joining
     * first corrupts one of them:
     *
     *   CAN:        014 / 0:490201574241 / 1:46523743355842 / 2:43313233343536
     *               one 4902 prefix, then indexed continuation frames of pure data
     *   ISO 9141:   4902010000004A / 49020254454550 / 49020332314138 / ...
     *               EVERY line repeats 4902 plus its own sequence byte
     *
     * A 2006 Highlander answered in the second shape and the VIN was thrown away, because
     * 0x49 is ASCII 'I': joining the lines whole spliced an 'I' between each four-byte
     * chunk, turning JTE00000000000000 into JITEEPI21A8I6014I2539 with no seventeen-
     * character run left to match.
     *
     * THIS IS SHARED ON PURPOSE. The same Highlander then returned a calibration ID of
     * "3487" -- frame one of several -- because Mode 09's other PIDs went through
     * Obd.payloadOf, which takes the FIRST matching line and stops. Two functions that both
     * assemble ISO 9141 frames will drift, and this reply shape has now cost four separate
     * bugs (the phantom C0300, the corrupted VIN, the Mode-09 bitmap offset, this).
     * One implementation, used everywhere frames must be joined.
     */
    fun joinFrames(request: String, raw: String): String {
        val expect = "%02X".format(
            (request.substring(0, 2).toIntOrNull(16) ?: return "") + 0x40,
        ) + request.substring(2)
        val hex = StringBuilder()
        for (line in raw.split('\r', '\n', '>')) {
            var t = line.trim().uppercase()
            if (t.isEmpty()) continue
            val colon = t.indexOf(':')
            if (colon in 1..2) t = t.substring(colon + 1)        // CAN continuation index
            t = t.replace(" ", "")
            if (!t.all { it in "0123456789ABCDEF" }) continue
            if (t.length <= 3 && hex.isEmpty()) continue          // ISO-TP length line
            // A line carrying its own prefix + sequence byte contributes only what follows.
            // A bare continuation frame contributes all of itself.
            hex.append(
                if (t.startsWith(expect) && t.length > expect.length + 2)
                    t.substring(expect.length + 2)
                else t,
            )
        }
        return hex.toString()
    }

    fun vinFrom(raw: String): String {
        val body = joinFrames("0902", raw)
        val bytes = ByteArray(body.length / 2) {
            ((Character.digit(body[it * 2], 16) shl 4) or Character.digit(body[it * 2 + 1], 16)).toByte()
        }
        val ascii = bytes.map { (it.toInt() and 0xFF).toChar() }
            .filter { it.isLetterOrDigit() }.joinToString("")
        val m = Regex("[A-HJ-NPR-Z0-9]{17}").find(ascii) ?: return ""
        return m.value
    }
}

data class DiscoveredBlock(
    val name: String,
    val prefix: Int,
    val header: String,
    val reconHits: List<String>,
    val fullHits: MutableList<Pair<String, String>> = mutableListOf(),   // request to payload
    /**
     * Whether all 256 offsets of this block have actually been asked.
     *
     * WITHOUT THIS A RUN CANNOT BE RESUMED HONESTLY. A block the sweep never reached and a
     * block the sweep finished and found nothing in are both "no full hits", and a resumed
     * run that cannot tell them apart either re-does work it has done or skips work it has
     * not. The file has to say which.
     */
    var swept: Boolean = false,
    /**
     * How many SEPARATE runs have swept this block and found nothing in it.
     *
     * An empty block is never treated as done -- ten of twelve captures contain no empty
     * blocks at all, so re-queueing them costs nothing on a healthy car and repairs an
     * interrupted one by itself. But a car really can contradict its own recon, so the
     * escape hatch is repetition across runs: seen empty twice, in different sessions, it is
     * believed. One run cannot decide this, because one run is exactly what an outage is.
     */
    var emptyRuns: Int = 0,
    /**
     * The vehicle state this block was swept in, and when.
     *
     * A map built across several sessions is a blend, and without this nobody can unpick it.
     * A block swept at a cold soak and a block swept at warm idle are answers to different
     * questions, and merging them silently produces a record that is true of no vehicle in
     * any single state. Recorded per block rather than per capture, because in a resumed map
     * the capture-level label only describes the session that happened to finish it.
     */
    var state: String = "",
    var sweptAt: String = "",
)

class DiscoverRunner(private val ctx: Context, private val ble: ElmBle) {

    var running by mutableStateOf(false); private set
    var progress by mutableStateOf(""); private set
    var probes by mutableStateOf(0); private set

    // Split so the file can say WHERE the time went. Recon dominating a run is the single
    // most useful number here and was not recoverable from the total.
    private var probesKnown = 0
    private var probesRecon = 0
    private var probesSweep = 0

    /**
     * Running totals that only ever go UP.
     *
     * The per-block hit count used to be the only number on screen, and it resets at every
     * block boundary -- so a working run looked like it climbed to ~30 and lost everything,
     * over and over, with no way to tell progress from failure. These accumulate across the
     * whole run instead. `didsFound` counts confirmed DIDs, `blocksFound` counts blocks.
     */
    var blocksFound by mutableStateOf(0); private set
    var didsFound by mutableStateOf(0); private set
    /** 0-100 through the current phase, so a long recon does not look stalled. */
    /**
     * Progress across the WHOLE run, not the current phase.
     *
     * It used to be per-phase, so it ran 0->100 three times and the number on screen meant
     * something different depending on which phase you happened to be watching. Combined
     * with a hardcoded "about 10 minutes" on runs that take 19.5, the screen told an
     * operator 19 minutes in that a nearly-finished scan was a quarter done. Two BMW runs
     * were stopped by hand on 2026-08-27; the first had 514 of its 571 DIDs still ahead of
     * it, in blocks the sweep had not reached.
     */
    var pct by mutableStateOf(0); private set

    /** Remaining time from the measured probe rate, or "" before there is enough to say. */
    var eta by mutableStateOf(""); private set

    private var runStartMs = 0L
    private var knownTotal = 0
    private var reconTotalP = 0
    private var estBlocks = 0
    private var pctFloor = 0
    private var etaSecs = 0.0

    /**
     * The FIRST estimate this run committed to, kept so the file can grade it.
     *
     * An estimate that only ever appears on screen is never checked against anything: the
     * run ends, the number is gone, and the next argument about whether it was any good is
     * settled by recollection. Written beside elapsed_s, so every capture carries its own
     * prediction and its own outcome and nobody has to remember either (#7, #10).
     *
     * The first one rather than the last, because the last is near zero by construction and
     * grades nothing. This is the number a person actually plans around.
     */
    private var etaFirstSecs = 0.0

    /**
     * Sweep cost cannot be known until recon ends, so until then it is a PRIOR, not an
     * extrapolation.
     *
     * The extrapolation -- blocks found so far, scaled by how much of recon is done -- ran
     * the bar backwards, and the old comment claiming otherwise guarded the wrong direction.
     * It stopped the estimate falling below blocksFound; the problem is it RISING. Blocks do
     * not arrive uniformly: hinted high bytes are probed first, so a car whose real blocks
     * sit outside the hints finds nothing and then several at once. Each arrival multiplied
     * up by the remaining recon fraction inflates the denominator far faster than `done`
     * grows. Simulated on a three-header car finding 9 blocks in a burst: 12% -> 10% -> 5%.
     *
     * A flat prior removes the swing entirely. Measured counts are 9 (Subaru), 15 (Ford),
     * 16-17 (BMW), so 20 is high enough that it rarely has to grow -- and when recon ends
     * with fewer, the denominator DROPS and the bar jumps forward, which is the direction
     * that does not feel broken.
     *
     * [pctFloor] is the backstop. A progress bar must never go backwards even if the
     * arithmetic says so; the ETA is where bad news belongs, and it is not clamped.
     */
    private fun overall(done: Int) {
        val sweepEst = maxOf(estBlocks, blocksFound) * 256
        val total = maxOf(knownTotal + reconTotalP + sweepEst, 1)
        val raw = (done.toLong() * 100 / total).toInt().coerceIn(0, 99)
        pctFloor = maxOf(pctFloor, raw)
        pct = pctFloor
        val ms = System.currentTimeMillis() - runStartMs
        // ENOUGH SAMPLES BEFORE SAYING ANYTHING. The rate is elapsed/done, so at the old
        // floor of 40 probes a single stalled probe -- which costs a full 2 s where an
        // answered one costs 61 ms -- moved the estimate from 22 minutes to 31. Waiting for
        // 400 probes puts that same stall at under 7%.
        if (done < 400 || ms < 15_000) { eta = ""; return }
        val estimate = ((total - done).toDouble() * ms / done) / 1000.0
        // Then smooth what is left. The estimate is honest either way; this only stops it
        // flickering between two values while the underlying number barely moves. Not
        // clamped in either direction -- an ETA getting worse is information, and clamping
        // it is the mistake the progress bar already made once.
        etaSecs = if (etaSecs <= 0.0) estimate
                  else etaSecs * (1 - Discover.ETA_SMOOTH) + estimate * Discover.ETA_SMOOTH
        if (etaFirstSecs <= 0.0) etaFirstSecs = etaSecs
        val left = etaSecs.toLong()
        eta = when {
            left <= 0 -> ""
            left < 90 -> "about ${left}s left"
            else -> "about ${(left + 30) / 60} min left"
        }
    }
    var phase by mutableStateOf(""); private set
    var outFile by mutableStateOf<File?>(null); private set

    /**
     * (header, requests) for the header that answered with the most DIDs, ready to hand
     * straight to ScanRunner -- which is what turns this map into Alan's drive.csv.
     *
     * ONE header, because ScanRunner polls a single header per run. Most vehicles answer
     * everything on one; where they do not, this takes the richest and the rest need a
     * second run. The count is surfaced in the UI so a split is visible rather than silent.
     */
    var logPlan by mutableStateOf<Pair<String, List<String>>?>(null); private set
    var logPlanSkipped by mutableStateOf(0); private set

    /** Every discovered DID with the header it answered on, richest header first. */
    var logPlanAll by mutableStateOf<List<Poll>>(emptyList()); private set

    @Volatile private var stopFlag = false

    /**
     * Set the instant Stop is pressed, so the screen can say so before the worker unwinds.
     *
     * WHY: `stop()` only raises a flag. The worker is inside a BLE round trip and cannot
     * notice until it returns, so for up to a couple of seconds nothing on screen changed --
     * the button still read "Stop" and was still enabled. That is indistinguishable from a
     * press that did not register, so it gets pressed again, which is exactly the complaint.
     */
    var stopping by mutableStateOf(false); private set

    /** True if the last run ended because Stop was pressed rather than finishing. */
    var aborted by mutableStateOf(false); private set

    /**
     * Models whose documented locations were ALL found here. Several means a genuine tie,
     * not a failure to decide -- Bolt EV and Bolt EUV are one platform.
     */
    var matchedModels by mutableStateOf<List<String>>(emptyList()); private set

    /** Every hit as (header, request, payload), for naming against a signalset. */
    var allHits by mutableStateOf<List<Triple<String, String, String>>>(emptyList()); private set

    /** Count of identifiers the vehicle declined for a reason other than "does not exist". */
    val refusedButPresent: Int get() = refusals.size
    fun stop() { stopFlag = true; stopping = true }

    /**
     * Ask once. Returns (positive?, payload, moduleNakked?).
     *
     * ONLY A POSITIVE counts as evidence a block is populated. A `7F` does not, and getting
     * that wrong is the single biggest error this stage can make: a module implementing
     * Mode 22 NAKs EVERY unsupported DID across the whole 16-bit space, so a NAK says "this
     * module speaks Mode 22", never "this block holds something".
     *
     * Measured, not theorised. The first Subaru run (2026-08-25) scored NAKs as block
     * evidence and reported 256 of 256 blocks present where 4 had data; the follow-up sweep
     * then chased 252 phantoms -- 65536 probes instead of 1024 -- and was killed before it
     * reached the real blocks above 2216xx.
     *
     * The NAK is still returned, because it distinguishes two very different zero results:
     * "Mode 22 works here, these offsets found nothing" from "nothing spoke Mode 22 at all".
     */
    /**
     * The header every probe is currently aimed at, so a refusal can be attributed.
     *
     * ATSH is sent once per header rather than per probe -- that is most of the speed of
     * this scan -- so the only way `ask` can know who answered is to be told.
     */
    private var curHeader = ""

    private var extActive = false
    private fun selectHeader(h: String) {
        curHeader = h
        extActive = Discover.applyHeader(ble, h, extActive)
    }

    /**
     * Refusal codes seen, per header. PHASE 1 OF THE NRC PLAN: recorded, acted on nowhere.
     *
     * The question this exists to answer is whether these vehicles say anything other than
     * `0x31 requestOutOfRange` when they decline. Both reference implementations of DID
     * discovery -- Fraunhofer's gallia and pylessard/python-udsoncan -- treat the refusal
     * code as the primary evidence, and ISO 14229 defines a whole conditions block at
     * 0x81-0x94 (EngineIsNotRunning, VehicleSpeedTooLow, BrakeSwitchNotClosed...) that does
     * not merely prove a DID exists but says what to do to read it.
     *
     * None of that is worth building on until we know these cars emit it. A 2006 Highlander
     * answered 36 probes across two passes without a single 7F, and the last time this app
     * reasoned from refusals without measuring them first, a Subaru reported 256 of 256
     * blocks present when 4 held data.
     */
    private val nrcByHeader = LinkedHashMap<String, LinkedHashMap<Int, Int>>()

    /** Probes still unanswered after retries. gallia counts these separately. */
    private var timeouts = 0
    /** Extra probes spent retrying. Small by construction -- see ask(). */
    private var retries = 0
    /** Consecutive unanswered probes, to tell a flaky link from a dead one. */
    private var consecutiveDead = 0

    /**
     * Retry ONLY where the adapter itself did not answer, and only there.
     *
     * The distinction is the whole design. `sawPrompt == false` means no '>' came back: the
     * link stalled, the dongle missed a write, BLE dropped a notification. That is rare --
     * measured at 51 of 4,309 probes on a Subaru, 1.2% -- so retrying every one of them
     * costs about 2% and is worth it.
     *
     * A refusal is NOT retried. The ECU answered; asking again gets the same answer. That
     * Subaru returned 4,114 refusals and every single one was 0x31 requestOutOfRange --
     * retrying those would have doubled the run to learn nothing.
     *
     * "NO DATA" is not retried either, and this is the line that matters most. It comes back
     * with a prompt, so the adapter is fine; it is the ordinary reply for an unsupported DID
     * on a car that stays silent rather than refusing. On such a vehicle almost every probe
     * is NO DATA, and retrying them would triple a full sweep.
     *
     * NOT scoped to blocks already known to answer, deliberately. A timeout during recon on
     * a block nothing has found yet is the expensive case: it does not lose one DID, it
     * loses the evidence that a whole block exists. Header liveness is worse still -- one
     * probe decides a header, and losing it loses every block reachable only through it. A
     * Subaru's 7A2 answered on one run and not the next, on the same car.
     */
    /**
     * Requests the vehicle REFUSED for a reason other than "no such identifier".
     *
     * Phase 2 of the NRC plan, and the Subaru is why it took a second vehicle to justify.
     * That car answered 8,347 refusals across two runs with one distinct code, 0x31
     * requestOutOfRange -- which is in gallia's own absent-set, so reverse matching would
     * have recovered nothing and the plan was shelved.
     *
     * A Chevrolet Silverado 2500HD then produced four codes in one run, including 105
     * securityAccessDenied and 9 conditionsNotCorrect. Those say the identifier EXISTS and
     * was declined: the first is behind an unlock, the second wants a different vehicle
     * state. 116 DIDs recorded as absent that are not.
     *
     * RECORDED, NEVER PROMOTED. These do not create a block and are not counted as DIDs
     * found. Letting a refusal stand as block evidence is exactly what produced 256 of 256
     * blocks present on that Subaru when 4 held data, and the fix for it was to count
     * positives only. This keeps that rule and writes the refusals down beside it.
     */
    private val refusals = LinkedHashMap<String, String>()

    private fun ask(req: String): Triple<Boolean, String?, Boolean> {
        val a = Discover.sendWithRetry(consecutiveDead, abandon = { stopFlag }) { n ->
            if (n == 0) ble.cmd(req) else ble.cmd(req, Discover.RETRY_TIMEOUT_MS)
        }
        retries += a.sent - 1
        val raw = a.raw
        if (!a.sawPrompt) { timeouts++; consecutiveDead += a.sent; return Triple(false, null, false) }
        consecutiveDead = 0
        val pl = Obd.payloadOf(req, raw)
        if (pl != null && pl.isNotEmpty()) return Triple(true, Obd.hex(pl), false)
        // Parse `7F 22 <nrc>` rather than searching the reply for "7F".
        //
        // The old test was `clean.contains("7F")`, which cannot tell a Mode-22 refusal from
        // a Mode-21 one on the same line, and matches any reply text that happens to contain
        // those two characters. It fed speaks_mode22, which is the evidence the CLI uses to
        // separate "Mode 22 works, these offsets are empty" from "nothing spoke Mode 22" --
        // two zero results needing opposite responses.
        val nrc = Mode22.negativeCode(raw)
        if (nrc != null) {
            nrcByHeader.getOrPut(curHeader) { LinkedHashMap() }
                .merge(nrc, 1) { a, b -> a + b }
            // gallia's absent-set: these five mean "no such identifier". Anything else is
            // the ECU declining something it has.
            if (nrc !in setOf(0x11, 0x12, 0x31, 0x7E, 0x7F) && refusals.size < 500) {
                refusals["$curHeader|$req"] = Mode21.negativeName(nrc)
            }
        }
        return Triple(false, null, nrc != null)
    }

    /**
     * Blocks to sweep FIRST, from the bundled per-make table. Empty means no hints.
     *
     * These reorder the sweep; they never replace it. A hinted block that answers gives
     * usable data in the first minute instead of the eleventh, which matters when the
     * operator is sitting in a parked car deciding whether this is working.
     */
    var hintedBlocks: List<Int> = emptyList()

    /** Headers this make must not be probed on at all. See VehicleId.excludedHeaders. */
    var excludedHeaders: Set<String> = emptySet()

    /**
     * Progress carried in from an earlier run on this same vehicle.
     *
     * A map is a queue of independent block jobs, each about a minute, and until now the
     * position in that queue was thrown away the moment a run ended early -- so a scan that
     * got 80% of the way through a GM truck was worth exactly as much as one that never
     * started. Resuming needs three facts and no more: which blocks exist, which of them
     * have actually been swept end to end, and whether recon finished.
     *
     * EMPTY IS NOT DONE. A block swept with no hits comes back on the queue, because ten of
     * twelve captures contain no empty blocks at all -- so re-queueing costs nothing on a
     * healthy car, and on an interrupted one it repairs the outage by itself with no
     * threshold to tune. Believed only after two separate runs have found it empty, since
     * one run finding a block empty is exactly what an outage looks like.
     */
    var resumeBlocks: List<DiscoveredBlock> = emptyList()
    var resumeReconDone: Boolean = false

    /**
     * Something the operator can still do something about, while they are standing there.
     *
     * A capture that records nine consecutive empty blocks is a ruined run discovered
     * afterwards; a message at the third one is a run somebody saves by turning the ignition
     * back on. Cleared as soon as a block answers again.
     */
    var warning by mutableStateOf(""); private set
    /** Extra headers this make is known to use, e.g. Toyota's 700. */
    var hintedHeaders: List<String> = emptyList()

    /** Extra 29-bit targets for this make, full six-character form. See VehicleId.headers29. */
    var hinted29: List<String> = emptyList()

    /** True when this make documents 6F1 extended addressing. See Discover.BMW_TARGETS. */
    var hintedExt: Boolean = false

    /** VIN identity CaptureRunner already established, so it is not read a second time. */
    /** Full VIN found by the late recovery, or "" -- memory only, never written. */
    var recoveredVin: String = ""

    /** Supported Mode-01 PIDs, scanned by Capture before discovery. */
    var stdPidsIn: List<String> = emptyList()
    var wmiIn: String = ""
    var vinKeyIn: String = ""

    /** (header, block) pairs to sweep in FULL, not merely sample. See VehicleId.hintedPairs. */
    var hintedPairs: List<Pair<String, Int>> = emptyList()

    /** Exact (header, request) pairs known supported for this make. See VehicleId.supportedFor. */
    var knownRequests: List<Pair<String, String>> = emptyList()

    /**
     * The make the hint tables resolved to, or "" if none did.
     *
     * Recorded because `preset` was a hardcoded literal `"generic"` and an outside reader
     * took it, reasonably, as the result of a lookup -- and concluded across four captures
     * that the hint database was never consulted. It was: this Ford's 720 and 726 headers
     * came from it, and they hold 130 of its 257 DIDs. A field that always says the same
     * thing cannot be distinguished from a field reporting a failure.
     */
    var hintMake: String = ""

    fun start(onFinished: (File?) -> Unit) {
        if (running) return
        stopFlag = false; running = true; probes = 0
        blocksFound = 0; didsFound = 0; pct = 0; phase = ""; eta = ""
        probesKnown = 0; probesRecon = 0; probesSweep = 0
        nrcByHeader.clear(); refusals.clear(); timeouts = 0; retries = 0; consecutiveDead = 0; curHeader = ""
        stopping = false; aborted = false; matchedModels = emptyList(); allHits = emptyList()
        runStartMs = System.currentTimeMillis()
        knownTotal = 0; reconTotalP = 0; pctFloor = 0; etaSecs = 0.0; etaFirstSecs = 0.0
        estBlocks = Discover.blockPrior(hintedBlocks.size)

        ble.runOnWorker {
            var f: File? = null
            val found = LinkedHashMap<String, DiscoveredBlock>()
            var wmi = ""
            var vinKey = ""
            var is29bit = false
            val speaksMode22 = LinkedHashSet<String>()
            val liveHeaders = ArrayList<String>()
            try {
                // No AT SP6 here: CaptureRunner has already run the adapter's own
                // protocol search, and re-asserting 11-bit CAN would silently undo it
                // on any vehicle that is not on that bus.

                // TAKE THE VIN CAPTURE ALREADY READ. Do not read it again here.
                //
                // This second read hardcoded ATSH 7DF, which is the same 11-bit assumption
                // fixed in CaptureRunner -- and on a 29-bit bus it fails, so the capture went
                // out with wmi and vin_key empty even though CaptureRunner had resolved the
                // make perfectly well. Measured on a Silverado 2500HD: preset "Chevrolet"
                // beside wmi "". An empty vin_key means findCached can never match, so an
                // hour-long sweep was repeated on every plug-in of a truck the app had
                // already mapped -- which was the entire point of fixing the VIN.
                //
                // Falls back to reading it here only if CaptureRunner had none either.
                if (wmiIn.isNotEmpty() || vinKeyIn.isNotEmpty()) {
                    wmi = wmiIn; vinKey = vinKeyIn
                } else {
                    val (vinRaw, vinOk) = ble.cmd("0902", 4_000)
                    if (vinOk) { wmi = Discover.wmiFrom(vinRaw); vinKey = Discover.vinKey(vinRaw) }
                }
                progress = if (wmi.isEmpty()) "VIN unreadable — continuing" else "WMI $wmi"

                // Cheap liveness check: a handful of probes decides which headers are worth
                // 1792 each. 11-bit first, then 29-bit ONLY if 11-bit found nothing --
                // trying both always would double the setup cost on the common car.
                // Standard powertrain headers, then any this make is documented to use.
                // The second list is not optional padding: 100% of Toyota's known Mode-22
                // commands sit on headers outside the standard four (700, 701, 745...), so
                // without them a Toyota scan finds nothing and reads as "no enhanced data".
                // 6F1 expands into one entry per curated target. headers() excludes it (it
                // is not a plain three-character CAN id), so this is the only route to it.
                val ext = if (hintedExt) Discover.BMW_TARGETS.map { "6F1@$it" } else emptyList()
                // Applied to the UNION, not to the hints. 7E2 arrives from the
                // make-independent default and 7E5 from the hint table, and both sit inside
                // the range the exclusion is about -- filtering either source alone would
                // leave the other in. See VehicleId.excludedHeaders.
                val censusHeaders = (Discover.HEADERS_11BIT + hintedHeaders + ext)
                    .distinct().filter { it !in excludedHeaders }
                for (h in censusHeaders) {
                    if (stopFlag) break
                    selectHeader(h)
                    val (ok, _, nak) = ask("0100")
                    probes++
                    if (ok || nak) liveHeaders.add(h)
                }
                if (liveHeaders.isEmpty() && !stopFlag) {
                    ble.log("DISCOVER: no 11-bit header answered; trying 29-bit")
                    ble.cmd("ATSP7"); ble.cmd("ATCP18")
                    is29bit = true
                    // Hinted 29-bit targets too, not just the fixed five. OBDb puts 45 of
                    // the Silverado 1500's 52 commands on DA11, which HEADERS_29BIT does not
                    // contain -- so a Silverado's main enhanced header could not be probed
                    // at all, and a 1,929-DID scan of one never saw it.
                    for (h in (Discover.HEADERS_29BIT + hinted29).distinct()) {
                        if (stopFlag) break
                        selectHeader(h)
                        val (ok, _, nak) = ask("0100")
                        probes++
                        if (ok || nak) liveHeaders.add(h)
                    }
                }
                progress = "live headers: ${liveHeaders.joinToString(", ").ifEmpty { "none" }}"

                // LAST CHANCE AT THE VIN, now that we know who is actually on the bus.
                //
                // CaptureRunner has to ask before this point, so it can only guess: the
                // functional broadcast, then the engine ECU at 7E0 or DA10F1. A 2025 Ioniq 5
                // has no engine ECU -- its live headers were 7DF and 7E2 -- so every attempt
                // after the broadcast went to an address that does not exist on that car,
                // and the VIN came back unreadable while the app was seconds away from
                // knowing exactly which headers answer.
                //
                // A VIN found here is too late to steer this run's hints, but it is NOT too
                // late for vin_key, and that is what decides whether the next plug-in re-runs
                // an hour of sweeping or skips straight to the drive.
                if (wmi.isEmpty() && liveHeaders.isNotEmpty() && !stopFlag) {
                    for (h in liveHeaders) {
                        if (stopFlag) break
                        selectHeader(h)
                        for (req in listOf("0902", "22F190")) {
                            val (raw, ok) = ble.cmd(req, 4_000)
                            probes++
                            if (!ok || Discover.vinFrom(raw).isEmpty()) continue
                            wmi = Discover.wmiFrom(raw); vinKey = Discover.vinKey(raw)
                            // Keep the VIN itself, not just its first three characters.
                            // It is too late to steer THIS run's hints, but a contributed
                            // record is written long afterwards, and without this the car
                            // that most needs the fallback is the one whose record comes
                            // out unnamed -- make, model, year and pattern all missing.
                            recoveredVin = Discover.vinFrom(raw)
                            ble.log("VIN recovered on $h via $req -> WMI $wmi")
                            break
                        }
                        if (wmi.isNotEmpty()) break
                    }
                    if (wmi.isEmpty()) ble.log("VIN: not readable on any live header either")
                    progress = if (wmi.isEmpty()) "VIN unreadable — continuing" else "WMI $wmi"
                }

                // --- phase 1: recon -------------------------------------------------
                // Everything an earlier run on this vehicle already established. Blocks
                // come back with their swept flag intact, so the sweeps below skip what is
                // genuinely finished and redo what only looked finished.
                if (resumeBlocks.isNotEmpty()) {
                    var done = 0; var requeued = 0
                    for (b in resumeBlocks) {
                        if (!liveHeaders.contains(b.header)) continue
                        found[b.name] = b
                        if (b.swept && b.fullHits.isNotEmpty()) done++
                        else if (b.swept) requeued++
                    }
                    blocksFound = found.size
                    ble.log("resuming: ${found.size} block(s) known, $done already swept, " +
                        "$requeued empty and re-queued" +
                        if (resumeReconDone) ", recon already complete" else "")

                    // --- the overlap block -------------------------------------------
                    //
                    // One block, re-swept, costing about a minute. It was asked for as
                    // insurance against missing something at a session boundary, and it buys
                    // something larger: it is the only cheap way to find out whether two
                    // sessions are comparable AT ALL.
                    //
                    // A map assembled over several sessions is inherently multi-state. Same
                    // hits as last time and the car is in a comparable state; different hits
                    // and it is not -- learned in sixty seconds rather than discovered in a
                    // merged map weeks later. This morning's BMW is what that costs: 195
                    // identifiers that three prior runs found consistently, absent, and no
                    // way to know until the file was read at a desk.
                    //
                    // The union is kept either way. An identifier that answered once is a
                    // fact about the vehicle; the state it answered in is a separate fact,
                    // and recording that per block is phase 4.
                    val overlap = found.values.lastOrNull {
                        it.swept && it.fullHits.isNotEmpty() && liveHeaders.contains(it.header)
                    }
                    if (overlap != null && !stopFlag) {
                        phase = "overlap"
                        progress = "re-checking ${overlap.name} against the last session"
                        val before = overlap.fullHits.map { it.first }.toSet()
                        val now = LinkedHashSet<String>()
                        selectHeader(overlap.header)
                        for (off in 0..255) {
                            if (stopFlag) break
                            val req = "%04X%02X".format(overlap.prefix, off)
                            val (present, payload, _) = ask(req)
                            probes++; probesSweep++
                            if (present && payload != null) {
                                now.add(req)
                                if (req !in before) overlap.fullHits.add(req to payload)
                            }
                        }
                        val lost = before - now
                        val gained = now - before
                        if (!stopFlag && (lost.isNotEmpty() || gained.isNotEmpty())) {
                            // Naming both states turns "something changed" into something the
                            // operator can act on -- most of the time they will recognise the
                            // difference and either fix it or accept it deliberately.
                            val was = overlap.state.ifEmpty { "an unrecorded state" }
                            warning = "${overlap.name} answered differently than last session " +
                                "(${lost.size} gone, ${gained.size} new) — last swept in " +
                                "\"$was\", now \"${Session.captureState}\""
                            ble.log("WARNING: $warning")
                        } else if (!stopFlag) {
                            ble.log("overlap ${overlap.name}: ${now.size} identifiers, " +
                                "unchanged — sessions are comparable")
                        }
                    }
                }

                // --- phase 0: ask for what is already known, by name ------------------
                //
                // Cheapest and most certain step there is. These are supported-command
                // censuses from real vehicles, so a hit here is confirmation rather than
                // discovery -- and every block one lands in gets a full sweep below, which
                // is how the offsets that recon would never have sampled get reached.
                phase = "known"
                val knownHits = LinkedHashMap<String, MutableList<Pair<String,String>>>()
                // Known DIDs for the make, plus the UDS identification set on EVERY live
                // header. The identification DIDs are asked even where the tables know
                // nothing about the make, because they are legislated locations rather than
                // community knowledge -- and because recon structurally cannot reach 0xF190.
                // See Discover.IDENT_DIDS.
                val identReqs = Discover.IDENT_DIDS.map { it.first }
                val byHdr = liveHeaders.associateWith { h ->
                    (knownRequests.filter { it.first == h }.map { it.second } + identReqs)
                        .distinct()
                }.filterValues { it.isNotEmpty() }
                var kdone = 0
                val ktotal = byHdr.values.sumOf { it.size }
                knownTotal = ktotal
                for ((hdr, reqs) in byHdr) {
                    if (stopFlag) break
                    selectHeader(hdr)
                    for (req in reqs) {
                        if (stopFlag) break
                        val (present, payload, _) = ask(req)
                        probes++; probesKnown++; kdone++
                        if (present && payload != null) {
                            knownHits.getOrPut("%s|%s".format(hdr, req.substring(0,4))) { mutableListOf() }
                                .add(req to payload)
                            didsFound++
                        }
                        if (kdone % 8 == 0) {
                            overall(probesKnown)
                            progress = "known $hdr  $kdone/$ktotal  —  $didsFound DIDs"
                        }
                    }
                }
                for ((key, hits) in knownHits) {
                    val hdr = key.substringBefore("|")
                    val prefix = key.substringAfter("|").toInt(16)
                    val nm = "%04Xxx".format(prefix)
                    if (!found.containsKey(nm)) {
                        found[nm] = DiscoveredBlock(nm, prefix, hdr, hits.map { it.first })
                    }
                }
                blocksFound = found.size
                didsFound = 0     // the sweeps below recount these

                // One block, swept end to end. Used twice: once on what phase 0 already
                // proved, and once on everything recon turns up afterwards.
                var swept = 0
                var emptyRun = 0
                val swept0 = HashSet<String>()
                fun sweepOne(b: DiscoveredBlock, denom: () -> Int, exact: Boolean) {
                    swept0.add(b.name)
                    selectHeader(b.header)
                    val hitsBefore = b.fullHits.size
                    for (off in 0..255) {
                        if (stopFlag) return                  // partway: still pending
                        val req = "%04X%02X".format(b.prefix, off)
                        val (present, payload, _) = ask(req)
                        probes++; probesSweep++
                        if (present && payload != null) {
                            b.fullHits.add(req to payload)
                            didsFound++                     // total, not per-block
                        }
                        if (off % 16 == 0) {
                            // Only once recon has ended is the block count final; before
                            // that the prior stands, or the bar would size the whole run
                            // from the handful of blocks phase 0 happened to seed.
                            if (exact) estBlocks = found.size
                            overall(probesKnown + probesRecon + probesSweep)
                            progress = "sweep ${b.name} (${swept + 1}/${denom()})  " +
                                "%02X/FF  —  $didsFound DIDs found".format(off)
                        }
                    }
                    // Only a sweep that RAN TO THE END has asked all 256 offsets. A stop
                    // partway through leaves the block pending, not empty -- otherwise the
                    // block being swept when the operator hit stop would be recorded as a
                    // fact about the vehicle.
                    b.swept = true
                    b.state = Session.captureState
                    b.sweptAt = Obd.isoUtc(System.currentTimeMillis())
                    if (b.fullHits.size == hitsBefore) {
                        b.emptyRuns++
                        emptyRun++
                        // THREE, because a healthy capture has never produced one. Ten of
                        // twelve contain no empty block at all -- every block recon finds,
                        // the sweep finds data in, which follows from a block only entering
                        // the sweep because recon already answered there. Three in a row has
                        // only ever happened while a vehicle was away.
                        //
                        // The transport looks perfectly healthy while this happens: the BMW
                        // that lost nine blocks to a refuelling stop recorded 0 timeouts and
                        // 0 retries, because the DME was answering the whole time, just with
                        // conditionsNotCorrect instead of data. consecutiveDead counts dead
                        // probes and there were none, which is why nothing noticed.
                        if (emptyRun >= 3 && warning.isEmpty()) {
                            warning = "$emptyRun blocks in a row answered nothing — " +
                                "is the vehicle still on?"
                            ble.log("WARNING: $warning")
                            buzz(ctx, false)
                        } else if (emptyRun >= 3) {
                            warning = "$emptyRun blocks in a row answered nothing — " +
                                "is the vehicle still on?"
                        }
                    } else {
                        b.emptyRuns = 0
                        emptyRun = 0
                        warning = ""
                    }
                    swept++
                }

                // --- phase 1: sweep what phase 0 PROVED, before recon looks for more ---
                //
                // ORDER, NOT SCOPE. Every block here still gets swept; recon still runs in
                // full afterwards. What changes is which twelve minutes come first.
                //
                // Recon is 1,792 probes per live header -- on a 2025 Ioniq 5 with ten live
                // headers that is about a hundred minutes at the rate that car achieves,
                // spent looking for blocks nobody has documented. Phase 0 has by this point
                // already landed hits in the blocks somebody HAS documented, and under the
                // old order those sat unswept behind the entire search. An operator who
                // stopped at twenty minutes kept nothing but a list of block names.
                //
                // Swept first, the documented modules -- battery, charger, motor, odometer
                // -- are complete in about a quarter of an hour, and the open-ended search
                // is what gets deferred. That is the half worth deferring: it is the half
                // with no known payoff.
                // A block is finished only if it was swept AND something answered. Swept
                // and empty goes round again -- unless two separate runs have now found it
                // empty, at which point the vehicle has contradicted its own recon twice and
                // is believed.
                fun finished(b: DiscoveredBlock) =
                    b.swept && (b.fullHits.isNotEmpty() || b.emptyRuns >= 2)
                val seeded = found.values.filterNot { finished(it) }
                if (seeded.isNotEmpty()) {
                    phase = "sweep"
                    ble.log("sweeping ${seeded.size} block(s) proved by phase 0, before recon")
                    for (b in seeded) {
                        if (stopFlag) break
                        sweepOne(b, { seeded.size }, exact = false)
                    }
                }

                phase = "recon"
                // An earlier run that reached the end of recon ruled out every block it did
                // not find. One that was interrupted ruled out nothing, so it runs again.
                var reconComplete = resumeReconDone
                if (resumeReconDone) ble.log("recon already complete on an earlier run; skipping")
                val reconTotal = liveHeaders.size * 256
                // In PROBES, not blocks: recon asks all seven offsets of every block with no
                // early exit, so this is the real cost and the one the bar must be scaled by.
                reconTotalP = reconTotal * Discover.OFFSETS.size
                var reconDone = 0
                for (h in if (resumeReconDone) emptyList() else liveHeaders) {
                    if (stopFlag) break
                    selectHeader(h)                         // once per header
                    // Hinted high-bytes first, then everything else. Same 256 blocks
                    // either way -- only the order changes, so nothing is skipped.
                    val hintedHi = hintedBlocks.map { it and 0xFF }.filter { it in 0..255 }
                    val order = (hintedHi + (0..255).filter { it !in hintedHi })
                    for (hi in order) {
                        if (stopFlag) break
                        val prefix = (Discover.SERVICE shl 8) or hi
                        val name = "%04Xxx".format(prefix)
                        val hitsHere = ArrayList<String>()
                        for (off in Discover.OFFSETS) {
                            if (stopFlag) break
                            val req = "%04X%02X".format(prefix, off)
                            val (present, _, nak) = ask(req)
                            probes++; probesRecon++
                            if (nak) speaksMode22.add(h)
                            if (present) hitsHere.add(req)
                        }
                        if (hitsHere.isNotEmpty() && !found.containsKey(name)) {
                            found[name] = DiscoveredBlock(name, prefix, h, hitsHere)
                            blocksFound = found.size
                        }
                        reconDone++
                        if (hi % 8 == 0) {
                            // Revise the sweep estimate from what recon has found so far.
                            // Below a tenth of recon the sample is too small to extrapolate
                            // from, so the prior stands.
                            // No extrapolation -- see overall(). The prior stands until
                            // recon ends and the real count is known.
                            estBlocks = maxOf(Discover.blockPrior(hintedBlocks.size), blocksFound)
                            overall(probesKnown + probesRecon)
                            progress = "recon $h  %02X/FF  —  $blocksFound blocks so far".format(hi)
                        }
                    }
                }

                // Recon reached the end only if nothing stopped it. A resumed run skips
                // recon when this was true, and repeats it when it was not -- an interrupted
                // recon has not ruled anything out.
                if (!stopFlag) reconComplete = true
                reconTotalP = if (resumeReconDone) 0 else reconTotalP

                // Documented blocks get a FULL sweep even when recon drew a blank in them.
                // Recon samples seven offsets; a block whose DIDs all sit elsewhere reads as
                // empty. On a Ford Ranger that silently lost transmission oil temperature
                // (221E1C), fuel level (22F42F) and four tyre pressures -- every one of them
                // in a block the hint table already named, at offsets 0x12 to 0x2F.
                for ((hdr, prefix) in hintedPairs) {
                    if (stopFlag) break
                    if (!liveHeaders.contains(hdr)) continue
                    val nm = "%04Xxx".format(prefix)
                    if (found.containsKey(nm)) continue
                    found[nm] = DiscoveredBlock(nm, prefix, hdr, emptyList())
                    ble.log("hinted block $nm @ $hdr queued for a full sweep")
                }
                blocksFound = found.size

                // --- phase 2: sweep everything recon added -------------------------
                // Blocks phase 1 already swept are skipped by name, never re-asked.
                phase = "sweep"
                for (b in found.values.toList()) {
                    if (stopFlag) break
                    if (b.name in swept0 || finished(b)) continue
                    sweepOne(b, { found.size }, exact = true)
                }

                // Stamped once, before writing, so the filename and finished_at agree
                // rather than differing by however long serialising takes.
                val finishedMs = System.currentTimeMillis()
                val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
                f = File(dir, "discover-$finishedMs.json")
                outFile = f
                f.bufferedWriter().use { out ->
                    out.write("{\n")
                    // WHICH BUILD WROTE THIS. A map from before a fix looks identical to
                    // one from after it -- the Silverado's 2 supported PIDs and the Subaru's
                    // 45 were the same code path, one of them raced. Without a stamp there
                    // is no way to tell a stale capture from a current one except by
                    // remembering, and nobody remembers.
                    out.write("\"build\": \"${BuildTag.ID}\",\n")
                    // WHEN, AND FOR HOW LONG. The filename carries an epoch stamp, but that
                    // is when the file was WRITTEN, not when the run began -- and it does not
                    // survive a copy or a rename. Without these, two captures of one vehicle
                    // cannot be ordered, a stage result cannot be paired with the drive log
                    // that followed it, and a run-time estimate can never be graded against a
                    // measurement, which leaves it a guess forever (#7, #10). The drive CSV
                    // has stamped every row since the beginning; it was the file saying what
                    // the vehicle IS that had no clock. Same format, so they sort together.
                    out.write("\"started_at\": \"${Obd.isoUtc(runStartMs)}\",\n")
                    out.write("\"finished_at\": \"${Obd.isoUtc(finishedMs)}\",\n")
                    out.write("\"elapsed_s\": ${"%.1f".format((finishedMs - runStartMs) / 1000.0)},\n")
                    // The prediction, beside the outcome, so the estimator grades itself.
                    // null when the run ended before it was willing to say anything.
                    out.write("\"eta_first_s\": " +
                        (if (etaFirstSecs > 0.0) "%.1f".format(etaFirstSecs) else "null") + ",\n")
                    // The condition the car was in. Without it a capture cannot be compared
                    // with anyone else's, and a constant cannot be told from an untouched field.
                    out.write("\"state\": \"${Session.captureState}\",\n")
                    out.write("\"wmi\": \"$wmi\",\n")
                    // Per-car key, not the VIN. See Discover.vinKey.
                    out.write("\"vin_key\": \"$vinKey\",\n")
                    // What actually resolved, not a constant. obd_scan reads only `blocks`
                    // from this file (_blocks_from_discover), so naming the make here is
                    // safe for `sweep --blocks-from`.
                    out.write("\"preset\": \"${hintMake.ifEmpty { "generic" }}\",\n")
                    out.write("\"answers_like\": [${matchedModels.joinToString(", ") { "\"$it\"" }}],\n")
                    out.write("\"hints\": {")
                    out.write("\"make\": \"$hintMake\", ")
                    out.write("\"headers_added\": [${hintedHeaders.filter { it !in Discover.HEADERS_11BIT }.joinToString(", ") { "\"$it\"" }}], ")
                    out.write("\"blocks_hinted\": ${hintedBlocks.size}, ")
                    out.write("\"headers_excluded\": [${excludedHeaders.sorted().joinToString(", ") { "\"$it\"" }}], ")
                    out.write("\"known_requests_offered\": ${knownRequests.size}, ")
                    out.write("\"known_requests_sent\": $probesKnown},\n")
                    out.write("\"probe_breakdown\": {\"known\": $probesKnown, ")
                    out.write("\"recon\": $probesRecon, \"sweep\": $probesSweep, ")
                    out.write("\"timeouts\": $timeouts, \"retries\": $retries},\n")
                    // PHASE 1: what this vehicle says when it declines. Recorded only.
                    out.write("\"refused_but_present\": {")
                    out.write(refusals.entries.joinToString(", ") {
                        "\"${it.key.replace("|", " ")}\": \"${it.value}\""
                    })
                    out.write("},\n")
                    out.write("\"nrc_histogram\": {")
                    out.write(nrcByHeader.entries.joinToString(", ") { (h, counts) ->
                        "\"$h\": {" + counts.entries.sortedBy { it.key }.joinToString(", ") {
                            "\"0x%02X %s\": %d".format(it.key, Mode21.negativeName(it.key), it.value)
                        } + "}"
                    })
                    out.write("},\n")
                    out.write("\"probes\": $probes,\n")
                    out.write("\"offsets_probed\": [${Discover.OFFSETS.joinToString(", ")}],\n")
                    out.write("\"headers_targeted\": [${liveHeaders.joinToString(", ") { "\"$it\"" }}],\n")
                    out.write("\"addressing\": \"${if (is29bit) "29-bit" else "11-bit"}\",\n")
                    out.write("\"speaks_mode22\": [${speaksMode22.joinToString(", ") { "\"$it\"" }}],\n")
                    // The legislated set, recorded rather than assumed. Every OBD-II car
                    // answers the Mode-01 bitmaps, but only the non-CAN path was storing
                    // the result -- so a CAN capture threw away the twenty-odd identifiers
                    // whose meanings the standard already defines, and the drive logger
                    // read nine of them anyway without anything recording that they exist.
                    out.write("\"mode01\": [${stdPidsIn.joinToString(", ") { "\"$it\"" }}],\n")
                    out.write("\"recon_done\": $reconComplete,\n")
                    out.write("\"aborted\": ${if (stopFlag) "true" else "false"},\n")
                    // Schema below matches obd_scan's discover.json so that
                    // `sweep --blocks-from` can read this file unmodified.
                    out.write("\"blocks\": [\n")
                    out.write(found.values.joinToString(",\n") { b ->
                        "  {\"name\": \"${b.name}\", \"prefix\": ${b.prefix}, \"lo\": 0, \"hi\": 255, " +
                            "\"note\": \"discovered: answered at ${b.header}\"}"
                    })
                    out.write("\n],\n")
                    out.write("\"detail\": [\n")
                    out.write(found.values.joinToString(",\n") { b ->
                        val recon = b.reconHits.joinToString(", ") { "\"$it\"" }
                        val full = b.fullHits.joinToString(", ") { "[\"${it.first}\", \"${it.second}\"]" }
                        // swept says all 256 offsets were asked; empty_runs counts the
                        // separate runs that asked them and found nothing. Together they are
                        // what lets the next run pick up honestly instead of starting over.
                        "  {\"name\": \"${b.name}\", \"header\": \"${b.header}\", " +
                            "\"swept\": ${b.swept}, \"empty_runs\": ${b.emptyRuns}, " +
                            "\"state\": \"${b.state}\", \"swept_at\": \"${b.sweptAt}\", " +
                            "\"recon_hits\": [$recon], \"full_hits\": [$full]}"
                    })
                    out.write("\n]\n}\n")
                }
                // Drop hinted blocks that turned out genuinely empty. They were swept on the
                // table's word rather than on evidence, and reporting them as discovered
                // would put unfounded blocks into a candidate preset.
                // A block the sweep FINISHED and found nothing in is a contradiction, not a
                // discovery: recon said a DID answered at some offset, and the full sweep
                // re-asked that exact offset along with the other 255 and got nothing.
                //
                // MEASURED. A BMW produced 2216xx from a single positive at 221600 on
                // 2026-08-27, in one run out of three. Two complete runs of that car return
                // 571 DIDs in 16 blocks with an identical DID set twelve hours apart, and
                // neither contains it. It went into blocks[] as `{"name": "2216xx", ...
                // "discovered: answered at 7DF"}` and would have travelled to obd_scan and
                // into a candidate preset -- exactly what the positives-only rule exists to
                // prevent, defeated by one spurious positive.
                //
                // Scoped to blocks the sweep actually reached. In an aborted run an empty
                // fullHits means "not got to yet", which is not evidence of anything, so
                // those are kept.
                found.entries.removeIf { (name, b) ->
                    if (b.fullHits.isEmpty() && b.reconHits.isEmpty()) return@removeIf true
                    if (name !in swept0) return@removeIf false        // never swept: no verdict
                    val phantom = b.fullHits.isEmpty()
                    if (phantom) ble.log(
                        "dropped $name: recon hit ${b.reconHits.joinToString(",")} " +
                        "but the full sweep of all 256 offsets found nothing",
                    )
                    phantom
                }
                blocksFound = found.size

                val byHeader = found.values
                    .flatMap { b -> b.fullHits.map { b.header to it.first } }
                    .groupBy({ it.first }, { it.second })
                val best = byHeader.maxByOrNull { it.value.size }
                aborted = stopFlag
                // WHICH MODEL DOES THIS CAR ANSWER LIKE? Computed from what was found, so it
                // costs nothing and cannot be wrong in the way a VIN decode is wrong -- it is
                // a statement about this vehicle's replies, not about a table.
                matchedModels = VehicleId.modelsMatching(
                    hintMake,
                    found.values.flatMap { b -> listOf(b.header.uppercase() to
                        "%02X".format(b.prefix and 0xFF)) }.toSet(),
                )
                if (matchedModels.isNotEmpty()) {
                    ble.log("answers like: ${matchedModels.joinToString(" or ")}")
                }
                logPlan = best?.let { it.key to it.value }
                logPlanSkipped = byHeader.entries.filter { it.key != best?.key }.sumOf { it.value.size }
                // EVERY header's DIDs, not just the richest one's. A Ford Ranger finds 257
                // and its best single header holds 127, so 130 -- the transfer-case and
                // ABS-side blocks that only exist because the hint table added 720 and 726 --
                // were discovered and then dropped from every drive. correlate never saw them.
                //
                // The cost is honest: a row is one request per DID, so this roughly doubles
                // the time per row on that truck. Completeness over speed, same as the recon
                // offsets. The single-header plan stays as logPlan for anything that wants it.
                allHits = found.values.flatMap { b ->
                    b.fullHits.map { Triple(b.header, it.first, it.second) }
                }
                logPlanAll = byHeader.entries
                    .sortedByDescending { it.value.size }
                    .flatMap { e -> e.value.map { Poll(it, e.key) } }

                phase = "done"; pct = 100
                progress = "DONE — $blocksFound blocks, $didsFound DIDs, $probes probes" +
                    (if (stopFlag) " (STOPPED EARLY — partial)" else "")
            } catch (e: Exception) {
                progress = "ERROR ${e.javaClass.simpleName}: ${e.message}"
                ble.log("DISCOVER ERROR: $progress")
            } finally {
                running = false
                onFinished(f)
            }
        }
    }
}
