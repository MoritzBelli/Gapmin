package de.moritzbelli.gapmin.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Training load and performance from all activities: Banister TRIMP, fitness-fatigue model (CTL/ATL/TSB), EWMA
 * acute:chronic workload ratio, Foster's monotony and strain, intensity distribution (Seiler 3 zones, polarization index),
 * VO2max estimate (ACSM running equation + %HRR ≈ %VO2R), VDOT, best efforts and Riegel predictions.
 * Pure functions on read models; methods and sources: docs/research/training.md
 */
data class TrainingActivity(
    val id: String,
    val sport: String,
    val category: RunCategory?,
    val startTs: Long,
    val epochDay: Long,
    val durationS: Double,
    val distanceM: Double,
    val avgHr: Double?,
    val maxHr: Double?,
    val ascentM: Double?,
    val metrics: RunMetrics?,
) {
    val isRun: Boolean get() = Runs.isRun(sport)
}

/** Personal heart-rate anchors. `*FromData` = false: no measurement yet, a population default is used. */
data class HrAnchors(val max: Double, val rest: Double, val maxFromData: Boolean, val restFromData: Boolean) {
    /** Heart-rate reserve fraction (Karvonen). */
    fun hrr(hr: Double) = ((hr - rest) / (max - rest)).coerceIn(0.0, 1.0)
}

data class LoadDay(
    val epochDay: Long,
    /** Sum of TRIMP of the day. */
    val load: Double,
    /** Fitness (chronic training load, τ = 42 d), fatigue (acute, τ = 7 d), form = CTL − ATL of the day before. */
    val ctl: Double, val atl: Double, val tsb: Double,
    /** EWMA loads for the ACWR (Williams et al. 2017, N = 7 and 28). */
    val acute: Double, val chronic: Double,
    /** Days of history up to and including this one. */
    val history: Int,
) {
    val acwr: Double? get() = if (history >= 21 && chronic > 1.0) acute / chronic else null
}

data class TrainingWeek(val monday: Long, val runs: Int, val distanceM: Double, val durationS: Double, val load: Double)

/** Time in zones (s): five zones by %HRmax (50/60/70/80/90) and Seiler's three zones (< 82 %, 82–87 %, ≥ 87 % HRmax). */
class Tid(val z5: DoubleArray, val z3: DoubleArray) {
    val total3: Double get() = z3.sum()
    fun f3(i: Int): Double = if (total3 > 0) z3[i] / total3 else 0.0

    /** Polarization index, Treff et al. 2019: log10(Z1/Z2 · Z3 · 100) with zone fractions; > 2,00 = polarised. */
    val pi: Double?
        get() {
            if (total3 <= 0) return null
            val z1 = f3(0); val z2 = maxOf(f3(1), 0.01); val z3v = f3(2)
            if (z3v < 0.01 || z1 <= 0) return 0.0
            return log10(z1 / z2 * z3v * 100)
        }

    val label: String
        get() {
            if (total3 <= 0) return "–"
            val p = pi ?: 0.0
            val z1 = f3(0); val z2 = f3(1); val z3v = f3(2)
            return when {
                p > 2.0 && z1 > z3v && z3v > z2 -> "Polarisiert"
                z1 > z2 && z2 > z3v -> "Pyramidal"
                z2 >= z1 -> "Schwellenbetont"
                z1 >= 0.9 -> "Grundlagenbetont"
                else -> "Gemischt"
            }
        }
}

data class Vo2Point(val epochDay: Long, val value: Double, val activityId: String)
data class BestEffort(val distance: Int, val seconds: Double, val activityId: String, val epochDay: Long)
data class Prediction(val distance: Int, val seconds: Double, val fromDistance: Int, val fromSeconds: Double)

