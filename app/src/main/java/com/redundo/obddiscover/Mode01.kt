package com.redundo.obddiscover

/**
 * Which legislated Mode-01 PIDs this particular vehicle implements.
 *
 * THIS IS A SCAN, and it is the one that works everywhere. Enhanced-DID discovery addresses
 * modules by CAN header, so it stops at ISO 9141-2 and KWP2000 -- but every OBD-II vehicle
 * ever sold, on any of the nine protocols, answers the support bitmaps. Seven requests return
 * a bit per PID saying whether the car implements it, and no two models answer identically:
 * a 2001 4Runner and a 2013 F10 support overlapping but different sets.
 *
 * So a pre-2008 vehicle is not "unscannable". It is unscannable for ENHANCED data, while the
 * legislated set -- rpm, load, coolant, MAF, timing, O2, fuel trims, and whatever else that
 * ECU chose to implement -- is fully discoverable and fully loggable. Treating those cars as
 * producing nothing was a limit of this app, not of the vehicles.
 *
 * HOW THE BITMAPS CHAIN. 0100 returns four bytes, 32 bits, MSB first: bit 1 means PID 01 is
 * supported, bit 2 means PID 02, and so on to PID 0x20. The LAST bit does not describe a PID
 * -- it says whether 0120 exists, which describes the next 32. Walking 0100, 0120, 0140,
 * 0160, 0180, 01A0, 01C0 covers the whole legislated space, and stopping as soon as a
 * continuation bit is clear avoids asking for ranges the ECU has already said it lacks.
 */
object Mode01 {

    /** The seven range requests, each describing the 32 PIDs above it. */
    private val RANGES = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    /** PIDs this app never needs to log itself: the bitmaps, and 0902 which is read elsewhere. */
    private val SKIP = RANGES.map { "01%02X".format(it) }.toSet()

    /**
     * Walk the bitmaps and return the supported PID requests, e.g. ["0104", "0105", "010C"].
     *
     * @param ask sends one request and returns (payload hex or null). Injected so this is
     *        testable and so the caller decides about headers -- which is the point, because
     *        on a non-CAN protocol there is no header to set.
     */
    fun supportedPids(ask: (String) -> String?): List<String> {
        val out = ArrayList<String>()
        for (base in RANGES) {
            val req = "01%02X".format(base)
            val payload = ask(req) ?: break
            if (payload.length < 8) break
            val bits = payload.substring(0, 8).toLongOrNull(16) ?: break
            for (i in 0 until 32) {
                // Bit 31 is PID base+1, bit 0 is PID base+32 (the continuation flag).
                if ((bits shr (31 - i)) and 1L == 1L) {
                    val pid = base + i + 1
                    if (pid <= 0xFF) {
                        val r = "01%02X".format(pid)
                        if (r !in SKIP) out.add(r)
                    }
                }
            }
            // Last bit clear means the next range is not supported: stop rather than ask.
            if ((bits and 1L) == 0L) break
        }
        return out.distinct()
    }

    /** Names for the PIDs worth labelling on screen. Legislated, so they are the same on every car. */
    val NAMES = mapOf(
        "0104" to "engine load", "0105" to "coolant temp", "0106" to "short fuel trim 1",
        "0107" to "long fuel trim 1", "010A" to "fuel pressure", "010B" to "intake MAP",
        "010C" to "engine RPM", "010D" to "vehicle speed", "010E" to "timing advance",
        "010F" to "intake air temp", "0110" to "MAF rate", "0111" to "throttle position",
        "0114" to "O2 B1S1", "0115" to "O2 B1S2", "011F" to "run time since start",
        "0121" to "distance with MIL on", "0122" to "fuel rail pressure",
        "012F" to "fuel tank level", "0133" to "barometric pressure",
        "013C" to "cat temp B1S1", "0142" to "control module voltage",
        "0143" to "absolute load", "0144" to "commanded equivalence ratio",
        "0145" to "relative throttle", "0146" to "ambient air temp",
        "014F" to "max values", "015C" to "engine oil temp", "015E" to "engine fuel rate",
    )
}
