package de.moritzbelli.gapmin.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Night detection, sleep metrics and the Gapmin Sleep Score (GSS).
 * Rationale, thresholds and sources: docs/research/schlaf-score.md
 * Pure functions only; database access lives in SleepJob.
 */
object Stage {
    const val UNMEASURABLE = 0
    const val AWAKE = 1
    const val LIGHT = 2
    const val DEEP = 3
    const val REM = 4
}

data class StageEvent(val ts: Long, val stage: Int)
data class Sample(val ts: Long, val value: Double)
data class Segment(val start: Long, var end: Long, val stage: Int)

data class NightMetrics(
    val bed: Long, val onset: Long, val wake: Long, val out: Long,
    val tibMin: Double, val tstMin: Double, val solMin: Double, val wasoMin: Double,
    val awakenings: Int, val awakeningsLong: Int, val efficiency: Double,
    val deepMin: Double, val lightMin: Double, val remMin: Double, val awakeMin: Double,
    val remLatencyMin: Double?,
    val deepFirstHalfShare: Double?,
    val remSecondHalfShare: Double?,
    val segments: List<Segment>,
)

data class NightPhysiology(
    val hrAvg: Double?, val hrMin: Double?, val hrMinTs: Long?, val hrDayAvg: Double?, val hrDip: Double?,
    val hrvAvg: Double?, val hrvMax: Double?, val stressAvg: Double?,
    val respAvg: Double?, val respMin: Double?, val respMax: Double?,
    val spo2Avg: Double?, val spo2Min: Double?, val spo2Below90: Double?, val bbGain: Double?,
)

data class Baselines(val nights: Int, val lnHrvMean: Double?, val lnHrvSd: Double?, val hrMean: Double?, val respMean: Double?)
data class Regularity(val nights: Int, val spreadMin: Double?, val midpointMin: Double)

enum class Component(val key: String, val weight: Double, val label: String, val hint: String) {
    DURATION("duration", 0.25, "Dauer", "7–9 h Schlaf (AASM, NSF)"),
    CONTINUITY("continuity", 0.25, "Kontinuität", "Effizienz, Wachzeit, Aufwachen (NSF 2017)"),
    RECOVERY("recovery", 0.25, "Erholung", "HRV, Schlafpuls, Stress vs. dein Normal"),
    STAGES("stages", 0.10, "Phasen", "Tief-, REM-, Leichtschlaf-Anteile"),
    REGULARITY("regularity", 0.10, "Regelmäßigkeit", "Gleiche Schlafzeiten (NSF 2023)"),
    BREATHING("breathing", 0.05, "Atmung", "Atemfrequenz und SpO2"),
}

data class ScoreResult(
    val score: Int,
    val label: String,
    val components: Map<Component, Double?>,
    val coverage: Double,
    val provisional: Boolean,
    val factors: List<String>,
)

object Sleep {
    /** Split stage events into sleep sessions (gap > 4 h starts a new one). */
    fun splitSessions(events: List<StageEvent>, maxGapS: Long = 4 * 3600): List<List<StageEvent>> {
        val out = ArrayList<MutableList<StageEvent>>()
        for (e in events.sortedBy { it.ts }) {
            val cur = out.lastOrNull()
            if (cur == null || e.ts - cur.last().ts > maxGapS) out.add(mutableListOf(e)) else cur.add(e)
        }
        return out
    }

    /** Turn change events into contiguous segments; a single gap longer than 90 min is treated as unknown. */
    fun toSegments(events: List<StageEvent>, maxSegS: Long = 90 * 60): List<Segment> {
        val segs = ArrayList<Segment>()
        for (i in 0 until events.size - 1) {
            val start = events[i].ts
            val end = min(events[i + 1].ts, start + maxSegS)
            val stage = if (events[i].stage == Stage.UNMEASURABLE) Stage.AWAKE else events[i].stage
            val prev = segs.lastOrNull()
            if (prev != null && prev.stage == stage && prev.end == start) prev.end = end
            else if (end > start) segs.add(Segment(start, end, stage))
        }
        return segs
    }

    private fun isSleep(s: Int) = s == Stage.LIGHT || s == Stage.DEEP || s == Stage.REM
    private fun minutes(s: Long) = s / 60.0
    private fun minutes(s: Double) = s / 60.0

