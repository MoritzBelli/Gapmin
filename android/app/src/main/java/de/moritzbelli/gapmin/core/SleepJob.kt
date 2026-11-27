package de.moritzbelli.gapmin.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Compute `sleep_nights` from stored samples (see Sleep for the maths). */
object SleepJob {
    private const val MIN_NIGHT_S = 3 * 3600L
    private const val BASELINE_NIGHTS = 28

    data class Clock(val date: String, val minutes: Double)

    fun localClock(ts: Long, zone: ZoneId = ZoneId.systemDefault()): Clock {
        val d = Instant.ofEpochSecond(ts).atZone(zone)
        return Clock(d.toLocalDate().toString(), d.hour * 60 + d.minute + d.second / 60.0)
    }

    private fun samples(db: Sql, metric: String, a: Long, b: Long): List<Sample> =
        db.query("SELECT ts, value FROM metric_samples WHERE metric = ? AND ts BETWEEN ? AND ? ORDER BY ts", listOf(metric, a, b))
            .mapNotNull { r -> r[1].num()?.let { Sample(r[0].lng()!!, it) } }

    private fun minuteHr(db: Sql, a: Long, b: Long): List<Sample> =
        db.query("SELECT ts, hr FROM minute_samples WHERE hr IS NOT NULL AND ts BETWEEN ? AND ? ORDER BY ts", listOf(a, b))
            .map { r -> Sample(r[0].lng()!!, r[1].num()!!) }

