package com.redundo.obddiscover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ISO 9141 reply shape, pinned.
 *
 * Every case here is a reply this app actually received from a 2006 Highlander, and every
 * one of them has already caused a bug: the phantom C0300 from concatenating two ECUs'
 * lines, the VIN corrupted into JITEEPI21A8I6014I2539, a Mode-09 support bitmap read one
 * byte off, and a calibration ID truncated to its first frame.
 *
 * They keep costing us because the shape is easy to half-remember: on K-line EVERY line
 * repeats the response prefix and its own sequence byte, where on CAN one prefix is
 * followed by indexed frames of pure data. Assertions are cheaper than another car.
 */
class ReplyShapeTest {

    // --- Discover.joinFrames -------------------------------------------------------

    /** ISO 9141: every line repeats 4902 plus a sequence byte. 0x49 is ASCII 'I'. */
    @Test fun vinIso9141() {
        val raw = "4902010000004A\r49020254453030\r49020330303030\r" +
            "49020430303030\r49020530303030\r>"
        assertEquals("JTE00000000000000", Discover.vinFrom(raw))
    }

    /** CAN: one prefix, then indexed continuation frames. */
    @Test fun vinCan() {
        val raw = "014\r0:490201574241\r1:46523743355842\r2:43313233343536\r>"
        assertEquals("WBA", Discover.vinFrom(raw).take(3))
    }

    /**
     * The calibration ID that came back as "3487".
     *
     * A K-line frame carries four data bytes, so an 8-character calibration ID needs two.
     * Obd.payloadOf returns the first matching line and stops, which is correct for a
     * single-frame read and silently lossy here.
     */
    @Test fun calibrationIdSpansFrames() {
        val raw = "49040133343837\r49040231303030\r>"
        assertEquals("34871000", Mode09.render(0x04, Discover.joinFrames("0904", raw)))
    }

    /** payloadOf is what truncated it. Kept as a test so the difference stays visible. */
    @Test fun payloadOfTakesOneFrameOnly() {
        val raw = "49040133343837\r49040231303030\r>"
        assertEquals("0133343837", Obd.hex(Obd.payloadOf("0904", raw)!!))
    }

    // --- Mode09 support bitmap -----------------------------------------------------

    /**
     * 0900 -> 01FC000000. Five bytes where a Mode-01 bitmap is four.
     *
     * Reading the first four gives 01FC0000 and claims PIDs 08-0F, none of which exist on
     * this ECU -- so every one returned nothing and Mode 09 looked absent. The VIN proves
     * the other reading: 0902 answers, so PID 02 is supported, and only FC000000 says so.
     */
    @Test fun bitmapSkipsTheCountByte() {
        val pids = Mode09.probe { if (it == "0900") listOf("01FC000000") else null }
        // 01/03/05 are message counts, 02 is the VIN and handled elsewhere.
        assertEquals(listOf(0x04, 0x06), pids.pids)
        assertEquals("01FC000000", pids.bitmap)
        assertEquals(false, pids.viaFallback)
    }

    /** A four-byte bitmap has no count byte to skip. */
    @Test fun bitmapWithoutCountByte() {
        assertEquals(listOf(0x04, 0x06), Mode09.probe { listOf("FC000000") }.pids)
    }

    /**
     * An ECU that ignores 0900 while answering 0902 is common before 2008, and trusting
     * the silence writes "no calibration ID" into a capture for a car that has one.
     */
    @Test fun noBitmapFallsBackToLegislatedPids() {
        val p = Mode09.probe { null }
        assertEquals(listOf(0x04, 0x06, 0x0A, 0x0B), p.pids)
        assertEquals(true, p.viaFallback)
        assertEquals(null, p.bitmap)
    }

    // --- Mode09.render -------------------------------------------------------------

    /** Leading pad bytes are not text and must not fail the printable-majority test. */
    @Test fun renderStripsLeadingPadding() {
        assertEquals("JTEE", Mode09.render(0x04, "000000" + "4A544545"))
    }

    /**
     * The reply this Highlander actually gave: TWO calibration IDs, 16 bytes each, NUL
     * padded. Filtering the padding without splitting first glues them into one 16-char
     * ID that does not exist -- the C0300 failure, in a new place.
     */
    @Test fun twoCalibrationIds() {
        val pad = "0000000000000000"
        val hex = "34876100".toHex() + pad + "54830100".toHex() + pad
        assertEquals("34876100, 54830100", Mode09.render(0x04, hex))
    }

    /** Two CVNs, four bytes each, in one reply. */
    @Test fun twoCvns() {
        assertEquals("0B103FEC, B5D28FF8", Mode09.render(0x06, "0B103FECB5D28FF8"))
    }

    /** One record still renders as one value, not a list. */
    @Test fun singleCalibrationId() {
        assertEquals("34871000", Mode09.render(0x04, "34871000".toHex() + "0000000000000000"))
    }

    /** Spaces inside an ECU name are text and must not be filtered out with the padding. */
    @Test fun renderKeepsInteriorSpaces() {
        assertEquals("ECM -EngineControl",
            Mode09.render(0x0A, "ECM -EngineControl".toHex() + "0000"))
    }

    /**
     * A reply that is not a whole number of records is left whole. Guessing a boundary in
     * a reply we do not understand writes a wrong value down as a right one.
     */
    @Test fun unevenReplyIsNotSplit() {
        assertEquals("ABC", Mode09.render(0x04, "ABC".toHex()))
    }

    /** A genuinely binary field stays hex rather than becoming mojibake. */
    @Test fun renderKeepsBinaryAsHex() {
        assertEquals("0B103FEC", Mode09.render(0x06, "0B103FEC"))
    }


    // --- Mode21 bitmap chain ---------------------------------------------------------

