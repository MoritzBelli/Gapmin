package de.moritzbelli.gapmin.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Circadian and multi-night sleep measures: Sleep Regularity Index (Phillips et al. 2017), social jetlag (Wittmann et al. 2006),
 * chronotype MSFsc (Roenneberg et al. 2004), 7-night sleep balance and duration variability.
 * Sources: docs/research/schlaf-score.md §1.9
 */
data class RhythmStats(
    /** SRI −100…100 over the 7 days up to the night, and the number of 24-h day pairs it is based on. */
    val sri: Double?, val sriPairs: Int,
    /** Social jetlag: |MSF − MSW| in minutes over the last 28 nights. */
    val sjlMin: Double?,
    /** Mid-sleep on free days / workdays, minutes after midnight (negative = before midnight). */
    val msf: Double?, val msw: Double?,
    /** Chronotype: MSF corrected for oversleep on free days. */
    val msfsc: Double?,
    val freeNights: Int, val workNights: Int,
    /** Σ (TST − 7 h) over the last 7 nights, minutes. */
    val balance7: Double?,
    val nights7: Int,
    /** SD of TST over the last 7 nights, minutes. */
    val tstSd7: Double?,
)

object Rhythm {
    private const val EPOCH_S = 300L
    private const val PER_DAY = (86400 / EPOCH_S).toInt()

    /** Sleep intervals (unix s) of a night: the sleep stages of the hypnogram, or onset–wake if only totals exist. */
    fun sleepIntervals(n: SleepNight): List<LongArray> {
        if (n.hypnogram.isEmpty()) return if (n.wakeTs > n.onsetTs) listOf(longArrayOf(n.onsetTs, n.wakeTs)) else emptyList()
        return n.hypnogram.filter { it.stage == Stage.LIGHT || it.stage == Stage.DEEP || it.stage == Stage.REM }
            .map { longArrayOf(n.bedTs + it.offsetMin * 60L, n.bedTs + (it.offsetMin + it.durMin) * 60L) }
    }

    private fun noon(date: LocalDate, zone: ZoneId) = date.atTime(12, 0).atZone(zone).toEpochSecond()

    /**
     * Sleep Regularity Index (Phillips et al. 2017): probability of being in the same state (asleep/awake) at two time points
     * 24 h apart, scaled to −100…100. Days run noon to noon; a day without a recorded night is left out.
     * The watch records the main sleep only, naps count as awake.
     */
    fun sri(byDate: Map<String, SleepNight>, endDate: LocalDate, days: Int = 7, zone: ZoneId = ZoneId.systemDefault()): Pair<Double, Int>? {
        val states = arrayOfNulls<BooleanArray>(days)
        for (k in 0 until days) {
            val wake = endDate.minusDays((days - 1 - k).toLong())
            val n = byDate[wake.toString()] ?: continue
            val start = noon(wake.minusDays(1), zone)
            val s = BooleanArray(PER_DAY)
            for (iv in sleepIntervals(n)) {
                val a = ((iv[0] - start) / EPOCH_S).toInt().coerceIn(0, PER_DAY)
                val b = ((iv[1] - start + EPOCH_S - 1) / EPOCH_S).toInt().coerceIn(0, PER_DAY)
                for (e in a until b) s[e] = true
            }
            states[k] = s
        }
        var same = 0L
        var total = 0L
        var pairs = 0
        for (k in 0 until days - 1) {
            val a = states[k] ?: continue
            val b = states[k + 1] ?: continue
            pairs++
            for (e in 0 until PER_DAY) { if (a[e] == b[e]) same++; total++ }
        }
        if (pairs < 4 || total == 0L) return null
        return (-100 + 200.0 * same / total) to pairs
    }

    /** SRI for every night (7 days ending with it), keyed by night. */
    fun sriSeries(nights: List<SleepNight>, zone: ZoneId = ZoneId.systemDefault()): Map<String, Double> {
        val byDate = nights.associateBy { it.night }
        return nights.mapNotNull { n -> sri(byDate, LocalDate.parse(n.night), zone = zone)?.let { n.night to it.first } }.toMap()
    }

    private fun midOf(n: SleepNight): Double = n.midpointMin.let { if (it > 720) it - 1440 else it }