data class TrainingSummary(
    val hr: HrAnchors,
    /** Newest first. */
    val activities: List<TrainingActivity>,
    /** Oldest first, one entry per calendar day up to today. */
    val days: List<LoadDay>,
    /** Oldest first. */
    val weeks: List<TrainingWeek>,
    val monotony: Double?,
    val strain: Double?,
    val tid28: Tid,
    val tid90: Tid,
    /** Per qualifying run, oldest first. */
    val vo2: List<Vo2Point>,
    /** Median of the qualifying runs of the last 90 days (at most 8). */
    val vo2Now: Double?,
    /** Daniels' VDOT of the best effort of the last 90 days. */
    val vdot: Double?,
    val bestsAll: Map<Int, BestEffort>,
    val bests90: Map<Int, BestEffort>,
    val predictions: List<Prediction>,
    /** Efficiency factor of easy and long runs (epoch day → m/min per bpm), oldest first. */
    val ef: List<Pair<Long, Double>>,
) {
    val today: LoadDay? get() = days.lastOrNull()
    fun day(epochDay: Long): LoadDay? = days.getOrNull((epochDay - (days.firstOrNull()?.epochDay ?: epochDay)).toInt())?.takeIf { it.epochDay == epochDay }
}

object Training {
    const val CTL_DAYS = 42.0
    const val ATL_DAYS = 7.0
    val PREDICT = listOf(5000, 10000, 21097, 42195)

    // ---- per activity ----------------------------------------------------------------------------

    /** Banister TRIMP weighting y = 0,64·e^(1,92·x) (Morton, Fitz-Clarke & Banister 1990; coefficients for men). */
    fun trimpWeight(x: Double) = x * 0.64 * exp(1.92 * x)

    /** Banister TRIMP: minutes × HRR fraction × exponential weighting, integrated over the heart-rate histogram if there is one. */
    fun trimp(a: TrainingActivity, hr: HrAnchors): Double {
        val hist = a.metrics?.hrHist
        if (!hist.isNullOrEmpty()) return hist.entries.sumOf { (bpm, s) -> s / 60 * trimpWeight(hr.hrr(bpm.toDouble())) }
        val avg = a.avgHr ?: return 0.0
        return a.durationS / 60 * trimpWeight(hr.hrr(avg))
    }

    fun zones(a: TrainingActivity, hr: HrAnchors): Tid {
        val z5 = DoubleArray(5)
        val z3 = DoubleArray(3)
        fun add(bpm: Double, s: Double) {
            val f = bpm / hr.max
            if (f < 0.5) return
            z5[((f - 0.5) / 0.1).toInt().coerceIn(0, 4)] += s
            z3[if (f < 0.82) 0 else if (f < 0.87) 1 else 2] += s
        }
        val hist = a.metrics?.hrHist
        if (!hist.isNullOrEmpty()) for ((bpm, s) in hist) add(bpm.toDouble(), s)
        else a.avgHr?.let { add(it, a.durationS) }
        return Tid(z5, z3)
    }

    /**
     * VO2max estimate of a steady run: oxygen cost of the (grade-adjusted) speed by the ACSM running equation
     * VO2 = 0,2·v + 3,5 (v in m/min), scaled to maximum by %HRR ≈ %VO2R (Swain & Leutholtz 1997).
     * Only continuous runs ≥ 20 min at 50–95 % HRR and ≥ 8 km/h; intervals and trail runs are excluded.
     */
    fun vo2(a: TrainingActivity, hr: HrAnchors): Double? {
        if (!a.isRun || a.category == RunCategory.INTERVALS || a.category == RunCategory.TRAIL) return null
        if (!hr.maxFromData) return null
        val m = a.metrics
        val moving = m?.movingS ?: a.durationS
        if (moving < 1200 || a.distanceM <= 0) return null
        val hrAvg = m?.hrAvg ?: a.avgHr ?: return null
        val x = hr.hrr(hrAvg)
        if (x < 0.5 || x > 0.95) return null
        val v = m?.gapSpeed ?: run {
            if ((a.ascentM ?: 0.0) / a.distanceM > 0.015) return null
            a.distanceM / (moving / 60)
        }
        if (v < 134 || v > 450) return null
        val vo2 = 0.2 * v + 3.5
        return (3.5 + (vo2 - 3.5) / x).takeIf { it in 20.0..90.0 }
    }

