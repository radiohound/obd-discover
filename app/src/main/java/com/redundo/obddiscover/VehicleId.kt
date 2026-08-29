package com.redundo.obddiscover

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Who is this car, and where does its maker keep its enhanced data.
 *
 * ENTIRELY OFFLINE. Both tables are bundled (~24 KB): a WMI->make map from NHTSA vPIC and a
 * per-make (header, service, block) hint table derived from OBDb. A car park is exactly
 * where a network lookup fails, and the whole point of these hints is to work there.
 *
 * See assets/ATTRIBUTION.txt. The OBDb-derived table is CC-BY-SA-4.0 and stays that way.
 */
object VehicleId {

    private var wmiMap: JSONObject? = null
    private var hints: JSONObject? = null
    private var supported: JSONObject? = null

    /**
     * OBDb locations at MODEL granularity, keyed "Make|Model".
     *
     * ADDITIVE. The make-level tables are untouched and remain the fallback, because their
     * contents cannot be reproduced from today's OBDb signalsets -- the shipped BMW entry
     * holds 2,009 requests on 6F1 across 79 blocks where OBDb/BMW itself carries 46
     * commands. Whatever second source built that, overwriting it would lose it.
     *
     * What this file adds is separation. OBDb keeps one repo per model and one per make;
     * the make repo is an aggregate, so a Silverado was being handed Malibu, Camaro, Bolt
     * and Traverse locations -- 39 rows over ten headers, where its own model needs 18 over
     * four, and a Bolt is 11-bit while a Corvette is 29-bit.
     */
    private var models: JSONObject? = null

    fun load(ctx: Context) {
        if (wmiMap != null) return
        runCatching {
            wmiMap = JSONObject(ctx.assets.open("wmi_to_make.json").bufferedReader().readText())
            hints = JSONObject(ctx.assets.open("obdb_hints.json").bufferedReader().readText())
            supported = JSONObject(ctx.assets.open("obdb_supported.json").bufferedReader().readText())
            models = JSONObject(ctx.assets.open("obdb_models.json").bufferedReader().readText())
        }
    }

    /** Models this make has model-specific data for. Empty when it has none. */
    fun modelsFor(make: String): List<String> {
        val m = models ?: return emptyList()
        val out = ArrayList<String>()
        for (k in m.keys()) if (k.substringBefore('|') == make) out.add(k.substringAfter('|'))
        return out.sorted()
    }

    /**
     * OBDb repository from a make plus whatever vPIC called the model or series.
     *
     * Replaces signature matching as the route to a model, because that route does not work.
     * It required every documented (header, block) pair to be found, and OBDb documents them
     * across modules this app deliberately never probes -- 753, 7A2, 7B0 on a Subaru. Four
     * captures on builds carrying it produced zero matches.
     *
     * The "122 of 147 uniquely identifiable" figure behind it was measured on FULL
     * signatures. On the headers the app actually sees it is 63 of 94, and the reachable
     * subset is usually one or two pairs, which discriminates nothing. That was a property
     * of the data, not of the app.
     *
     * vPIC gives the answer directly, and tries series when the model name is not a repo:
     * a 535i is not an OBDb repository but BMW-5-Series is.
     */
    fun repoForName(make: String, model: String, series: String): String {
        val m = models ?: return ""
        val want = listOf(model, series).filter { it.isNotBlank() }
            .map { "$make-${it.replace(" ", "-")}".lowercase() }
        for (k in m.keys()) {
            val r = m.optJSONObject(k)?.optString("r", "") ?: continue
            if (r.lowercase() in want) return r
        }
        return ""
    }

    /** OBDb repository for a model, e.g. "Chevrolet-Silverado-1500". "" if unknown. */
    fun repoFor(make: String, model: String): String =
        models?.optJSONObject("$make|$model")?.optString("r", "") ?: ""

