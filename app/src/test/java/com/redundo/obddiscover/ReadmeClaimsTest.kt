package com.redundo.obddiscover

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The README, checked against the code and the shipped assets.
 *
 * WHY THIS EXISTS. The README was swept for accuracy on 2026-08-27 and had drifted again
 * within a day, because behaviour kept changing underneath it. That sweep found six claims
 * that were false or contradicted another claim in the same file -- including "Mode 21 ...
 * is not a service this tool is permitted to send", sitting eight lines under a paragraph
 * explaining how the app sends Mode 21, and a read-only mode list that omitted it.
 *
 * A stale README costs more than a stale comment: it is what a stranger reads INSTEAD of the
 * source, and the claims most worth checking -- which services reach the vehicle, what the
 * bundled tables cover -- are exactly the ones nobody can verify by looking at the app.
 *
 * So every number in it is asserted here. A change that makes the README wrong fails the
 * build rather than a reader's trust. When one of these fails, fix whichever of the two is
 * lying -- sometimes it is the README, sometimes the code regressed.
 */
class ReadmeClaimsTest {

    private val root: File = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .first { File(it, "README.md").isFile }
    private val readme = File(root, "README.md").readText()
    private fun asset(n: String) = File(root, "app/src/main/assets/$n").readText()
    private fun source(n: String) = File(root, "app/src/main/java/com/redundo/obddiscover/$n").readText()

    private fun claims(text: String) = assertTrue("README no longer contains: $text",
        readme.contains(text))

    // --- what actually reaches the vehicle ------------------------------------------

    /**
     * The read-only list. The single most important sentence in the file, and the one that
     * went stale silently when the Mode-21 sweep landed.
     */
    @Test fun readOnlyListNamesEveryServiceSent() {
        for (m in listOf("Mode 01", "Mode 03", "Mode 09", "Mode 22", "Mode 21")) claims(m)
        claims("Mode 04 is")          // still absent, still explained
        assertTrue("Mode 04 must never become sendable",
            !source("Mode21.kt").contains("\"04\"") && !source("Mode22.kt").contains("\"04\""))
    }

    /** Two bytes is what makes Mode 21 safe; the README explains it, so it must stay true. */
    @Test fun mode21RequestsAreTwoBytes() {
        assertTrue(Mode21.isSafeRequest("2100"))
        assertTrue(!Mode21.isSafeRequest("210000"))     // a third byte is transmissionMode
        claims("exactly two bytes")
    }

    @Test fun identificationDidsMatchTheReadme() {
        for ((req, _) in Discover.IDENT_DIDS) claims(req.removePrefix("22"))
        assertEquals(5, Discover.IDENT_DIDS.size)
    }

    // --- numbers quoted about the scan ----------------------------------------------

    @Test fun nineProtocolsAreTried() {
        val n = Regex("\"\\d\" to \"").findAll(source("ElmBle.kt")).count()
        assertEquals("README says the nine are tried explicitly", 9, n)
        claims("each of the nine")
    }

    @Test fun fourGattProfiles() {
        // Minus one for the `data class Profile(` declaration. One of the four is written
        // across several lines, so matching `Profile("` alone undercounts.
        val n = Regex("Profile\\(").findAll(source("ElmBle.kt")).count() - 1
        assertEquals(4, n)
        claims("Four GATT profiles")
    }

    /**
     * The README must say a BLE adapter is needed, before the reader has to go looking.
     * The opening used to read "from an Android phone, with no laptop", which is true and
     * reads as phone-only -- the hardware requirement was 200 lines down.
     */
    @Test fun theAdapterRequirementIsInTheOpening() {
        val opening = readme.take(900)
        assertTrue("the opening must say an adapter is needed",
            opening.contains("adapter"))
        assertTrue("the opening must say it has to be BLE",
            opening.contains("Bluetooth Low Energy") || opening.contains("BLE"))
        assertTrue("the opening must name the verified adapter",
            opening.contains("Vgate iCar Pro BLE 4.0"))
        // An adapter may only be marked verified because someone RAN it. The count is
        // pinned so that adding a row is a deliberate act with evidence attached, not a
        // hopeful edit -- which is exactly what this guard caught when the second one
        // was added.
        //
        // 2 since 2026-08-30: the Vgate vLinker MS, in BLE mode, on a 2025 GMC Sierra
        // 3.0L Duramax (GM Global B). 18,768 probes, 38 blocks, 5 headers speaking
        // Mode-22, 0 timeouts and 0 retries. That it connects would not be enough; that
        // it sustained 18,768 round trips without a retry is the claim being made.
        assertEquals("an adapter may only be marked verified if someone ran it", 2,
            Regex("Verified on this app").findAll(readme).count())
    }

    @Test fun nineGenericAnchors() {
        assertEquals(9, Obd.ANCHORS.size)
        claims("nine generic anchors")
        // The two beyond catalog.ANCHORS. correlate treats an unrecognised column as a
        // candidate rather than failing, so the file still parses upstream.
        assertEquals("012F", Obd.ANCHORS["fuel"])
        assertEquals("0131", Obd.ANCHORS["distance"])
    }

