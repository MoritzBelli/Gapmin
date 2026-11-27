package de.moritzbelli.gapmin.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Daily health statistics (stress, Body Battery, HRV, heart rate, activity, SpO2, respiration),
 * aggregated per local calendar day from the stored samples. Rationale: docs/research/gesundheit.md
 */
data class HealthDay(
    val date: String,
    // heart rate (minute samples)
    val hrAvg: Double?, val hrMin: Double?, val hrMax: Double?, val hrMinutes: Int,
    val restingHr: Double?,
    // activity
    val steps: Double?, val activeKcal: Double?, val imModerate: Double?, val imVigorous: Double?,
    // stress (Garmin 0-100): mean, max and share of samples per Garmin category
    val stressAvg: Double?, val stressMax: Double?, val stressN: Int,
    val stressRest: Double?, val stressLow: Double?, val stressMed: Double?, val stressHigh: Double?,
    // Body Battery
    val bbMax: Double?, val bbMin: Double?, val bbCharged: Double?, val bbDrained: Double?,
    // HRV (Garmin nightly summary)
    val hrvNight: Double?, val hrvWeekly: Double?, val hrvLow: Double?, val hrvHigh: Double?, val hrvStatus: Int?,
    // breathing
    val spo2Avg: Double?, val spo2Min: Double?, val respAvg: Double?, val respMin: Double?, val respMax: Double?,
) {
    /** WHO-weighted intensity minutes of the day: moderate + 2 × vigorous. */
    val imWeighted: Double? get() = if (imModerate == null && imVigorous == null) null else (imModerate ?: 0.0) + 2 * (imVigorous ?: 0.0)
    val epochDay: Long get() = LocalDate.parse(date).toEpochDay()
}

data class DaySeries(val stress: List<Sample>, val bb: List<Sample>, val hr: List<Sample>, val startTs: Long, val endTs: Long)

object Health {
    private const val LOCAL_DAY = "date(ts, 'unixepoch', 'localtime')"

    private class Acc {
        val v = HashMap<String, Double?>()
    }