    /** (header, block) pairs a model is documented to answer at -- its signature. */
    fun signatureOf(make: String, model: String): Set<Pair<String, String>> {
        val arr = models?.optJSONObject("$make|$model")?.optJSONArray("h") ?: return emptySet()
        val out = HashSet<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: continue
            out.add(r.optString(0).uppercase() to r.optString(2).uppercase())
        }
        return out
    }

    /** Known requests for one model, same shape as supportedFor. */
    fun supportedForModel(make: String, model: String): List<Pair<String, String>> {
        val arr = models?.optJSONObject("$make|$model")?.optJSONArray("k") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val row = arr.optString(i); val c = row.indexOf(':')
            if (c <= 0) continue
            val hdr = row.substring(0, c).uppercase(); val req = row.substring(c + 1).uppercase()
            // 29-bit targets are written as four characters ("DA11"); the bus address is
            // DA11F1. Filtering to three dropped every one, so on a 29-bit vehicle phase 0
            // matched nothing against liveHeaders: a Silverado offered 409 known requests
            // and sent 20, all of which were the identification DIDs.
            val h = when {
                hdr.length == 3 && hdr != "6F1" -> hdr
                hdr.length == 4 && hdr.startsWith("DA") -> hdr + "F1"
                else -> continue
            }
            if (!h.all { it in "0123456789ABCDEF" }) continue
            if (!req.startsWith("22") || req.length < 6) continue
            out.add(h to req)
        }
        return out.distinct()
    }

    /**
     * Which model this vehicle ANSWERS like, from what the scan actually found.
     *
     * Not decoded from the VIN. Alan's vin.cpp documents why that route is brittle --
     * positional rules are not stable across model years, year codes repeat every 30 years,
     * and a 2011-12 Express van once satisfied the GM 1500 diesel rule exactly. A previous
     * attempt at model naming in this app was abandoned for being wrong too often, and it
     * was decoding rather than measuring.
     *
     * This asks the car instead: 122 of 147 documented models (82%) have a unique set of
     * (header, block) pairs, so what answered is usually enough to say which model it is.
     *
     * Returns EVERY model whose signature is fully contained in what was found, so a tie is
     * reported as a tie rather than resolved by guessing. Bolt EV and Bolt EUV are the same
     * platform and will always tie; saying so is correct.
     */
    fun modelsMatching(make: String, found: Set<Pair<String, String>>): List<String> {
        if (found.isEmpty()) return emptyList()
        val scored = modelsFor(make).mapNotNull { model ->
            val sig = signatureOf(make, model)
            if (sig.isEmpty()) null else model to (sig intersect found).size to sig.size
        }
        // A model only qualifies if EVERY location it documents was seen. A partial match is
        // a different vehicle that happens to share a block.
        val full = scored.filter { it.first.second == it.second }.map { it.first.first to it.second }
        if (full.isEmpty()) return emptyList()
        val best = full.maxOf { it.second }
        return full.filter { it.second == best }.map { it.first }.sorted()
    }

    /**
     * Makes this VIN's WMI builds for, most likely first. Empty when the WMI is unknown.
     *
     * A WMI is a plant, not a brand, so one can legitimately carry several makes: 1N4 is
     * Nissan AND Infiniti, 3C4 is the whole Chrysler/Dodge/Jeep/Ram/Fiat family, and 19U
     * is Acura built by Honda. The table stores a bare string for the ordinary case and an
     * array for those, so only the 53 that need it pay for it.
     */
    fun makes(vin: String): List<String> {
        if (vin.length < 3) return emptyList()
        val v = wmiMap?.opt(vin.take(3).uppercase()) ?: return emptyList()
        if (v is JSONArray) return (0 until v.length()).map { v.optString(it) }.filter { it.isNotEmpty() }
        val s = v.toString()
        return if (s.isEmpty()) emptyList() else listOf(s)
    }

    /** Manufacturer from the WMI (VIN chars 1-3), or "" if unknown. */
    fun make(vin: String): String = makes(vin).firstOrNull() ?: ""

    /**
     * The other makes this VIN's plant builds for -- everything after the primary.
     *
     * Passed to the hint lookups as `also`, never merged into the make itself. Merging at
     * make level looked tempting and is wrong: one rebadge (the Chevrolet City Express is
     * a Nissan NV200) would drag Infiniti's locations into every Chevrolet scan, taking
     * Nissan's own hint set from 12 rows to 90. Scoped to the WMI, a 1N4 gets Nissan then
     * Infiniti and nothing else does.
     */
    fun siblings(vin: String): List<String> = makes(vin).drop(1)

    /**
     * Model year from VIN position 10.
     *
     * The code repeats on a 30-year cycle, so 'L' is 1990 AND 2020. Disambiguated by
     * position 7: for 1980-2009 it is numeric, for 2010+ it is alphabetic. That rule is
     * how the standard itself resolves the collision.
     */
    fun year(vin: String): Int? {
        if (vin.length < 10) return null
        val c = vin[9].uppercaseChar()
        val codes = "ABCDEFGHJKLMNPRSTVWXY123456789"
        val i = codes.indexOf(c)
        if (i < 0) return null
        val older = 1980 + i
        val newer = 2010 + i
        val pos7Alpha = vin.length >= 7 && vin[6].isLetter()
        return if (pos7Alpha) { if (newer <= 2039) newer else older } else older
    }

    /** What a VIN tells us with no network at all. */
    data class Info(val vin: String, val wmi: String, val make: String, val year: Int?) {
        val known: Boolean get() = make.isNotEmpty()
    }

    fun identify(vin: String) = Info(vin, vin.take(3).uppercase(), make(vin), year(vin))

    /** One documented location: header, service, 256-DID block, and whether it is powertrain. */
    data class Hint(val header: String, val service: String, val block: String,
                    val powertrain: Boolean)

    /**
     * Every documented (header, service, block) for a make.
     *
     * These REORDER a scan, they never restrict it — and the BMW data is the proof. OBDb
     * lists blocks 40,41,43,44,45,4C,57... for BMW, but an on-car sweep of an F10 found its
     * 462 DIDs in 42,43,44,45,4A,58. Three of the six — including 2258xx with 227 DIDs and
     * the oil-pressure row — are absent from the community list entirely. Probing only what
     * is written down would have lost 358 of 462 DIDs, and would reproduce the failure that
     * hid oil temperature for a month: 2244xx was not in the preset, so a DID the project's
     * own documentation already named was never queried.
     *
     * Hinted locations go first because they pay off in the first minute. The blind sweep
     * still follows, and it is what finds the rest.
     */
    fun hintsFor(make: String, also: List<String> = emptyList()): List<Hint> {
        val out = ArrayList<Hint>()
        // Primary make first so its locations keep their head start; siblings only extend
        // the tail. Deduped because plants that share a brand share documented rows.
        for (m in listOf(make) + also) {
            val arr = hints?.optJSONArray(m) ?: continue
            for (i in 0 until arr.length()) {
                val r = arr.optJSONArray(i) ?: continue
                if (r.length() >= 4) {
                    val h = Hint(r.optString(0), r.optString(1), r.optString(2), r.optInt(3) == 1)
                    if (h !in out) out.add(h)
                }
            }
        }
        return out
    }

    /**
     * Mode-22 block prefixes this make is known to use, e.g. 0x2244.
     *
     * Powertrain-pathed rows only by default. The filter is OBDb's own `path` on each
     * signal (Engine/Fuel/Transmission/...), which is better evidence than the address-range
     * rule it replaces: that rule excluded Toyota's engine module at 700 while admitting
     * addresses it knew nothing about.
     */
    fun blockPrefixes(make: String, powertrainOnly: Boolean = true, also: List<String> = emptyList()): List<Int> =
        hintsFor(make, also)
            .filter { it.service == "22" && it.block.length == 2 && (!powertrainOnly || it.powertrain) }
            .mapNotNull { it.block.toIntOrNull(16) }
            .distinct().map { 0x2200 or it }.sorted()

    /**
     * Headers this make answers Mode 22 on AND that this app can actually address.
     *
     * Only plain 11-bit headers, which is all `AT SH` alone selects. Two documented kinds
     * are deliberately excluded because handing them to ATSH would not merely fail, it
     * would fail QUIETLY -- the adapter accepts the command and every subsequent probe
     * returns nothing, which is indistinguishable from a car that has no data there:
     *
     *   6F1  BMW extended addressing. Needs AT CEA <target> and AT CRA as well; the header
     *        alone addresses nothing. This is most of OBDb's BMW data.
     *   DA10, D016, ... 29-bit targets, written in short form. Need AT SP7 and AT CP.
     *
     * They are still surfaced by unaddressable(), so the screen can say what is known but
     * out of reach rather than pretending it does not exist.
     */
    fun headers(make: String, powertrainOnly: Boolean = true, also: List<String> = emptyList()): List<String> =
        hintsFor(make, also)
            .filter {
                it.service == "22" && (!powertrainOnly || it.powertrain) &&
                    it.header.length == 3 && it.header != "6F1" &&
                    it.header.all { c -> c in "0123456789ABCDEFabcdef" }
            }
            .map { it.header.uppercase() }.distinct().sorted()

    /**
     * Documented 29-bit targets, as full six-character headers.
     *
     * `headers()` keeps only three-character names, so every 29-bit hint OBDb carries was
     * being dropped silently -- and the 29-bit fallback used a fixed list that does not
     * include them. OBDb puts 45 of the Silverado 1500's 52 commands on DA11, which is
     * absent from HEADERS_29BIT, so the header holding most of that truck's enhanced data
     * had no way of being probed.
     *
     * OBDb writes the target as "DA11"; the bus address is DA11F1, tester F1 appended.
     */
    fun headers29(make: String, powertrainOnly: Boolean = true, also: List<String> = emptyList()): List<String> =
        hintsFor(make, also)
            .filter {
                it.service == "22" && (!powertrainOnly || it.powertrain) &&
                    it.header.length == 4 && it.header.uppercase().startsWith("DA") &&
                    it.header.all { c -> c in "0123456789ABCDEFabcdef" }
            }
            .map { it.header.uppercase() + "F1" }.distinct().sorted()

    /** Documented Mode-22 headers this app cannot address, with the reason. */
    fun unaddressable(make: String, also: List<String> = emptyList()): List<Pair<String, String>> =
        hintsFor(make, also).filter { it.service == "22" && it.header.isNotEmpty() }
            .map { it.header }.distinct().sorted()
            .mapNotNull { h ->
                when {
                    // 6F1 is the tester address in ISO-TP EXTENDED addressing, where the
                    // target ECU is selected by a separate byte (AT CEA) rather than by the
                    // header. BMW uses it heavily, which is why it was labelled that way --
                    // but a 2006 Toyota showed the label reading "BMW extended addressing"
                    // on a Toyota, which is simply wrong. The scheme is not BMW's property.
                    // 6F1 IS reachable now -- Discover.BMW_TARGETS expands it via AT CEA and
                    // AT CRA. Only the targets documented as powertrain are tried, so the
                    // rest of that address space stays unvisited and is still worth naming.
                    h == "6F1" -> h to "extended addressing — targets ${Discover.BMW_TARGETS.joinToString("/")} only"
                    h.length > 3 -> h to "29-bit target (needs AT SP7/CP)"
                    else -> null
                }
            }

    /**
     * (header, block-prefix) pairs this make is documented to answer on.
     *
     * Recon probes seven offsets per block, which finds a block only if one of its DIDs
     * happens to sit on a probed offset. Measured on a Ford Ranger, 2026-08-26: blocks
     * 221Exx, 22F4xx and 2228xx were reported absent, while OBDb documents transmission oil
     * temperature at 221E1C, fuel level at 22F42F and tyre pressures at 222813-16 -- offsets
     * 0x12 to 0x2F, none of them probed. The block was not empty; the sampling missed it.
     *
     * These pairs are therefore swept IN FULL rather than sampled, whenever their header is
     * alive. It is a targeted cost -- 26 pairs on that Ranger, about nine minutes -- and it
     * applies only where the community has already recorded that data exists.
     */
    fun hintedPairs(make: String, powertrainOnly: Boolean = false, also: List<String> = emptyList()): List<Pair<String, Int>> =
        hintsFor(make, also)
            .filter {
                it.service == "22" && it.block.length == 2 &&
                    it.header.length == 3 && it.header != "6F1" &&
                    it.header.all { c -> c in "0123456789ABCDEFabcdef" } &&
                    (!powertrainOnly || it.powertrain)
            }
            .mapNotNull { hn -> hn.block.toIntOrNull(16)?.let { hn.header.uppercase() to (0x2200 or it) } }
            .distinct()

    /**
     * Exact (header, request) pairs this make is KNOWN to answer, from OBDb's per-model-year
     * command_support censuses -- real supported-command captures, not inference.
     *
     * This is what block hints should have been. A block hint says "something lives in
     * 221Exx" and costs 256 probes to act on; this says the answer is 221E1C and costs one.
     * Measured on a Ford Ranger: the six documented DIDs the offset sampling missed cost 181
     * probes to ask for directly, against 6656 to find them by sweeping the hinted blocks --
     * fifteen seconds instead of nine minutes, and better coverage.
     *
     * Mode 21 entries are filtered out: the whitelist does not permit sending them.
     */
    fun supportedFor(make: String): List<Pair<String, String>> {
        val arr = supported?.optJSONArray(make) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optString(i)
            val c = row.indexOf(':')
            if (c <= 0) continue
            val hdr = row.substring(0, c).uppercase()
            val req = row.substring(c + 1).uppercase()
            // Plain 11-bit headers only, and Mode 22 only -- same limits as headers().
            // 29-bit targets are written as four characters ("DA11"); the bus address is
            // DA11F1. Filtering to three dropped every one, so on a 29-bit vehicle phase 0
            // matched nothing against liveHeaders: a Silverado offered 409 known requests
            // and sent 20, all of which were the identification DIDs.
            val h = when {
                hdr.length == 3 && hdr != "6F1" -> hdr
                hdr.length == 4 && hdr.startsWith("DA") -> hdr + "F1"
                else -> continue
            }
            if (!h.all { it in "0123456789ABCDEF" }) continue
            if (!req.startsWith("22") || req.length < 6) continue
            out.add(h to req)
        }
        return out.distinct()
    }

    /**
     * True if this make is known to use Mode 21 for enhanced data.
     *
     * Said "which we cannot send" until the Mode-21 sweep landed. It is sent on KWP2000,
     * and on ISO 9141-2 behind the opt-in.
     */
    fun usesMode21(make: String, also: List<String> = emptyList()): Boolean =
        hintsFor(make, also).any { it.service == "21" }
}