    /** Daniels & Gilbert (1979): VDOT of a performance, distance in m, time in s. */
    fun vdot(distanceM: Double, seconds: Double): Double? {
        if (distanceM <= 0 || seconds <= 0) return null
        val t = seconds / 60
        val v = distanceM / t
        val vo2 = -4.60 + 0.182258 * v + 0.000104 * v * v
        val pct = 0.8 + 0.1894393 * exp(-0.012778 * t) + 0.2989558 * exp(-0.1932605 * t)
        return (vo2 / pct).takeIf { it.isFinite() && it in 15.0..90.0 }
    }

    /** Riegel (1981): T2 = T1 · (D2/D1)^1,06. */
    fun riegel(t1: Double, d1: Double, d2: Double) = t1 * (d2 / d1).pow(1.06)

    // ---- summary ---------------------------------------------------------------------------------

    fun anchors(acts: List<TrainingActivity>, days: List<HealthDay>): HrAnchors {
        val maxes = acts.mapNotNull { it.maxHr }.filter { it in 120.0..230.0 }.sortedDescending()
        // a single optical spike far above everything else is ignored
        val max = when {
            maxes.isEmpty() -> null
            maxes.size >= 2 && maxes[0] - maxes[1] > 8 -> maxes[1]
            else -> maxes[0]
        }
        val rests = days.mapNotNull { it.restingHr }.filter { it in 25.0..120.0 }.take(30).sorted()
        val rest = if (rests.isEmpty()) null else rests[rests.size / 2]
        return HrAnchors(max ?: 190.0, rest ?: 60.0, max != null, rest != null)
    }

    fun list(db: Sql, zone: ZoneId = ZoneId.systemDefault()): List<TrainingActivity> =
        db.query(
            """SELECT a.id, a.sport, a.category, a.start_ts, a.duration_s, a.distance_m, a.avg_hr, a.max_hr, a.ascent_m, r.data
               FROM activities a LEFT JOIN run_metrics r ON r.activity_id = a.id ORDER BY a.start_ts DESC""",
        ).map { r ->
            val ts = r[3].lng() ?: 0
            val sport = r[1].str() ?: "other"
            TrainingActivity(
                id = r[0].str()!!,
                sport = sport,
                category = if (Runs.isRun(sport)) RunCategory.of(r[2].str()) else null,
                startTs = ts,
                epochDay = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().toEpochDay(),
                durationS = r[4].num() ?: 0.0,
                distanceM = r[5].num() ?: 0.0,
                avgHr = r[6].num()?.takeIf { it > 0 },
                maxHr = r[7].num()?.takeIf { it > 0 },
                ascentM = r[8].num(),
                metrics = RunMetrics.parse(r[9].str()),
            )
        }

