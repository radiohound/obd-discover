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
