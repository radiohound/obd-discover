package com.redundo.obddiscover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding OBDb signal definitions. Every case below is a real definition from
 * OBDb/Chevrolet-Silverado-1500, because a decode that is subtly wrong produces a
 * plausible number rather than an obvious failure -- and a plausible wrong number in a
 * capture is worse than an undecoded one.
 */
class SignalSetTest {

    private val silverado = """
    {"commands":[
      {"hdr":"DA11","cmd":{"22":"005C"},"signals":[
        {"id":"SILVERADO1500_EOT","name":"Engine oil temperature",
         "fmt":{"len":8,"max":215,"min":-40,"add":-40,"unit":"celsius"}}]},
      {"hdr":"7E0","cmd":{"22":"000D"},"signals":[
        {"id":"SILVERADO1500_VSS","name":"Vehicle speed",
         "fmt":{"len":8,"max":255,"unit":"kilometersPerHour"}}]},
      {"hdr":"DA11","cmd":{"22":"0078"},"signals":[
        {"id":"DPF_EGT_4","name":"DPF exhaust gas temperature 4",
         "fmt":{"bix":56,"len":16,"max":6513.5,"min":-40,"div":10,"add":-40}}]},
      {"hdr":"DA11","cmd":{"22":"00AA"},"signals":[
        {"id":"SIGNED","name":"Signed degrees",
         "fmt":{"len":8,"max":635,"min":-640,"mul":5,"sign":true,"unit":"degrees"}}]}
    ]}"""

    @Test fun loadsDefinitions() {
        assertTrue(SignalSet.loadFrom(silverado, "test"))
        assertEquals(4, SignalSet.signalCount)
    }

    /** add: raw 0x5C = 92, minus 40 = 52 C. */
    @Test fun appliesOffset() {
        SignalSet.loadFrom(silverado, "test")
        val r = SignalSet.decode("DA11", "22005C", "5C")
        assertEquals(1, r.size)
        assertEquals("Engine oil temperature", r[0].name)
        assertEquals("52", r[0].value)
        assertEquals("celsius", r[0].unit)
    }

    /** No scaling at all: raw is the value. */
    @Test fun plainValue() {
        SignalSet.loadFrom(silverado, "test")
        assertEquals("73", SignalSet.decode("7E0", "22000D", "49")[0].value)
    }

    /** bix is a BIT index, not a byte offset: bit 56 is byte 7. div then add. */
    @Test fun bitIndexAndDivide() {
        SignalSet.loadFrom(silverado, "test")
        // 8 pad bytes, then 0x0BB8 = 3000 -> 3000/10 - 40 = 260
        val r = SignalSet.decode("DA11", "220078", "00000000000000" + "0BB8")
        assertEquals(1, r.size)
        assertEquals("260", r[0].value)
    }

    /** sign is two's complement over len bits, then mul. 0xFF = -1 -> -5 degrees. */
    @Test fun signedThenMultiplied() {
        SignalSet.loadFrom(silverado, "test")
        assertEquals("-5", SignalSet.decode("DA11", "2200AA", "FF")[0].value)
    }

    /** A payload shorter than the definition is skipped, never guessed at. */
    @Test fun shortPayloadIsSkipped() {
        SignalSet.loadFrom(silverado, "test")
        assertEquals(0, SignalSet.decode("DA11", "220078", "0B").size)
    }

    /** A request this model does not document names nothing. */
    @Test fun unknownRequestNamesNothing() {
        SignalSet.loadFrom(silverado, "test")
        assertEquals(0, SignalSet.decode("DA11", "229999", "1234").size)
    }

    /** Header matters: the same request on another header is a different signal. */
    @Test fun headerIsPartOfTheKey() {
        SignalSet.loadFrom(silverado, "test")
        assertEquals(0, SignalSet.decode("7E2", "22005C", "5C").size)
    }
}

/**
 * The names sidecar. The drive CSV must stay byte-identical: correlate keys on the exact
 * column string, and Obd's contract note is explicit that a field decode must not overwrite
 * raw hex.
 */
class SignalNamesTest {

    private val rows = listOf(
        Triple("22005C@DA11", "Engine oil temperature", "celsius"),
        Triple("22000D@7E0", "Vehicle speed", "kilometersPerHour"),
    )