    fun stats(nights: List<SleepNight>, selected: Int, zone: ZoneId = ZoneId.systemDefault()): RhythmStats {
        val hist = nights.drop(selected)
        val sel = hist.firstOrNull()
        val sri = sel?.let { sri(nights.associateBy { n -> n.night }, LocalDate.parse(it.night), zone = zone) }

        // MCTQ logic: nights before free days end on Saturday or Sunday, nights before workdays on Monday–Friday
        val end = sel?.let { LocalDate.parse(it.night) }
        val win = if (end == null) emptyList() else hist.filter { LocalDate.parse(it.night) > end.minusDays(28) }
        val free = win.filter { LocalDate.parse(it.night).dayOfWeek.let { d -> d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY } }
        val work = win - free.toSet()
        val msf = if (free.size >= 2) free.map(::midOf).average() else null
        val msw = if (work.size >= 3) work.map(::midOf).average() else null
        val sdf = free.map { (it.wakeTs - it.onsetTs) / 60.0 }.takeIf { it.isNotEmpty() }?.average()
        val sdw = work.map { (it.wakeTs - it.onsetTs) / 60.0 }.takeIf { it.isNotEmpty() }?.average()
        val msfsc = if (msf != null && sdf != null && sdw != null) {
            val week = (5 * sdw + 2 * sdf) / 7
            if (sdf > sdw) msf - (sdf - week) / 2 else msf
        } else null

        val last7 = if (end == null) emptyList() else hist.filter { LocalDate.parse(it.night) > end.minusDays(7) }
        val tst = last7.map { it.tstMin }
        val mean = tst.takeIf { it.isNotEmpty() }?.average()
        val sd = if (tst.size >= 3 && mean != null) sqrt(tst.sumOf { (it - mean) * (it - mean) } / (tst.size - 1)) else null

        return RhythmStats(
            sri = sri?.first, sriPairs = sri?.second ?: 0,
            sjlMin = if (msf != null && msw != null) abs(msf - msw) else null,
            msf = msf, msw = msw, msfsc = msfsc, freeNights = free.size, workNights = work.size,
            balance7 = if (last7.size >= 3) last7.sumOf { it.tstMin - 420 } else null,
            nights7 = last7.size,
            tstSd7 = sd,
        )
    }
}

/**
 * HRV-guided monitoring (Plews et al. 2012/2013; Vesterinen et al. 2016; Javaloyes et al. 2019): 7-day rolling mean of
 * ln RMSSD against the smallest worthwhile change (baseline mean ± 0,5 SD), plus its coefficient of variation.
 */
data class HrvGuideDay(
    val date: String,
    val epochDay: Long,
    /** Nightly ln RMSSD of the day, if measured. */
    val ln: Double?,
    /** 7-day rolling mean of ln RMSSD (at least 4 of 7 nights). */
    val roll7: Double?,
    /** CV of ln RMSSD over the 7 days in %. */
    val cv7: Double?,
    /** Baseline: mean and SD of ln RMSSD over the 60 days before the 7-day window (at least 14 values). */
    val baseMean: Double?, val baseSd: Double?,
) {
    val swcLo: Double? get() = if (baseMean != null && baseSd != null) baseMean - 0.5 * baseSd else null
    val swcHi: Double? get() = if (baseMean != null && baseSd != null) baseMean + 0.5 * baseSd else null

    /** −1 below the SWC band, 0 inside, +1 above, null without baseline. */
    val status: Int?
        get() {
            val r = roll7 ?: return null
            val lo = swcLo ?: return null
            val hi = swcHi ?: return null
            return if (r < lo) -1 else if (r > hi) 1 else 0
        }
}

object HrvGuide {
    fun statusLabel(s: Int?) = when (s) {
        -1 -> "Unter Normalbereich"
        0 -> "Im Normalbereich"
        1 -> "Über Normalbereich"
        else -> "Baseline im Aufbau"
    }

    /** One entry per health day (newest first, like the input). */
    fun series(days: List<HealthDay>): List<HrvGuideDay> {
        val ln = HashMap<Long, Double>()
        for (d in days) d.hrvNight?.takeIf { it > 0 }?.let { ln[d.epochDay] = kotlin.math.ln(it) }
        return days.map { d ->
            val e = d.epochDay
            val w = (0L..6L).mapNotNull { ln[e - it] }
            val roll = if (w.size >= 4) w.average() else null
            val cv = if (w.size >= 4 && roll != null && roll != 0.0) {
                sqrt(w.sumOf { (it - roll) * (it - roll) } / (w.size - 1)) / roll * 100
            } else null
            val base = (7L..66L).mapNotNull { ln[e - it] }
            val bm = if (base.size >= 14) base.average() else null
            val bs = if (bm != null) sqrt(base.sumOf { (it - bm) * (it - bm) } / (base.size - 1)) else null
            HrvGuideDay(d.date, e, ln[e], roll, cv, bm, bs)
        }
    }
}
