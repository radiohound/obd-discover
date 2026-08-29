package com.redundo.obddiscover

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * OBDb signal definitions for one model, fetched at run time and used to NAME what a scan
 * found. Optional, off by default, behind the same toggle as the model lookup.
 *
 * WHY THIS IS NOT BUNDLED. The shipped tables take locations only -- which header, which
 * block, which requests answer -- and deliberately not "the signal definitions, names or
 * decode formulas". Bundling those would make this app a CC BY-SA 4.0 adaptation of OBDb's
 * whole corpus. Fetching a model's file from OBDb at run time is not redistribution at all:
 * the device gets it from the source, licence and attribution intact. It is also 19 KB for
 * one model against roughly 10 MB for all of them.
 *
 * WHAT LEAVES THE PHONE: a repository name, e.g. "Chevrolet-Silverado-1500". No VIN, no
 * serial, nothing about this particular vehicle.
 *
 * This is the first thing in the app that answers "what does this DID mean" rather than
 * "which DIDs answer". It only covers what OBDb documents -- 55 signals on a Silverado
 * against the 1,929 DIDs a sweep of one finds -- so it names a minority and says so.
 */
object SignalSet {

    /** One decoded reading: what it is, the number, and its unit. */
    data class Reading(val name: String, val value: String, val unit: String) {
        val label: String get() = if (unit.isEmpty()) "$name: $value" else "$name: $value $unit"
    }

    /** A signal's position and scaling within a response payload. */
    private data class Sig(
        val name: String, val bix: Int, val len: Int, val signed: Boolean,
        val mul: Double, val div: Double, val add: Double, val unit: String,
    )

    private val byRequest = HashMap<String, MutableList<Sig>>()   // "HDR|22XXXX" -> signals
    private var loadedRepo = ""

    val loaded: Boolean get() = byRequest.isNotEmpty()
    val repo: String get() = loadedRepo
    val signalCount: Int get() = byRequest.values.sumOf { it.size }

    private const val RAW = "https://raw.githubusercontent.com/OBDb/"

    /** Blocking. Returns true if a signalset was loaded. Cached on disk after the first call. */
    fun load(ctx: Context, repoName: String): Boolean {
        if (loadedRepo == repoName && byRequest.isNotEmpty()) return true
        byRequest.clear(); loadedRepo = ""
        val cache = File(ctx.filesDir, "signalset-$repoName.json")
        val text = if (cache.exists()) runCatching { cache.readText() }.getOrNull()
                   else fetch(repoName)?.also { runCatching { cache.writeText(it) } }
        val doc = runCatching { JSONObject(text ?: return false) }.getOrNull() ?: return false
        val cmds = doc.optJSONArray("commands") ?: return false
        for (i in 0 until cmds.length()) {
            val c = cmds.optJSONObject(i) ?: continue
            val hdr = c.optString("hdr", "").uppercase()
            val cmd = c.optJSONObject("cmd") ?: continue
            val svc = cmd.keys().asSequence().firstOrNull() ?: continue
            val key = "$hdr|${svc.uppercase()}${cmd.optString(svc).uppercase()}"
            val sigs = c.optJSONArray("signals") ?: continue
            for (j in 0 until sigs.length()) {
                val s = sigs.optJSONObject(j) ?: continue
                val f = s.optJSONObject("fmt") ?: continue
                val len = f.optInt("len", 0)
                if (len <= 0 || len > 32) continue
                byRequest.getOrPut(key) { mutableListOf() }.add(
                    Sig(
                        name = s.optString("name").ifEmpty { s.optString("id") },
                        bix = f.optInt("bix", 0), len = len,
                        signed = f.optBoolean("sign", false),
                        mul = f.optDouble("mul", 1.0), div = f.optDouble("div", 1.0),
                        add = f.optDouble("add", 0.0), unit = f.optString("unit", ""),
                    ),
                )
            }
        }
        if (byRequest.isEmpty()) return false
        loadedRepo = repoName
        return true
    }

    private fun fetch(repoName: String): String? {
        for (br in listOf("main", "master")) {
            val r = runCatching {
                val c = URL("$RAW$repoName/$br/signalsets/v3/default.json")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 8_000; c.readTimeout = 10_000
                c.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    /**
     * Decode one reply, or an empty list if this model documents nothing at that request.
     *
     * `bix` is a BIT index from the start of the payload, not a byte offset, and `sign`
     * means two's complement over `len` bits. Getting either wrong yields a plausible
     * number rather than an obvious failure, which is why they are applied literally and
     * anything that does not fit is skipped rather than guessed at.
     */
    fun decode(header: String, request: String, payloadHex: String): List<Reading> {
        val sigs = byRequest["${header.uppercase()}|${request.uppercase()}"] ?: return emptyList()
        val bytes = runCatching {
            ByteArray(payloadHex.length / 2) {
                ((Character.digit(payloadHex[it * 2], 16) shl 4) or
                 Character.digit(payloadHex[it * 2 + 1], 16)).toByte()
            }
        }.getOrNull() ?: return emptyList()
        val total = bytes.size * 8
        val out = ArrayList<Reading>()
        for (s in sigs) {
            if (s.bix + s.len > total) continue          // payload shorter than documented
            var raw = 0L
            for (b in 0 until s.len) {
                val bit = s.bix + b
                val v = (bytes[bit / 8].toInt() shr (7 - (bit % 8))) and 1
                raw = (raw shl 1) or v.toLong()
            }
            var v = if (s.signed && s.len < 64 && (raw shr (s.len - 1)) and 1L == 1L)
                        (raw - (1L shl s.len)).toDouble() else raw.toDouble()
            v = v * s.mul / (if (s.div == 0.0) 1.0 else s.div) + s.add
            val shown = if (v == Math.floor(v) && Math.abs(v) < 1e9) v.toLong().toString()
                        else String.format("%.2f", v)
            out.add(Reading(s.name, shown, s.unit))
        }
        return out
    }

    fun clear() { byRequest.clear(); loadedRepo = "" }

    /** Test seam: load definitions from a signalset string instead of the network. */
    internal fun loadFrom(json: String, tag: String): Boolean {
        byRequest.clear(); loadedRepo = ""
        val doc = runCatching { JSONObject(json) }.getOrNull() ?: return false
        val cmds = doc.optJSONArray("commands") ?: return false
        for (i in 0 until cmds.length()) {
            val c = cmds.optJSONObject(i) ?: continue
            val hdr = c.optString("hdr", "").uppercase()
            val cmd = c.optJSONObject("cmd") ?: continue
            val svc = cmd.keys().asSequence().firstOrNull() ?: continue
            val key = "$hdr|${svc.uppercase()}${cmd.optString(svc).uppercase()}"
            val sigs = c.optJSONArray("signals") ?: continue
            for (j in 0 until sigs.length()) {
                val sg = sigs.optJSONObject(j) ?: continue
                val f = sg.optJSONObject("fmt") ?: continue
                val len = f.optInt("len", 0)
                if (len <= 0 || len > 32) continue
                byRequest.getOrPut(key) { mutableListOf() }.add(
                    Sig(sg.optString("name").ifEmpty { sg.optString("id") },
                        f.optInt("bix", 0), len, f.optBoolean("sign", false),
                        f.optDouble("mul", 1.0), f.optDouble("div", 1.0),
                        f.optDouble("add", 0.0), f.optString("unit", "")),
                )
            }
        }
        if (byRequest.isEmpty()) return false
        loadedRepo = tag
        return true
    }
}