    /** One row per day with any data, newest first. */
    fun listDays(db: Sql, days: Int = 3650): List<HealthDay> {
        val from = System.currentTimeMillis() / 1000 - days * 86400L
        val acc = java.util.TreeMap<String, Acc>(reverseOrder())
        fun put(d: String, k: String, x: Double?) { acc.getOrPut(d) { Acc() }.v[k] = x }

        db.forEach(
            """SELECT $LOCAL_DAY d, AVG(hr), MIN(hr), MAX(hr), COUNT(hr), SUM(steps), SUM(active_kcal)
               FROM minute_samples WHERE ts >= ? GROUP BY d""",
            listOf(from),
        ) { r ->
            val d = r[0].str() ?: return@forEach
            put(d, "hrAvg", r[1].num()); put(d, "hrMin", r[2].num()); put(d, "hrMax", r[3].num())
            put(d, "hrN", r[4].num()); put(d, "steps", r[5].num()); put(d, "kcal", r[6].num())
        }

        db.forEach(
            """SELECT metric, $LOCAL_DAY d, AVG(value), MIN(value), MAX(value), COUNT(*), SUM(value),
                      SUM(value <= 25), SUM(value > 25 AND value <= 50), SUM(value > 50 AND value <= 75), SUM(value > 75)
               FROM metric_samples
               WHERE ts >= ? AND metric IN ('stress', 'spo2', 'respiration', 'resting_hr', 'intensity_moderate', 'intensity_vigorous')
               GROUP BY metric, d""",
            listOf(from),
        ) { r ->
            val d = r[1].str() ?: return@forEach
            val avg = r[2].num(); val mn = r[3].num(); val mx = r[4].num(); val n = r[5].num() ?: 0.0; val sum = r[6].num()
            when (r[0].str()) {
                "stress" -> {
                    put(d, "stressAvg", avg); put(d, "stressMax", mx); put(d, "stressN", n)
                    if (n > 0) {
                        put(d, "sRest", (r[7].num() ?: 0.0) / n); put(d, "sLow", (r[8].num() ?: 0.0) / n)
                        put(d, "sMed", (r[9].num() ?: 0.0) / n); put(d, "sHigh", (r[10].num() ?: 0.0) / n)
                    }
                }
                "spo2" -> { put(d, "spo2Avg", avg); put(d, "spo2Min", mn) }
                "respiration" -> { put(d, "respAvg", avg); put(d, "respMin", mn); put(d, "respMax", mx) }
                "resting_hr" -> put(d, "rhr", avg)
                "intensity_moderate" -> put(d, "imMod", sum)
                "intensity_vigorous" -> put(d, "imVig", sum)
            }
        }

        // Body Battery: charged / drained = sum of positive / negative steps within the day
        var lastDay: String? = null
        var last: Double? = null
        db.forEach(
            "SELECT $LOCAL_DAY d, value FROM metric_samples WHERE metric = 'body_battery' AND ts >= ? ORDER BY ts",
            listOf(from),
        ) { r ->
            val d = r[0].str() ?: return@forEach
            val x = r[1].num() ?: return@forEach
            val a = acc.getOrPut(d) { Acc() }.v
            a["bbMax"] = maxOf(a["bbMax"] ?: x, x)
            a["bbMin"] = minOf(a["bbMin"] ?: x, x)
            if (d == lastDay && last != null) {
                val delta = x - last!!
                if (delta > 0) a["bbUp"] = (a["bbUp"] ?: 0.0) + delta else a["bbDown"] = (a["bbDown"] ?: 0.0) - delta
            } else {
                a.putIfAbsent("bbUp", 0.0); a.putIfAbsent("bbDown", 0.0)
            }
            lastDay = d
            last = x
        }

        db.forEach(
            """SELECT $LOCAL_DAY d, last_night_avg, weekly_avg, baseline_balanced_lower, baseline_balanced_upper, status
               FROM hrv_summary WHERE ts >= ? ORDER BY ts""",
            listOf(from),
        ) { r ->
            val d = r[0].str() ?: return@forEach
            put(d, "hrvNight", r[1].num()); put(d, "hrvWeekly", r[2].num())
            put(d, "hrvLow", r[3].num()); put(d, "hrvHigh", r[4].num()); put(d, "hrvStatus", r[5].num())
        }

        // Daily records of a Garmin Connect data export fill what has no samples (steps, stress, Body Battery, ...)
        val fromDate = java.time.LocalDate.now().minusDays(days.toLong()).toString()
        db.forEach("SELECT date, data FROM daily_summary WHERE kind = 'garmin_uds' AND date >= ?", listOf(fromDate)) { r ->
            val d = r[0].str() ?: return@forEach
            val j = Json.parse(r[1].str()).asMap() ?: return@forEach
            val a = acc.getOrPut(d) { Acc() }.v
            fun fill(k: String, x: Double?) { if (x != null && a[k] == null) a[k] = x }
            fill("steps", j["totalSteps"].asDouble()); fill("kcal", j["activeKilocalories"].asDouble())
            fill("imMod", j["moderateIntensityMinutes"].asDouble()); fill("imVig", j["vigorousIntensityMinutes"].asDouble())
            fill("hrMin", j["minHeartRate"].asDouble()); fill("hrMax", j["maxHeartRate"].asDouble())
            fill("rhr", (j["restingHeartRate"] ?: j["currentDayRestingHeartRate"]).asDouble())
            val resp = j["respiration"].asMap()
            if (resp != null) {
                fill("respAvg", resp["avgWakingRespirationValue"].asDouble()); fill("respMin", resp["lowestRespirationValue"].asDouble())
                fill("respMax", resp["highestRespirationValue"].asDouble())
            }
            val stress = j["allDayStress"].asMap()?.get("aggregatorList").asList()?.mapNotNull { it.asMap() }
            val total = stress?.firstOrNull { (it["type"] as? String).equals("TOTAL", true) } ?: stress?.firstOrNull()
            if (total != null) {
                fill("stressAvg", total["averageStressLevel"].asDouble()?.takeIf { it >= 0 }); fill("stressMax", total["maxStressLevel"].asDouble()?.takeIf { it >= 0 })
                val rest = total["restDuration"].asDouble() ?: 0.0; val low = total["lowDuration"].asDouble() ?: 0.0
                val med = total["mediumDuration"].asDouble() ?: 0.0; val high = total["stressDuration"].asDouble() ?: 0.0
                val sum = rest + low + med + high
                if (sum > 0 && a["stressN"] == null) {
                    a["stressN"] = sum / 180 // Garmin measures stress about every 3 min
                    a["sRest"] = rest / sum; a["sLow"] = low / sum; a["sMed"] = med / sum; a["sHigh"] = high / sum
                }
            }
            val bb = j["bodyBattery"].asMap()
            if (bb != null) {
                val stats = bb["bodyBatteryStatList"].asList()?.mapNotNull { it.asMap() } ?: emptyList()
                fun stat(name: String) = stats.firstOrNull { (it["bodyBatteryStatType"] as? String ?: "").uppercase().contains(name) }?.get("statsValue").asDouble()
                val all = stats.mapNotNull { it["statsValue"].asDouble() }.filter { it in 0.0..100.0 }
                fill("bbMax", stat("HIGH") ?: all.maxOrNull()); fill("bbMin", stat("LOW") ?: all.minOrNull())
                fill("bbUp", bb["chargedValue"].asDouble()); fill("bbDown", bb["drainedValue"].asDouble())
            }
        }

        return acc.map { (d, a) ->
            val v = a.v
            HealthDay(
                date = d,
                hrAvg = v["hrAvg"], hrMin = v["hrMin"], hrMax = v["hrMax"], hrMinutes = v["hrN"]?.toInt() ?: 0,
                restingHr = v["rhr"],
                steps = v["steps"], activeKcal = v["kcal"], imModerate = v["imMod"], imVigorous = v["imVig"],
                stressAvg = v["stressAvg"], stressMax = v["stressMax"], stressN = v["stressN"]?.toInt() ?: 0,
                stressRest = v["sRest"], stressLow = v["sLow"], stressMed = v["sMed"], stressHigh = v["sHigh"],
                bbMax = v["bbMax"], bbMin = v["bbMin"], bbCharged = v["bbUp"], bbDrained = v["bbDown"],
                hrvNight = v["hrvNight"], hrvWeekly = v["hrvWeekly"], hrvLow = v["hrvLow"], hrvHigh = v["hrvHigh"],
                hrvStatus = v["hrvStatus"]?.toInt(),
                spo2Avg = v["spo2Avg"], spo2Min = v["spo2Min"], respAvg = v["respAvg"], respMin = v["respMin"], respMax = v["respMax"],
            )
        }
    }

