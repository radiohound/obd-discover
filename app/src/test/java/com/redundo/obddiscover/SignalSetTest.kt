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
