package de.moritzbelli.gapmin.core

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Parse GPS tracks from GPX or FIT files and derive route previews. */
data class TrackPoint(
    val ts: Long,             // unix seconds
    val lat: Double?,
    val lon: Double?,
    val ele: Double?,
    val hr: Double?,
    val cadence: Double?,
    var speed: Double?,       // m/s
    var dist: Double?,        // cumulative metres
)

data class LatLon(val lat: Double, val lon: Double)

object Tracks {
    private val trkpt = Regex("<trkpt\\b([^>]*)>([\\s\\S]*?)</trkpt>")
    private val latRe = Regex("lat=\"([^\"]+)\"")
    private val lonRe = Regex("lon=\"([^\"]+)\"")
    private val timeRe = Regex("<time>([^<]+)</time>")
    private val eleRe = Regex("<ele>([^<]+)</ele>")
    private val hrRe = Regex("<(?:\\w+:)?hr>([^<]+)</(?:\\w+:)?hr>")
    private val cadRe = Regex("<(?:\\w+:)?cad>([^<]+)</(?:\\w+:)?cad>")

    fun parseGpx(xml: String): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        for (m in trkpt.findAll(xml)) {
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val time = timeRe.find(body)?.groupValues?.get(1) ?: continue
            val ts = parseIso(time) ?: continue
            out.add(
                TrackPoint(
                    ts = ts,
                    lat = latRe.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull(),
                    lon = lonRe.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull(),
                    ele = eleRe.find(body)?.groupValues?.get(1)?.toDoubleOrNull(),
                    hr = hrRe.find(body)?.groupValues?.get(1)?.toDoubleOrNull(),
                    cadence = cadRe.find(body)?.groupValues?.get(1)?.toDoubleOrNull(),
                    speed = null,
                    dist = null,
                ),
            )
        }
        return withDistance(out)
    }

    private fun parseIso(s: String): Long? = try {
        OffsetDateTime.parse(s.trim().let { if (it.endsWith("Z") || it.contains('+') || it.lastIndexOf('-') > 9) it else "${it}Z" })
            .toInstant().let { (it.toEpochMilli() / 1000.0).roundToInt().toLong() }
    } catch (_: DateTimeParseException) {
        null
    }

    fun parseFit(bytes: ByteArray): List<TrackPoint> {
        val pts = Fit.records(bytes)
        return if (pts.any { it.dist != null }) pts else withDistance(pts)
    }

    fun isFit(b: ByteArray) = b.size > 12 && b[8] == 0x2e.toByte() && b[9] == 0x46.toByte() && b[10] == 0x49.toByte() && b[11] == 0x54.toByte()

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val toRad = Math.PI / 180
        val dLat = (lat2 - lat1) * toRad
        val dLon = (lon2 - lon1) * toRad
        val h = sin(dLat / 2).let { it * it } + cos(lat1 * toRad) * cos(lat2 * toRad) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }

    private fun withDistance(pts: List<TrackPoint>): List<TrackPoint> {
        var d = 0.0
        var prev: TrackPoint? = null
        for (p in pts) {
            val pr = prev
            if (pr?.lat != null && pr.lon != null && p.lat != null && p.lon != null) {
                val step = haversine(pr.lat, pr.lon, p.lat, p.lon)
                d += step
                if (p.speed == null && p.ts > pr.ts) p.speed = step / (p.ts - pr.ts)
            }
            p.dist = d
            prev = p
        }
        return pts
    }

    /** Ramer-Douglas-Peucker on locally projected metres. */
    fun routePreview(pts: List<TrackPoint>, maxPoints: Int = 250): List<LatLon> {
        val geo = pts.filter { it.lat != null && it.lon != null }
        if (geo.size < 2) return emptyList()
        val k = cos(geo[0].lat!! * Math.PI / 180) * 111320
        val xy = geo.map { doubleArrayOf(it.lon!! * k, it.lat!! * 110540) }
        // Never coarser than 8 m (repeated track laps would otherwise collapse); then thin evenly.
        var keep = BooleanArray(0)
        for (eps in doubleArrayOf(2.0, 3.0, 5.0, 8.0)) {
            keep = rdp(xy, eps)
            if (keep.count { it } <= maxPoints) break
        }
        var kept = geo.filterIndexed { i, _ -> keep[i] }
        if (kept.size > maxPoints) {
            val step = kept.size.toDouble() / maxPoints
            val thinned = (0 until maxPoints).map { i -> kept[minOf(kept.size - 1, Math.round(i * step).toInt())] }.toMutableList()
            thinned.add(geo.last())
            kept = thinned
        }
        return kept.map { LatLon(round5(it.lat!!), round5(it.lon!!)) }
    }

    private fun round5(x: Double) = Math.round(x * 1e5) / 1e5

    private fun rdp(pts: List<DoubleArray>, eps: Double): BooleanArray {
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, pts.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            val ax = pts[a][0]; val ay = pts[a][1]; val bx = pts[b][0]; val by = pts[b][1]
            val dx = bx - ax; val dy = by - ay
            val len = hypot(dx, dy)
            var maxD = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                // closed loops: start == end, so measure plain distance to that point
                val d = if (len < 1e-6) hypot(pts[i][0] - ax, pts[i][1] - ay)
                else abs(dy * pts[i][0] - dx * pts[i][1] + bx * ay - by * ax) / len
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > eps && idx > 0) {
                keep[idx] = true
                stack.addLast(intArrayOf(a, idx))
                stack.addLast(intArrayOf(idx, b))
            }
        }
        return keep
    }

    /** Coefficient of variation of 30-second average speed while moving (high for interval sessions). */
    fun paceVariation(pts: List<TrackPoint>): Double? {
        if (pts.size < 20) return null
        val buckets = LinkedHashMap<Long, DoubleArray>()
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val ad = a.dist ?: continue
            val bd = b.dist ?: continue
            if (b.ts <= a.ts) continue
            val e = buckets.getOrPut(Math.floorDiv(b.ts, 30L)) { DoubleArray(2) }
            e[0] += bd - ad
            e[1] += (b.ts - a.ts).toDouble()
        }
        val speeds = buckets.values.filter { it[1] >= 15 }.map { it[0] / it[1] }.filter { it > 1.2 }
        if (speeds.size < 10) return null
        val mu = speeds.average()
        val sd = sqrt(speeds.sumOf { (it - mu) * (it - mu) } / speeds.size)
        return sd / mu
    }

    /** Total ascent with a small hysteresis to ignore GPS/barometer noise. */
    fun ascent(pts: List<TrackPoint>, threshold: Double = 3.0): Double? {
        val e = pts.mapNotNull { it.ele }
        if (e.size < 2) return null
        var gain = 0.0
        var ref = e[0]
        for (v in e) {
            if (v - ref >= threshold) { gain += v - ref; ref = v } else if (v < ref) ref = v
        }
        return gain
    }
}

