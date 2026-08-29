package com.redundo.obddiscover

/**
 * Does this non-CAN vehicle answer Mode 22 at all?
 *
 * WHY THIS EXISTS. The app told the operator that "Mode-22 block discovery needs CAN header
 * addressing, which this protocol does not have." Half of that is true and half was never
 * measured. Addressing a specific module by CAN id is genuinely impossible on ISO 9141 --
 * but sending 22 xx xx to whatever the adapter is already talking to is not, and nothing in
 * the app had ever tried it. A 2006 Highlander with 63 Mode-21 identifiers is exactly the
 * car where the answer matters, because Mode 22's identifier space is two bytes wide where
 * Mode 21's is one.
 *
 * THE NEGATIVE RESPONSE IS THE MEASUREMENT, not the consolation prize. A silent probe means
 * nothing on K-line, where an ECU may simply ignore a service it does not implement. A
 * refusal is the ECU speaking:
 *
 *   7F 22 11 / 12   serviceNotSupported -- settled, this bus has no Mode 22
 *   7F 22 31        requestOutOfRange   -- Mode 22 WORKS, that identifier just is not there
 *
 * The second is the find. It says a whole 65536-identifier space is reachable on a car we
 * had written off at 256, and it costs one request to learn.
 *
 * Reads only. Mode 22 is readDataByIdentifier: three bytes, no transmission-mode field, no
 * sub-function that could ask for streaming. The same service the CAN path already sends.
 */
object Mode22 {

    enum class Verdict {
        /** Data came back. Nothing left to argue about. */
        ANSWERED,
        /** Refused with requestOutOfRange: the service is there, the identifier was not. */
        SUPPORTED_EMPTY,
        /** Refused with serviceNotSupported. Settled, and settled cheaply. */
        UNSUPPORTED,
        /** Nothing came back. On K-line that is not evidence either way. */
        SILENT,
    }

    data class Result(
        val verdict: Verdict,
        val hits: List<Pair<String, String>>,
        val evidence: String,
    )

    /**
     * Identifiers chosen to answer if Mode 22 works at all, cheapest first.
     *
     * F4xx is the legislated mapping of OBD Mode-01 PIDs into the Mode-22 space, so F40C is
     * the same engine speed the car has already proven it will report via 010C. If any DID
     * answers on a car like this, that is the one -- and if the service exists but F40C does
     * not, the refusal still tells us so.
     *
     * F190 is the UDS VIN, and 0000/0001 are the bottom of the first block: measured on a
     * BMW, a Subaru and a Ford, every populated Mode-22 block has a hit within its first
     * three offsets. Make-specific blocks are appended by the caller.
     */
    val BASE_PROBES = listOf("22F40C", "22F405", "22F190", "220000", "220001")

    /** Reject anything that is not a bare three-byte Mode-22 read. */
    fun isSafeRequest(req: String): Boolean {
        val r = req.uppercase().replace(" ", "")
        return r.length == 6 && r.startsWith("22") && r.all { it in "0123456789ABCDEF" }
    }

    fun negativeCode(raw: String): Int? {
        for (line in raw.split('\r', '\n', '>')) {
            val t = line.uppercase().replace(" ", "").trim()
            if (t.startsWith("7F22") && t.length >= 6) return t.substring(4, 6).toIntOrNull(16)
        }
        return null
    }

    /**
     * @param ask sends one request and returns the raw reply, or null if nothing came back.
     *
     * Stops at the first ANSWERED or UNSUPPORTED, because both are conclusive and every
     * further request is then spent on a question already settled. SUPPORTED_EMPTY does not
     * stop the run: it is worth learning whether any of the probes actually hold data.
     */
    fun probe(requests: List<String>, ask: (String) -> String?): Result {
        val hits = ArrayList<Pair<String, String>>()
        var best = Verdict.SILENT
        var evidence = "no reply to any of ${requests.size} probes"
        for (req in requests) {
            if (!isSafeRequest(req)) continue
            val raw = ask(req) ?: continue
            val pl = Obd.payloadOf(req, raw)
            if (pl != null && pl.isNotEmpty()) {
                hits.add(req to Obd.hex(pl))
                best = Verdict.ANSWERED
                evidence = "$req returned ${Obd.hex(pl)}"
                continue
            }
            val nrc = negativeCode(raw) ?: continue
            when (nrc) {
                0x11, 0x12 -> return Result(
                    Verdict.UNSUPPORTED, hits,
                    "$req refused with ${Mode21.negativeName(nrc)}",
                )
                0x31 -> if (best == Verdict.SILENT) {
                    best = Verdict.SUPPORTED_EMPTY
                    evidence = "$req refused with requestOutOfRange — the service answers"
                }
                else -> if (best == Verdict.SILENT) {
                    best = Verdict.SUPPORTED_EMPTY
                    evidence = "$req refused with ${Mode21.negativeName(nrc)} — a Mode-22 " +
                        "reply, so the service exists"
                }
            }
        }
        return Result(best, hits, evidence)
    }
}