    fun nightMetrics(events: List<StageEvent>): NightMetrics? {
        val segs = toSegments(events)
        val sleepSegs = segs.filter { isSleep(it.stage) }
        if (sleepSegs.isEmpty()) return null
        val bed = events.first().ts
        val out = events.last().ts
        val onset = sleepSegs.first().start
        val wake = sleepSegs.last().end

        var deep = 0L; var light = 0L; var rem = 0L; var awake = 0L; var waso = 0L
        var awakenings = 0; var awakeningsLong = 0
        for (s in segs) {
            val d = s.end - s.start
            when (s.stage) {
                Stage.DEEP -> deep += d
                Stage.LIGHT -> light += d
                Stage.REM -> rem += d
                else -> {
                    awake += d
                    if (s.start >= onset && s.end <= wake) {
                        waso += d
                        awakenings++
                        if (d > 5 * 60) awakeningsLong++
                    }
                }
            }
        }
        val tst = deep + light + rem
        val tib = max(out - bed, wake - bed)
        val mid = onset + (wake - onset) / 2.0
        fun share(stage: Int, first: Boolean): Double? {
            var inHalf = 0.0
            var total = 0.0
            for (s in segs) {
                if (s.stage != stage) continue
                total += s.end - s.start
                val a = if (first) s.start.toDouble() else max(s.start.toDouble(), mid)
                val b = if (first) min(s.end.toDouble(), mid) else s.end.toDouble()
                if (b > a) inHalf += b - a
            }
            return if (total > 0) inHalf / total else null
        }
        val firstRem = segs.firstOrNull { it.stage == Stage.REM }

        return NightMetrics(
            bed = bed, onset = onset, wake = wake, out = out,
            tibMin = minutes(tib), tstMin = minutes(tst), solMin = minutes(onset - bed), wasoMin = minutes(waso),
            awakenings = awakenings, awakeningsLong = awakeningsLong, efficiency = if (tib > 0) tst.toDouble() / tib else 0.0,
            deepMin = minutes(deep), lightMin = minutes(light), remMin = minutes(rem), awakeMin = minutes(awake),
            remLatencyMin = firstRem?.let { minutes(it.start - onset) },
            deepFirstHalfShare = share(Stage.DEEP, true),
            remSecondHalfShare = share(Stage.REM, false),
            segments = segs,
        )
    }

    private fun mean(xs: List<Double>) = if (xs.isEmpty()) null else xs.average()
    private fun within(xs: List<Sample>, a: Long, b: Long) = xs.filter { it.ts in a..b }

    class PhysioInput(
        val hr: List<Sample>, val hrDay: List<Sample>, val hrv: List<Sample>, val stress: List<Sample>,
        val resp: List<Sample>, val spo2: List<Sample>, val bb: List<Sample>,
    )

    fun nightPhysiology(m: NightMetrics, d: PhysioInput): NightPhysiology {
        val hr = within(d.hr, m.onset, m.wake).filter { it.value > 25 && it.value < 220 }
        var hrMin: Double? = null
        var hrMinTs: Long? = null
        var i = 0
        while (i + 5 <= hr.size) {
            var s = 0.0
            for (k in i until i + 5) s += hr[k].value
            val avg = s / 5
            if (hrMin == null || avg < hrMin) { hrMin = avg; hrMinTs = hr[i + 2].ts }
            i++
        }
        val hrAvg = mean(hr.map { it.value })
        val hrDayAvg = mean(d.hrDay.filter { it.value > 25 && it.value < 220 }.map { it.value })

        val hrv = within(d.hrv, m.bed, m.out).map { it.value }.filter { it > 0 }
        val stress = within(d.stress, m.onset, m.wake).map { it.value }.filter { it in 0.0..100.0 }
        val resp = within(d.resp, m.onset, m.wake).map { it.value }.filter { it > 3 && it < 60 }
        val spo2 = within(d.spo2, m.bed, m.out).map { it.value }.filter { it in 50.0..100.0 }

        fun nearest(t: Long): Sample? {
            var best: Sample? = null
            for (s in d.bb) if (abs(s.ts - t) <= 3600 && (best == null || abs(s.ts - t) < abs(best.ts - t))) best = s
            return best
        }
        val bbStart = nearest(m.onset)
        val bbEnd = nearest(m.wake)

        return NightPhysiology(
            hrAvg = hrAvg, hrMin = hrMin, hrMinTs = hrMinTs, hrDayAvg = hrDayAvg,
            hrDip = if (hrAvg != null && hrDayAvg != null && hrDayAvg != 0.0) 1 - hrAvg / hrDayAvg else null,
            hrvAvg = mean(hrv), hrvMax = hrv.maxOrNull(),
            stressAvg = mean(stress),
            respAvg = mean(resp), respMin = resp.minOrNull(), respMax = resp.maxOrNull(),
            spo2Avg = mean(spo2), spo2Min = spo2.minOrNull(),
            spo2Below90 = if (spo2.isEmpty()) null else spo2.count { it < 90 }.toDouble() / spo2.size,
            bbGain = if (bbStart != null && bbEnd != null) bbEnd.value - bbStart.value else null,
        )
    }