/**
 * Minimal FIT decoder: reads `record` messages (GPS, altitude, HR, cadence, speed, distance).
 * Handles definition/data messages, both byte orders, developer fields and compressed timestamps.
 */
object Fit {
    private const val FIT_EPOCH = 631065600L // 1989-12-31T00:00:00Z
    private const val SEMI = 180.0 / 2147483648.0

    private class Def(val arch: Int, val global: Int, val fields: List<IntArray>, val devSize: Int)

    fun records(b: ByteArray): List<TrackPoint> {
        if (!Tracks.isFit(b)) return emptyList()
        val headerSize = b[0].toInt() and 0xFF
        val dataSize = u32le(b, 4)
        val end = minOf(b.size.toLong(), headerSize + dataSize).toInt()
        val defs = arrayOfNulls<Def>(16)
        val out = ArrayList<TrackPoint>()
        var lastTs = 0L
        var i = headerSize
        try {
            while (i < end) {
                val h = b[i++].toInt() and 0xFF
                if (h and 0x80 != 0) {
                    // compressed timestamp header
                    val local = (h shr 5) and 0x3
                    val offset = (h and 0x1F).toLong()
                    var ts = (lastTs and 0x1F.toLong().inv()) + offset
                    if (offset < (lastTs and 0x1F)) ts += 0x20
                    lastTs = ts
                    val d = defs[local] ?: return out
                    i = data(b, i, d, ts + FIT_EPOCH, out) { lastTs = it }
                } else if (h and 0x40 != 0) {
                    val local = h and 0x0F
                    val hasDev = h and 0x20 != 0
                    val arch = b[i + 1].toInt() and 0xFF
                    val global = if (arch == 1) ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                    else (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
                    val n = b[i + 4].toInt() and 0xFF
                    i += 5
                    val fields = ArrayList<IntArray>(n)
                    repeat(n) {
                        fields.add(intArrayOf(b[i].toInt() and 0xFF, b[i + 1].toInt() and 0xFF, b[i + 2].toInt() and 0xFF))
                        i += 3
                    }
                    var devSize = 0
                    if (hasDev) {
                        val nd = b[i++].toInt() and 0xFF
                        repeat(nd) { devSize += b[i + 1].toInt() and 0xFF; i += 3 }
                    }
                    defs[local] = Def(arch, global, fields, devSize)
                } else {
                    val d = defs[h and 0x0F] ?: return out
                    i = data(b, i, d, null, out) { lastTs = it }
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            // truncated file: keep what we have
        }
        return out
    }

    private inline fun data(b: ByteArray, start: Int, d: Def, compressedTs: Long?, out: MutableList<TrackPoint>, setTs: (Long) -> Unit): Int {
        var i = start
        var ts: Long? = compressedTs
        var lat: Double? = null; var lon: Double? = null; var alt: Double? = null; var ealt: Double? = null
        var hr: Double? = null; var cad: Double? = null; var dist: Double? = null; var spd: Double? = null; var espd: Double? = null
        val isRecord = d.global == 20
        for (f in d.fields) {
            val num = f[0]; val size = f[1]
            if (isRecord || num == 253) {
                val v = read(b, i, size, d.arch)
                when (num) {
                    253 -> if (size == 4 && v != 0xFFFFFFFFL) { ts = v + FIT_EPOCH; setTs(v) }
                    0 -> if (isRecord && size == 4 && v != 0x7FFFFFFFL) lat = v.toInt() * SEMI
                    1 -> if (isRecord && size == 4 && v != 0x7FFFFFFFL) lon = v.toInt() * SEMI
                    2 -> if (isRecord && size == 2 && v != 0xFFFFL) alt = v / 5.0 - 500
                    3 -> if (isRecord && size == 1 && v != 0xFFL) hr = v.toDouble()
                    4 -> if (isRecord && size == 1 && v != 0xFFL) cad = v.toDouble()
                    5 -> if (isRecord && size == 4 && v != 0xFFFFFFFFL) dist = v / 100.0
                    6 -> if (isRecord && size == 2 && v != 0xFFFFL) spd = v / 1000.0
                    73 -> if (isRecord && size == 4 && v != 0xFFFFFFFFL) espd = v / 1000.0
                    78 -> if (isRecord && size == 4 && v != 0xFFFFFFFFL) ealt = v / 5.0 - 500
                }
            }
            i += size
        }
        i += d.devSize
        val t = ts
        if (isRecord && t != null) {
            out.add(TrackPoint(t, lat, lon, ealt ?: alt, hr, cad, espd ?: spd, dist))
        }
        return i
    }

    /** Unsigned little/big endian integer of 1, 2 or 4 bytes (other sizes: 0). */
    private fun read(b: ByteArray, i: Int, size: Int, arch: Int): Long {
        if (size != 1 && size != 2 && size != 4) return 0
        var v = 0L
        for (k in 0 until size) {
            val byte = (b[i + k].toInt() and 0xFF).toLong()
            v = if (arch == 1) (v shl 8) or byte else v or (byte shl (8 * k))
        }
        return v
    }

    private fun u32le(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)
}
