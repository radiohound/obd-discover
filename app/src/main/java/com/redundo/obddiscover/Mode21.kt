package com.redundo.obddiscover

/**
 * readDataByLocalIdentifier — the enhanced read on KWP2000, where Mode 22 often is not.
 *
 * WHY THIS IS A READ, from ISO 14230-3 rather than from folklore. The service table separates
 * the concerns cleanly: 0x21 readDataByLocalIdentifier and 0x22 readDataByCommonIdentifier
 * are reads; 0x2E and 0x3D are the corresponding WRITES; 0x2F and 0x30 are
 * inputOutputControl (actuators); 0x31 is startRoutine. The spec's own words for 0x21 are
 * that the identifier "identifies a server specific local data record" which "shall be
 * available in the server's memory". A popular summary online claims Mode 21 performs
 * actuator tests. It does not — that is 0x2F/0x30, two services away.
 *
 * THE ONE REAL HAZARD, and why every request here is exactly two bytes.
 *
 * Table 7.1.2 defines the request as: byte 1 service (M), byte 2 recordLocalIdentifier (M),
 * byte 3 transmissionMode (U — optional), byte 4 maximumNumberOfResponsesToSend (U). And
 * transmissionMode 02/03/04 mean slow/medium/fast, where "the server transmits the positive
 * response message periodically/repeatedly" at a manufacturer-defined rate until stopped.
 *
 * Blind-sweeping 256 local identifiers while emitting a wrong third byte could therefore
 * leave several ECUs streaming onto a shared K-line. Because transmissionMode is OPTIONAL, a
 * bare two-byte request cannot express it at all. That is a structural guarantee rather than
 * a careful convention: emit `21 XX`, never a third byte, and periodic transmission is not
 * reachable.
 *
 * KWP2000 ONLY. Mode 21 exists on ISO 9141-2 implementations too, but far less consistently,
 * and there is no reference data to check a result against. Restricting to protocols 4 and 5
 * keeps the sweep to the bus the spec above actually governs.
 *
 * NEVER SENT, and worth naming so nothing drifts in later:
 *   0x2C dynamicallyDefineLocalIdentifier — defines a new record in the ECU's RAM. A write
 *        in all but name, and it would make later reads irreproducible.
 *   0x3D writeDataByLocalIdentifier — the write twin of 0x21, one bit pattern away.
 *   0x2E, 0x2F, 0x30, 0x31 — writes, actuator control, routines.
 */
object Mode21 {

    /** ELM327 protocol numbers for KWP2000: 5-baud init and fast init. */
    val KWP_PROTOCOLS = setOf("4", "5")

    fun appliesTo(protocol: String) = protocol.trimStart('A') in KWP_PROTOCOLS

    /**
     * ISO 9141-2, where this is OPT-IN and the reasoning is weaker.
     *
     * On KWP2000 the argument rests on ISO 14230-3: service 0x21 is a defined read and the
     * transmission-mode byte is optional, so two bytes cannot request periodic streaming. On
     * ISO 9141-2 the application layer is ISO 15031-5, which defines modes 01 to 0A and says
     * nothing about 0x21. Whatever lives there is the manufacturer's choice.
     *
     * The two-byte guarantee still holds structurally -- a request with no third byte cannot
     * express a transmission mode whatever the bus -- but that is extrapolation from a spec
     * which does not govern this one. Hence a switch the owner sets deliberately, defaulting
     * off, rather than a default this code makes on their behalf.
     */
    fun appliesToIso(protocol: String) = protocol.trimStart('A') == "3"

    /**
     * Every local identifier, as a two-byte request. 256 of them, and that is the whole space.
     *
     * No offset sampling here, unlike the Mode-22 block sweep. The identifier is a single
     * byte, so the entire range is 256 requests — there is nothing to sample and no reason
     * to guess which ones matter.
     */
    fun allRequests(): List<String> = (0x00..0xFF).map { "21%02X".format(it) }

    /**
     * Reject anything that is not a bare two-byte Mode-21 read.
     *
     * Enforced rather than assumed: this is the check that keeps transmissionMode
     * unreachable, so it validates length as strictly as it validates the service byte.
     */
    fun isSafeRequest(req: String): Boolean {
        val r = req.uppercase().replace(" ", "")
        return r.length == 4 && r.startsWith("21") &&
            r.all { it in "0123456789ABCDEF" }
    }