    /** Recompute all nights whose events start at or after `fromTs` (minus a day of context). Returns count. */
    fun recompute(db: Sql, fromTs: Long = 0, zone: ZoneId = ZoneId.systemDefault()): Int {
        val start = fromTs - 36 * 3600
        val events = db.query("SELECT ts, value FROM metric_samples WHERE metric = 'sleep_stage' AND ts >= ? ORDER BY ts", listOf(start))
            .map { StageEvent(it[0].lng()!!, it[1].num()!!.toInt()) }
        val device = db.one("SELECT device_id FROM metric_samples WHERE metric = 'sleep_stage' LIMIT 1").str() ?: ""
        val sessions = Sleep.splitSessions(events).filter { it.last().ts - it.first().ts >= MIN_NIGHT_S }

        val upsert = db.prepare(
            """INSERT OR REPLACE INTO sleep_nights (
              night, device_id, bed_ts, onset_ts, wake_ts, out_ts, tib_min, tst_min, sol_min, waso_min, awakenings, awakenings_long,
              efficiency, deep_min, light_min, rem_min, awake_min, hr_avg, hr_min, hrv_avg, stress_avg, resp_avg, spo2_avg, bb_gain,
              midpoint_min, regularity_sd, score, score_duration, score_continuity, score_recovery, score_stages, score_regularity,
              score_breathing, coverage, garmin_score, details, updated_at
            ) VALUES (${List(37) { "?" }.joinToString(",")})""",
        )
        var count = 0
        try {
            db.transaction {
                for (ev in sessions) {
                    val m = Sleep.nightMetrics(ev) ?: continue
                    if (m.tstMin < 120) continue
                    val night = localClock(m.wake, zone).date

                    val p = Sleep.nightPhysiology(
                        m,
                        Sleep.PhysioInput(
                            hr = minuteHr(db, m.onset, m.wake),
                            hrDay = minuteHr(db, m.bed - 14 * 3600, m.bed - 2 * 3600),
                            hrv = samples(db, "hrv", m.bed, m.out),
                            stress = samples(db, "stress", m.onset, m.wake),
                            resp = samples(db, "respiration", m.onset, m.wake),
                            spo2 = samples(db, "spo2", m.bed, m.out),
                            bb = samples(db, "body_battery", m.bed - 3600, m.out + 3600),
                        ),
                    )

                    // Personal baselines from the previous nights (computed earlier in this loop or in past imports)
                    val prev = db.query(
                        "SELECT hrv_avg, hr_avg, resp_avg, onset_ts, wake_ts FROM sleep_nights WHERE night < ? ORDER BY night DESC LIMIT $BASELINE_NIGHTS",
                        listOf(night),
                    )
                    val lnHrv = prev.mapNotNull { it[0].num() }.filter { it > 0 }.map { ln(it) }
                    fun stat(xs: List<Double>): Pair<Double?, Double?> {
                        if (xs.isEmpty()) return null to null
                        val mu = xs.average()
                        return mu to sqrt(xs.sumOf { (it - mu) * (it - mu) } / maxOf(1, xs.size - 1))
                    }
                    val hrv = stat(lnHrv)
                    val b = Baselines(
                        nights = lnHrv.size,
                        lnHrvMean = hrv.first, lnHrvSd = hrv.second,
                        hrMean = stat(prev.mapNotNull { it[1].num() }).first,
                        respMean = stat(prev.mapNotNull { it[2].num() }).first,
                    )

                    val last6 = prev.take(6)
                    val onsets = (listOf(m.onset) + last6.mapNotNull { it[3].lng() }).map { localClock(it, zone).minutes }
                    val wakes = (listOf(m.wake) + last6.mapNotNull { it[4].lng() }).map { localClock(it, zone).minutes }
                    val so = Sleep.clockSpread(onsets)
                    val sw = Sleep.clockSpread(wakes)
                    val spread = if (so != null && sw != null) (so + sw) / 2 else null
                    var mid = localClock(m.onset + (m.wake - m.onset) / 2, zone).minutes
                    if (mid > 720) mid -= 1440 // e.g. 23:30 → -30

                    val r = Sleep.scoreNight(m, p, b, Regularity(onsets.size, spread, mid))
                    val garmin = samples(db, "garmin_sleep_score", m.wake - 3 * 3600, m.wake + 8 * 3600).firstOrNull()?.value

                    val details = linkedMapOf(
                        "hypnogram" to m.segments.map {
                            listOf(((it.start - m.bed) / 60.0).roundToLong(), ((it.end - it.start) / 60.0).roundToLong(), it.stage)
                        },
                        "remLatencyMin" to m.remLatencyMin,
                        "deepFirstHalfShare" to m.deepFirstHalfShare,
                        "remSecondHalfShare" to m.remSecondHalfShare,
                        "physiology" to linkedMapOf(
                            "hrAvg" to p.hrAvg, "hrMin" to p.hrMin, "hrMinTs" to p.hrMinTs, "hrDayAvg" to p.hrDayAvg, "hrDip" to p.hrDip,
                            "hrvAvg" to p.hrvAvg, "hrvMax" to p.hrvMax, "stressAvg" to p.stressAvg,
                            "respAvg" to p.respAvg, "respMin" to p.respMin, "respMax" to p.respMax,
                            "spo2Avg" to p.spo2Avg, "spo2Min" to p.spo2Min, "spo2Below90" to p.spo2Below90, "bbGain" to p.bbGain,
                        ),
                        "baselines" to linkedMapOf(
                            "nights" to b.nights, "lnHrvMean" to b.lnHrvMean, "lnHrvSd" to b.lnHrvSd,
                            "hrMean" to b.hrMean, "respMean" to b.respMean,
                        ),
                        "factors" to r.factors,
                        "label" to r.label,
                        "provisional" to r.provisional,
                    )
                    val c = r.components
                    upsert.run(
                        listOf(
                            night, device, m.bed, m.onset, m.wake, m.out, m.tibMin, m.tstMin, m.solMin, m.wasoMin, m.awakenings, m.awakeningsLong,
                            m.efficiency, m.deepMin, m.lightMin, m.remMin, m.awakeMin, p.hrAvg, p.hrMin, p.hrvAvg, p.stressAvg, p.respAvg, p.spo2Avg,
                            p.bbGain, mid, spread, r.score, c[Component.DURATION], c[Component.CONTINUITY], c[Component.RECOVERY],
                            c[Component.STAGES], c[Component.REGULARITY], c[Component.BREATHING], r.coverage, garmin?.roundToLong(), Json.write(details),
                            System.currentTimeMillis() / 1000,
                        ),
                    )
                    count++
                }
            }
        } finally {
            upsert.close()
        }
        return count
    }