    /** Linear 0 → 100 between `bad` and `good` (works for either direction), clamped. */
    fun ramp(x: Double, bad: Double, good: Double) = max(0.0, min(100.0, (x - bad) / (good - bad) * 100))

    /** Piecewise-linear interpolation through (x, y) knots sorted by x. */
    private fun interp(x: Double, vararg knots: Pair<Double, Double>): Double {
        if (x <= knots[0].first) return knots[0].second
        for (i in 1 until knots.size) {
            val (x0, y0) = knots[i - 1]
            val (x1, y1) = knots[i]
            if (x <= x1) return y0 + (x - x0) / (x1 - x0) * (y1 - y0)
        }
        return knots.last().second
    }

    private fun fmtH(min: Double) = "${floor(min / 60).toInt()}:${(min % 60).roundToInt().toString().padStart(2, '0')} h"

    /** Sub-score of one input; `share` is its weight inside the component. */
    data class PartScore(val component: Component, val key: String, val label: String, val share: Double, val score: Double?)

    class PartInput(
        val tstMin: Double, val efficiency: Double, val wasoMin: Double, val awakeningsLong: Int, val solMin: Double,
        val deepMin: Double, val lightMin: Double, val remMin: Double,
        val hrvAvg: Double?, val hrAvg: Double?, val stressAvg: Double?, val respAvg: Double?, val spo2Avg: Double?,
        val regularityNights: Int, val regularitySpread: Double?, val b: Baselines,
    )

    /** z of ln(nightly HRV) against the personal 28-night baseline (needs 7 nights). */
    fun hrvZ(hrvAvg: Double?, b: Baselines): Double? =
        if (hrvAvg != null && hrvAvg > 0 && b.nights >= 7 && b.lnHrvMean != null && b.lnHrvSd != null && b.lnHrvSd != 0.0) {
            (ln(hrvAvg) - b.lnHrvMean) / max(b.lnHrvSd, 0.03)
        } else {
            null
        }

