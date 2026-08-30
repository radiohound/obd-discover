package com.redundo.obddiscover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shapes here are the real ones from the BMW F10 sweep that obd-gauge-cluster mapped,
 * not invented values -- the duplicate pair especially, which cost a drive to find.
 */
class PreDriveTriageTest {

    private fun q(req: String, a: String, b: String?) = PreDriveTriage.Quad("7DF", req, a, b)

    @Test
    fun `a changed payload is a live signal`() {
        assertEquals(PreDriveTriage.Kind.MOVED, PreDriveTriage.classify("05CD", "0A11"))
    }

    @Test
    fun `an unchanged payload is static, NOT dead`() {
        // Coolant at equilibrium and a stopped car hold still too, which is why this is
        // ranked below MOVED rather than discarded.
        assertEquals(PreDriveTriage.Kind.STATIC, PreDriveTriage.classify("0F95", "0F95"))
    }

    @Test
    fun `all-zero and all-F payloads are unpopulated`() {
        // 224404-224407 on the F10 read 0000 on every probe.
        assertEquals(PreDriveTriage.Kind.UNPOPULATED, PreDriveTriage.classify("0000", "0000"))
        assertEquals(PreDriveTriage.Kind.UNPOPULATED, PreDriveTriage.classify("FFFF", "FFFF"))
        assertTrue(PreDriveTriage.isBlank("00"))
        assertTrue(PreDriveTriage.isBlank(null))
        assertTrue(!PreDriveTriage.isBlank("00BA"))
    }

    @Test
    fun `a re-probe that did not answer is static, not moved`() {
        // Absence of a reply is not evidence the value changed.
        assertEquals(PreDriveTriage.Kind.STATIC, PreDriveTriage.classify("05CD", null))
    }

    @Test
    fun `movers come first and only movers are recommended`() {
        val r = PreDriveTriage.rank(listOf(
            q("224404", "0000", "0000"),     // unpopulated
            q("224517", "0F95", "0F95"),     // static
            q("2258BA", "05CD", "0A11"),     // moved -- crank torque
        ))
        assertEquals("2258BA", r.rows.first().request)
        assertEquals(listOf("2258BA"), r.recommended.map { it.request })
        assertEquals(1, r.moved)
        assertEquals(1, r.static)
        assertEquals(1, r.unpopulated)
    }

    @Test
    fun `two DIDs carrying the same signal collapse to one`() {
        // 225817 and 2258EB were byte-identical on 99.51% of 1427 logged F10 rows: the same
        // signal under two DIDs. Two probes standing still find that for free.
        val r = PreDriveTriage.rank(listOf(
            q("225817", "6E", "70"),
            q("2258EB", "6E", "70"),
            q("22587E", "68", "9D"),
        ))
        assertEquals(1, r.duplicates)
        val dup = r.rows.first { it.duplicateOf != null }
        assertEquals("2258EB", dup.request)
        assertEquals("225817", dup.duplicateOf)
        // The drive spends its budget on distinct signals.
        assertEquals(listOf("225817", "22587E"), r.recommended.map { it.request })
    }

    @Test
    fun `the same payload on a different header is not a duplicate`() {
        // Two modules can legitimately report the same value; collapsing across headers
        // would hide a second ECU rather than a redundant DID.
        val r = PreDriveTriage.rank(listOf(
            PreDriveTriage.Quad("7DF", "2258BA", "05CD", "0A11"),
            PreDriveTriage.Quad("7E1", "2258BA", "05CD", "0A11"),
        ))
        assertEquals(0, r.duplicates)
        assertEquals(2, r.recommended.size)
    }

    @Test
    fun `an unpopulated DID is never marked a duplicate of another`() {
        // Every blank DID reads alike; calling them duplicates of each other would be noise.
        val r = PreDriveTriage.rank(listOf(
            q("224404", "0000", "0000"),
            q("224405", "0000", "0000"),
        ))
        assertEquals(0, r.duplicates)
        r.rows.forEach { assertNull(it.duplicateOf) }
    }
}

/**
 * Triage wired into the capture flow (#8).
 *
 * The ranking was merged in #4 and never called, because where it belonged was a question
 * the app could not answer: there was no moment between "mapping" and "the drive". The
 * session budget created one.
 */