    private fun hypnogramOf(segs: List<Segment>, bed: Long): List<List<Long>> =
        segs.map { listOf(((it.start - bed) / 60.0).roundToLong(), ((it.end - it.start) / 60.0).roundToLong(), it.stage.toLong()) }

    /**
     * Garmin Connect `sleepLevels` ({startGMT, endGMT, activityLevel}) → stage segments. Garmin numbers the levels
     * 0 deep, 1 light, 2 REM, 3 awake; because that is not documented, the mapping is checked against the night's own
     * stage totals and the permutation that fits them best wins (default order on ties).
     */
    fun parseGarminLevels(levels: List<Any?>?, deepS: Double, lightS: Double, remS: Double, awakeS: Double): List<Segment> {
        if (levels == null) return emptyList()
        val raw = levels.mapNotNull { v ->
            val m = v.asMap() ?: return@mapNotNull null
            val a = GarminExportImport.isoToS(m["startGMT"]) ?: return@mapNotNull null
            val b = GarminExportImport.isoToS(m["endGMT"]) ?: return@mapNotNull null
            val lv = m["activityLevel"].asDouble()?.roundToLong()?.toInt() ?: return@mapNotNull null
            if (b <= a) null else Triple(a, b, lv)
        }.sortedBy { it.first }
        if (raw.size < 2) return emptyList()
        val sec = HashMap<Int, Double>()
        for ((a, b, lv) in raw) sec[lv] = (sec[lv] ?: 0.0) + (b - a)
        val keys = sec.keys.sorted()
        val stages = listOf(Stage.DEEP, Stage.LIGHT, Stage.REM, Stage.AWAKE)
        val totals = mapOf(Stage.DEEP to deepS, Stage.LIGHT to lightS, Stage.REM to remS, Stage.AWAKE to awakeS)
        val default = mapOf(0 to Stage.DEEP, 1 to Stage.LIGHT, 2 to Stage.REM, 3 to Stage.AWAKE)
        fun err(map: Map<Int, Int>): Double {
            val got = HashMap<Int, Double>()
            for ((lv, s) in sec) map[lv]?.let { got[it] = (got[it] ?: 0.0) + s }
            return stages.sumOf { abs((got[it] ?: 0.0) - (totals[it] ?: 0.0)) }
        }
        var best = default
        if (keys.size == 4 && keys.all { it in 0..3 } && err(default) > 0.15 * (deepS + lightS + remS + awakeS)) {
            fun perms(l: List<Int>): List<List<Int>> = if (l.size <= 1) listOf(l) else l.flatMap { x -> perms(l - x).map { listOf(x) + it } }
            var bestErr = err(default)
            for (p in perms(stages)) {
                val m = keys.zip(p).toMap()
                val e = err(m)
                if (e < bestErr) { bestErr = e; best = m }
            }
        }
        val out = ArrayList<Segment>()
        for ((a, b, lv) in raw) {
            val st = best[lv] ?: continue
            val last = out.lastOrNull()
            if (last != null && last.stage == st && last.end == a) last.end = b else out.add(Segment(a, b, st))
        }
        return out
    }