    @Test fun twoHundredFiftySixCandidateBlocks() = claims("256 candidate")

    /** The drive floor in the README is correlate's, not a number someone liked. */
    @Test fun driveFloorIsCorrelatesMinSamples() {
        assertEquals(30, Triage.MIN_SAMPLES)
        claims("30 samples")
    }

    // --- the bundled tables ---------------------------------------------------------

    @Test fun hintTableCounts() {
        val h = JSONObject(asset("obdb_hints.json"))
        val makes = h.keys().asSequence().toList()
        val rows = makes.sumOf { h.getJSONArray(it).length() }
        assertEquals(58, makes.size); claims("58 makes")
        assertEquals(2218, rows);     claims("2,218")
    }

    @Test fun supportedTableCounts() {
        val s = JSONObject(asset("obdb_supported.json"))
        val makes = s.keys().asSequence().toList()
        assertEquals(44, makes.size); claims("44 makes")
        assertEquals(13723, makes.sumOf { s.getJSONArray(it).length() }); claims("13,723")
        assertEquals(2039, s.getJSONArray("BMW").length()); claims("2,039")
    }

    /** The model table's size is quoted in the README. */
    @Test fun modelTableCount() {
        val m = JSONObject(asset("obdb_models.json"))
        assertEquals(147, m.length()); claims("147 models")
        assertEquals(33, m.keys().asSequence().map { it.substringBefore('|') }.toSet().size)
        claims("33 makes")
    }

    /**
     * OBDb writes 29-bit targets as four characters ("DA11"); headers() keeps only three,
     * so every one was dropped. HEADERS_29BIT does not list DA11 either, which is where 45
     * of the Silverado 1500's 52 commands live.
     */
    @Test fun twentyNineBitHintsAreReachable() {
        val src = source("VehicleId.kt")
        assertTrue("headers29 must exist", src.contains("fun headers29("))
        assertTrue("the 29-bit fallback must use it",
            source("Discover.kt").contains("HEADERS_29BIT + hinted29"))
    }

    @Test fun wmiTableCount() {
        assertEquals(461, JSONObject(asset("wmi_to_make.json")).length()); claims("461")
    }

    @Test fun troubleCodeCounts() {
        val d = JSONObject(asset("dtc_generic.json"))
        assertEquals(9415, d.length()); claims("9,415")
        val byLetter = d.keys().asSequence().groupingBy { it.first() }.eachCount()
        assertEquals(7387, byLetter['P']); claims("7,387 P")
        assertEquals(1230, byLetter['U']); claims("1,230 U")
        assertEquals(498, byLetter['C']);  claims("498 C")
        assertEquals(300, byLetter['B']);  claims("300 B")
    }

    // --- promises about behaviour ---------------------------------------------------

    /**
     * "Hints reorder a sweep; they never restrict one." The F10 rule. If a change ever lets
     * the tables decide WHICH blocks get probed, this is the sentence that becomes a lie.
     */
    @Test fun reconStillProbesEveryBlock() {
        val src = source("Discover.kt")
        assertTrue("recon must still walk all 256 high bytes",
            src.contains("(0..255).filter { it !in hintedHi }"))
        assertEquals(7, Discover.OFFSETS.size)
        claims("never restrict")
    }

    /** The VIN never reaches a capture file; only the raw export writes it, on request. */
    @Test fun onlyTheRawExportWritesTheVin() {
        val e = source("Export.kt")
        assertTrue(e.contains("if (!scrub && !info?.vin.isNullOrEmpty())"))
        assertTrue("scrub whitelist must not carry a payload field",
            !Regex("keep = listOf\\([^)]*\"mode09\"").containsMatchIn(e) &&
                !Regex("keep = listOf\\([^)]*\"mode21\"[^_]").containsMatchIn(e))
        claims("EXPORT RAW writes the VIN")
    }

    @Test fun minSdkMatchesTheReadme() {
        val g = File(root, "app/build.gradle.kts").readText()
        assertTrue(Regex("minSdk\\s*=\\s*26").containsMatchIn(g))
        claims("API 26")
    }


    /**
     * The model tables are ADDITIVE. The make tables cannot be reproduced from today's OBDb
     * signalsets -- the shipped BMW entry holds 2,009 requests on 6F1 across 79 blocks where
     * OBDb/BMW carries 46 commands -- so overwriting them would lose whatever built that.
     */
    @Test fun modelTableIsAdditiveNotAReplacement() {
        val models = JSONObject(asset("obdb_models.json"))
        val makes = JSONObject(asset("obdb_hints.json"))
        assertTrue("model file should be present", models.length() > 100)
        assertEquals("the make table must not have shrunk", 2218,
            makes.keys().asSequence().sumOf { makes.getJSONArray(it).length() })
        assertEquals(2039, JSONObject(asset("obdb_supported.json")).getJSONArray("BMW").length())
    }

