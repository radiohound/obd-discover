package com.redundo.obddiscover

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bundle a capture and hand it to the share sheet.
 *
 * Without this the only way off the phone is `adb pull`, which needs the laptop this app
 * exists to avoid. Sharing goes through Android's own sheet, so the destination is the
 * owner's choice and nothing leaves the device until they pick one.
 *
 * IT SCRUBS ON THE WAY OUT, and the distinction it draws is the point. `vin_key` is a
 * truncated SHA-256: it cannot be reversed into a VIN, but anyone holding a CANDIDATE VIN
 * can hash it and confirm a match. That is a reasonable trade for a cache sitting on the
 * owner's own phone, where it answers "have I mapped this car". It is the wrong trade for a
 * file attached to a public pull request, where it lets a reader test whether a particular
 * vehicle produced the capture. So the local copy keeps it and the shared copy does not.
 *
 * What survives: make, model year, WMI, and the measurements. A WMI is the manufacturer --
 * every Subaru shares one -- so it identifies a make, never a car.
 */
object Export {

    /** Files a contribution needs: the block map and the drive log it produced. */
    private fun latest(dir: File, prefix: String, suffix: String): File? =
        dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(suffix) }
            ?.maxByOrNull { it.lastModified() }

    /**
     * The newest map belonging to THIS car, not merely the newest map.
     *
     * `latest` alone was an attribution bug waiting for a second vehicle: scan car A, scan
     * car B, export, and B's bundle contains A's map under B's make in the filename and the
     * README. Observed 2026-08-27 with a Subaru map exported as a Toyota capture.
     *
     * Matching is on vin_key, the same per-car key Capture uses to decide whether a stored
     * map applies. An empty vinKey (VIN unreadable) matches only files that are ALSO
     * keyless: an unidentified car is not evidence that some identified car's map is his.
     */
    private fun mapFor(dir: File, vinKey: String): File? =
        dir.listFiles { f -> f.name.startsWith("discover-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull {
                try { JSONObject(it.readText()).optString("vin_key", "") == vinKey }
                catch (_: Exception) { false }   // unreadable or pre-vin_key: not ours to claim
            }

    /**
     * Strip identifying fields from a discover.json.
     *
     * Rebuilt key by key rather than by deleting from the original, so a field added later
     * cannot be carried out by accident: anything not named here simply does not appear.
     */
    private fun scrubbedJson(src: File): ByteArray {
        val o = JSONObject(src.readText())
        // WHICH identifiers answered is the discovery and is safe to share. What they
        // RETURNED is not on this list: `mode21` and `mode09` hold payloads, and a Mode-09
        // record is the VIN itself while an unknown one-byte Mode-21 identifier may be a
        // serial. Those two reach a file only through the raw export.
        val keep = listOf("wmi", "preset", "probes", "offsets_probed", "headers_targeted",
                          "addressing", "aborted", "blocks", "detail", "speaks_mode22",
                          "protocol", "mode01", "mode21_ids")
        val out = JSONObject()
        for (k in keep) if (o.has(k)) out.put(k, o.get(k))
        out.put("_note", "vin_key removed for sharing; wmi identifies the manufacturer only")
        return out.toString(1).toByteArray()
    }

    data class Bundle(val file: File, val contents: List<String>, val scrubbed: Boolean)

    /**
     * Stored trouble codes, as their own file in the bundle.
     *
     * They were read on every capture, shown on screen, and then lost -- a code seen on two
     * vehicles could not be looked up afterwards because nothing had written it down. A
     * capture should carry what it observed.
     *
     * Codes are not identifying: a P0420 says a catalyst is below threshold, not whose car
     * it is. So this file is included in the scrubbed export too.
     */
    private fun dtcText(codes: List<Dtc.Code>): String = buildString {
        appendLine("Stored trouble codes, Mode 03")
        appendLine("=============================")
        appendLine()
        if (codes.isEmpty()) { appendLine("none stored"); return@buildString }
        codes.forEach { c ->
            appendLine(c.code)
            appendLine("    system     : ${c.system}${if (c.subsystem.isNotEmpty()) " / " + c.subsystem else ""}")
            appendLine("    defined by : ${if (c.generic) "SAE (generic)" else "the manufacturer"}")
            appendLine("    description: ${c.description ?: "none — manufacturer-defined, no standard meaning"}")
            appendLine()
        }
    }

    /**
     * @param scrub true for a bundle meant to leave the owner's hands.
     *
     * TWO EXPORTS, because the two uses have genuinely different requirements and pretending
     * otherwise makes one of them wrong. A bundle going onto a pull request must not carry
     * anything that ties it to a specific car. A bundle going to the owner's own laptop
     * should carry everything, including the VIN, because its whole job is to say WHICH car
     * this was among several.
     *
     * The raw form is the only thing in this app that writes a VIN to disk, and it does so
     * only when the owner explicitly asks for it about their own vehicle.
     */
    fun build(ctx: Context, info: VehicleId.Info?, scrub: Boolean,
              codes: List<Dtc.Code> = emptyList(), vinKey: String = "",
              adapterLog: List<String> = emptyList(),
              names: List<Triple<String, String, String>> = emptyList(),
              namesFrom: String = ""): Bundle? {
        val dir = File(ctx.getExternalFilesDir(null), "logs")
        val map = mapFor(dir, vinKey)

        // A drive log carries no vehicle key of its own, so it is attributed by ORDER: the
        // log for this capture is written after this capture's map. An older log is some
        // other car's drive and is left out rather than guessed at. With no map at all
        // there is nothing to date it against, so nothing is claimed.
        val log = latest(dir, "discovered-", ".csv")
            ?.takeIf { map != null && it.lastModified() >= map.lastModified() }
        if (map == null && log == null) return null

        // Inside logs/, because that is the only directory file_paths.xml exposes to the
        // FileProvider. A zip written to the parent throws IllegalArgumentException at
        // share time, which would surface as a crash the moment the user taps Share.
        // Distinct names, so the two kinds stay tellable apart after the fact -- on a
        // laptop, in a downloads folder, months later.
        val out = File(dir, "capture-${info?.make ?: "vehicle"}${if (scrub) "" else "-RAW"}.zip")
        val names0 = ArrayList<String>()
        var scrubbed = false
        ZipOutputStream(FileOutputStream(out)).use { z ->
            map?.let {
                z.putNextEntry(ZipEntry(it.name))
                z.write(if (scrub) scrubbedJson(it) else it.readBytes())
                z.closeEntry(); names0.add(it.name); scrubbed = scrub
            }
            log?.let {
                z.putNextEntry(ZipEntry(it.name))
                it.inputStream().use { s -> s.copyTo(z) }
                z.closeEntry(); names0.add(it.name)
            }
            // THE ADAPTER LOG. Highest-value diagnostic this app produces, and until now it
            // was discarded on exit -- a bundle showed `preset: generic` and a missing VIN,
            // which is indistinguishable from a dozen causes. The 29-bit VIN defect was only
            // diagnosable because someone photographed this view before it scrolled away.
            //
            // Newest-first in the UI, so it is reversed here: a file is read top-down.
            if (adapterLog.isNotEmpty()) {
                z.putNextEntry(ZipEntry("adapter-log.txt"))
                val body = adapterLog.reversed().joinToString("\n")
                z.write(
                    ("OBD Discover adapter log — build ${BuildTag.ID}\n" +
                        "oldest first; the on-screen view is newest first\n\n" +
                        (if (scrub) redactVins(body) else body) + "\n").toByteArray(),
                )
                z.closeEntry(); names0.add("adapter-log.txt")
            }
            // NAMES, NOT VALUES, AND IN THEIR OWN FILE.
            //
            // The drive CSV stores hits as raw hex on purpose -- Obd's contract note: "a
            // wrong decode guess made in the field must not destroy data that a better guess
            // at home could still use". An OBDb name is exactly such a guess: it is right for
            // the model that was MATCHED, and the match can be wrong. Writing decoded numbers
            // into the CSV would overwrite the evidence with an interpretation, and renaming
            // the columns would destroy the request identity that `sweep --blocks-from` and
            // every later re-read depend on.
            //
            // So the CSV is left byte-identical and the names ride alongside, keyed by the
            // exact column string. correlate ignores the file today; a reader does not have
            // to, and neither would correlate if it later chose to.
            if (names.isNotEmpty()) {
                z.putNextEntry(ZipEntry("signal-names.csv"))
                z.write(namesCsv(names, namesFrom).toByteArray())
                z.closeEntry(); names0.add("signal-names.csv")
            }
            if (codes.isNotEmpty()) {
                z.putNextEntry(ZipEntry("trouble-codes.txt"))
                z.write(dtcText(codes).toByteArray())
                z.closeEntry(); names0.add("trouble-codes.txt")
            }
            z.putNextEntry(ZipEntry("README.txt"))
            z.write(readme(info, names0, scrub, names, namesFrom).toByteArray())
            z.closeEntry(); names0.add("README.txt")
        }
        return Bundle(out, names0, scrubbed)
    }

    /**
     * Belt and braces over the redaction at the logging site.
     *
     * Two defences because they fail differently: a new log line can forget to redact, and a
     * VIN can appear as plain text OR as ASCII-in-hex split across ISO-TP frame markers --
     * which is how one survived a regex sweep of a bundle before being spotted by eye.
     *
     * Only runs for the scrubbed export. The raw export is meant to carry the VIN; that is
     * its entire purpose and it says so at the top of its README.
     */
    internal fun redactVins(text: String): String {
        var out = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b").replace(text) {
            if (it.value.all { c -> c.isDigit() }) it.value else "[VIN REDACTED]"
        }
        // ASCII-in-hex. Read the PAYLOAD TOKENS, not the whole line: sweeping every hex
        // character in "VIN 0902 @7DF try 1 -> ok: 014 0:4902..." drags in 0902, 7DF and 014
        // too, which shifts the byte boundary so the VIN never aligns and the check passes
        // on a line that is leaking. A token may carry an ISO-TP frame index ("0:", "1:").
        out = out.lines().joinToString("\n") { line ->
            val hex = StringBuilder()
            for (tok in line.split(Regex("\\s+"))) {
                val t = tok.replace(Regex("^\\d+:"), "")
                if (t.length >= 6 && t.length % 2 == 0 && t.all { it in "0123456789ABCDEFabcdef" }) {
                    hex.append(t.uppercase())
                }
            }
            if (hex.length < 34) return@joinToString line
            val ascii = StringBuilder()
            var i = 0
            while (i + 1 < hex.length) {
                val v = hex.substring(i, i + 2).toInt(16)
                ascii.append(if (v in 0x20..0x7E) v.toChar() else '.')
                i += 2
            }
            if (Regex("[A-HJ-NPR-Z0-9]{17}").containsMatchIn(ascii.toString()))
                line.substringBefore("->").trimEnd() + " -> [VIN REDACTED]"
            else line
        }
        return out
    }

    /**
     * A small bundle for a public issue, built from whatever state exists.
     *
     * The capture exports need a successful capture. This one does not -- the case it serves
     * is the adapter that will not connect, or the car that answers nothing, where there is
     * no discover.json to attach and the useful evidence is entirely in the log.
     *
     * ALWAYS SCRUBBED. It is meant for an issue tracker, so there is no raw variant and no
     * choice to get wrong. VINs are redacted, and Bluetooth addresses go too: an adapter MAC
     * is not the phone's, but it is a stable identifier and it costs nothing to drop. Which
     * adapter it is survives in the ATZ string and the bound GATT profile, which is the part
     * anyone diagnosing needs.
     */
    fun report(
        ctx: Context, adapterLog: List<String>, ident: String?, profile: String?,
        mtu: Int, connected: Boolean, protocol: String, phase: String, status: String,
        info: VehicleId.Info?,
    ): Bundle? {
        val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val out = File(dir, "report.zip")
        val names = ArrayList<String>()
        ZipOutputStream(FileOutputStream(out)).use { z ->
            z.putNextEntry(ZipEntry("report.txt"))
            z.write(buildString {
                appendLine("OBD Discover — troubleshooting report")
                appendLine("=====================================")
                appendLine()
                appendLine("Build:      ${BuildTag.ID}")
                appendLine("Android:    ${android.os.Build.VERSION.RELEASE} " +
                    "(API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("Device:     ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine()
                appendLine("Adapter:    ${ident?.trim() ?: "not identified"}")
                appendLine("GATT:       ${profile ?: "none bound"}   mtu $mtu   " +
                    (if (connected) "connected" else "not connected"))
                appendLine("Protocol:   ${protocol.ifEmpty { "not detected" }}")
                appendLine()
                appendLine("Vehicle:    ${info?.year?.toString() ?: "?"} ${info?.make ?: "unknown make"}" +
                    (if (info?.wmi.isNullOrEmpty()) "" else "  (WMI ${info?.wmi})"))
                appendLine("Phase:      $phase")
                appendLine("Status:     ${status.ifBlank { "(nothing run yet)" }}")
                appendLine()
                appendLine("No VIN, no Bluetooth address and no drive data are in this file.")
                appendLine("adapter-log.txt is what was ASKED, not merely what answered.")
            }.toByteArray())
            z.closeEntry(); names.add("report.txt")

            // ALWAYS PRESENT, even when empty. Omitting it produced a bundle with no log at
            // all, which reads as a broken export rather than as "nothing has run yet" --
            // and the reader cannot tell which. Said in the file instead.
            z.putNextEntry(ZipEntry("adapter-log.txt"))
            z.write(
                (if (adapterLog.isEmpty())
                    "(no adapter activity recorded)\n\n" +
                    "Nothing had been attempted when this report was taken. Tap START, let it\n" +
                    "reach the vehicle, then take the report again -- the log is what makes a\n" +
                    "connection problem diagnosable.\n"
                 else redactAddresses(redactVins(adapterLog.reversed().joinToString("\n"))) + "\n")
                    .toByteArray(),
            )
            z.closeEntry(); names.add("adapter-log.txt")
            // The map, if there is one -- scrubbed, and only the newest.
            latest(dir, "discover-", ".json")?.let {
                z.putNextEntry(ZipEntry(it.name))
                z.write(scrubbedJson(it)); z.closeEntry(); names.add(it.name)
            }
        }
        return Bundle(out, names, true)
    }

    /** Bluetooth addresses out of anything bound for an issue tracker. */
    internal fun redactAddresses(text: String): String =
        Regex("\\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b").replace(text, "[MAC REDACTED]")

    /** column,name,unit — keyed by the exact drive-CSV column string. */
    internal fun namesCsv(rows: List<Triple<String, String, String>>, from: String) = buildString {
        appendLine("# Signal names for the columns in discovered-*.csv.")
        appendLine("# Source: OBDb/$from — https://github.com/OBDb — CC BY-SA 4.0")
        appendLine("# These name a column; they do NOT decode it. The CSV keeps raw hex on")
        appendLine("# purpose, and this mapping is only as right as the model match was.")
        appendLine("column,name,unit")
        for ((c, n, u) in rows) appendLine("\"$c\",\"${n.replace("\"", "'")}\",\"$u\"")
    }

    internal fun readme(
        info: VehicleId.Info?, names: List<String>, scrub: Boolean,
        signals: List<Triple<String, String, String>> = emptyList(), signalsFrom: String = "",
    ) = buildString {
        appendLine("OBD capture")
        appendLine("===========")
        appendLine()
        appendLine("Vehicle:      ${info?.year?.toString() ?: "?"} ${info?.make ?: "unknown make"}")
        appendLine("WMI:          ${info?.wmi ?: "?"}   (manufacturer only, not a specific car)")
        if (!scrub && !info?.vin.isNullOrEmpty()) {
            appendLine("VIN:          ${info?.vin}")
            appendLine("              ^ THIS IS THE RAW EXPORT. It identifies one specific")
            appendLine("                vehicle and its owner. Do not attach it to a public")
            appendLine("                issue or pull request -- use the scrubbed export.")
        }
        appendLine("Tool:         OBD Discover build ${BuildTag.ID}")
        if (signals.isNotEmpty()) {
            appendLine()
            appendLine("Known signals (${signals.size})")
            appendLine("-".repeat(14 + signals.size.toString().length + 2))
            appendLine("From OBDb/$signalsFrom — https://github.com/OBDb — CC BY-SA 4.0.")
            appendLine("These NAME a drive-log column; they do not decode it. The CSV keeps raw")
            appendLine("hex on purpose, and this list is only as right as the model match was.")
            appendLine("Repeated in signal-names.csv, keyed by column, for anything that parses.")
            appendLine()
            val w = signals.maxOf { it.first.length }
            for ((col, name, unit) in signals.sortedBy { it.second }) {
                appendLine("  ${col.padEnd(w)}  $name${if (unit.isEmpty()) "" else "  [$unit]"}")
            }
        }
        appendLine()
        appendLine("Files:")
        names.forEach { appendLine("  $it") }
        appendLine()
        appendLine("discover-*.json  Which Mode-22 blocks this vehicle answers, and every DID")
        appendLine("                 that returned data. Matches obd_scan's discover.json, so")
        appendLine("                 `sweep --blocks-from` reads it unmodified.")
        appendLine("signal-names.csv Names for the drive-log columns, where OBDb documents")
        appendLine("                 this model. Names only -- the CSV keeps raw hex, because a")
        appendLine("                 decode guessed in the field must not overwrite evidence a")
        appendLine("                 better guess at home could still use.")
        appendLine("adapter-log.txt  Every command and reply, oldest first. Read this when a")
        appendLine("                 capture looks wrong -- it is the only file that says what")
        appendLine("                 was ASKED, not merely what answered.")
        appendLine("discovered-*.csv Drive log of those DIDs plus the generic anchors. Matches")
        appendLine("                 obd_scan's drive.csv, so `correlate` reads it directly.")
        appendLine()
        appendLine("Raw hex is stored undecoded on purpose: a wrong decode guess made in the")
        appendLine("field must not destroy data a better guess at home could still use.")
        appendLine()
        if (scrub) {
            appendLine("PRIVACY: no VIN is present, and the per-car key the app keeps locally")
            appendLine("has been removed from this copy. Safe to attach to a public thread.")
        } else {
            appendLine("PRIVACY: this is the RAW export and contains the VIN and the per-car")
            appendLine("key. Keep it. Share the scrubbed export instead.")
        }
    }

    /** Hand the bundle to the share sheet. The destination is the owner's choice. */
    /**
     * One vehicles/ record, ready to drop into a pull request.
     *
     * THE ONE EXPORT THAT WIDENS WHAT LEAVES THE PHONE, deliberately and by five
     * characters. Everywhere else this app emits the WMI alone (3 chars) and a SHA-256 of
     * the VIN. A record carries positions 1-8: WMI plus VDS, which encode model, body,
     * engine and restraint. Millions of cars share those, vPIC publishes them as a
     * "pattern", and they cannot be traced to one vehicle. Position 9 is a check digit,
     * 11 the plant, and 12-17 the SERIAL -- take(8) is what keeps every one of them off
     * the file, and tools/merge_vehicles.py rejects the record if anything longer arrives.
     *
     * Payloads stay out for the same reason scrubbedJson keeps them out: a Mode-09 record
     * IS the VIN, and an unidentified one-byte Mode-21 value may be a serial. What a
     * record carries is WHICH identifiers answered -- headers and 256-DID blocks -- which
     * is the discovery, and is the part no public source has.
     */
    fun contribute(ctx: Context, info: VehicleId.Info?, model: String, vinKey: String,
                   series: String = ""): Bundle? {
        val dir = File(ctx.getExternalFilesDir(null), "logs")
        val src = mapFor(dir, vinKey) ?: return null
        val m = JSONObject(src.readText())
        val blocks = m.optJSONArray("blocks")
        val blk = sortedSetOf<String>()
        if (blocks != null) for (i in 0 until blocks.length()) {
            blocks.optJSONObject(i)?.optString("name")?.takeIf { it.length >= 4 }
                ?.let { blk.add(it.take(4).uppercase()) }
        }
        fun ids(key: String): List<String> {
            val a = m.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
        }
        // A NON-CAN CAR HAS NO BLOCKS AND IS STILL WORTH CONTRIBUTING -- requiring them
        // would have excluded the Highlander, whose record is the richer one: Mode 22
        // SILENT against 23 probes, 63 Mode-21 identifiers, 20 Mode-01 PIDs. What a record
        // describes is what a vehicle ANSWERS, which on K-line is those lists.
        val pids = ids("mode01"); val m21 = ids("mode21_ids")
        if (blk.isEmpty() && pids.isEmpty() && m21.isEmpty()) return null
        val hdrSrc = m.optJSONArray("speaks_mode22") ?: m.optJSONArray("headers_targeted")
        val hdr = sortedSetOf<String>()
        if (hdrSrc != null) for (i in 0 until hdrSrc.length())
            hdrSrc.optString(i).takeIf { it.isNotEmpty() }?.let { hdr.add(it) }

        val make = info?.make ?: ""
        val out = JSONObject()
        // take(8), never more. This is the line the privacy claim rests on.
        info?.vin?.takeIf { it.length >= 3 }?.let { out.put("vin_pattern", it.take(8).uppercase()) }
        info?.year?.let { out.put("year", it) }
        out.put("make", make)
        // The BARE model. Not the display label: "2006 MCU23L/MCU28L/ACU20L/ACU25L
        // Highlander" would key its own silo and never match another Highlander.
        out.put("model", model)
        if (series.isNotEmpty()) out.put("series", series)
        out.put("addressing", m.optString("addressing"))
        out.put("protocol", m.optString("protocol"))
        if (hdr.isNotEmpty()) out.put("headers", JSONArray(hdr.toList()))
        if (blk.isNotEmpty()) out.put("blocks", JSONArray(blk.toList()))
        if (pids.isNotEmpty()) out.put("pids", JSONArray(pids))
        if (m21.isNotEmpty()) out.put("mode21_ids", JSONArray(m21))
        // The Mode-22 verdict is the most valuable single field a K-line car produces: it
        // is why a future scan need not spend the sweep finding the same silence. Recorded
        // as evidence, NOT as permission to skip -- see VehicleId.hintsFor on why observed
        // locations reorder a scan and never restrict it.
        m.optString("mode22_verdict").takeIf { it.isNotEmpty() }?.let { out.put("mode22", it) }
        m.optString("mode22_evidence").takeIf { it.isNotEmpty() }
            ?.let { out.put("mode22_evidence", it) }
        // Everything the capture knows and the record can carry. These are repo-side --
        // the merge ships only patterns, locations and named signals -- so being generous
        // here costs nothing in the APK and keeps a contribution from throwing away what
        // was measured. `detail` in particular holds WHICH identifiers answered, where
        // `blocks` holds only the 256-wide ranges they fall in.
        m.optJSONArray("detail")?.let { out.put("detail", it) }
        m.optJSONArray("mode21_overlaps_mode01")?.let { out.put("mode21_mirrors_mode01", it) }
        m.optString("mode09_bitmap").takeIf { it.isNotEmpty() }
            ?.let { out.put("mode09_bitmap", it) }
        m.optJSONArray("mode21_claim_refusals")?.let { out.put("mode21_claimed_no_reply", it) }
        if (m.optInt("probes") > 0) {
            out.put("stats", JSONObject().put("probes", m.optInt("probes"))
                .put("blocks", blk.size))
        }
        out.put("source", "obd-discover")
        // Say what still needs a human. An aborted run's block list is real but partial,
        // and a record that claims to map a car it only half-swept is worse than none.
        val notes = ArrayList<String>()
        if (model.isEmpty()) notes.add("model not resolved — fill this in before opening a pull request")
        if (m.optBoolean("aborted")) notes.add("run was stopped early; block list is partial")
        if (notes.isNotEmpty()) out.put("notes", notes.joinToString("; "))

        val name = "${make.ifEmpty { "vehicle" }}-${model.ifEmpty { "MODEL" }}"
            .replace(Regex("[^A-Za-z0-9-]"), "-")
        val f = File(dir, "$name.json")
        f.writeText(out.toString(2) + "\n")
        return Bundle(f, listOf(f.name), scrubbed = true)
    }

    /** Where contributed records go. One repo, so a record cannot be aimed elsewhere. */
    private const val CONTRIB_REPO = "https://github.com/radiohound/obd-discover"

    /**
     * A prefilled "new issue" on the project, carrying the record in the body.
     *
     * IT OPENS A DRAFT AND NOTHING MORE. GitHub will not create the issue until the person
     * reads it and taps Submit, so the app never publishes on its own -- it fills a form
     * and hands over. That is deliberate: every record needs a human to look at it, and the
     * first two on-car runs are why. They decoded both cars correctly and still wrote a
     * model name that would have siloed every vehicle by year.
     *
     * An issue rather than a pull request because a PR needs a GitHub token stored on the
     * device and a second network path that uploads vehicle data. This needs neither, and
     * the record is 0.4-1.6 KB, which fits a URL with room to spare.
     */
    fun contributeUrl(record: File, make: String, model: String, year: Int?): String {
        val what = listOfNotNull(year?.toString(), make.ifEmpty { null },
                                 model.ifEmpty { null }).joinToString(" ")
        val title = "vehicle: " + what.ifEmpty { "unidentified — needs a model name" }
        val body = buildString {
            append("Adding a vehicle to `vehicles/`, captured with OBD Discover.\n\n")
            if (model.isEmpty())
                append("**The model did not resolve** — please help name it before merge.\n\n")
            append("```json\n").append(record.readText().trim()).append("\n```\n\n")
            append("_No VIN serial is included: the record carries VIN positions 1-8 only._\n")
        }
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        return "$CONTRIB_REPO/issues/new?labels=vehicle&title=${enc(title)}&body=${enc(body)}"
    }

    /**
     * Opens a URL in a BROWSER, explicitly, never in whichever app claims the domain.
     *
     * PRECAUTIONARY, NOT A MEASURED FIX. The GitHub app registers github.com as a verified
     * app link, and it cannot render a prefilled new-issue form, so a device that routes
     * the link there would get a 404 with a correct URL. On the test device Chrome won the
     * resolution anyway, so this has never actually been observed -- it is cheap insurance
     * against a real failure mode, not a repair of one.
     *
     * The 404 seen during testing was neither: the repository is private and the phone's
     * browser was not signed in, so GitHub answered 404 rather than prompting to log in.
     *
     * The selector restricts resolution to handlers of a bare "https:" scheme, which is
     * browsers only. If nothing matches -- a device with no browser at all -- fall back to
     * the plain intent rather than throwing, so the caller still gets whatever is there.
     */
    fun openUrl(ctx: Context, url: String) {
        val uri = android.net.Uri.parse(url)
        val browserOnly = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            selector = Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                data = android.net.Uri.fromParts("https", "", null)
            }
        }
        runCatching { ctx.startActivity(browserOnly) }.onFailure {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun share(ctx: Context, zip: File, mime: String = "application/zip") {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", zip)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Share capture")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