    /** All sub-scores (0-100) of the GSS, see docs/research/schlaf-score.md §2.2. */
    fun parts(x: PartInput): List<PartScore> {
        val personal = x.b.nights >= 7
        val tstH = x.tstMin / 60
        val duration = if (tstH < 7) ramp(tstH, 4.0, 7.0) else if (tstH <= 9) 100.0 else interp(tstH, 9.0 to 100.0, 11.0 to 80.0)

        val z = hrvZ(x.hrvAvg, x.b)
        val hrv = when {
            x.hrvAvg == null -> null
            z != null -> interp(z, -2.0 to 0.0, -1.0 to 45.0, 0.0 to 80.0, 0.5 to 100.0)
            else -> ramp(x.hrvAvg, 20.0, 60.0) // grob, bis eine Baseline existiert
        }
        val hr = when {
            x.hrAvg == null -> null
            personal && x.b.hrMean != null -> interp(x.hrAvg - x.b.hrMean, 0.0 to 100.0, 3.0 to 70.0, 6.0 to 35.0, 9.0 to 0.0)
            else -> ramp(-x.hrAvg, -75.0, -50.0)
        }
        val stress = x.stressAvg?.let { ramp(-it, -40.0, -10.0) }

        fun pct(v: Double) = if (x.tstMin > 0) v / x.tstMin * 100 else 0.0
        fun band(v: Double, lo: Double, hi: Double, k: Double) = max(0.0, 100 - k * (if (v < lo) lo - v else if (v > hi) v - hi else 0.0))

        val reg = if (x.regularityNights >= 3 && x.regularitySpread != null) ramp(-x.regularitySpread, -90.0, -20.0) else null
        val resp = x.respAvg?.let {
            if (personal && x.b.respMean != null) interp(abs(it - x.b.respMean), 1.0 to 100.0, 3.0 to 40.0, 4.0 to 0.0)
            else if (it in 11.8..19.2) 100.0 else 50.0
        }
        val spo2 = x.spo2Avg?.let { interp(it, 90.0 to 0.0, 92.0 to 40.0, 95.0 to 100.0) }

        val third = 1.0 / 3
        return listOf(
            PartScore(Component.DURATION, "tst", "Gesamtschlafzeit (TST)", 1.0, duration),
            PartScore(Component.CONTINUITY, "se", "Schlafeffizienz (SE)", 0.4, ramp(x.efficiency * 100, 70.0, 90.0)),
            PartScore(Component.CONTINUITY, "waso", "Wach nach Einschlafen (WASO)", 0.3, ramp(-x.wasoMin, -60.0, -10.0)),
            PartScore(Component.CONTINUITY, "wake", "Aufwachphasen > 5 min", 0.2, interp(x.awakeningsLong.toDouble(), 1.0 to 100.0, 2.0 to 70.0, 3.0 to 40.0, 4.0 to 10.0)),
            PartScore(Component.CONTINUITY, "sol", "Einschlaflatenz (SOL)", 0.1, ramp(-x.solMin, -60.0, -15.0)),
            PartScore(Component.RECOVERY, "hrv", "HRV, ln RMSSD (z)", 0.5, hrv),
            PartScore(Component.RECOVERY, "hr", "Schlafpuls (Δ zum Mittel)", 0.3, hr),
            PartScore(Component.RECOVERY, "stress", "Stress im Schlaf", 0.2, stress),
            PartScore(Component.STAGES, "deep", "Tiefschlaf-Anteil", third, band(pct(x.deepMin), 13.0, 23.0, 6.0)),
            PartScore(Component.STAGES, "rem", "REM-Anteil", third, band(pct(x.remMin), 18.0, 26.0, 5.0)),
            PartScore(Component.STAGES, "light", "Leichtschlaf-Anteil", third, band(pct(x.lightMin), 0.0, 65.0, 4.0)),
            PartScore(Component.REGULARITY, "reg", "Streuung Einschlaf-/Aufwachzeit", 1.0, reg),
            PartScore(Component.BREATHING, "resp", "Atemfrequenz", 0.6, resp),
            PartScore(Component.BREATHING, "spo2", "SpO2", 0.4, spo2),
        )
    }

    fun componentScores(ps: List<PartScore>): Map<Component, Double?> {
        val out = LinkedHashMap<Component, Double?>()
        for (c in Component.entries) out[c] = weighted(*ps.filter { it.component == c }.map { it.score to it.share }.toTypedArray())
        return out
    }