    /** Every model key is "Make|Model" and names a make the hint table knows. */
    @Test fun modelKeysAreWellFormed() {
        val models = JSONObject(asset("obdb_models.json"))
        val makes = JSONObject(asset("obdb_hints.json"))
        for (k in models.keys()) {
            assertTrue("key '$k' must be Make|Model", k.contains('|') && !k.endsWith("|"))
            assertTrue("'$k' names an unknown make", makes.has(k.substringBefore('|')))
        }
    }

    /** The README must not claim the signature match works -- it never has on a car. */
    @Test fun readmeDoesNotOverclaimSignatureMatching() {
        assertTrue("the discredited uniqueness figure must be gone", !readme.contains("122 of the 147"))
        claims("has never produced a match on a real vehicle")
    }

    /** vPIC's model, not the signature match, is what selects an OBDb repo. */
    @Test fun namingGoesThroughTheLookup() {
        assertTrue(source("VehicleId.kt").contains("fun repoForName("))
        assertTrue("Capture must prefer the vPIC repo",
            source("Capture.kt").contains("vpicRepo.ifEmpty"))
    }

    /** The Silverado is the case this was built for: fewer headers than the merged make. */
    @Test fun silveradoIsNarrowerThanTheMergedMake() {
        val models = JSONObject(asset("obdb_models.json"))
        val sig = models.getJSONObject("Chevrolet|Silverado 1500").getJSONArray("h")
        val modelHdrs = (0 until sig.length()).map { sig.getJSONArray(it).getString(0) }.toSet()
        val merged = JSONObject(asset("obdb_hints.json")).getJSONArray("Chevrolet")
        val makeHdrs = (0 until merged.length()).map { merged.getJSONArray(it).getString(0) }.toSet()
        assertTrue("model headers must be a subset of the make's", makeHdrs.containsAll(modelHdrs))
        assertTrue("model must be narrower: $modelHdrs vs $makeHdrs", modelHdrs.size < makeHdrs.size)
    }


    /**
     * The privacy claim about the online lookup. This is the sentence someone trusts instead
     * of reading VinLookup, so the number in it has to be the number in the code.
     */
    @Test fun onlineLookupSendsTenCharacters() {
        assertEquals("WBA0000000", VinLookup.abbreviate("WBA00000000000001"))
        assertEquals(10, VinLookup.abbreviate("WBA00000000000001").length)
        assertEquals("", VinLookup.abbreviate("SHORT"))
        claims("first 10 characters")
        claims("serial number")
    }

    /** It must stay off unless the operator turns it on. */
    @Test fun onlineLookupIsOffByDefault() {
        assertTrue("Session.onlineVinLookup must default to false",
            source("Session.kt").contains("var onlineVinLookup by mutableStateOf(false)"))
        assertTrue("the lookup must be gated on it",
            source("Capture.kt").contains("if (Session.onlineVinLookup"))
    }

    /** INTERNET is held for exactly one purpose, and the manifest says so. */
    @Test fun internetPermissionIsExplained() {
        val m = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android.permission.INTERNET"))
        assertTrue("the manifest must say why", m.contains("vPIC"))
    }


    /** The install instructions must match the manifest and the build config. */
    @Test fun installSectionIsAccurate() {
        claims("## Installing")
        claims("adb install -r app/build/outputs/apk/debug/app-debug.apk")
        claims("Android 8.0 or later (API 26)")
        val m = File(root, "app/src/main/AndroidManifest.xml").readText()
        // Every permission the README explains must actually be requested, and the
        // location claim must stay true: nothing in the app may read a location.
        for (p in listOf("BLUETOOTH_SCAN", "ACCESS_FINE_LOCATION", "POST_NOTIFICATIONS")) {
            assertTrue("$p must be in the manifest", m.contains(p))
        }
        val src = File(root, "app/src/main/java/com/redundo/obddiscover").walkTopDown()
            .filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertTrue("the README says the app never reads your location",
            !src.contains("LocationManager") && !src.contains("getLastKnownLocation") &&
                !src.contains("FusedLocation"))
    }


    /** The reporting section points at things that exist. */
    @Test fun reportingSectionPointsAtRealThings() {
        claims("## Reporting something")
        claims("adapter-log.txt")
        claims("REPORT")
        // The README promises the report carries no VIN and no MAC. Both redactions
        // must therefore exist and be applied on that path.
        val e = source("Export.kt")
        assertTrue("report() must redact VINs", e.contains("redactVins(adapterLog"))
        assertTrue("report() must redact addresses", e.contains("redactAddresses(redactVins"))
        // the build tag really is on the main screen and in the export
        assertTrue(source("MainActivity.kt").contains("BuildTag.ID"))
        assertTrue(source("Export.kt").contains("BuildTag.ID"))
    }

    /** The documented build command has to exist. It did not, for the repo's whole life. */
    @Test fun theDocumentedBuildCommandExists() {
        claims("./gradlew assembleDebug")
        assertTrue("gradlew is missing", File(root, "gradlew").isFile)
        assertTrue("gradle-wrapper.jar is missing",
            File(root, "gradle/wrapper/gradle-wrapper.jar").isFile)
    }
}