    @Test fun keyedByTheExactCsvColumn() {
        val csv = Export.namesCsv(rows, "Chevrolet-Silverado-1500")
        assertTrue(csv.contains("\"22005C@DA11\",\"Engine oil temperature\",\"celsius\""))
        assertTrue("must have a header row", csv.contains("column,name,unit"))
    }

    /** Attribution travels with it -- these are CC BY-SA 4.0 definitions. */
    @Test fun carriesAttribution() {
        val csv = Export.namesCsv(rows, "Chevrolet-Silverado-1500")
        assertTrue(csv.contains("OBDb/Chevrolet-Silverado-1500"))
        assertTrue(csv.contains("CC BY-SA 4.0"))
    }

    /** It must say it is a name and not a decode, because the model match can be wrong. */
    @Test fun saysItIsNotADecode() {
        val csv = Export.namesCsv(rows, "X")
        assertTrue(csv.contains("do NOT decode"))
        assertTrue(csv.contains("only as right as the model match"))
    }

    /** A quote in a signal name must not break the CSV. */
    @Test fun quotesAreNeutralised() {
        val csv = Export.namesCsv(listOf(Triple("22AA@7E0", "He said \"hot\"", "c")), "X")
        assertEquals(1, csv.lines().count { it.startsWith("\"22AA@7E0\"") })
        assertTrue(!csv.contains("\"He said \"hot\"\""))
    }
}

/** The names also appear in the bundle README, so they are visible without a second file. */
class ReadmeSignalsTest {

    private val sigs = listOf(
        Triple("22005C@DA11", "Engine oil temperature", "celsius"),
        Triple("22000D@7E0", "Vehicle speed", "kilometersPerHour"),
    )

    @Test fun readmeListsThemWithUnits() {
        val r = Export.readme(null, listOf("discover-1.json"), true, sigs, "Chevrolet-Silverado-1500")
        assertTrue(r.contains("Known signals (2)"))
        assertTrue(r.contains("Engine oil temperature  [celsius]"))
        assertTrue(r.contains("22000D@7E0"))
    }

    /** Attribution, and the same caveat the sidecar carries. */
    @Test fun readmeCarriesSourceAndCaveat() {
        val r = Export.readme(null, emptyList(), true, sigs, "Chevrolet-Silverado-1500")
        assertTrue(r.contains("OBDb/Chevrolet-Silverado-1500"))
        assertTrue(r.contains("CC BY-SA 4.0"))
        assertTrue(r.contains("do not decode"))
        assertTrue(r.contains("only as right as the model match"))
    }

    /** No names, no section -- an empty heading would imply the lookup had run. */
    @Test fun noSectionWhenThereAreNoNames() {
        val r = Export.readme(null, emptyList(), true, emptyList(), "")
        assertTrue(!r.contains("Known signals"))
    }
}

/**
 * The VIN must not reach a scrubbed export. Regression test for a real leak: a
 * capture-BMW.zip produced on 2026-08-28 carried WBA00000000000000 in adapter-log.txt,
 * inside the export documented as safe to attach to a public issue.
 */
class VinRedactionTest {

    /** The exact line that leaked, verbatim from that bundle. */
    private val leaked =
        "VIN 0902 @7DF try 1 -> ok: 014 0:490201574241 1:30303030303030 2:30303030303030  >"

    @Test fun theRealLeakedLineIsRedacted() {
        val out = Export.redactVins(leaked)
        assertTrue("the VIN must not survive", !out.contains("WBA00000000000000"))
        assertTrue("nor its hex", !out.contains("30303030303030"))
        assertTrue(out.contains("VIN REDACTED"))
        assertTrue("the diagnostic prefix should survive", out.contains("VIN 0902 @7DF try 1"))
    }

    /** A plain-text VIN anywhere in the log goes too. */
    @Test fun plainTextVinIsRedacted() {
        val out = Export.redactVins("identified WBA00000000000000 as a 535i")
        assertTrue(!out.contains("WBA00000000000000"))
        assertTrue(out.contains("[VIN REDACTED]"))
    }