    fun summarize(acts: List<TrainingActivity>, health: List<HealthDay>, today: Long = LocalDate.now().toEpochDay()): TrainingSummary? {
        if (acts.isEmpty()) return null
        val hr = anchors(acts, health)
        val loads = HashMap<Long, Double>()
        for (a in acts) loads[a.epochDay] = (loads[a.epochDay] ?: 0.0) + trimp(a, hr)

        val first = minOf(acts.minOf { it.epochDay }, today)
        val last = maxOf(today, acts.maxOf { it.epochDay })
        val kc = 1 - exp(-1 / CTL_DAYS)
        val ka = 1 - exp(-1 / ATL_DAYS)
        val la = 2.0 / (7 + 1)
        val lc = 2.0 / (28 + 1)
        var ctl = 0.0; var atl = 0.0; var acute = 0.0; var chronic = 0.0
        val days = ArrayList<LoadDay>()
        for ((i, d) in (first..last).withIndex()) {
            val l = loads[d] ?: 0.0
            val tsb = ctl - atl
            ctl += (l - ctl) * kc
            atl += (l - atl) * ka
            acute = la * l + (1 - la) * acute
            chronic = lc * l + (1 - lc) * chronic
            days.add(LoadDay(d, l, ctl, atl, tsb, acute, chronic, i + 1))
        }

        val week7 = days.takeLast(7).map { it.load }
        val mean7 = week7.average()
        val sd7 = if (week7.size > 1) sqrt(week7.sumOf { (it - mean7) * (it - mean7) } / (week7.size - 1)) else 0.0
        val monotony = if (sd7 > 1e-6 && week7.sum() > 0) mean7 / sd7 else null
        val strain = monotony?.let { it * week7.sum() }

        val weeks = acts.groupBy { it.epochDay - Math.floorMod(it.epochDay + 3, 7L) }.toSortedMap().map { (monday, l) ->
            val runs = l.filter { it.isRun }
            TrainingWeek(monday, runs.size, runs.sumOf { it.distanceM }, runs.sumOf { it.durationS }, l.sumOf { trimp(it, hr) })
        }

        fun tid(daysBack: Int): Tid {
            val z5 = DoubleArray(5)
            val z3 = DoubleArray(3)
            for (a in acts) if (a.epochDay > last - daysBack) {
                val z = zones(a, hr)
                for (k in 0 until 5) z5[k] += z.z5[k]
                for (k in 0 until 3) z3[k] += z.z3[k]
            }
            return Tid(z5, z3)
        }

        val vo2 = acts.mapNotNull { a -> vo2(a, hr)?.let { Vo2Point(a.epochDay, it, a.id) } }.sortedBy { it.epochDay }
        val recent = vo2.filter { it.epochDay > last - 90 }.takeLast(8).map { it.value }.sorted()
        val vo2Now = if (recent.size >= 3) recent[recent.size / 2] else null

        val runs = acts.filter { it.isRun && it.metrics != null }
        fun bests(daysBack: Int?): Map<Int, BestEffort> {
            val out = HashMap<Int, BestEffort>()
            for (a in runs) {
                if (daysBack != null && a.epochDay <= last - daysBack) continue
                for ((d, s) in a.metrics!!.bests) {
                    val cur = out[d]
                    if (cur == null || s < cur.seconds) out[d] = BestEffort(d, s, a.id, a.epochDay)
                }
            }
            return out
        }
        val bestsAll = bests(null)
        val bests90 = bests(90)
        val refs = bests90.values.filter { it.distance >= 3000 }
        val vdot = refs.mapNotNull { vdot(it.distance.toDouble(), it.seconds) }.maxOrNull()
        // Riegel works best from a similar distance: each target is predicted from the closest *other* distance (log scale)
        val predictions = PREDICT.mapNotNull { target ->
            val ref = refs.filter { it.distance != target }.minByOrNull { kotlin.math.abs(ln(it.distance.toDouble() / target)) } ?: return@mapNotNull null
            Prediction(target, riegel(ref.seconds, ref.distance.toDouble(), target.toDouble()), ref.distance, ref.seconds)
        }

        val ef = runs.filter { it.category == RunCategory.EASY || it.category == RunCategory.LONG || it.category == RunCategory.RECOVERY }
            .mapNotNull { a -> a.metrics?.ef?.let { a.epochDay to it } }.sortedBy { it.first }

        return TrainingSummary(
            hr = hr, activities = acts, days = days, weeks = weeks, monotony = monotony, strain = strain,
            tid28 = tid(28), tid90 = tid(90), vo2 = vo2, vo2Now = vo2Now, vdot = vdot,
            bestsAll = bestsAll, bests90 = bests90, predictions = predictions, ef = ef,
        )
    }

    fun distanceLabel(d: Int) = when (d) {
        1000 -> "1 km"
        5000 -> "5 km"
        10000 -> "10 km"
        21097 -> "Halbmarathon"
        42195 -> "Marathon"
        else -> "${d / 1000} km"
    }

    fun acwrLabel(r: Double?) = when {
        r == null -> "–"
        r < 0.8 -> "Unterfordert"
        r <= 1.3 -> "Optimal"
        r <= 1.5 -> "Erhöht"
        else -> "Sprunghaft"
    }

    /** Form relative to fitness (TSB / CTL in %); TRIMP and TSS have different scales, so absolute TSB limits do not transfer. */
    fun formPct(d: LoadDay): Double? = if (d.ctl > 5) d.tsb / d.ctl * 100 else null

    /** Practice convention (TrainingPeaks/Coggan, intervals.icu), not validated. */
    fun formLabel(pct: Double?) = when {
        pct == null -> "–"
        pct > 25 -> "Erholt, Fitness sinkt"
        pct > 5 -> "Frisch"
        pct > -10 -> "Neutral"
        pct > -30 -> "Aufbau"
        else -> "Hohe Ermüdung"
    }
}