    /** Every bitmap this Highlander returned, as returned. */
    private val highlander = mapOf(
        "2100" to "BF9FA891", "2120" to "11000000", "21A0" to "E5F1",
        "21B0" to "7401", "21C0" to "EBF88F71", "21E0" to "D1F327A4",
    )

    /**
     * B0 is not 32-aligned, so it is reachable only by following A0's "next range" bit.
     * Seeding the aligned anchors alone would stop at A0 and miss B4/B6, which answered.
     */
    @Test fun bitmapChainReachesUnalignedAnchor() {
        val claims = Mode21.bitmapClaims(highlander)
        assertEquals(true, claims.contains("21B4"))
        assertEquals(true, claims.contains("21B6"))
    }

    /** The final bit means "next range follows"; it is not an identifier of its own. */
    @Test fun bitmapLastBitIsNotAnIdentifier() {
        val claims = Mode21.bitmapClaims(highlander)
        assertEquals(false, claims.contains("21A0"))   // reached as an anchor, not claimed
        assertEquals(false, claims.contains("21E0"))
    }

    /**
     * Recall on the real car was perfect: 63 identifiers answered and every one was
     * claimed. This pins the direction that matters -- a bitmap must never fail to claim
     * something that answers, or using claims to drive a retry would skip real data.
     */
    @Test fun everyAnsweringIdentifierIsClaimed() {
        val answered = listOf(
            "2101","2103","2104","2105","2106","2107","2108","2109","210C","210D","210E",
            "210F","2110","2111","2113","2115","2119","211C","2124","2128","21A3","21A6",
            "21A8","21A9","21AA","21AB","21AC","21B4","21B6","21C2","21C3","21CA","21CB",
            "21CC","21CD","21D1","21D5","21D8","21DC","21E1","21E2","21E4","21E8","21E9",
            "21EA","21EB","21EC","21EF","21F0","21F3","21F6","21F7","21F8","21F9","21FB","21FE",
        )
        val claims = Mode21.bitmapClaims(highlander)
        assertEquals(emptyList<String>(), answered.filter { it !in claims })
    }

    /** 2170 answered and no bitmap claims it: claims must not gate the sweep. */
    @Test fun claimsAreNotExhaustive() {
        assertEquals(false, Mode21.bitmapClaims(highlander).contains("2170"))
    }

    /** The 13 the ECU claimed and did not deliver. */
    @Test fun claimedButSilentIsThirteen() {
        val answered = highlander.keys + setOf(
            "2101","2103","2104","2105","2106","2107","2108","2109","210C","210D","210E",
            "210F","2110","2111","2113","2115","2119","211C","2124","2128","21A3","21A6",
            "21A8","21A9","21AA","21AB","21AC","21B4","21B6","21C2","21C3","21CA","21CB",
            "21CC","21CD","21D1","21D5","21D8","21DC","21E1","21E2","21E4","21E8","21E9",
            "21EA","21EB","21EC","21EF","21F0","21F3","21F6","21F7","21F8","21F9","21FB","21FE",
        )
        assertEquals(
            listOf("21A1","21A2","21B2","21B3","21C1","21C5","21C7","21C8","21C9",
                   "21D6","21D7","21DA","21DB"),
            Mode21.bitmapClaims(highlander).filter { it !in answered }.sorted(),
        )
    }

    // --- negative response codes -----------------------------------------------------

    @Test fun readsConditionsNotCorrect() {
        assertEquals(0x22, Mode21.negativeCode("7F 21 22\r>"))
        assertEquals("conditionsNotCorrect", Mode21.negativeName(0x22))
    }

    @Test fun readsRequestOutOfRange() {
        assertEquals(0x31, Mode21.negativeCode("7F2131\r>"))
    }

    /** A positive reply is not a refusal. */
    @Test fun positiveReplyHasNoNegativeCode() {
        assertEquals(null, Mode21.negativeCode("61A0E5F1\r>"))
    }


    // --- Mode 22 on a non-CAN bus ----------------------------------------------------

    /**
     * requestOutOfRange is the find, not the failure. It says the ECU parsed a Mode-22
     * request and answered it -- so a 65536-identifier space is reachable on a car we had
     * written off at Mode 21's 256.
     */
    @Test fun outOfRangeMeansSupported() {
        val r = Mode22.probe(listOf("22F40C")) { "7F 22 31\r>" }
        assertEquals(Mode22.Verdict.SUPPORTED_EMPTY, r.verdict)
    }

    /** serviceNotSupported settles it the other way, just as cheaply. */
    @Test fun serviceNotSupportedIsConclusive() {
        val r = Mode22.probe(listOf("22F40C", "22F405")) { "7F 22 11\r>" }
        assertEquals(Mode22.Verdict.UNSUPPORTED, r.verdict)
    }

    /** Silence on K-line is not evidence: an ECU may ignore a service it lacks. */
    @Test fun silenceIsNotAVerdict() {
        assertEquals(Mode22.Verdict.SILENT, Mode22.probe(listOf("22F40C")) { null }.verdict)
    }

    /** Data outranks a refusal seen earlier in the same run. */
    @Test fun dataWinsOverEarlierRefusal() {
        val r = Mode22.probe(listOf("22F190", "22F40C")) { req ->
            if (req == "22F190") "7F 22 31\r>" else "62F40C0B78\r>"
        }
        assertEquals(Mode22.Verdict.ANSWERED, r.verdict)
        assertEquals(listOf("22F40C" to "0B78"), r.hits)
    }