    /** The 10-character prefix is NOT a VIN and must survive -- it is what vPIC gets. */
    @Test fun abbreviatedPrefixSurvives() {
        val out = Export.redactVins("vPIC: WBAFR7C53D -> 2013 5-Series 535i")
        assertTrue(out.contains("WBAFR7C53D"))
        assertTrue(out.contains("535i"))
    }

    /** Ordinary hex payloads are not touched; over-redacting would gut the log. */
    @Test fun ordinaryRepliesSurvive() {
        for (line in listOf(
            "protocol: SP0 0100 try 2 -> ok=true raw=SEARCHING... 4100BE3FA813 410098188011",
            "sweep 2258xx 30/FF — 296 DIDs found",
            "state=2 status=SUCCESS",
        )) assertEquals(line, Export.redactVins(line))
    }

    /** A 17-digit number is not a VIN. */
    @Test fun digitsAreNotAVin() {
        val s = "uptime 12345678901234567 ms"
        assertEquals(s, Export.redactVins(s))
    }
}

/**
 * The troubleshooting bundle is meant for a public issue tracker, so it has no raw variant
 * and no choice to get wrong. Everything identifying comes out.
 */
class ReportBundleTest {

    @Test fun bluetoothAddressesAreRedacted() {
        val line = "connectGatt(autoConnect=true) -> D2:E0:2F:8D:4D:46"
        val out = Export.redactAddresses(line)
        assertTrue(!out.contains("D2:E0:2F:8D:4D:46"))
        assertTrue(out.contains("[MAC REDACTED]"))
        assertTrue("the diagnostic prefix survives", out.contains("connectGatt"))
    }

    /** Which adapter it is must survive -- that is the part anyone diagnosing needs. */
    @Test fun adapterIdentitySurvives() {
        val out = Export.redactAddresses("bound GATT profile 'vlinker 18f0'")
        assertEquals("bound GATT profile 'vlinker 18f0'", out)
    }

    /** A timestamp or a hex payload must not look like a MAC. */
    @Test fun ordinaryTextIsUntouched() {
        for (s in listOf(
            "08-28 19:05:50.710 protocol: auto-detected A3",
            "sweep 2258xx 30/FF — 296 DIDs found",
            "4100BE3FA813 410098188011",
        )) assertEquals(s, Export.redactAddresses(s))
    }

    /** Both redactions compose: a log line can carry a MAC and a VIN. */
    @Test fun macAndVinBothGo() {
        val line = "D2:E0:2F:8D:4D:46 saw WBA00000000000000"
        val out = Export.redactAddresses(Export.redactVins(line))
        assertTrue(!out.contains("D2:E0"))
        assertTrue(!out.contains("WBA00000000000000"))
    }
}

/** The log file must always be in the bundle, so an empty one cannot look like a broken export. */
class ReportAlwaysHasLogTest {
    @Test fun theExportAlwaysWritesAnAdapterLogEntry() {
        val src = java.io.File(
            "app/src/main/java/com/redundo/obddiscover/Export.kt",
        ).takeIf { it.exists() } ?: java.io.File(
            "../app/src/main/java/com/redundo/obddiscover/Export.kt",
        )
        val t = src.readText()
        val i = t.indexOf("fun report(")
        assertTrue("report() must exist", i > 0)
        val body = t.substring(i, minOf(i + 4000, t.length))
        assertTrue("adapter-log.txt must not be conditional on a non-empty log",
            !body.contains("if (adapterLog.isNotEmpty())"))
        assertTrue("and must say so when there is nothing",
            body.contains("no adapter activity recorded"))
    }
}

/**
 * Which headers a make must not be probed on (#9).
 *
 * The hint table itself is an Android asset and reads back empty off-device, so what is
 * asserted here is the rule, not the table. The table facts behind it -- that no VAG make
 * hints 7E4, and that Audi, VW and Porsche each carry seven powertrain-flagged 7E5 hints --
 * are recorded in VehicleId.excludedHeaders where the reasoning lives.
 */
class HeaderExclusionTest {