    /**
     * Identifiers the ECU's own support bitmaps claim to have data at.
     *
     * MEASURED ON A 2006 HIGHLANDER. Mode 21 on that ECU is self-describing exactly the way
     * Mode 01 is: 2100 returned BF9FA891, which is bit for bit the Mode-01 support bitmap
     * the independent 0100 scan produced, and 21A0/21B0/21C0/21E0 continue the pattern into
     * Toyota's enhanced range. The last bit means "the next range exists", so the anchors
     * chain: A0 -> B0 -> C0 -> E0, and B0 is reachable ONLY through that chain because it is
     * not 32-aligned.
     *
     * Recall was perfect -- of 63 identifiers that answered, every single one was claimed by
     * a bitmap. Precision was not: 13 were claimed and returned nothing. That asymmetry is
     * the useful part. A bitmap claim is the ECU contradicting our own measurement, which is
     * worth a second look in a way that a plain silent probe is not.
     *
     * NOT used to decide what to sweep. 0x70 answered on that car and no bitmap claims it,
     * so the bitmaps are demonstrably not exhaustive -- letting them gate the sweep would
     * lose real data, the same failure as VehicleId's hint tables (see VehicleId.kt:70-74).
     * They are a cross-check on top of a full sweep, never a substitute for one.
     */
    fun bitmapClaims(answered: Map<String, String>): Set<String> {
        val claims = LinkedHashSet<String>()
        val seen = HashSet<Int>()
        // The 32-aligned anchors are seeded directly; anything further is chain-discovered.
        val queue = ArrayDeque((0x00..0xFF step 0x20).toList())
        while (queue.isNotEmpty()) {
            val anchor = queue.removeFirst()
            if (!seen.add(anchor)) continue
            val hex = answered["21%02X".format(anchor)] ?: continue
            val nibbles = hex.length
            if (nibbles == 0 || nibbles % 2 != 0 || nibbles > 8) continue
            val v = hex.toLongOrNull(16) ?: continue
            val width = nibbles * 4                      // bits, and identifiers covered
            for (i in 0 until width) {
                if ((v shr (width - 1 - i)) and 1L != 1L) continue
                val id = anchor + i + 1
                if (id > 0xFF) continue
                // The final bit is "next range follows", not an identifier of its own.
                if (i == width - 1) queue.addLast(id) else claims.add("21%02X".format(id))
            }
        }
        return claims
    }

    /**
     * The ECU's refusal code, or null if this was not a negative response.
     *
     * `Obd.payloadOf` returns null for every non-positive reply alike, so a sweep records
     * "nothing here" identically for an identifier that does not exist and one that exists
     * but is not readable right now. Those are different findings and the byte that tells
     * them apart was being dropped: 0x31 requestOutOfRange says the bitmap is wrong, 0x22
     * conditionsNotCorrect says come back with the engine running.
     */
    fun negativeCode(raw: String): Int? {
        for (line in raw.split('\r', '\n', '>')) {
            val t = line.uppercase().replace(" ", "").trim()
            if (t.startsWith("7F21") && t.length >= 6) return t.substring(4, 6).toIntOrNull(16)
        }
        return null
    }

    /**
     * ISO 14229 names, from the table in pylessard/python-udsoncan.
     *
     * The 0x81-0x94 block is the reason this list is long. Those codes do not merely say a
     * request was refused -- they say WHAT WOULD MAKE IT WORK. A DID answering
     * VehicleSpeedTooLow is one the drive log should re-ask; EngineIsNotRunning is a scan to
     * repeat with the engine on; BrakeSwitchNotClosed is a controls-test step. Naming them
     * is what makes the recorded histogram readable enough to act on.
     */
    fun negativeName(nrc: Int): String = when (nrc) {
        0x10 -> "generalReject"
        0x11 -> "serviceNotSupported"
        0x12 -> "subFunctionNotSupported"
        0x13 -> "incorrectMessageLengthOrInvalidFormat"
        0x14 -> "responseTooLong"
        0x21 -> "busyRepeatRequest"
        0x22 -> "conditionsNotCorrect"
        0x24 -> "requestSequenceError"
        0x25 -> "noResponseFromSubnetComponent"
        0x26 -> "failurePreventsExecutionOfRequestedAction"
        0x31 -> "requestOutOfRange"
        0x33 -> "securityAccessDenied"
        0x35 -> "invalidKey"
        0x36 -> "exceedNumberOfAttempts"
        0x37 -> "requiredTimeDelayNotExpired"
        0x78 -> "responsePending"
        0x7E -> "subFunctionNotSupportedInActiveSession"
        0x7F -> "serviceNotSupportedInActiveSession"
        // Conditions: these name the state the vehicle has to be in.
        0x81 -> "rpmTooHigh"
        0x82 -> "rpmTooLow"
        0x83 -> "engineIsRunning"
        0x84 -> "engineIsNotRunning"
        0x85 -> "engineRunTimeTooLow"
        0x86 -> "temperatureTooHigh"
        0x87 -> "temperatureTooLow"
        0x88 -> "vehicleSpeedTooHigh"
        0x89 -> "vehicleSpeedTooLow"
        0x8A -> "throttlePedalTooHigh"
        0x8B -> "throttlePedalTooLow"
        0x8C -> "transmissionRangeNotInNeutral"
        0x8D -> "transmissionRangeNotInGear"
        0x8F -> "brakeSwitchNotClosed"
        0x90 -> "shifterLeverNotInPark"
        0x91 -> "torqueConverterClutchLocked"
        0x92 -> "voltageTooHigh"
        0x93 -> "voltageTooLow"
        0x94 -> "resourceTemporarilyNotAvailable"
        else -> "NRC 0x%02X".format(nrc)
    }
}
