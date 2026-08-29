package com.redundo.obddiscover

import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A quick, ADVISORY read of a drive log, computed on the phone before you unplug.
 *
 * WHAT THIS IS NOT. It is not `correlate`, and nothing it prints is a finding. The
 * authoritative analysis is obd_scan's correlate.py on the host, which does considerably
 * more than this: parallel search, mirror-tautology detection, anchor-coverage checks,
 * per-column sample accounting, and a report that explains why r alone is not proof.
 * Deliberately duplicating all of that in Kotlin would create a second source of findings
 * that drifts from the first, which is precisely the trap this project avoids elsewhere by
 * keeping one implementation and one whitelist.
 *
 * WHAT IT IS FOR. Answering one question while you are still in the car: was that drive
 * worth keeping? The first Subaru drive was 25 rows, under correlate's MIN_SAMPLES of 30,
 * so every column came back "too-few-samples" and nothing could be identified -- discovered
 * an hour later at a laptop. Four more minutes of driving would have fixed it. That is the
 * failure this exists to catch, and it needs no analytical sophistication at all: it needs
 * a row count and a rough sense of whether anything moved.
 *
 * The constants below MIRROR correlate.py. If they drift there, the triage will disagree
 * with the real report, and the triage is the one that is wrong.
 */
object Triage {

    const val MIN_SAMPLES = 30          // correlate.MIN_SAMPLES
    const val MIN_R_STRONG = 0.90       // correlate.MIN_R_STRONG
    const val MIN_R_WEAK = 0.60         // correlate.MIN_R_WEAK

    /** correlate.SENTINELS -- a whole cell of these decodes to "no reading", not a value. */
    val SENTINELS = setOf("FF", "00", "FFFF", "0000", "FFFFFF", "FFFFFFFF")

    data class Hit(val column: String, val interp: String, val anchor: String, val r: Double)

    data class Result(
        val rows: Int,
        val didColumns: Int,
        val varying: Int,
        val strong: List<Hit>,
        val weak: List<Hit>,
        val enoughSamples: Boolean,
        val note: String,
    )

    /** correlate.interpretations -- u8 at each offset, then u16 and s16 at each pair. */
    private fun interps(nbytes: Int): List<Triple<Int, Int, Boolean>> {
        val out = mutableListOf<Triple<Int, Int, Boolean>>()
        for (o in 0 until nbytes) out.add(Triple(o, 1, false))
        for (o in 0 until nbytes - 1) out.add(Triple(o, 2, false))
        for (o in 0 until nbytes - 1) out.add(Triple(o, 2, true))
        return out
    }

    private fun decode(hexes: List<String>, off: Int, width: Int, signed: Boolean): DoubleArray {
        val out = DoubleArray(hexes.size) { Double.NaN }
        for (i in hexes.indices) {
            val h = hexes[i].trim().uppercase()
            if (h.isEmpty() || h in SENTINELS) continue
            if (h.length % 2 != 0 || !h.all { it in "0123456789ABCDEF" }) continue
            val end = off + width
            if (h.length / 2 < end) continue
            var v = 0
            for (b in off until end) {
                v = (v shl 8) or ((Character.digit(h[b * 2], 16) shl 4) or Character.digit(h[b * 2 + 1], 16))
            }
            if (signed && width == 2 && v >= 0x8000) v -= 0x10000
            out[i] = v.toDouble()
        }
        return out
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        var n = 0; var sa = 0.0; var sb = 0.0
        for (i in a.indices) {
            if (a[i].isNaN() || b[i].isNaN()) continue
            n++; sa += a[i]; sb += b[i]
        }
        if (n < MIN_SAMPLES) return Double.NaN
        val ma = sa / n; val mb = sb / n
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) {
            if (a[i].isNaN() || b[i].isNaN()) continue
            val x = a[i] - ma; val y = b[i] - mb
            num += x * y; da += x * x; db += y * y
        }
        if (da == 0.0 || db == 0.0) return Double.NaN     // one side never varied
        return num / sqrt(da * db)
    }

    fun run(csv: File): Result {
        val lines = csv.readLines().filter { it.isNotBlank() }
        if (lines.size < 2) return Result(0, 0, 0, emptyList(), emptyList(), false,
            "no rows in the log")
        val head = lines[0].split(",")
        val body = lines.drop(1).map { it.split(",") }
        val rows = body.size

        val didIdx = head.indices.filter { "@" in head[it] }
        val anchorIdx = head.indices.filter { head[it] in Obd.ANCHORS.keys }
        val anchors = anchorIdx.associate { i ->
            head[i] to DoubleArray(rows) { r -> body[r].getOrNull(i)?.trim()?.toDoubleOrNull() ?: Double.NaN }
        }.filterValues { a -> a.count { !it.isNaN() } >= MIN_SAMPLES && a.filter { !it.isNaN() }.distinct().size > 1 }

        val strong = mutableListOf<Hit>(); val weak = mutableListOf<Hit>()
        var varying = 0
        for (i in didIdx) {
            val col = head[i]
            val hexes = body.map { it.getOrNull(i)?.trim() ?: "" }
            val widest = hexes.maxOfOrNull { it.length / 2 } ?: 0
            if (widest == 0) continue
            var best: Hit? = null
            var moved = false
            for ((off, w, signed) in interps(widest)) {
                val v = decode(hexes, off, w, signed)
                val present = v.filter { !it.isNaN() }
                // "Did this column ever change" is worth knowing even when there are too
                // few rows to correlate. Gating it on MIN_SAMPLES made a short log report
                // "0/148 DIDs moved", which reads as "this car sends nothing" when the
                // truth is "nothing was evaluated yet".
                if (present.distinct().size > 1) moved = true
                if (present.size < MIN_SAMPLES || present.distinct().size <= 1) continue
                for ((name, ref) in anchors) {
                    val r = pearson(v, ref)
                    if (r.isNaN()) continue
                    if (best == null || abs(r) > abs(best!!.r)) {
                        best = Hit(col, (if (signed) "s16@" else if (w == 2) "u16@" else "u8@") + off, name, r)
                    }
                }
            }
            if (moved) varying++
            best?.let {
                if (abs(it.r) >= MIN_R_STRONG) strong.add(it)
                else if (abs(it.r) >= MIN_R_WEAK) weak.add(it)
            }
        }
        val enough = rows >= MIN_SAMPLES
        val note = when {
            !enough -> "ONLY $rows ROWS — correlate needs $MIN_SAMPLES. Keep driving; this log " +
                "will report 'too-few-samples' for everything."
            anchors.isEmpty() -> "no anchor column varied — correlate has nothing to compare against"
            else -> "usable: $rows rows, ${anchors.size} anchors varied"
        }
        return Result(rows, didIdx.size, varying,
            strong.sortedByDescending { abs(it.r) }.take(8),
            weak.sortedByDescending { abs(it.r) }.take(5),
            enough, note)
    }
}
