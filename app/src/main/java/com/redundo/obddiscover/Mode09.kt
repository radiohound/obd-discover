package com.redundo.obddiscover

/**
 * Vehicle information -- Mode 09, legislated and available on every protocol.
 *
 * Like Mode 01, this space describes itself: 0900 returns a bitmap saying which info PIDs
 * the ECU implements, so there is nothing to sweep. A handful of requests gets calibration
 * identifiers, the calibration verification numbers and the ECU's own name.
 *
 * WHY IT IS WORTH HAVING on a car where enhanced discovery cannot run. A calibration ID and
 * CVN identify the exact firmware in that ECU. Two vehicles of the same year and model can
 * carry different calibrations, and a capture that records them is one somebody can match
 * against later, or notice a reflash in. On an ISO 9141 vehicle it is most of what
 * distinguishes one car's data from another's.
 *
 * Reads only, and every PID here is defined by ISO 15031-5 rather than by a manufacturer.
 */
object Mode09 {

    /** PID 0x00 is the support bitmap; the rest are described by it. */
    val NAMES = mapOf(
        0x02 to "VIN",
        0x04 to "Calibration ID",
        0x06 to "Calibration verification number (CVN)",
        0x08 to "In-use performance tracking (spark)",
        0x0A to "ECU name",
        0x0B to "In-use performance tracking (compression)",
    )

    /**
     * Walk the 0900 bitmap and return the supported info PIDs, VIN excluded.
     *
     * 0902 is read separately and deliberately: the VIN is shown on screen and never written
     * to a capture file, so it must not arrive here and be logged with everything else.
     */
    fun supported(ask: (String) -> String?): List<Int> = probe(ask).pids

    /**
     * The PIDs to read, and the bitmap that was used to decide -- or why there wasn't one.
     *
     * [bitmap] exists because "no Mode-09 items" had two indistinguishable causes on a 2006
     * Highlander: the ECU answered 0900 and advertised nothing but the VIN, or it never
     * answered 0900 at all. The capture recorded an empty list either way, so the file could
     * not tell us which, and neither could the operator once the adapter went out of range.
     * Recording the raw reply makes that question answerable from the file afterwards.
     */
    data class Probe(val pids: List<Int>, val bitmap: String?, val viaFallback: Boolean)

    /**
     * Fallback is not belt-and-braces, it is the documented failure of this bitmap.
     *
     * 0900 is mandatory in ISO 15031-5 for any ECU implementing Mode 09, but pre-2008
     * implementations are widely observed answering 0902 while ignoring 0900 -- and this
     * app has one in hand, a Highlander whose VIN read cleanly on the very same connection
     * that produced no Mode-09 items. Trusting the bitmap alone means an ECU that HAS a
     * calibration ID reports none, which is a false negative written into a capture file.
     *
     * The cost of being wrong the other way is four requests on a car that has nothing.
     */
    private val LEGISLATED = listOf(0x04, 0x06, 0x0A, 0x0B)

    /**
     * Mode 09 replies carry a leading "number of data items" byte that Mode 01 replies do not.
     *
     * A 2006 Highlander answered 0900 with `01FC000000` -- FIVE bytes where a Mode-01 support
     * bitmap is four. Taking the first four gave 01FC0000, which claims PIDs 08-0F; none of
     * them answered, so the capture recorded no Mode-09 items and looked like a car that
     * simply had none. Dropping the count byte gives FC000000, which claims 01-06.
     *
     * The VIN settles which reading is right, with no further requests. 0902 returned
     * JTE00000000000000 on that same connection, so PID 02 IS supported -- and only the
     * second decode says so. The first denies it.
     *
     * This is the reply-shape assumption that produced the C0300 phantom code and the
     * corrupted VIN, for a third time. `Discover.vinFrom` already steps past this exact byte
     * (substring(6), not (4), after `4902`); the bitmap path had not learned it.
     */
    private fun bitmapOf(payload: String): String? {
        if (payload.length < 8) return null
        // The count byte is a PREFIX, so the bitmap is the last four bytes, not the first.
        return if (payload.length >= 10) payload.substring(2, 10) else payload.substring(0, 8)
    }