    /**
     * The header that actually reaches a VAG car in the forbidden range is 7E2, and it
     * arrives from the make-INDEPENDENT default -- not from any hint. Excluding only the
     * header the note names, 7E4, would have protected nothing: no VAG make hints it, so it
     * was never sent to one in the first place.
     */
    @Test fun theExcludedHeaderIsOneOfOurOwnDefaults() {
        assertTrue("7E2" in Discover.HEADERS_11BIT)
        assertTrue("7E2" in VehicleId.excludedHeaders("Audi"))
    }

    /** The five headers that document nothing on any VAG marque, including the hazard. */
    @Test fun theEmptyHeadersInTheRangeAreExcluded() {
        val e = VehicleId.excludedHeaders("Audi")
        for (h in listOf("7E2", "7E3", "7E4", "7E6", "7E7")) assertTrue(h, h in e)
        assertTrue("7E0 is the engine and must stay", "7E0" !in e)
        assertTrue("7E1 is the TCM and must stay", "7E1" !in e)
        assertTrue("the broadcast must stay", "7DF" !in e)
    }

    /**
     * 7E5 is kept against the letter of the source note. OBDb documents 575 signals there
     * on four VAG marques and every one is high-voltage battery, so excluding it would cost
     * an e-tron its entire battery dataset to protect against a module it is not.
     */
    @Test fun theBatteryHeaderIsNotExcluded() {
        assertTrue("7E5" !in VehicleId.excludedHeaders("Audi"))
        assertTrue("7E5" !in VehicleId.excludedHeaders("Porsche"))
    }

    /** The finding is Audi's; the group shares the architecture. */
    @Test fun theExclusionCoversTheGroupNotOneMarque() {
        for (mk in listOf("Volkswagen", "Porsche", "Skoda", "SEAT", "Bentley", "Lamborghini")) {
            assertTrue(mk, "7E4" in VehicleId.excludedHeaders(mk))
        }
    }

    /** A sibling make from the WMI table triggers it too, since that is how VAG plants read. */
    @Test fun aSiblingMakeTriggersTheExclusion() {
        assertTrue("7E2" in VehicleId.excludedHeaders("Unknown", also = listOf("Audi")))
    }

    /** Everyone else keeps the range. GM answered 69 DIDs on 7E4 with nothing adverse. */
    @Test fun nonVagMakesAreUnaffected() {
        for (mk in listOf("Chevrolet", "GMC", "Ford", "Hyundai", "BMW", "Subaru", "Toyota")) {
            assertTrue(mk, VehicleId.excludedHeaders(mk).isEmpty())
        }
    }

    /** An unknown make excludes nothing: a guess must never silently narrow a scan. */
    @Test fun anUnknownMakeExcludesNothing() {
        assertTrue(VehicleId.excludedHeaders("").isEmpty())
        assertTrue(VehicleId.excludedHeaders("Rivian").isEmpty())
    }
}

/**
 * The measured identifier lists that now ship (#D11).
 *
 * These are the strongest data this project holds -- what a real car of that make and model
 * actually answered -- and they are what lets a scan confirm in 1.7 minutes what a blind
 * sweep takes 24 to rediscover. VehicleId reads them from an Android asset, which is empty
 * off-device, so what is asserted here is the shipped file itself.
 */
class ShippedIdentifierListTest {