    /**
     * Nights imported from a Garmin export before the timeline was read have an empty hypnogram. The raw day JSON is still in
     * daily_summary, so the timeline is filled in afterwards without a new import. Returns the number of nights updated.
     */
    fun backfillGarminHypnograms(db: Sql): Int {
        val rows = db.query("SELECT night, details FROM sleep_nights WHERE device_id = ?", listOf(GarminExport.DEVICE))
        var n = 0
        db.transaction {
            for (r in rows) {
                val night = r[0].str() ?: continue
                val details = Json.parse(r[1].str() ?: continue).asMap()?.toMutableMap() ?: continue
                if ((details["hypnogram"].asList()?.size ?: 0) > 0) continue
                val raw = db.one("SELECT data FROM daily_summary WHERE date = ? AND kind = 'garmin_sleep'", listOf(night)).str() ?: continue
                val d = Json.parse(raw).asMap() ?: continue
                val start = GarminExportImport.isoToS(d["sleepStartTimestampGMT"]) ?: continue
                val segs = parseGarminLevels(
                    d["sleepLevels"].asList(),
                    d["deepSleepSeconds"].asDouble() ?: 0.0, d["lightSleepSeconds"].asDouble() ?: 0.0,
                    d["remSleepSeconds"].asDouble() ?: 0.0, d["awakeSleepSeconds"].asDouble() ?: 0.0,
                )
                if (segs.isEmpty()) continue
                details["hypnogram"] = hypnogramOf(segs, start)
                db.exec("UPDATE sleep_nights SET details = ? WHERE night = ? AND device_id = ?", listOf(Json.write(details), night, GarminExport.DEVICE))
                n++
            }
        }
        return n
    }

    /** One night of a data export that only has totals (no stage timeline), e.g. Garmin Connect. */
    class Summary(
        val night: String, val startTs: Long, val endTs: Long,
        val deepS: Double, val lightS: Double, val remS: Double, val awakeS: Double, val awakeCount: Int,
        val stress: Double?, val resp: Double?, val respMin: Double?, val respMax: Double?,
        val hrv: Double?, val garminScore: Double?,
        /** Stage timeline (Garmin `sleepLevels`), empty if the export has none. */
        val levels: List<Segment> = emptyList(),
    )