class TriageWiringTest {
    private fun src(n: String) = java.io.File(
        generateSequence(java.io.File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { java.io.File(it, "README.md").isFile },
        "app/src/main/java/com/redundo/obddiscover/$n").readText()

    /** It has to actually be called, which for four months it was not. */
    @Test fun theRankingIsCalled() {
        val d = src("Discover.kt")
        assertTrue("classify must be used", d.contains("PreDriveTriage.classify(first, second)"))
        assertTrue("and rank", d.contains("PreDriveTriage.rank(quads)"))
    }

    /**
     * Re-probing 703 identifiers every session would spend a quarter of the budget
     * re-deciding what was already decided. Classifications persist, like swept blocks.
     */
    @Test fun classificationsPersistAndAreReused() {
        val d = src("Discover.kt")
        assertTrue("prior decisions come in", d.contains("triage.putAll(resumeTriage)"))
        assertTrue("and filter what gets probed",
            d.contains("""triage["${'$'}h|${'$'}req"]?.endsWith("@${'$'}stateNow") != true"""))
        val c = src("Capture.kt")
        assertTrue("and are read back",
            c.contains("val priorTriage = findTriage(vinKey)") &&
                c.contains("discover.resumeTriage = priorTriage"))
    }

    /** STATIC at warm idle and MOVED while driving is the distinction being drawn. */
    @Test fun aClassificationIsBoundToTheStateItWasMadeIn() {
        assertTrue(src("Discover.kt").contains("""}@${'$'}stateNow""""))
    }

    /**
     * The drive drops only what two standing probes prove is worthless: all-zeros or
     * all-Fs twice, and a second name for a signal already carried. STATIC stays --
     * coolant at equilibrium and a stopped car hold still too.
     */
    @Test fun onlyTheProvablyWorthlessIsDroppedFromTheDrive() {
        val d = src("Discover.kt")
        val i = d.indexOf("val dropped = ranked?.rows")
        assertTrue("the drop set must exist", i > 0)
        val body = d.substring(i, i + 300)
        assertTrue("unpopulated goes", body.contains("Kind.UNPOPULATED"))
        assertTrue("duplicates go", body.contains("duplicateOf != null"))
        assertTrue("static does not", !body.contains("Kind.STATIC"))
    }

    /** Nothing is removed from the capture -- only the drive plan is narrowed. */
    @Test fun theCaptureKeepsEverything() {
        val d = src("Discover.kt")
        assertTrue("allHits is unfiltered",
            d.contains("allHits = found.values.flatMap { b ->"))
        assertTrue("the drop applies to the log plan", d.contains("in dropped }"))
    }

    /** It respects the session clock like every other pass. */
    @Test fun triageStopsWhenTheSessionDoes() {
        assertTrue(src("Discover.kt").contains("if (stopFlag || outOfTime()) { paused = true; break }"))
    }
}

/**
 * The summary is worded for a reader, not for the enum.
 *
 * MOVED stays as the constant because it is obd_scan's and this project mirrors his names.
 * On a screen it reads as "found while driving" in an app whose other open question is
 * whether to map while the car is moving — so the word people see is "dynamic".
 */
class TriageWordingTest {
    @Test fun theSummarySaysDynamicNotMoved() {
        val r = PreDriveTriage.rank(listOf(
            PreDriveTriage.Quad("7DF", "220001", "AA", "BB"),   // dynamic
            PreDriveTriage.Quad("7DF", "220002", "CC", "CC"),   // static
            PreDriveTriage.Quad("7DF", "220003", "0000", "0000"), // unpopulated
        ))
        assertTrue("must read dynamic", r.summary().startsWith("dynamic 1"))
        assertTrue("must not read moved", !r.summary().contains("moved"))
        assertTrue("static is unchanged", r.summary().contains("static 1"))
    }

    /** The constant is untouched, so a comparison with obd_scan still lines up. */
    @Test fun theEnumStillMatchesUpstream() {
        assertEquals("MOVED", PreDriveTriage.Kind.MOVED.name)
        assertEquals("STATIC", PreDriveTriage.Kind.STATIC.name)
        assertEquals("UNPOPULATED", PreDriveTriage.Kind.UNPOPULATED.name)
    }
}