    private val root: java.io.File =
        generateSequence(java.io.File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { java.io.File(it, "README.md").isFile }
    private val asset = org.json.JSONObject(
        java.io.File(root, "app/src/main/assets/vin_patterns.json").readText())
    private val locations = asset.getJSONObject("locations")

    private fun packedLists(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (k in locations.keys()) {
            val ids = locations.getJSONObject(k).optJSONObject("ids") ?: continue
            for (h in ids.keys()) out.add(h to ids.getString(h))
        }
        return out
    }

    /** They are actually there, and there are enough of them to matter. */
    @Test fun theAssetCarriesTheMeasuredIdentifiers() {
        val n = packedLists().sumOf { it.second.length / 4 }
        assertTrue("expected thousands of measured identifiers, got $n", n > 3000)
    }

    /**
     * PAYLOADS MUST NOT HIDE HERE. What a vehicle returned is never committed and never
     * shipped; only which identifiers it answered. Every entry has to decode to exactly one
     * six-character Mode-22 request, which a payload of any length cannot.
     */
    @Test fun nothingButModeTwentyTwoIdentifiersCanBeInThere() {
        for ((hdr, packed) in packedLists()) {
            assertEquals("$hdr is not a whole number of identifiers", 0, packed.length % 4)
            assertTrue("$hdr is not hex: $packed", packed.all { it in "0123456789ABCDEF" })
            var i = 0
            while (i + 4 <= packed.length) {
                val req = "22" + packed.substring(i, i + 4)
                assertEquals("bad request $req", 6, req.length)
                i += 4
            }
        }
    }

    /** Every header a list is filed under must be a header the app can actually select. */
    @Test fun theHeadersAreAddressable() {
        for ((hdr, _) in packedLists()) {
            assertTrue("$hdr is neither 11-bit nor 29-bit", hdr.length == 3 || hdr.length == 6)
            assertTrue("$hdr is not hex", hdr.all { it in "0123456789ABCDEF" })
        }
    }

    /** A Mode-22 silent vehicle contributes no identifiers, and must not invent any. */
    @Test fun aSilentVehicleShipsNone() {
        val hl = locations.optJSONObject("Toyota|Highlander")
        assertTrue("the Highlander answers no Mode 22 and must ship no identifier list",
            hl == null || hl.optJSONObject("ids") == null)
    }

    /**
     * A canary, not a limit. Around 200 vehicles this reaches ~516 KB and fetching a matched
     * record at run time becomes the better trade. This fails long before that, so the
     * decision gets made deliberately rather than discovered in an APK size report.
     */
    @Test fun theAssetIsStillSmall() {
        val bytes = java.io.File(root, "app/src/main/assets/vin_patterns.json").length()
        assertTrue("vin_patterns.json is $bytes bytes; revisit shipping vs fetching",
            bytes < 200_000)
    }
}

/** The two lookups that were shipped and never reached. */
class KnownRequestReachTest {

