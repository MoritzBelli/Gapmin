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

    fun scoreNight(m: NightMetrics, p: NightPhysiology, b: Baselines, r: Regularity): ScoreResult {
        val notes = HashMap<Component, String>()

        // Dauer (AASM/NSF: 7-9 h)
        val tstH = m.tstMin / 60
        val duration = if (tstH < 7) ramp(tstH, 4.0, 7.0) else if (tstH <= 9) 100.0 else interp(tstH, 9.0 to 100.0, 11.0 to 80.0)
        notes[Component.DURATION] = if (tstH < 7) "Kurz geschlafen (${fmtH(m.tstMin)})" else "Lang geschlafen (${fmtH(m.tstMin)})"

        // Kontinuität (NSF 2017)
        val se = ramp(m.efficiency * 100, 70.0, 90.0)
        val waso = ramp(-m.wasoMin, -60.0, -10.0)
        val wake = interp(m.awakeningsLong.toDouble(), 1.0 to 100.0, 2.0 to 70.0, 3.0 to 40.0, 4.0 to 10.0)
        val sol = ramp(-m.solMin, -60.0, -15.0)
        val continuity = 0.4 * se + 0.3 * waso + 0.2 * wake + 0.1 * sol
        notes[Component.CONTINUITY] = when {
            m.wasoMin > 20 -> "${m.wasoMin.roundToInt()} min wach nach dem Einschlafen"
            m.awakeningsLong > 1 -> "${m.awakeningsLong}× länger wach"
            else -> "Schlafeffizienz ${(m.efficiency * 100).roundToInt()} %"
        }

        // Autonome Erholung (individuelle Baseline, Plews: SWC ≈ 0,5 SD)
        val personal = b.nights >= 7
        var hrvScore: Double? = null
        if (p.hrvAvg != null) {
            if (personal && b.lnHrvMean != null && b.lnHrvSd != null && b.lnHrvSd != 0.0) {
                val z = (ln(p.hrvAvg) - b.lnHrvMean) / max(b.lnHrvSd, 0.03)
                hrvScore = interp(z, -2.0 to 0.0, -1.0 to 45.0, 0.0 to 80.0, 0.5 to 100.0)
                val pct = ((p.hrvAvg / exp(b.lnHrvMean) - 1) * 100).roundToInt()
                notes[Component.RECOVERY] = if (pct < 0) "HRV ${-pct} % unter deinem Normal" else "HRV $pct % über deinem Normal"
            } else {
                hrvScore = ramp(p.hrvAvg, 20.0, 60.0) // grob, bis eine Baseline existiert
            }
        }
        var hrScore: Double? = null
        if (p.hrAvg != null) {
            if (personal && b.hrMean != null) {
                val delta = p.hrAvg - b.hrMean
                hrScore = interp(delta, 0.0 to 100.0, 3.0 to 70.0, 6.0 to 35.0, 9.0 to 0.0)
                if (delta > 3) notes[Component.RECOVERY] = "Schlafpuls ${"%.0f".format(delta)} bpm über Normal"
            } else {
                hrScore = ramp(-p.hrAvg, -75.0, -50.0)
            }
        }
        val stressScore = p.stressAvg?.let { ramp(-it, -40.0, -10.0) }
        val recovery = weighted(hrvScore to 0.5, hrScore to 0.3, stressScore to 0.2)
        if (notes[Component.RECOVERY] == null) {
            notes[Component.RECOVERY] = p.stressAvg?.let { "Stress im Schlaf ${it.roundToInt()}" } ?: "Erholung"
        }

        // Phasen (geringe Messgenauigkeit, daher Bänder)
        fun pct(x: Double) = if (m.tstMin > 0) x / m.tstMin * 100 else 0.0
        fun band(x: Double, lo: Double, hi: Double, k: Double) = max(0.0, 100 - k * (if (x < lo) lo - x else if (x > hi) x - hi else 0.0))
        val deepPct = pct(m.deepMin)
        val remPct = pct(m.remMin)
        val lightPct = pct(m.lightMin)
        val stages = (band(deepPct, 13.0, 23.0, 6.0) + band(remPct, 18.0, 26.0, 5.0) + band(lightPct, 0.0, 65.0, 4.0)) / 3
        notes[Component.STAGES] = when {
            deepPct < 13 -> "Wenig Tiefschlaf (${deepPct.roundToInt()} %)"
            remPct < 18 -> "Wenig REM-Schlaf (${remPct.roundToInt()} %)"
            else -> "Schlafphasen"
        }

        // Regelmäßigkeit (Windred 2024, NSF 2023)
        val regularity = if (r.nights >= 3 && r.spreadMin != null) ramp(-r.spreadMin, -90.0, -20.0) else null
        notes[Component.REGULARITY] = r.spreadMin?.let { "Schlafzeiten schwanken um ±${it.roundToInt()} min" } ?: "Regelmäßigkeit"

        // Atmung & SpO2
        var respScore: Double? = null
        if (p.respAvg != null) {
            respScore = if (personal && b.respMean != null) interp(abs(p.respAvg - b.respMean), 1.0 to 100.0, 3.0 to 40.0, 4.0 to 0.0)
            else if (p.respAvg in 11.8..19.2) 100.0 else 50.0
        }
        val spo2Score = p.spo2Avg?.let { interp(it, 90.0 to 0.0, 92.0 to 40.0, 95.0 to 100.0) }
        val breathing = weighted(respScore to 0.6, spo2Score to 0.4)
        notes[Component.BREATHING] = when {
            p.respAvg != null && b.respMean != null && p.respAvg - b.respMean >= 3 ->
                "Atemfrequenz erhöht (${"%.1f".format(java.util.Locale.GERMANY, p.respAvg)}/min)"
            p.spo2Avg != null && p.spo2Avg < 93 -> "Niedrige Sauerstoffsättigung (${p.spo2Avg.roundToInt()} %)"
            else -> "Atmung"
        }

        val components = linkedMapOf(
            Component.DURATION to duration, Component.CONTINUITY to continuity, Component.RECOVERY to recovery,
            Component.STAGES to stages, Component.REGULARITY to regularity, Component.BREATHING to breathing,
        )
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
            label = when {
                score >= 90 -> "Hervorragend"
                score >= 80 -> "Gut"
                score >= 60 -> "Ausreichend"
                else -> "Schlecht"
            },
            components = components,
            coverage = sumW,
            provisional = !personal,
            factors = factors,
        )
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
