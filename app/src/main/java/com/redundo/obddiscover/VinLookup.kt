package com.redundo.obddiscover

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Optional model lookup against NHTSA vPIC, from an ABBREVIATED VIN.
 *
 * OFF BY DEFAULT, and the only thing in this app that opens a socket.
 *
 * WHY IT IS NOT A BUNDLED TABLE. Size was never the problem -- patterns for the 147 models
 * OBDb covers would be 30-60 KB, smaller than any other asset here. The problem is getting
 * them: valid VIN patterns occupy a tiny, irregular fraction of the space and cannot be
 * found by enumerating it. Forty-two constructed BMW prefixes were tried and none decoded;
 * only one taken from a real vehicle did. NHTSA does not expose the pattern table through
 * the API -- it lives in a 1-2 GB database dump -- and a table extracted from it goes stale
 * as model years add patterns. That decay is exactly what made a previous attempt at naming
 * models wrong often enough to abandon.
 *
 * WHAT LEAVES THE PHONE: ten characters.
 *
 *   positions 1-3    WMI, the manufacturer
 *   positions 4-9    VDS, the model, body and engine
 *   position  10     model year
 *   positions 11-17  NEVER SENT -- 11 is the plant, 12-17 the serial
 *
 * The serial is what identifies one vehicle. Thousands of cars share any given ten-character
 * prefix, so what is sent describes a model and year, not a car. Ten was chosen by measuring:
 * it is the shortest prefix that still returns both model and year.
 *
 * Answers are cached BY PREFIX rather than by vehicle, so a second car of the same model and
 * year costs no request at all.
 */
object VinLookup {

    data class Result(val model: String, val series: String, val year: String, val engine: String) {
        val label: String get() = listOf(year, model, series)
            .filter { it.isNotEmpty() && it != model }.plus(model).distinct()
            .joinToString(" ").trim()
    }

    private const val ENDPOINT = "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/"

    /** The ten characters that are allowed to leave the device, or "" if the VIN is short. */
    fun abbreviate(vin: String): String =
        if (vin.length < 10) "" else vin.take(10).uppercase()

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "vin_models.json")

    private fun cached(ctx: Context): JSONObject =
        runCatching { JSONObject(cacheFile(ctx).readText()) }.getOrElse { JSONObject() }

    /**
     * Blocking. Call from a worker thread.
     *
     * Returns null on any failure -- no network, a timeout, an unknown pattern. A failed
     * lookup is not an error worth surfacing: the scan does not depend on it.
     */
    fun lookup(ctx: Context, vin: String): Result? {
        val prefix = abbreviate(vin)
        if (prefix.isEmpty()) return null
        val store = cached(ctx)
        store.optJSONObject(prefix)?.let {
            return Result(it.optString("model"), it.optString("series"),
                          it.optString("year"), it.optString("engine"))
        }
        val r = runCatching {
            val c = (URL("$ENDPOINT$prefix?format=json").openConnection() as HttpURLConnection)
            c.connectTimeout = 8_000; c.readTimeout = 8_000
            c.requestMethod = "GET"
            c.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        val row = runCatching {
            JSONObject(r).getJSONArray("Results").getJSONObject(0)
        }.getOrNull() ?: return null
        val out = Result(row.optString("Model", ""), row.optString("Series", ""),
                         row.optString("ModelYear", ""), row.optString("DisplacementL", ""))
        if (out.model.isEmpty()) return null
        runCatching {
            store.put(prefix, JSONObject().apply {
                put("model", out.model); put("series", out.series)
                put("year", out.year); put("engine", out.engine)
            })
            cacheFile(ctx).writeText(store.toString())
        }
        return out
    }
}
