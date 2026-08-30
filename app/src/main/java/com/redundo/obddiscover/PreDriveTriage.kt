package com.redundo.obddiscover

/**
 * Which of a sweep's hits are worth logging on the drive you are about to take.
 *
 * NOT THE SAME QUESTION AS [Triage], despite the name. That one is post-drive and asks
 * "was the drive I just took worth keeping?" -- row count, did anything vary, against
 * correlate's thresholds. This one runs BEFORE the drive and asks "what should the drive
 * capture?". They sit at opposite ends of the same trip and neither replaces the other.
 *
 * THE PROBLEM. A sweep on an unlisted vehicle returns hundreds of DIDs -- 462 on the F10
 * that obd-gauge-cluster mapped. A drive log cannot carry them: every value is a request
 * and a reply, so 462 DIDs gives each one a sample every several minutes, and correlate
 * then ranks noise. Somebody has to choose maybe 30, and until now that somebody read hex.
 *
 * THE ONE THING A SECOND PROBE ESTABLISHES. A DID whose bytes CHANGED between two reads
 * seconds apart is live -- something in the car is updating it. That is a positive fact and
 * it is the only one available standing still. A DID that did NOT change is not thereby
 * dead: coolant at thermal equilibrium and road speed at a standstill hold still too.
 *
 * So this RANKS, it does not delete. Every hit comes back, in an order, with its evidence
 * attached, and the caller decides how many to take.
 *
 * Ported from obd_scan's stages.run_triage (MIT, obd-gauge-cluster). Kept deliberately
 * dumb -- no correlation, no interpretation search. Which of the movers is oil temperature
 * still takes a thermal ramp, a drive log and correlate on a host.
 */
object PreDriveTriage {

    enum class Kind {
        /** Bytes differed between the two reads. A live signal, certainly. */
        MOVED,

        /** Identical both times. Equilibrium, or a counter, or a config byte, or a VIN fragment. */
        STATIC,

        /** All 00 or all FF both times: answering, carrying nothing. */
        UNPOPULATED,
    }

    /** One swept DID, re-probed. [second] is null when the re-probe did not answer. */
    data class Row(
        val header: String,
        val request: String,
        val first: String,
        val second: String?,
        val kind: Kind,
        /** Set when another DID read identically on BOTH probes -- the same signal, twice. */
        val duplicateOf: String? = null,
    )

    data class Result(
        val rows: List<Row>,
        /** MOVED, duplicates removed, best first. What to hand the drive. */
        val recommended: List<Row>,
        val moved: Int,
        val static: Int,
        val unpopulated: Int,
        val duplicates: Int,
    ) {
        /**
         * Worded for a reader, not for the enum.
         *
         * The constant stays MOVED, because it is obd_scan's and this project mirrors his
         * names so the two cannot quietly disagree. But "moved" on a screen, in an app whose
         * other open question is whether a map should be built while the car is moving, reads
         * as "found while driving" rather than "its bytes changed between two reads". Same
         * classification, a word that cannot be misread.
         */
        fun summary(): String =
            "dynamic $moved  |  static $static  |  unpopulated $unpopulated  |  duplicates $duplicates"
    }

    /**
     * True for a payload that is all zeros or all Fs -- answering, carrying nothing.
     * 224404-224407 on the F10 read 0000 on every probe.
     */
    fun isBlank(payload: String?): Boolean {
        val p = payload?.trim()?.uppercase() ?: return true
        if (p.isEmpty()) return true
        return p.all { it == '0' } || p.all { it == 'F' }
    }

    /** PURE, so it is testable with no car and no adapter. */
    fun classify(first: String, second: String?): Kind = when {
        isBlank(first) && isBlank(second) -> Kind.UNPOPULATED
        second == null || second == first -> Kind.STATIC
        else -> Kind.MOVED
    }

    /**
     * Classify, collapse duplicates and order. [probed] is (header, request, firstPayload,
     * secondPayload) -- the caller does the probing, so this stays pure and unit-testable.
     *
     * DUPLICATES. On the F10, 225817 and 2258EB were byte-identical on 99.51% of 1427 logged
     * rows: the same signal under two DIDs. Finding that cost a drive. Two probes standing
     * still find it for free, and the drive spends its budget on distinct signals instead.
     */
    fun rank(probed: List<Quad>): Result {
        val rows = probed.map { (header, request, first, second) ->
            Row(header, request, first, second, classify(first, second))
        }

        // Same payload on BOTH probes, same header -> the second one is telling us nothing new.
        val seen = HashMap<Triple<String, String, String?>, String>()
        val marked = rows.map { r ->
            if (r.kind == Kind.UNPOPULATED) return@map r
            val key = Triple(r.header, r.first, r.second)
            val prior = seen[key]
            if (prior == null) { seen[key] = r.request; r } else r.copy(duplicateOf = prior)
        }

        val ordered = marked.sortedWith(
            compareBy({ it.kind.ordinal }, { it.duplicateOf != null }, { it.request })
        )
        return Result(
            rows = ordered,
            recommended = ordered.filter { it.kind == Kind.MOVED && it.duplicateOf == null },
            moved = ordered.count { it.kind == Kind.MOVED },
            static = ordered.count { it.kind == Kind.STATIC },
            unpopulated = ordered.count { it.kind == Kind.UNPOPULATED },
            duplicates = ordered.count { it.duplicateOf != null },
        )
    }

    /** (header, request, firstPayload, secondPayload). */
    data class Quad(
        val header: String,
        val request: String,
        val first: String,
        val second: String?,
    )
}