    /** Intraday samples of one local day (00:00-24:00). */
    fun daySeries(db: Sql, date: String, zone: ZoneId = ZoneId.systemDefault()): DaySeries {
        val d = LocalDate.parse(date)
        val a = d.atStartOfDay(zone).toEpochSecond()
        val b = d.plusDays(1).atStartOfDay(zone).toEpochSecond()
        fun metric(m: String) = db.query("SELECT ts, value FROM metric_samples WHERE metric = ? AND ts >= ? AND ts < ? ORDER BY ts", listOf(m, a, b))
            .mapNotNull { r -> r[1].num()?.let { Sample(r[0].lng()!!, it) } }
        val hr = db.query("SELECT ts, hr FROM minute_samples WHERE hr IS NOT NULL AND ts >= ? AND ts < ? ORDER BY ts", listOf(a, b))
            .map { r -> Sample(r[0].lng()!!, r[1].num()!!) }
        return DaySeries(metric("stress"), metric("body_battery"), hr, a, b)
    }
}

/** Descriptive statistics for the dashboards. */
data class Desc(val n: Int, val mean: Double, val sd: Double, val median: Double, val q1: Double, val q3: Double, val min: Double, val max: Double)

object Stat {
    fun desc(xs: List<Double>): Desc? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val mu = s.average()
        val sd = if (s.size > 1) sqrt(s.sumOf { (it - mu) * (it - mu) } / (s.size - 1)) else 0.0
        return Desc(s.size, mu, sd, quantile(s, 0.5), quantile(s, 0.25), quantile(s, 0.75), s.first(), s.last())
    }

    /** Linear interpolation quantile (type 7) of sorted values. */
    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val h = (sorted.size - 1) * q
        val lo = h.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
    }

    /** Ordinary least squares: slope per x unit and Pearson r. */
    fun regression(pts: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (pts.size < 5) return null
        val mx = pts.sumOf { it.first } / pts.size
        val my = pts.sumOf { it.second } / pts.size
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for ((x, y) in pts) { sxy += (x - mx) * (y - my); sxx += (x - mx) * (x - mx); syy += (y - my) * (y - my) }
        if (sxx == 0.0 || syy == 0.0) return null
        return sxy / sxx to sxy / sqrt(sxx * syy)
    }

    /** OLS trend with standard error of the slope (per x unit). Needs at least 8 points. */
    data class Trend(val n: Int, val slope: Double, val r: Double, val se: Double)

    fun trend(pts: List<Pair<Double, Double>>): Trend? {
        if (pts.size < 8) return null
        val mx = pts.sumOf { it.first } / pts.size
        val my = pts.sumOf { it.second } / pts.size
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for ((x, y) in pts) { sxy += (x - mx) * (y - my); sxx += (x - mx) * (x - mx); syy += (y - my) * (y - my) }
        if (sxx == 0.0 || syy == 0.0) return null
        val slope = sxy / sxx
        val resid = maxOf(0.0, syy - slope * sxy) / (pts.size - 2)
        return Trend(pts.size, slope, sxy / sqrt(sxx * syy), sqrt(resid / sxx))
    }

    /** Cohen's d with pooled SD (a − b). */
    fun cohenD(a: Desc, b: Desc): Double? {
        if (a.n < 5 || b.n < 5) return null
        val sp = sqrt(((a.n - 1) * a.sd * a.sd + (b.n - 1) * b.sd * b.sd) / (a.n + b.n - 2))
        return if (sp < 1e-9) null else (a.mean - b.mean) / sp
    }

    /** Bland-Altman: mean difference (a − b) and its SD. */
    fun blandAltman(pairs: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (pairs.size < 5) return null
        val d = pairs.map { it.first - it.second }
        val mu = d.average()
        return mu to sqrt(d.sumOf { (it - mu) * (it - mu) } / (d.size - 1))
    }

    fun pearson(pairs: List<Pair<Double, Double>>): Double? = regression(pairs)?.second

    fun rollingMean(xs: List<Double?>, window: Int): List<Double?> = xs.indices.map { i ->
        val w = (maxOf(0, i - window + 1)..i).mapNotNull { xs[it] }
        if (w.size >= (window + 1) / 2) w.average() else null
    }

    fun absMax(xs: List<Double>) = xs.maxOfOrNull { abs(it) } ?: 0.0
}