    fun scoreNight(m: NightMetrics, p: NightPhysiology, b: Baselines, r: Regularity): ScoreResult {
        val ps = parts(
            PartInput(
                m.tstMin, m.efficiency, m.wasoMin, m.awakeningsLong, m.solMin, m.deepMin, m.lightMin, m.remMin,
                p.hrvAvg, p.hrAvg, p.stressAvg, p.respAvg, p.spo2Avg, r.nights, r.spreadMin, b,
            ),
        )
        val components = componentScores(ps)
        val personal = b.nights >= 7

        val notes = HashMap<Component, String>()
        notes[Component.DURATION] = if (m.tstMin < 420) "Kurz geschlafen (${fmtH(m.tstMin)})" else "Lang geschlafen (${fmtH(m.tstMin)})"
        notes[Component.CONTINUITY] = when {
            m.wasoMin > 20 -> "${m.wasoMin.roundToInt()} min wach nach dem Einschlafen"
            m.awakeningsLong > 1 -> "${m.awakeningsLong}× länger wach"
            else -> "Schlafeffizienz ${(m.efficiency * 100).roundToInt()} %"
        }
        if (hrvZ(p.hrvAvg, b) != null) {
            val pct = ((p.hrvAvg!! / exp(b.lnHrvMean!!) - 1) * 100).roundToInt()
            notes[Component.RECOVERY] = if (pct < 0) "HRV ${-pct} % unter deinem Normal" else "HRV $pct % über deinem Normal"
        }
        if (personal && p.hrAvg != null && b.hrMean != null && p.hrAvg - b.hrMean > 3) {
            notes[Component.RECOVERY] = "Schlafpuls ${"%.0f".format(p.hrAvg - b.hrMean)} bpm über Normal"
        }
        if (notes[Component.RECOVERY] == null) {
            notes[Component.RECOVERY] = p.stressAvg?.let { "Stress im Schlaf ${it.roundToInt()}" } ?: "Erholung"
        }
        val deepPct = if (m.tstMin > 0) m.deepMin / m.tstMin * 100 else 0.0
        val remPct = if (m.tstMin > 0) m.remMin / m.tstMin * 100 else 0.0
        notes[Component.STAGES] = when {
            deepPct < 13 -> "Wenig Tiefschlaf (${deepPct.roundToInt()} %)"
            remPct < 18 -> "Wenig REM-Schlaf (${remPct.roundToInt()} %)"
            else -> "Schlafphasen"
        }
        notes[Component.REGULARITY] = r.spreadMin?.let { "Schlafzeiten schwanken um ±${it.roundToInt()} min" } ?: "Regelmäßigkeit"
        notes[Component.BREATHING] = when {
            p.respAvg != null && b.respMean != null && p.respAvg - b.respMean >= 3 ->
                "Atemfrequenz erhöht (${"%.1f".format(java.util.Locale.GERMANY, p.respAvg)}/min)"
            p.spo2Avg != null && p.spo2Avg < 93 -> "Niedrige Sauerstoffsättigung (${p.spo2Avg.roundToInt()} %)"
            else -> "Atmung"
        }

        var sumW = 0.0
        var sum = 0.0
        val losses = ArrayList<Pair<Component, Double>>()
        for (k in Component.entries) {
            val v = components[k] ?: continue
            sumW += k.weight
            sum += k.weight * v
            losses.add(k to k.weight * (100 - v))
        }
        val score = if (sumW > 0) Math.round(sum / sumW).toInt() else 0
        val factors = losses
            .filter { (k, l) -> l >= 2 && (components[k] ?: 100.0) < 85 }
            .sortedByDescending { it.second }
            .take(2)
            .map { notes[it.first]!! }

        return ScoreResult(
            score = score,
            label = label(score),
            components = components,
            coverage = sumW,
            provisional = !personal,
            factors = factors,
        )
    }

    fun label(score: Int) = when {
        score >= 90 -> "Hervorragend"
        score >= 80 -> "Gut"
        score >= 60 -> "Ausreichend"
        else -> "Schlecht"
    }

    private fun weighted(vararg parts: Pair<Double?, Double>): Double? {
        var w = 0.0
        var s = 0.0
        for ((v, k) in parts) if (v != null) { w += k; s += v * k }
        return if (w > 0) s / w else null
    }

    /** Robust spread (MAD × 1.4826) of clock times in minutes, handling the midnight wrap. */
    fun clockSpread(minutesOfDay: List<Double>): Double? {
        if (minutesOfDay.size < 2) return null
        val ang = minutesOfDay.map { it / 1440 * 2 * Math.PI }
        val c = atan2(ang.map { sin(it) }.average(), ang.map { cos(it) }.average())
        val centre = ((c / (2 * Math.PI)) * 1440 + 1440) % 1440
        val dev = minutesOfDay.map {
            var d = it - centre
            if (d > 720) d -= 1440
            if (d < -720) d += 1440
            d
        }
        val med = median(dev)
        return median(dev.map { abs(it - med) }) * 1.4826
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }
}