    /**
     * Odd PIDs 01-09 report how many data items the following even PID returns.
     *
     * They are counts about the reply, not facts about the vehicle, and the even PID's own
     * response carries the same byte. Reading them costs requests on a 10.4 kbaud bus and
     * writes rows that mean nothing to whoever opens the capture.
     */
    private fun isMessageCount(pid: Int) = pid % 2 == 1 && pid <= 0x09

    fun probe(ask: (String) -> String?): Probe {
        val payload = ask("0900")
        val bits = payload?.let { bitmapOf(it) }?.toLongOrNull(16)
        if (bits != null) {
            val out = ArrayList<Int>()
            for (i in 0 until 32) {
                if ((bits shr (31 - i)) and 1L == 1L) {
                    val pid = i + 1
                    // VIN handled elsewhere: it is shown on screen and kept out of the
                    // capture file, so it must not arrive here and be logged with the rest.
                    if (pid != 0x02 && pid <= 0x20 && !isMessageCount(pid)) out.add(pid)
                }
            }
            // An answered bitmap is authoritative. Do NOT fall back on top of it: the
            // ECU said what it has, and probing past that is asking a question already
            // answered.
            return Probe(out, payload, viaFallback = false)
        }
        return Probe(LEGISLATED, payload, viaFallback = true)
    }

    /**
     * Fixed-width record sizes, ISO 15031-5. A reply holds N of them back to back.
     *
     * NOT a detail. A 2006 Highlander returned 32 bytes for PID 04 -- TWO calibration IDs,
     * 34876100 and 54830100, each padded to 16 with NULs -- and 8 bytes for PID 06, two
     * CVNs. Treating either reply as one value concatenates two real records into a third
     * that does not exist. That is precisely how `43 00` from two ECUs became the phantom
     * code C0300.
     */
    private val RECORD = mapOf(0x04 to 16, 0x06 to 4, 0x0A to 20)

    /** Text PIDs; the rest stay hex because they are not characters. */
    private fun isText(pid: Int) = pid == 0x04 || pid == 0x0A

    /**
     * One record: ASCII for the text PIDs, hex otherwise.
     *
     * Padding is DROPPED rather than trimmed. These fields are fixed width and the pad is
     * as often 0x00 as 0x20; trim() strips whitespace only, so a NUL-padded ID rendered as
     * eight characters followed by eight invisible ones -- correct-looking on screen while
     * carrying junk into the capture file. Spaces INSIDE an ECU name survive.
     */
    private fun renderOne(pid: Int, bytes: ByteArray): String {
        if (!isText(pid)) return bytes.joinToString("") { "%02X".format(it) }
        val printable = bytes.count { it >= 0x20 && it < 0x7f }
        if (printable == 0 || printable * 2 < bytes.size) {
            return bytes.joinToString("") { "%02X".format(it) }
        }
        return bytes.filter { it >= 0x20 && it < 0x7f }
            .map { (it.toInt() and 0xFF).toChar() }.joinToString("").trim()
    }

    /**
     * Render a Mode-09 reply, splitting it into records first.
     *
     * A reply that is not a whole number of records is NOT split. Guessing a boundary in a
     * reply we do not understand is how a wrong value gets written down as a right one; one
     * odd-length string that someone can look at beats two confident halves of it.
     */
    fun render(pid: Int, payloadHex: String): String {
        val bytes = ByteArray(payloadHex.length / 2) {
            ((Character.digit(payloadHex[it * 2], 16) shl 4) or
             Character.digit(payloadHex[it * 2 + 1], 16)).toByte()
        }
        val size = RECORD[pid]
        if (size == null || bytes.isEmpty() || bytes.size % size != 0) {
            // Leading pad still has to go, or a single record starting 00 00 00 4A fails
            // the printable test and falls back to hex for perfectly good ASCII.
            var i = 0
            while (i < bytes.size - 1 && bytes[i] >= 0 && bytes[i] < 0x20) i++
            return renderOne(pid, if (i > 0) bytes.copyOfRange(i, bytes.size) else bytes)
        }
        return (0 until bytes.size / size)
            .map { renderOne(pid, bytes.copyOfRange(it * size, (it + 1) * size)) }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
    }
}