    /** Only bare three-byte reads. Nothing longer can carry a sub-function. */
    @Test fun rejectsMalformedMode22() {
        assertEquals(false, Mode22.isSafeRequest("22F4"))
        assertEquals(false, Mode22.isSafeRequest("22F40C01"))
        assertEquals(false, Mode22.isSafeRequest("2EF40C"))
        assertEquals(true, Mode22.isSafeRequest("22F40C"))
    }


    // --- phase 1: negative-response capture ------------------------------------------

    /**
     * The old test was `clean.contains("7F")`, which cannot tell a Mode-22 refusal from a
     * Mode-21 one and matches any reply text containing those two characters. It fed
     * speaks_mode22, the evidence separating "Mode 22 works, offsets empty" from "nothing
     * spoke Mode 22" -- two zero results needing opposite answers.
     */
    @Test fun aMode21RefusalIsNotAMode22One() {
        assertEquals(null, Mode22.negativeCode("7F 21 11\r>"))
        assertEquals(0x11, Mode22.negativeCode("7F 22 11\r>"))
        assertEquals(0x31, Mode22.negativeCode("7F2231\r>"))
    }

    /** Text containing 7F is not a refusal. */
    @Test fun strayTextIsNotARefusal() {
        assertEquals(null, Mode22.negativeCode("NO DATA\r>"))
        assertEquals(null, Mode22.negativeCode("SEARCHING...\r>"))
        assertEquals(null, Mode22.negativeCode("62F40C0B7F\r>"))   // 7F inside a payload
    }

    /**
     * The conditions block is why the histogram is worth recording: these name the state
     * the vehicle must be in, so a refusal becomes an instruction rather than a dead end.
     */
    @Test fun conditionsCodesAreNamed() {
        assertEquals("engineIsNotRunning", Mode21.negativeName(0x84))
        assertEquals("vehicleSpeedTooLow", Mode21.negativeName(0x89))
        assertEquals("brakeSwitchNotClosed", Mode21.negativeName(0x8F))
        assertEquals("shifterLeverNotInPark", Mode21.negativeName(0x90))
        assertEquals("requestOutOfRange", Mode21.negativeName(0x31))
    }

    /** An unknown code still renders, so an unnamed one is visible rather than lost. */
    @Test fun unknownCodeStillRenders() {
        assertEquals("NRC 0xA5", Mode21.negativeName(0xA5))
    }

    /** The histogram is hand-built JSON. Its shape has to parse. */
    @Test fun histogramFragmentIsValidJson() {
        val counts = linkedMapOf("7DF" to linkedMapOf(0x31 to 3580, 0x22 to 4),
                                 "7E1" to linkedMapOf<Int, Int>())
        val body = counts.entries.joinToString(", ") { (h, c) ->
            "\"$h\": {" + c.entries.sortedBy { it.key }.joinToString(", ") {
                "\"0x%02X %s\": %d".format(it.key, Mode21.negativeName(it.key), it.value)
            } + "}"
        }
        val o = org.json.JSONObject("{\"nrc_histogram\": {$body}}")
            .getJSONObject("nrc_histogram")
        assertEquals(3580, o.getJSONObject("7DF").getInt("0x31 requestOutOfRange"))
        assertEquals(4, o.getJSONObject("7DF").getInt("0x22 conditionsNotCorrect"))
        assertEquals(0, o.getJSONObject("7E1").length())
    }


    // --- retry scope -----------------------------------------------------------------

    private fun send(vararg replies: Pair<String, Boolean>): (Int) -> Pair<String, Boolean> =
        { n -> replies[minOf(n, replies.size - 1)] }

    /** The first attempt gets the full budget; retries get the short one. */
    @Test fun retriesUseTheShortWindow() {
        val windows = mutableListOf<Int>()
        Discover.sendWithRetry(0) { n -> windows.add(n); "" to false }
        assertEquals(listOf(0, 1, 2), windows)
        assertEquals(true, Discover.RETRY_TIMEOUT_MS < 2_000L)
    }

    /**
     * The cost of retrying rests entirely on this: anything that comes back WITH a prompt
     * ends the loop after one send. A Subaru returned 4,114 refusals in a partial run --
     * retrying those would have doubled it to learn nothing, since every one was 0x31.
     */
    @Test fun anAnsweredProbeIsSentOnce() {
        for (reply in listOf("7F2231\r>", "62F40C0B78\r>", "NO DATA\r>")) {
            assertEquals("'$reply' must cost one send",
                1, Discover.sendWithRetry(0, send = send(reply to true)).sent)
        }
    }

    /** NO DATA especially: on a car that stays silent it is most of a sweep. */
    @Test fun noDataIsNotRetried() {
        val a = Discover.sendWithRetry(0, send = send("NO DATA\r>" to true))
        assertEquals(1, a.sent)
        assertEquals(true, a.sawPrompt)
    }

    /** A stalled link is retried, up to the cap. */
    @Test fun anUnansweredProbeIsRetried() {
        val a = Discover.sendWithRetry(0, send = send("" to false))
        assertEquals(1 + Discover.MAX_RETRY, a.sent)
        assertEquals(false, a.sawPrompt)
    }

    /** And stops as soon as one attempt lands. */
    @Test fun retryStopsOnTheFirstAnswer() {
        val a = Discover.sendWithRetry(0, send = send("" to false, "7F2231\r>" to true))
        assertEquals(2, a.sent)
        assertEquals(true, a.sawPrompt)
    }

    /**
     * A dead link times out on everything. Retrying then triples a run that is already
     * lost, so past DEAD_LINK consecutive failures the retries stop.
     */
    @Test fun aDeadLinkStopsCostingRetries() {
        val a = Discover.sendWithRetry(Discover.DEAD_LINK, send = send("" to false))
        assertEquals("no retries once the link is gone", 1, a.sent)
    }

