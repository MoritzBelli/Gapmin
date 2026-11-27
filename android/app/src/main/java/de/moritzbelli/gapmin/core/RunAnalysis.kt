package de.moritzbelli.gapmin.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Analysis of one run from its track (GPS distance, heart rate, elevation), stored per activity in `run_metrics`.
 * Everything here is independent of HRmax and resting HR; TRIMP, zones and the VO2max estimate apply those at read time
 * (Training). Methods and sources: docs/research/training.md
 */
data class RunMetrics(
    /** Seconds spent at each heart rate (bpm). */
    val hrHist: Map<Int, Double>,
    /** Moving time of every full kilometre (s). */
    val splits: List<Double>,
    /** Fastest moving time over the standard distances (m → s). */
    val bests: Map<Int, Double>,
    /** Efficiency factor: speed (m/min) per bpm after the warm-up, whole run and first/second half. */
    val ef: Double?, val ef1: Double?, val ef2: Double?,
    /** Grade-adjusted pace (s/km, Minetti 2002) and the flat-equivalent speed (m/min). */
    val gapSPerKm: Double?,
    val movingS: Double,
    val distM: Double,
    /** Time-weighted mean heart rate. */
    val hrAvg: Double?,
) {
    /** Aerobic decoupling Pa:HR in % (EF first half vs second half); positive = heart rate drifted up at the same speed. */
    val decoupling: Double? get() = if (ef1 != null && ef2 != null && ef1 > 0) (ef1 - ef2) / ef1 * 100 else null
    val gapSpeed: Double? get() = gapSPerKm?.let { if (it > 0) 60_000 / it else null }
    val paceSPerKm: Double? get() = if (distM > 0 && movingS > 0) movingS / (distM / 1000) else null

    fun toJson(): String = Json.write(
        linkedMapOf(
            "v" to RunAnalysis.VERSION,
            "hr" to hrHist.toSortedMap().map { (k, v) -> listOf(k, Math.round(v * 10) / 10.0) },
            "splits" to splits.map { Math.round(it * 10) / 10.0 },
            "best" to bests.mapKeys { it.key.toString() },
            "ef" to ef, "ef1" to ef1, "ef2" to ef2, "gap" to gapSPerKm,
            "moving" to movingS, "dist" to distM, "hrAvg" to hrAvg,
        ),
    )

    companion object {
        fun parse(json: String?): RunMetrics? {
            val m = Json.parse(json).asMap() ?: return null
            if ((m["v"].asDouble() ?: 0.0).toInt() != RunAnalysis.VERSION) return null
            val hist = HashMap<Int, Double>()
            m["hr"].asList()?.forEach { e ->
                val l = e.asList() ?: return@forEach
                val b = l.getOrNull(0).asDouble() ?: return@forEach
                val s = l.getOrNull(1).asDouble() ?: return@forEach
                hist[b.toInt()] = s
            }
            return RunMetrics(
                hrHist = hist,
                splits = m["splits"].asList()?.mapNotNull { it.asDouble() } ?: emptyList(),
                bests = m["best"].asMap()?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { d -> v.asDouble()?.let { d to it } } }?.toMap() ?: emptyMap(),
                ef = m["ef"].asDouble(), ef1 = m["ef1"].asDouble(), ef2 = m["ef2"].asDouble(),
                gapSPerKm = m["gap"].asDouble(),
                movingS = m["moving"].asDouble() ?: 0.0,
                distM = m["dist"].asDouble() ?: 0.0,
                hrAvg = m["hrAvg"].asDouble(),
            )
        }
    }
}

object RunAnalysis {
    const val VERSION = 1
    val BEST_DISTANCES = listOf(1000, 5000, 10000, 21097, 42195)

    /** Longer gaps between two points are a pause (watch stopped or signal lost). */
    private const val MAX_GAP_S = 120.0
    /** Below this speed the runner stands (traffic light), comparable to auto-pause. */
    private const val MOVING_MPS = 0.3
    /** Elevation is evaluated over at least this distance to suppress altimeter/GPS noise. */
    private const val GRADE_WINDOW_M = 30.0

    /** Energy cost of running on a gradient i (J/kg/m), Minetti et al. 2002, valid for −0.45 ≤ i ≤ 0.45. */
    fun minettiCost(i: Double): Double {
        val g = i.coerceIn(-0.45, 0.45)
        return 155.4 * g.pow(5) - 30.4 * g.pow(4) - 43.3 * g.pow(3) + 46.3 * g * g + 19.5 * g + 3.6
    }