    /**
     * Scores nights from totals with the same model as [recompute]. Without a timeline the onset is taken as the start of
     * the sleep window, long awakenings are estimated as at most one per 5 min of waking time, and there is no hypnogram.
     * A night that already exists from another device (real stage data) is left alone. Returns the number of nights written.
     */
    fun importSummaries(db: Sql, items: List<Summary>, device: String, zone: ZoneId = ZoneId.systemDefault()): Int {
        val upsert = db.prepare(
            """INSERT OR REPLACE INTO sleep_nights (
              night, device_id, bed_ts, onset_ts, wake_ts, out_ts, tib_min, tst_min, sol_min, waso_min, awakenings, awakenings_long,
              efficiency, deep_min, light_min, rem_min, awake_min, hr_avg, hr_min, hrv_avg, stress_avg, resp_avg, spo2_avg, bb_gain,
              midpoint_min, regularity_sd, score, score_duration, score_continuity, score_recovery, score_stages, score_regularity,
              score_breathing, coverage, garmin_score, details, updated_at
            ) VALUES (${List(37) { "?" }.joinToString(",")})""",
        )
        var count = 0
        try {
            db.transaction {
                for (x in items.sortedBy { it.night }) {
                    val existing = db.one("SELECT device_id FROM sleep_nights WHERE night = ?", listOf(x.night)).str()
                    if (existing != null && existing != device) continue
                    val deepMin = x.deepS / 60; val lightMin = x.lightS / 60; val remMin = x.remS / 60; val awakeMin = x.awakeS / 60
                    val tst = deepMin + lightMin + remMin
                    if (tst < 120 || x.endTs <= x.startTs) continue
                    val tib = maxOf((x.endTs - x.startTs) / 60.0, tst + awakeMin)
                    val awakeningsLong = if (x.awakeCount > 0) minOf(x.awakeCount, (awakeMin / 5).toInt()) else 0
                    val m = NightMetrics(
                        bed = x.startTs, onset = x.startTs, wake = x.endTs, out = x.endTs,
                        tibMin = tib, tstMin = tst, solMin = 0.0, wasoMin = awakeMin,
                        awakenings = x.awakeCount, awakeningsLong = awakeningsLong, efficiency = tst / tib,
                        deepMin = deepMin, lightMin = lightMin, remMin = remMin, awakeMin = awakeMin,
                        remLatencyMin = null, deepFirstHalfShare = null, remSecondHalfShare = null, segments = x.levels,
                    )
                    val p = NightPhysiology(
                        hrAvg = null, hrMin = null, hrMinTs = null, hrDayAvg = null, hrDip = null,
                        hrvAvg = x.hrv, hrvMax = null, stressAvg = x.stress,
                        respAvg = x.resp, respMin = x.respMin, respMax = x.respMax,
                        spo2Avg = null, spo2Min = null, spo2Below90 = null, bbGain = null,
                    )
                    val prev = db.query(
                        "SELECT hrv_avg, hr_avg, resp_avg, onset_ts, wake_ts FROM sleep_nights WHERE night < ? ORDER BY night DESC LIMIT $BASELINE_NIGHTS",
                        listOf(x.night),
                    )
                    fun stat(xs: List<Double>): Pair<Double?, Double?> {
                        if (xs.isEmpty()) return null to null
                        val mu = xs.average()
                        return mu to sqrt(xs.sumOf { (it - mu) * (it - mu) } / maxOf(1, xs.size - 1))
                    }
                    val lnHrv = prev.mapNotNull { it[0].num() }.filter { it > 0 }.map { ln(it) }
                    val hrv = stat(lnHrv)
                    val b = Baselines(
                        nights = lnHrv.size, lnHrvMean = hrv.first, lnHrvSd = hrv.second,
                        hrMean = stat(prev.mapNotNull { it[1].num() }).first, respMean = stat(prev.mapNotNull { it[2].num() }).first,
                    )
                    val onsets = (listOf(m.onset) + prev.take(6).mapNotNull { it[3].lng() }).map { localClock(it, zone).minutes }
                    val wakes = (listOf(m.wake) + prev.take(6).mapNotNull { it[4].lng() }).map { localClock(it, zone).minutes }
                    val so = Sleep.clockSpread(onsets)
                    val sw = Sleep.clockSpread(wakes)
                    val spread = if (so != null && sw != null) (so + sw) / 2 else null
                    var mid = localClock(m.onset + (m.wake - m.onset) / 2, zone).minutes
                    if (mid > 720) mid -= 1440

                    val r = Sleep.scoreNight(m, p, b, Regularity(onsets.size, spread, mid))
                    val details = linkedMapOf(
                        "hypnogram" to hypnogramOf(x.levels, x.startTs),
                        "physiology" to linkedMapOf(
                            "hrvAvg" to p.hrvAvg, "stressAvg" to p.stressAvg, "respAvg" to p.respAvg, "respMin" to p.respMin, "respMax" to p.respMax,
                        ),
                        "baselines" to linkedMapOf("nights" to b.nights, "lnHrvMean" to b.lnHrvMean, "lnHrvSd" to b.lnHrvSd, "hrMean" to b.hrMean, "respMean" to b.respMean),
                        "factors" to r.factors,
                        "label" to r.label,
                        "provisional" to r.provisional,
                        "source" to "summary",
                    )
                    val c = r.components
                    upsert.run(
                        listOf(
                            x.night, device, m.bed, m.onset, m.wake, m.out, m.tibMin, m.tstMin, m.solMin, m.wasoMin, m.awakenings, m.awakeningsLong,
                            m.efficiency, m.deepMin, m.lightMin, m.remMin, m.awakeMin, null, null, p.hrvAvg, p.stressAvg, p.respAvg, null,
                            null, mid, spread, r.score, c[Component.DURATION], c[Component.CONTINUITY], c[Component.RECOVERY],
                            c[Component.STAGES], c[Component.REGULARITY], c[Component.BREATHING], r.coverage, x.garminScore?.roundToLong(), Json.write(details),
                            System.currentTimeMillis() / 1000,
                        ),
                    )
                    count++
                }
            }
        } finally {
            upsert.close()
        }
        return count
    }
}