    /** The breaker is a threshold, not a switch: a flaky link still gets its retries. */
    @Test fun aFlakyLinkStillRetries() {
        val a = Discover.sendWithRetry(Discover.DEAD_LINK - 3, send = send("" to false))
        assertEquals(true, a.sent > 1)
    }


    /**
     * Stop must be honoured inside the retry loop, not merely between probes. A stalled
     * probe already costs 2 s; retrying it twice more made Stop take up to 3.2 s to be
     * noticed, on the one control that has to feel immediate.
     */
    @Test fun stopAbandonsTheRetryLoop() {
        val a = Discover.sendWithRetry(0, abandon = { true }, send = send("" to false))
        assertEquals(1, a.sent)
    }

    /** And does not cut short a probe that would have answered. */
    @Test fun abandonDoesNotDiscardAnAnswer() {
        val a = Discover.sendWithRetry(0, abandon = { true }, send = send("7F2231\r>" to true))
        assertEquals(true, a.sawPrompt)
        assertEquals(1, a.sent)
    }

    private fun String.toHex() = toByteArray().joinToString("") { "%02X".format(it) }
}

/**
 * The WMI table used to be built by substring-matching vPIC manufacturer names against
 * OBDb make names, which silently produced makes out of unrelated companies: PYRAMID
 * (a trailer builder) became "Ram", LANDMARK became "Land", HUDSON BROTHERS became "DS",
 * and HYUNDAI STEEL INDUSTRIES became "Hyundai". 31 of 492 entries were vehicles that
 * cannot be scanned at all. These pin the shape so a regenerated table cannot regress.
 */
class WmiTableTest {
    private fun table() = org.json.JSONObject(
        java.io.File("src/main/assets/wmi_to_make.json").readText())

    @Test fun substringArtifactsAreGone() {
        val t = table()
        for (w in listOf("16R", "15V", "10H", "145", "1KB", "1RL", "40B", "3T1", "421")) {
            assertFalse("$w is a trailer/motorcycle builder, not a car make", t.has(w))
        }
    }

    @Test fun brandsResolveToThemselvesNotTheParent() {
        val t = table()
        // vPIC names the brand; the parent stays as a fallback because hints only reorder.
        for ((w, brand) in listOf("19U" to "Acura", "1LN" to "Lincoln", "2T2" to "Lexus")) {
            val a = t.optJSONArray(w)
            assertNotNull("$w should carry a candidate list", a)
            assertEquals("$w must lead with $brand", brand, a!!.optString(0))
        }
    }

    @Test fun jointVenturePlantsListEveryMake() {
        val t = table()
        val n = t.optJSONArray("1N4")   // Nissan and Infiniti share the plant
        assertNotNull(n); assertEquals("Nissan", n!!.optString(0))
        assertTrue("1N4 must also offer INFINITI",
            (0 until n.length()).map { n.optString(it) }.contains("INFINITI"))
    }

    @Test fun ordinaryEntriesStayPlainStrings() {
        val t = table()
        // Only the 53 that genuinely need a list pay for one.
        val lists = t.keys().asSequence().count { t.opt(it) is org.json.JSONArray }
        assertEquals(53, lists)
        assertEquals("Ford", t.optString("1FT"))
    }
}

/**
 * The project's own vehicle database: vehicles/<Make>/<Model>.json, compiled by
 * tools/merge_vehicles.py into one asset at build time.
 *
 * It exists because the public sources cannot supply this. Brute-forcing VIN prefixes
 * against vPIC names a model for 31% of WMIs (measured, 14 of a random 45), and DecodeWMI
 * is silent for JTM, WBA, KM8 and JF2 -- four of the six cars this project has captures
 * for. A scan supplies the model AND the locations, from the car.
 */
class VehicleDbTest {
    private fun asset() = org.json.JSONObject(
        java.io.File("src/main/assets/vin_patterns.json").readText())

    @Test fun mergeProducedTheAsset() {
        val a = asset()
        assertTrue("merge must emit both sections", a.has("patterns") && a.has("locations"))
    }

    @Test fun bmwCarriesTheBlocksTheCommunityListLacks() {
        // 2258xx holds 227 identifiers on the F10 and 2244xx the oil row; neither is in
        // OBDb's BMW list. Losing them is the exact regression this database prevents.
        val blk = asset().getJSONObject("locations").getJSONObject("BMW|5 Series")
            .getJSONArray("blk").let { a -> (0 until a.length()).map { a.getString(it) } }
        for (b in listOf("2258", "2244", "2217", "224A")) {
            assertTrue("BMW 5 Series must carry $b", b in blk)
        }
    }

    @Test fun noRecordCarriesMoreThanEightVinCharacters() {
        // Positions 9-17 include the serial. The merge refuses to write when one does,
        // so reaching here with a longer key would mean the guard was removed.
        val p = asset().getJSONObject("patterns")
        for (k in p.keys()) assertTrue("$k is longer than VIN positions 1-8", k.length <= 8)
    }

    @Test fun everyRecordFileIsValidAndSafe() {
        val dir = java.io.File("../vehicles")
        val files = dir.walkTopDown().filter { it.extension == "json" && !it.name.endsWith(".map.json") }.toList()
        assertTrue("expected seeded records", files.isNotEmpty())
        for (f in files) {
            val r = org.json.JSONObject(f.readText())
            assertTrue("${f.name} needs a make", r.optString("make").isNotEmpty())
            assertTrue("${f.name} must not carry a VIN", !r.has("vin") && !r.has("vin_key"))
            assertTrue("${f.name} must not carry payloads",
                !r.has("mode09") && !r.has("mode21"))
            assertTrue("${f.name} pattern too long", r.optString("vin_pattern").length <= 8)
        }
    }
}