    fun analyze(track: List<TrackSample>): RunMetrics? {
        val pts = track.filter { it.dist != null }.sortedBy { it.t }
        if (pts.size < 10) return null
        val n = pts.size
        val t = DoubleArray(n) { pts[it].t.toDouble() }
        // cumulative distance from the start, never decreasing
        val d = DoubleArray(n)
        val d0 = pts[0].dist!!
        for (i in 0 until n) d[i] = maxOf(if (i > 0) d[i - 1] else 0.0, pts[i].dist!! - d0)
        val total = d[n - 1]
        if (total < 200) return null

        // moving clock: pauses and standing still do not count
        val m = DoubleArray(n)
        for (i in 1 until n) {
            val dt = t[i] - t[i - 1]
            val dd = d[i] - d[i - 1]
            m[i] = m[i - 1] + if (dt in 0.0..MAX_GAP_S && dt > 0 && dd / dt >= MOVING_MPS) dt else 0.0
        }
        val moving = m[n - 1]
        if (moving < 60) return null

        // heart rate histogram (time-weighted; the interval ending at a point gets that point's heart rate)
        val hist = HashMap<Int, Double>()
        var hrPts = 0
        for (i in 1 until n) {
            val hr = pts[i].hr ?: continue
            if (hr < 35 || hr > 230) continue
            hrPts++
            val dt = t[i] - t[i - 1]
            if (dt <= 0 || dt > MAX_GAP_S) continue
            val b = hr.roundToInt()
            hist[b] = (hist[b] ?: 0.0) + dt
        }
        val histTime = hist.values.sum()
        val hrAvg = if (histTime > 0) hist.entries.sumOf { it.key * it.value } / histTime else null

        // splits per full kilometre on the moving clock
        val splits = ArrayList<Double>()
        var next = 1000.0
        var lastT = 0.0
        for (i in 1 until n) {
            while (d[i] >= next) {
                val f = if (d[i] > d[i - 1]) (next - d[i - 1]) / (d[i] - d[i - 1]) else 1.0
                val tb = m[i - 1] + f * (m[i] - m[i - 1])
                splits.add(tb - lastT)
                lastT = tb
                next += 1000
            }
        }

        // fastest segment for each standard distance (two pointers, start interpolated to the exact distance)
        val bests = HashMap<Int, Double>()
        for (dist in BEST_DISTANCES) {
            if (total < dist) continue
            var best = Double.MAX_VALUE
            var i = 0
            for (j in 1 until n) {
                if (d[j] < dist) continue
                while (i + 1 < j && d[j] - d[i + 1] >= dist) i++
                val s = d[j] - dist
                val f = if (d[i + 1] > d[i]) ((s - d[i]) / (d[i + 1] - d[i])).coerceIn(0.0, 1.0) else 0.0
                val ms = m[i] + f * (m[i + 1] - m[i])
                val time = m[j] - ms
                if (time > 0 && time < best) best = time
            }
            // faster than ~2:20/km over 1 km is a GPS glitch, not a run
            if (best < Double.MAX_VALUE && best >= dist * 0.14) bests[dist] = best
        }

        // efficiency factor after the warm-up; halves split on the moving clock
        var ef: Double? = null
        var ef1: Double? = null
        var ef2: Double? = null
        if (hrPts >= n * 0.7) {
            val warm = if (moving >= 1800) 600.0 else 300.0
            val start = (0 until n).firstOrNull { m[it] >= warm }
            if (start != null && moving - m[start] >= 600) {
                val mid = (m[start] + moving) / 2
                fun efOf(a: Int, b: Int): Double? {
                    if (b <= a) return null
                    var hrT = 0.0
                    var hrSum = 0.0
                    for (i in a + 1..b) {
                        val hr = pts[i].hr ?: continue
                        val dt = m[i] - m[i - 1]
                        if (dt <= 0 || hr < 35) continue
                        hrT += dt
                        hrSum += hr * dt
                    }
                    val time = m[b] - m[a]
                    if (hrT < time * 0.6 || time <= 0) return null
                    val speed = (d[b] - d[a]) / (time / 60)
                    return speed / (hrSum / hrT)
                }
                val midIdx = (start until n).first { m[it] >= mid }
                ef = efOf(start, n - 1)
                if (moving >= 1200) {
                    ef1 = efOf(start, midIdx)
                    ef2 = efOf(midIdx, n - 1)
                }
            }
        }

        // grade-adjusted pace: distance weighted by the energy cost of its gradient relative to flat running
        var gap: Double? = null
        val elePts = pts.count { it.ele != null }
        if (elePts >= n * 0.8) {
            var a = (0 until n).first { pts[it].ele != null }
            var eq = 0.0
            var time = 0.0
            val c0 = minettiCost(0.0)
            for (i in a + 1 until n) {
                val e = pts[i].ele ?: continue
                val dd = d[i] - d[a]
                if (dd < GRADE_WINDOW_M && i < n - 1) continue
                if (dd > 0) {
                    val g = (e - pts[a].ele!!) / dd
                    eq += dd * minettiCost(g) / c0
                    time += m[i] - m[a]
                }
                a = i
            }
            if (eq > 0 && time > 0) gap = time / (eq / 1000)
        }

        return RunMetrics(
            hrHist = hist, splits = splits, bests = bests, ef = ef, ef1 = ef1, ef2 = ef2,
            gapSPerKm = gap, movingS = moving, distM = total, hrAvg = hrAvg,
        )
    }

    /** Computes `run_metrics` for every activity with a track that has none yet (or an older version). Returns the count. */
    fun update(db: Sql): Int {
        val ids = db.query(
            """SELECT a.id FROM activities a LEFT JOIN run_metrics r ON r.activity_id = a.id
               WHERE a.has_track = 1 AND (r.activity_id IS NULL OR r.version < ?)""",
            listOf(VERSION),
        ).mapNotNull { it[0].str() }
        if (ids.isEmpty()) return 0
        val st = db.prepare("INSERT OR REPLACE INTO run_metrics (activity_id, version, data) VALUES (?,?,?)")
        try {
            for (chunk in ids.chunked(20)) {
                db.transaction {
                    for (id in chunk) {
                        val r = analyze(Repo.track(db, id))
                        st.run(listOf(id, VERSION, r?.toJson()))
                    }
                }
            }
        } finally {
            st.close()
        }
        return ids.size
    }

    /** Relative difference, used in tests. */
    internal fun rel(a: Double, b: Double) = abs(a - b) / maxOf(abs(b), 1e-9)
}