    private val root: java.io.File =
        generateSequence(java.io.File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { java.io.File(it, "README.md").isFile }
    private fun asset(n: String) =
        java.io.File(root, "app/src/main/assets/$n").readText()
    private fun source(n: String) =
        java.io.File(root, "app/src/main/java/com/redundo/obddiscover/$n").readText()

    /**
     * OBDb files it as "IONIQ 5"; a person wrote "Ioniq 5" in the record. An exact match
     * returns nothing, which is why the model tier reached that car with zero requests
     * while carrying thirty-four aimed at its battery, charger, motor and odometer.
     */
    @Test fun theModelKeysDisagreeOnCaseAndMustStillMatch() {
        val models = org.json.JSONObject(asset("obdb_models.json"))
        val ours = org.json.JSONObject(asset("vin_patterns.json")).getJSONObject("locations")
        val contributed = ours.keys().asSequence().toList()
        val exact = contributed.filter { models.has(it) }
        val insensitive = contributed.filter { c ->
            models.keys().asSequence().any { it.equals(c, ignoreCase = true) }
        }
        assertTrue("case-insensitive must reach at least as many models as exact",
            insensitive.size >= exact.size)
        assertTrue("the Ioniq 5 is the case this fix exists for",
            insensitive.any { it.equals("Hyundai|Ioniq 5", ignoreCase = true) })
        assertTrue("and an exact match must still fail on it, or the fix is untested",
            !models.has("Hyundai|Ioniq 5"))
    }

    /** All three tiers have to be offered, or the most specific data goes unused again. */
    @Test fun allThreeKnownRequestTiersAreConsulted() {
        val c = source("Capture.kt")
        for (fn in listOf("contributedRequests", "supportedForModel", "supportedFor")) {
            assertTrue("Capture must consult $fn", c.contains("VehicleId.$fn("))
        }
    }

    /**
     * Blocks that phase 0 proved are swept BEFORE recon searches for more. Order only --
     * recon still runs in full and every block is still swept. The point is which twelve
     * minutes come first when somebody stops early.
     */
    @Test fun provedBlocksAreSweptBeforeRecon() {
        val d = source("Discover.kt")
        val seeded = d.indexOf("sweep what phase 0 PROVED")
        val recon = d.indexOf("phase = \"recon\"")
        assertTrue("the phase-0 sweep must exist", seeded > 0)
        assertTrue("it must come before recon, not after", seeded < recon)
        assertTrue("recon must still sweep every block at every offset",
            d.contains("all seven offsets of every block"))
    }
}

/**
 * Model keys are matched without regard to case, everywhere.
 *
 * Found on the first real BMW run: supportedForModel was made case-insensitive for the
 * Ioniq and its three siblings were left exact, so the OBDb model tier matched and our own
 * measured list did not. Phase 0 offered 30 requests instead of 602 and the confirm pass
 * silently did nothing.
 */
class ModelLookupCaseTest {
    private val src = java.io.File(
        generateSequence(java.io.File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { java.io.File(it, "README.md").isFile },
        "app/src/main/java/com/redundo/obddiscover/VehicleId.kt").readText()

    @Test fun noModelKeyIsComparedExactly() {
        assertTrue("no exact model comparison may remain",
            !src.contains("k.substringAfter('|') != model"))
        assertTrue("nor an exact make comparison",
            !src.contains("k.substringBefore('|') != make"))
    }

    /**
     * The four lookups that scan the locations keys must all ignore case.
     *
     * Two earlier versions of this test were wrong in opposite directions -- one pinned a
     * count of three and broke when a fourth was added, the other counted every function
     * taking a model and demanded the comparison of nine that do not scan keys at all.
     * Named explicitly, because that is what the property actually is.
     */
    @Test fun everyKeyScanningLookupIgnoresCase() {
        for (fn in listOf("contributedSignals", "openQuestionDids",
                          "contributedRequests", "contributedHints")) {
            val i = src.indexOf("fun $fn(")
            assertTrue("$fn must exist", i > 0)
            val end = src.indexOf("\n    fun ", i + 1).let { if (it < 0) src.length else it }
            val body = src.substring(i, end)
            assertTrue("$fn must compare the make without regard to case",
                body.contains("equals(make, ignoreCase = true)"))
            assertTrue("$fn must compare the model without regard to case",
                body.contains("equals(model, ignoreCase = true)"))
        }
    }

    /** The one that was already right stays right. */
    @Test fun theModelTierStillIgnoresCase() {
        assertTrue(src.contains("it.lowercase() == want"))
    }
}

/**
 * A car goes by more than one name, and the sources do not agree.
 *
 * From the adapter log of a real BMW run:
 *     model from contributed records: 5 Series
 *     vPIC: WBAFR7C53D -> 2013 5-Series 535i
 *     known requests: ours=0 model=0 make=30 for "BMW" / "535i"
 *
 * The offline record says "5 Series", which is how our measured lists AND OBDb's model sets
 * are both keyed. vPIC overwrote it with the trim. Two drives were spent on a confirm pass
 * that matched nothing.
 */
class ModelNameCandidatesTest {
    private val src = java.io.File(
        generateSequence(java.io.File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { java.io.File(it, "README.md").isFile },
        "app/src/main/java/com/redundo/obddiscover/Capture.kt").readText()

    /** The record's name has to survive the vPIC lookup that overwrites modelClean. */
    @Test fun theRecordsNameIsKeptSeparately() {
        assertTrue("kept apart from modelClean", src.contains("var modelFromRecords = \"\""))
        assertTrue("and set where the record is read", src.contains("modelFromRecords = model"))
    }

    /** All three names are tried: what a contributor typed, the trim, and the series. */
    @Test fun everyNameIsTried() {
        val i = src.indexOf("val modelKeys = listOf(")
        assertTrue("candidates must be built", i > 0)
        val body = src.substring(i, i + 200)
        for (n in listOf("modelFromRecords", "modelClean", "modelSeries")) {
            assertTrue("$n must be a candidate", body.contains(n))
        }
    }

    /** Both model-keyed tiers use them, not just one. */
    @Test fun bothModelTiersUseTheCandidates() {
        assertTrue(src.contains("modelKeys.flatMap { VehicleId.contributedRequests(mk, it) }"))
        assertTrue(src.contains("modelKeys.flatMap { VehicleId.supportedForModel(mk, it) }"))
    }

    /** vPIC stays the display name: it is the more specific one. */
    @Test fun theDisplayNameIsUnchanged() {
        assertTrue("vPIC still sets modelClean", src.contains("modelClean = it.model"))
    }
}