/**
 * The contribute export, pinned at the schema it writes.
 *
 * The first version of it required `blocks` and so could not contribute a K-line car at
 * all -- it would have silently excluded the Highlander, whose record is the richer one.
 */
class ContributeSchemaTest {
    private fun records() = java.io.File("../vehicles").walkTopDown()
        .filter { it.extension == "json" && !it.name.endsWith(".map.json") }.map { it to org.json.JSONObject(it.readText()) }

    @Test fun aNonCanCarIsRepresentable() {
        val (_, h) = records().first { it.second.optString("model") == "Highlander" }
        assertEquals("A3", h.optString("protocol"))
        assertEquals("SILENT", h.optString("mode22"))
        assertTrue("must carry Mode-01 PIDs", h.getJSONObject("pids").length() > 0)
        assertTrue("must carry Mode-21 ids", h.getJSONArray("mode21_ids").length() > 0)
        assertTrue("a K-line car has no CAN blocks", !h.has("blocks"))
    }

    @Test fun mode22SilenceIsEvidenceNotAnInstruction() {
        // If this ever becomes a skip, the BMW regression returns: OBDb's list would have
        // lost 358 of 462 identifiers on the F10.
        val src = java.io.File("src/main/java/com/redundo/obddiscover/Export.kt").readText()
        assertTrue("the intent must stay written down where the field is set",
            src.contains("NOT as permission to skip"))
    }

    /**
     * The README quotes the Highlander's record as the argument that K-line cars are not
     * second-class. Numbers in a README rot silently; this makes them fail loudly.
     */
    @Test fun theReadmeNumbersForTheHighlanderAreTrue() {
        // Collapsed, because the README hard-wraps and a number can land on the line
        // before the noun it counts.
        val readme = java.io.File("../README.md").readText().replace(Regex("\\s+"), " ")
        val h = org.json.JSONObject(java.io.File("src/main/assets/vin_patterns.json").readText())
            .getJSONObject("locations").getJSONObject("Toyota|Highlander")
        val m21 = h.getJSONArray("m21").length()
        val pid = h.getJSONArray("pid").length()
        assertTrue("README says $m21 Mode-21 identifiers?",
            readme.contains("$m21 Mode-21 identifiers"))
        assertTrue("README says $pid Mode-01 PIDs?", readme.contains("$pid Mode-01 PIDs"))
        assertEquals("and that Mode 22 answers nothing", "SILENT", h.optString("m22"))
    }

    @Test fun everyRecordIsWithinTheVinPatternLimit() {
        for ((f, r) in records()) {
            assertTrue("${f.name}: positions 9-17 include the serial",
                r.optString("vin_pattern").length <= 8)
        }
    }
}

/**
 * A record's `model` must be the BARE model name.
 *
 * The first on-car CONTRIBUTE runs wrote vPIC's display label into it — "2015 Forester"
 * and "2006 MCU23L/MCU28L/ACU20L/ACU25L Highlander". Both decoded the car correctly; the
 * damage was to the key. A year-prefixed model splits one vehicle into a silo per model
 * year, so no second Forester would ever match the first, and the shared-locations premise
 * the whole database rests on quietly stops holding.
 */
class ModelNameTest {
    @Test fun noRecordCarriesAYearOrSeriesInsideItsModel() {
        for (f in java.io.File("../vehicles").walkTopDown().filter { it.extension == "json" && !it.name.endsWith(".map.json") }) {
            val r = org.json.JSONObject(f.readText())
            val m = r.optString("model")
            if (m.isEmpty()) continue
            assertFalse("${f.name}: model $m starts with a year", m.matches(Regex("^(19|20)\\d\\d .*")))
            assertFalse("${f.name}: model $m carries a series list", m.contains("/"))
            r.optInt("year", 0).takeIf { it > 0 }?.let {
                assertFalse("${f.name}: model $m repeats the year field", m.contains(it.toString()))
            }
        }
    }

    /**
     * The README names a button the user has to find. If the label moves and the README
     * does not, the instructions send someone hunting for a button that is not there.
     */
    @Test fun theReadmeNamesTheButtonThatActuallyExists() {
        val ui = java.io.File("src/main/java/com/redundo/obddiscover/MainActivity.kt").readText()
        val label = Regex("""Text\("(ADD VEHICLE|CONTRIBUTE)"\)""").find(ui)?.groupValues?.get(1)
        assertNotNull("the add-a-vehicle button must exist", label)
        for (f in listOf("../README.md", "../vehicles/README.md")) {
            assertTrue("${f} must name the button \"$label\"",
                java.io.File(f).readText().contains(label!!))
        }
        // "CONTRIBUTE" on a phone screen reads as a request for money.
        assertEquals("ADD VEHICLE", label)
    }

    @Test fun theBareModelIsWhatReachesTheRecord() {
        // Capture keeps both; only the bare one may be handed to contribute().
        val ui = java.io.File("src/main/java/com/redundo/obddiscover/MainActivity.kt").readText()
        assertTrue("contribute() must be passed modelClean, never modelName",
            ui.contains("cap.modelClean, cap.vinKey"))
    }
}

/**
 * Facts with a shape belong in fields, not in `notes`.
 *
 * The odometer on a BMW F10 -- 221700, kilometres, checked against a dashboard -- is the
 * first identifier this project ever named against ground truth, and it spent its first
 * day as a clause in a prose string where nothing could read it.
 */
class RecordRichnessTest {
    private fun records() = java.io.File("../vehicles").walkTopDown()
        .filter { it.extension == "json" && !it.name.endsWith(".map.json") }.map { it to org.json.JSONObject(it.readText()) }

