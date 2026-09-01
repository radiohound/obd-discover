package com.redundo.obddiscover

/**
 * Stored trouble codes, via Mode 03.
 *
 * READ ONLY, and deliberately so. Mode 03 asks for stored codes; Mode 04 CLEARS them and is
 * not implemented here and should not be. Clearing is a write, and beyond that it resets the
 * readiness monitors — a car with freshly cleared codes fails an emissions inspection until
 * it has completed a full drive cycle. Nobody should be able to do that by mistap.
 *
 * NAMING IS HONEST ABOUT ITS LIMITS. The structural decode below is algorithmic and always
 * correct: the letter, whether the code is SAE-generic or manufacturer-specific, and which
 * subsystem it belongs to all fall straight out of the bits. Plain-language descriptions are
 * a curated set of common generic codes only. A manufacturer-specific code (P1xxx) means
 * whatever that manufacturer decided, and inventing a description for one would be worse
 * than leaving it blank — so it is left blank and says so.
 */
object Dtc {

    data class Code(val code: String, val system: String, val generic: Boolean,
                    val subsystem: String, val description: String?)

    private val SYSTEM = arrayOf("Powertrain", "Chassis", "Body", "Network")

    /** SAE J2012 subsystem, from the third character of a P-code. */
    // Indexed by the HUNDREDS digit, so P00xx and P01xx both land on fuel/air metering --
    // hence the repeat at index 1. Without it every P-code from P02xx up reports the
    // subsystem one place too early: P0301 (a misfire) read as "auxiliary emission
    // controls", and P0420 (catalyst) as "vehicle speed / idle control".
    private val P_SUB = arrayOf(
        "Fuel and air metering",                        // P00xx
        "Fuel and air metering",                        // P01xx
        "Fuel and air metering (injector circuit)",     // P02xx
        "Ignition system or misfire",                   // P03xx
        "Auxiliary emission controls",                  // P04xx
        "Vehicle speed, idle control, auxiliary inputs",// P05xx
        "Computer and output circuit",                  // P06xx
        "Transmission",                                 // P07xx
        "Transmission",                                 // P08xx
    )

    /**
     * Decode the two-byte on-wire form into a code string plus what its structure implies.
     *
     * Bits 15-14 select the system letter (P/C/B/U), bits 13-12 the first digit, and the
     * remaining twelve bits are three hex digits. A first digit of 0 (and 2 for P-codes) is
     * SAE-defined; 1 is the manufacturer's own.
     */
    fun decode(word: Int): Code? {
        if (word == 0) return null                      // padding, not a code
        val sysIdx = (word shr 14) and 0x03
        val d1 = (word shr 12) and 0x03
        val letter = "PCBU"[sysIdx]
        val code = "%c%d%03X".format(letter, d1, word and 0x0FFF)
        val generic = d1 == 0 || (sysIdx == 0 && d1 == 2)
        val sub = if (sysIdx == 0) P_SUB.getOrElse((word shr 8) and 0x0F) { "" } else ""
        return Code(code, SYSTEM[sysIdx], generic, sub,
                    if (generic) describe(code) else null)
    }

    /**
     * Pull every code out of a Mode-03 reply.
     *
     * EACH ECU REPLY IS PARSED ON ITS OWN. Joining the hex across lines first is what the
     * previous version did, and on a functional broadcast it manufactures codes out of
     * nothing: every ECU answers, one with no stored codes answers `43 00`, and two of those
     * concatenate to `43004300`. Skipping the leading `43` and a count byte then lands on
     * `4300` -- which decodes to C0300, a chassis fault on a car that has none.
     *
     * That is exactly what happened here. C0300 appeared on two unrelated vehicles, looked
     * like a real shared fault, and was an artefact of the parser. It also corrupted genuine
     * results: a car with one real code reported that code plus a phantom C0301.
     *
     * So: split on frames, keep only lines that are themselves a Mode-03 response, and read
     * each one's own codes. `0000` is padding and is skipped rather than reported as P0000.
     */
    /**
     * [expect] is the positive-response byte: 43 for Mode 03 (stored), 47 for Mode 07
     * (pending). The frame format is identical, so only the prefix differs -- and matching
     * the wrong one silently returns nothing rather than failing, which is why it is a
     * parameter instead of a second copy of this loop.
     */
    fun parse(raw: String, expect: String = "43"): List<Code> {
        val out = ArrayList<Code>()
        for (line in raw.split('\r', '\n', '>')) {
            var t = line.trim().uppercase().replace(" ", "")
            val c = t.indexOf(':')
            if (c in 1..2) t = t.substring(c + 1)          // ISO-TP frame index
            if (t.isEmpty() || !t.all { it in "0123456789ABCDEF" }) continue
            if (!t.startsWith(expect)) continue            // not the reply we asked for
            val body = t.drop(4)                           // 43, then the count byte
            var p = 0
            while (p + 4 <= body.length) {
                body.substring(p, p + 4).toIntOrNull(16)?.let { w -> decode(w)?.let(out::add) }
                p += 4
            }
        }
        return out.distinctBy { it.code }
    }

    /**
     * Descriptions for SAE-generic codes, loaded from assets on first use.
     *
     * LAZY ON PURPOSE. The table is 9,415 entries; most scans find no trouble codes at all,
     * and parsing half a megabyte of JSON at every launch to describe nothing would be a
     * waste of both time and memory. It is read the first time a generic code actually needs
     * a name, and never otherwise.
     *
     * GENERIC ONLY, and that is the honest boundary rather than a gap. A manufacturer-
     * specific code (the P1xxx range and its siblings) means whatever that manufacturer
     * decided, and no table can say. Those render as their structural decode -- system,
     * subsystem, and the fact that they are manufacturer-defined -- which is true, where an
     * invented description would not be.
     */
    private var table: Map<String, String>? = null

    /**
     * Codes met in the field that the SAE table does not cover, kept SEPARATE from it.
     *
     * C0300 is the case that prompted this: it appeared on two vehicles, and it is absent
     * from all 18,805 rows of the bundled database -- generic and manufacturer-specific
     * alike, with no C03xx range present at all. The description in wide circulation for it
     * is "rear propshaft / rear wheel speed sensor", but manufacturer sources warn against
     * acting on a generic reading of that range.
     *
     * Merging it into the SAE map would have made a community guess indistinguishable from a
     * standard definition, which is the one thing a diagnostic table must not do. So it lives
     * here, and the UI marks it as unofficial wherever it appears.
     */
    private var supplement: Map<String, Pair<String, String>>? = null

    fun load(ctx: android.content.Context) {
        if (table != null) return
        table = runCatching {
            val o = org.json.JSONObject(
                ctx.assets.open("dtc_generic.json").bufferedReader().readText())
            buildMap(o.length()) {
                val it = o.keys()
                while (it.hasNext()) { val k = it.next(); put(k, o.getString(k)) }
            }
        }.getOrDefault(emptyMap())

        supplement = runCatching {
            val o = org.json.JSONObject(
                ctx.assets.open("dtc_supplement.json").bufferedReader().readText())
                .getJSONObject("codes")
            buildMap(o.length()) {
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next(); val e = o.getJSONObject(k)
                    put(k, e.getString("desc") to e.getString("note"))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun describe(code: String): String? = table?.get(code)

    /** (description, caveat) for a field-reported code, or null. Never an SAE definition. */
    fun unofficial(code: String): Pair<String, String>? = supplement?.get(code)
}