    @Test fun theVerifiedOdometerIsAField() {
        val (_, bmw) = records().first { it.second.optString("model") == "5 Series" }
        val sig = bmw.getJSONArray("signals").getJSONObject(0)
        assertEquals("221700", sig.getString("did"))
        assertEquals("odometer", sig.getString("name"))
        assertEquals("km", sig.getString("unit"))
        assertEquals("ground-truth", sig.getString("confidence"))
    }

    @Test fun namedSignalsReachTheShippedAsset() {
        val sig = org.json.JSONObject(
            java.io.File("src/main/assets/vin_patterns.json").readText())
            .getJSONObject("locations").getJSONObject("BMW|5 Series").getJSONObject("sig")
        assertEquals("odometer", sig.getJSONObject("221700").getString("n"))
    }

    /**
     * A record is read by a person on GitHub before anything else reads it, so the PIDs
     * carry their standard names. Committing a bare ["0105"] means the file says nothing
     * to a reader who does not already have the table open beside it.
     */
    @Test fun committedPidsCarryTheirNames() {
        var seen = 0
        for ((f, r) in records()) {
            val pids = r.optJSONObject("pids") ?: continue
            for (k in pids.keys()) {
                assertTrue("${f.name}: $k has no name", pids.getString(k).isNotEmpty())
                seen++
            }
        }
        assertTrue("expected named PIDs in the database", seen > 100)
    }

    @Test fun theShippedAssetDoesNotRepeatTheNames() {
        // The app carries pid_standard.json and can name them itself; shipping the strings
        // again would be the same text twice in one APK.
        val loc = org.json.JSONObject(
            java.io.File("src/main/assets/vin_patterns.json").readText())
            .getJSONObject("locations").getJSONObject("Subaru|Forester")
        val pid = loc.getJSONArray("pid")
        assertTrue("shipped pids stay bare identifiers", pid.getString(0).matches(Regex("[0-9A-F]{4}")))
    }

    /**
     * vPIC attributes come from decoding a VIN pattern, so a record without one cannot have
     * them. The Ioniq 5 is the case: no engine ECU, so its VIN was recovered too late to be
     * kept, and only its WMI survived. Requiring attributes everywhere would have forced a
     * guess into that record, which is the opposite of what this database is for.
     */
    @Test fun everyIdentifiedRecordCarriesItsVehicleAttributes() {
        for ((f, r) in records()) {
            if (r.optString("vin_pattern").isEmpty()) {
                assertTrue("${f.name} has no pattern, so it must say why",
                    r.optString("notes").isNotEmpty())
                continue
            }
            assertTrue("${f.name} has a pattern and should carry vPIC attributes",
                r.has("vehicle"))
        }
    }

    @Test fun confidenceIsAlwaysStated() {
        // An unlabelled name reads as fact. Every signal must say how sure it is.
        for ((f, r) in records()) {
            val sigs = r.optJSONArray("signals") ?: continue
            for (i in 0 until sigs.length()) {
                val c = sigs.getJSONObject(i).optString("confidence")
                assertTrue("${f.name}: signal $i has no confidence", c.isNotEmpty())
            }
        }
    }
}

/**
 * Standard PID names, from SAE J1979 / ISO 15031-5.
 *
 * The cross-check that matters is against ANCHORS: those nine were named by hand from the
 * same standard, so if the bundled table disagrees with them, one of the two is wrong.
 */
class StandardPidTest {
    private fun table() = org.json.JSONObject(
        java.io.File("src/main/assets/pid_standard.json").readText())

    @Test fun everyAnchorIsInTheStandardTable() {
        // The nine anchors were named by hand from the same standard, so spelling out what
        // each one IS turns two independent lists into a check on both.
        val expected = mapOf(
            "rpm" to "engine speed", "speed" to "vehicle speed",
            "load" to "calculated engine load", "coolant" to "engine coolant temperature",
            "maf" to "mass air flow rate", "baro" to "absolute barometric pressure",
            "ambient" to "ambient air temperature", "fuel" to "fuel tank level input",
            "distance" to "distance since codes cleared")
        val m01 = table().getJSONObject("mode01")
        for ((label, pid) in Obd.ANCHORS.entries.map { it.key to it.value }) {
            val e = m01.optJSONObject(pid.substring(2))
            assertNotNull("$label ($pid) is missing from the standard table", e)
            assertEquals("$label ($pid)", expected[label], e!!.getString("n"))
        }
        assertEquals("every anchor must be accounted for", expected.size, Obd.ANCHORS.size)
    }

    @Test fun keysAreCleanHex() {
        for (mode in listOf("mode01", "mode09")) {
            val t = table().getJSONObject(mode)
            for (k in t.keys()) {
                assertEquals("$mode key $k should be two hex digits", 2, k.length)
                assertTrue("$mode key $k is not hex",
                    k.all { it in "0123456789ABCDEF" })
            }
        }
    }

    @Test fun theWellKnownOnesAreRight() {
        val m01 = table().getJSONObject("mode01")
        assertEquals("engine speed", m01.getJSONObject("0C").getString("n"))
        assertEquals("rpm", m01.getJSONObject("0C").getString("u"))
        assertEquals("km/h", m01.getJSONObject("0D").getString("u"))
        assertEquals("degC", m01.getJSONObject("05").getString("u"))
        assertEquals("odometer", m01.getJSONObject("A6").getString("n"))
        assertEquals("ECU name", table().getJSONObject("mode09").getJSONObject("0A").getString("n"))
    }

    @Test fun mode22IsAbsentAndSaysWhy() {
        assertFalse("Mode 22 is manufacturer-specific and cannot be tabulated",
            table().has("mode22"))
        assertTrue("attribution must explain the Mode-22 omission",
            java.io.File("src/main/assets/ATTRIBUTION.txt").readText()
                .contains("manufacturer-specific"))
    }
}

/**
 * Mode-01 support is recorded on CAN, not only on K-line.
 *
 * Mode01.supportedPids was always protocol-agnostic -- it takes the ask and leaves headers
 * to the caller -- but only the non-CAN branch called it. The result was that four of the
 * five vehicles in vehicles/ carried no Mode-01 data, so a 116-name standard table could
 * name 20 identifiers on one car and nothing anywhere else, while the drive logger read
 * nine of those PIDs off every CAN vehicle on every run.
 */
class Mode01OnCanTest {
    private fun src(n: String) =
        java.io.File("src/main/java/com/redundo/obddiscover/$n").readText()

    @Test fun theCanBranchScansTheBitmaps() {
        val cap = src("Capture.kt")
        // Two call sites now: the non-CAN branch and the CAN one.
        assertEquals("Mode01.supportedPids must be called on both paths", 2,
            Regex("Mode01\\.supportedPids \\{").findAll(cap).count())
        assertTrue("the CAN scan must feed Discover", cap.contains("discover.stdPidsIn = stdPids"))
    }

    @Test fun theCanMapWritesIt() {
        assertTrue("the CAN map must carry mode01",
            src("Discover.kt").contains("\\\"mode01\\\": ["))
    }

    /**
     * Two name tables now describe the same PIDs: Mode01.NAMES for the screen, and
     * pid_standard.json from SAE J1979. They must not drift apart into two answers.
     */
    @Test fun theTwoNameTablesAgree() {
        val std = org.json.JSONObject(
            java.io.File("src/main/assets/pid_standard.json").readText())
            .getJSONObject("mode01")
        val missing = Mode01.NAMES.keys.filter { !std.has(it.substring(2)) }
        assertTrue("PIDs named on screen but absent from the standard table: $missing",
            missing.isEmpty())
    }
}


/**
 * Maps list which identifiers answered. They must never list what those identifiers said.
 *
 * This is not hypothetical. `detail` was on scrubbedJson's keep list because it records
 * WHICH identifiers answered -- and its full_hits are [identifier, payload] pairs, so the
 * export the README calls safe to attach to a public issue was carrying Mode-22 response
 * data. The keep list excludes mode09 and mode21 for exactly that reason, on exactly that
 * argument: an unidentified value can be a serial or an odometer.
 */
class NoPayloadsTest {
    @Test fun committedMapsCarryIdentifiersOnly() {
        val maps = java.io.File("../vehicles").walkTopDown()
            .filter { it.name.endsWith(".map.json") }.toList()
        assertTrue("expected committed maps", maps.isNotEmpty())
        for (f in maps) {
            val ids = org.json.JSONObject(f.readText()).getJSONObject("identifiers")
            for (h in ids.keys()) {
                val a = ids.getJSONArray(h)
                for (i in 0 until a.length()) {
                    // A bare identifier string. A [id, payload] pair would arrive as an
                    // array, which is precisely the shape that leaked.
                    assertTrue("${f.name}: $h[$i] is not a bare identifier",
                        a.opt(i) is String)
                }
            }
        }
    }

    @Test fun bothExportsStripPayloads() {
        val src = java.io.File("src/main/java/com/redundo/obddiscover/Export.kt").readText()
        assertTrue("scrubbedJson must reduce full_hits to identifiers",
            src.contains("e.put(\"full_hits\", ids)"))
        assertTrue("contribute must emit identifiers, not detail verbatim",
            src.contains("\"identifiers\", JSONArray(ids.toList())"))
        assertFalse("contribute must not copy detail wholesale",
            src.contains("out.put(\"detail\", it)"))
    }
}

/**
 * Two failures from one Ioniq 5 session, both pinned.
 *
 * The prefill URL: GitHub rejects an oversized one with "Whoops, something went wrong!",
 * which reads like an outage rather than a request that was too big. Measured on the
 * device -- 4,642 characters worked, 7,769 did not. `detail` is what pushes a record over,
 * and it was added the same morning the "records fit with room to spare" claim was made.
 *
 * The identity: a 2025 Ioniq 5 has no engine ECU, so every VIN attempt before discovery
 * went to an address that does not exist on that car. Discover recovers the VIN once it
 * knows the live headers, and used to keep only its first three characters -- so the
 * vehicle that most needs the fallback produced a record with no make, model or pattern.
 */
class LateIdentityAndUrlBudgetTest {
    private fun src(n: String) =
        java.io.File("src/main/java/com/redundo/obddiscover/$n").readText()

    @Test fun theRecoveredVinIsKeptWhole() {
        assertTrue("Discover must keep the recovered VIN",
            src("Discover.kt").contains("recoveredVin = Discover.vinFrom(raw)"))
        assertTrue("Capture must build identity from it",
            src("Capture.kt").contains("info = VehicleId.identify(vin)") &&
                src("Capture.kt").contains("discover.recoveredVin.isNotEmpty()"))
    }

    @Test fun theIssueUrlHasABudget() {
        val e = src("Export.kt")
        assertTrue("a budget must exist", e.contains("URL_BUDGET"))
        // Below what failed (7,769) and above what worked (4,642).
        val n = Regex("URL_BUDGET = (\\d+)").find(e)!!.groupValues[1].toInt()
        assertTrue("budget $n must sit between the measured pass and fail", n in 4643..7768)
        assertTrue("oversize must drop detail, not the record",
            e.contains("trimmed.remove(\"detail\")"))
        assertTrue("and must say where the full list is",
            e.contains("too long to prefill here"))
    }

    @Test fun theFileKeepsWhatTheIssueDrops() {
        // The trimming happens when BUILDING THE URL, never when writing the file.
        val e = src("Export.kt")
        val write = e.indexOf("f.writeText")
        val trim = e.indexOf("trimmed.remove")
        assertTrue("the record is written before any trimming", write in 1 until trim)
    }
}

/**
 * A functional broadcast is answered by every ECU, and they do not agree.
 *
 * Reading whichever reply arrived first made supported-PID discovery a race. The same
 * Silverado scored 2, 12, 6, 0 and 6 PIDs across five runs, because three of its modules
 * answer 0100 with 80000001 -- "PID 01 and nothing else" -- and only the engine ECU says
 * BFDFB993. A 2025 Ioniq 5 scored zero for the same reason. What a VEHICLE supports is
 * the union of what its modules support.
 */
class MultiEcuBitmapTest {
    private val silverado = listOf("80000001", "80000001", "80000001", "BFDFB993")

    @Test fun theUnionIsTakenNotTheFirstReply() {
        val union = Mode01.supportedPids { if (it == "0100") silverado else null }
        val first = Mode01.supportedPids { if (it == "0100") listOf(silverado[0]) else null }
        assertEquals("reading only the first module finds almost nothing", 1, first.size)
        assertTrue("the union must find far more, got ${union.size}", union.size > 15)
        assertTrue("and must include what the engine ECU reported", union.contains("010C"))
    }

    @Test fun orderDoesNotChangeTheAnswer() {
        val a = Mode01.supportedPids { if (it == "0100") silverado else null }
        val b = Mode01.supportedPids { if (it == "0100") silverado.reversed() else null }
        assertEquals("a race is a bug; order must not matter", a, b)
    }

    @Test fun allModulesSupportingNothingIsStillZero() {
        // The Ioniq's three first responders. Honest zero, not a crash.
        val none = Mode01.supportedPids {
            if (it == "0100") listOf("80000001", "00000000", "80000001") else null
        }
        assertEquals(listOf("0101"), none)
    }

    @Test fun payloadsOfReturnsEveryLine() {
        val raw = "4100BE3FA813\r410098188011\r"
        assertEquals(2, Obd.payloadsOf("0100", raw).size)
    }
}

/**
 * The Mode-01 scan must run on a cached capture, not only on a re-map.
 *
 * Putting it in the discovery branch looked right and was backwards: a vehicle that is
 * already mapped skips discovery, so the cars most likely to be plugged in again were
 * exactly the ones that would never scan. Getting the data would have meant forcing a
 * full re-map -- fifteen minutes to an hour -- for seven requests worth of answers.
 */
class ScanRunsOnCachedCaptureTest {
    private val cap =
        java.io.File("src/main/java/com/redundo/obddiscover/Capture.kt").readText()

    @Test fun theScanPrecedesTheCacheDecision() {
        val scan = cap.indexOf("Mode-01 bitmap scan")
        val cached = cap.indexOf("val cached = if (forceDiscover)")
        assertTrue("both must exist", scan > 0 && cached > 0)
        assertTrue("the scan must run before the cache branch is taken", scan < cached)
    }

    @Test fun aCacheHitFoldsTheScanIntoTheStoredMap() {
        assertTrue("a cache hit must update the stored map",
            cap.contains("cached map updated"))
        // Only ever upward: a short scan must not overwrite a fuller stored list.
        assertTrue("and must not replace a fuller list with a shorter one",
            cap.contains("if (stdPids.size > had)"))
    }
}

/**
 * LICENSE is the MIT text and nothing else, and every shipped asset has a notice.
 *
 * GitHub's licence detector matches the WHOLE file against known texts. Sixty-five lines of
 * appended notices put it under the threshold, so the API reported spdx_id NOASSERTION --
 * which to a dependency scanner or a policy gate reads as "no licence", not "MIT plus
 * notices". The notices moved to THIRD-PARTY-NOTICES.md rather than being deleted: they
 * carry a CC BY-SA obligation that travels with the APK.
 */
class LicenceShapeTest {
    private fun root(n: String) = java.io.File("../$n").readText()

    @Test fun licenseIsOnlyTheMitText() {
        val lines = root("LICENSE").trim().lines()
        assertEquals("MIT is 21 lines; anything more is what tripped the detector", 21, lines.size)
        assertTrue("must start with the MIT title", lines[0].contains("MIT License"))
        for (word in listOf("CC BY-SA", "OBDb", "DERIVED SOURCE", "THIRD-PARTY")) {
            assertFalse("$word belongs in THIRD-PARTY-NOTICES.md, not LICENSE",
                root("LICENSE").contains(word))
        }
    }

    @Test fun everyShippedDataFileHasANotice() {
        val notices = root("THIRD-PARTY-NOTICES.md")
        val assets = java.io.File("src/main/assets").listFiles()
            ?.filter { it.extension == "json" } ?: emptyList()
        assertTrue("expected bundled assets", assets.isNotEmpty())
        for (a in assets) {
            // obdb_models.json shipped for weeks with no notice at all, while carrying a
            // share-alike obligation. A missing name is the failure mode, so name them all.
            assertTrue("${a.name} has no entry in THIRD-PARTY-NOTICES.md",
                notices.contains(a.name))
        }
    }

    @Test fun theShareAlikeObligationIsStated() {
        val n = root("THIRD-PARTY-NOTICES.md")
        assertTrue("CC BY-SA must be named", n.contains("CC BY-SA 4.0"))
        assertTrue("and must say it travels with the APK",
            n.contains("travels with the APK"))
    }

    @Test fun theInAppCopyIsGeneratedNotMaintained() {
        val g = java.io.File("build.gradle.kts").readText()
        assertTrue("the shipped notices must be copied from the root file",
            g.contains("THIRD-PARTY-NOTICES.md") && g.contains("ATTRIBUTION.txt"))
    }
}
