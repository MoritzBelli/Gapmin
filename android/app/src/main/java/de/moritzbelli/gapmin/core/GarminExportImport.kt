package de.moritzbelli.gapmin.core

import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Import of the data export from Garmin Connect ("Export Ihrer Daten") straight from the ZIP Garmin sends.
 *
 * Activities come from DI_CONNECT/DI-Connect-Fitness/*summarizedActivities*.json, their tracks from the .fit files in the
 * nested UploadedFiles_*.zip (matched by start time). Daily values (resting HR, Garmin sleep score, HRV) go into the same
 * tables a Gadgetbridge import fills; every daily record is also kept as raw JSON in `daily_summary`.
 * The export has no sleep-stage or stress/Body-Battery curves, so no Gapmin sleep score is computed for these nights.
 *
 * JSON files are streamed element by element and FIT files are read one at a time, so a multi-GB export stays within
 * phone memory. Re-importing is idempotent (everything is upserted).
 */
object GarminExport {
    const val DEVICE = "GARMIN-EXPORT"

    fun isExport(names: Collection<String>) = names.any { it.contains("summarizedActivities", true) && it.endsWith(".json", true) }
}

class GarminExportImport(private val db: Sql) {
    private companion object {
        const val TOLERANCE_S = 120L
        val DEFAULT_SCALES = mapOf("distance" to 100.0, "duration" to 1000.0, "elevation" to 100.0)
        val KINDS = mapOf(
            "running" to 0x10L, "street_running" to 0x10L, "track_running" to 0x10L, "trail_running" to 0x040000F2L,
            "treadmill_running" to 0x100L, "indoor_running" to 0x100L, "walking" to 0x20L, "casual_walking" to 0x20L,
            "speed_walking" to 0x20L, "cycling" to 0x80L, "road_biking" to 0x80L, "mountain_biking" to 0x80L,
            "gravel_cycling" to 0x80L, "indoor_cycling" to 0x800L, "virtual_ride" to 0x800L,
        )

        // file name (without folders) pattern -> kind in daily_summary
        val DAILY = listOf(
            Regex("UDSFile_.*\\.json", RegexOption.IGNORE_CASE) to "garmin_uds",
            Regex(".*sleepData\\.json", RegexOption.IGNORE_CASE) to "garmin_sleep",
            Regex("TrainingReadinessDTO_.*\\.json", RegexOption.IGNORE_CASE) to "garmin_readiness",
            Regex(".*healthStatusData\\.json", RegexOption.IGNORE_CASE) to "garmin_health",
            Regex("EnduranceScore_.*\\.json", RegexOption.IGNORE_CASE) to "garmin_endurance",
            Regex("HillScore_.*\\.json", RegexOption.IGNORE_CASE) to "garmin_hill",
            Regex("MetricsAcuteTrainingLoad_.*\\.json", RegexOption.IGNORE_CASE) to "garmin_load",
            Regex("TrainingHistory_.*\\.json", RegexOption.IGNORE_CASE) to "garmin_training_status",
            Regex("MetricsMaxMetData_.*\\.json", RegexOption.IGNORE_CASE) to "garmin_vo2max",
            Regex("RunRacePredictions_.*\\.json", RegexOption.IGNORE_CASE) to "garmin_race",
            Regex("MetricsHeatAltitudeAcclimation_.*\\.json", RegexOption.IGNORE_CASE) to "garmin_acclimation",
        )

        fun baseName(n: String) = n.substringAfterLast('/')

        fun pow10(ratio: Double) = 10.0.pow(log10(ratio).roundToInt())

        fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        /** Epoch in s or ms, or ISO string ('2025-05-01T06:30:00.0', optional zone) -> unix seconds. */
        fun isoToS(v: Any?): Long? {
            if (v is Number) {
                val x = v.toDouble()
                return (if (x > 1e11) x / 1000 else x).toLong()
            }
            val s = (v as? String)?.trim()?.replace(Regex("\\.\\d+"), "") ?: return null
            return runCatching {
                if (s.endsWith("Z") || Regex(".*[+-]\\d{2}:\\d{2}$").matches(s)) OffsetDateTime.parse(s).toEpochSecond()
                else LocalDateTime.parse(s.replace(' ', 'T')).toEpochSecond(ZoneOffset.UTC)
            }.getOrNull()
        }

        /** calendarDate comes as 'YYYY-MM-DD' text or as number (epoch s/ms or YYYYMMDD). */
        fun calDate(v: Any?): String? {
            if (v is String) {
                if (Regex("\\d{4}-\\d{2}-\\d{2}.*").matches(v)) return v.take(10)
                return v.toDoubleOrNull()?.let { calDate(it) }
            }
            val x = (v as? Number)?.toDouble() ?: return null
            if (x > 10_000_000 && x < 100_000_000) {
                val t = x.toLong().toString()
                return "${t.substring(0, 4)}-${t.substring(4, 6)}-${t.substring(6)}"
            }
            val sec = (if (x > 1e11) x / 1000 else x).toLong()
            return Instant.ofEpochSecond(sec).atOffset(ZoneOffset.UTC).toLocalDate().toString()
        }

        fun noonUtc(date: String): Long? = runCatching { java.time.LocalDate.parse(date.take(10)).atTime(12, 0).toEpochSecond(ZoneOffset.UTC) }.getOrNull()

        fun hrvStatus(s: String?): Long? {
            val u = (s ?: "").uppercase()
            return when {
                "POOR" in u -> 1L
                "UNBALANCED" in u || "OUT_OF_RANGE" in u || "ABOVE" in u -> 3L
                "LOW" in u || "BELOW" in u -> 2L
                "IN_RANGE" in u || "BALANCED" in u || "NORMAL" in u -> 4L
                else -> null
            }
        }
    }

    private class Act(val startS: Long, val m: Map<String, Any?>) {
        fun n(k: String) = m[k].asDouble()
    }

    private class Derived(val distM: Double?, val durS: Double?, val ascentM: Double?, val preview: String?, val paceCv: Double?, val avgHr: Double?, val maxHr: Double?)

    fun run(zip: ZipFile, onProgress: (ImportProgress) -> Unit, t0: Long): ImportResult {
        val entries = zip.entries().toList().filter { !it.isDirectory }
        db.exec("INSERT OR REPLACE INTO devices (id, name, manufacturer, model) VALUES (?,?,?,?)", listOf(GarminExport.DEVICE, "Garmin (Export)", "Garmin", null))

        // ---- activities ----------------------------------------------------
        onProgress(ImportProgress("Aktivitäten lesen", 0.03f))
        val acts = ArrayList<Act>()
        for (e in entries.filter { it.name.contains("summarizedActivities", true) && it.name.endsWith(".json", true) }) {
            // normally [ { "summarizedActivitiesExport": [ {activity}, ... ] } ] (depth 3); a flat list of activities is depth 1
            for (depth in intArrayOf(3, 1)) {
                val before = acts.size
                zip.getInputStream(e).use { s ->
                    Json.forEachAt(InputStreamReader(s, Charsets.UTF_8).buffered(), depth) { v ->
                        val m = v.asMap() ?: return@forEachAt
                        val start = (m["startTimeGmt"] ?: m["beginTimestamp"]).asDouble() ?: return@forEachAt
                        // drop the bulky per-split lists, they are not used
                        val slim = m.filterKeys { it != "splits" && it != "splitSummaries" }
                        acts.add(Act((if (start > 1e11) start / 1000 else start).toLong(), slim))
                    }
                }
                if (acts.size > before) break
            }
        }
        acts.sortBy { it.startS }
        val starts = acts.map { it.startS }

        // ---- tracks (.fit) matched by start time --------------------------
        val derived = HashMap<Int, Derived>()
        val point = db.prepare("INSERT OR REPLACE INTO track_points VALUES (?,?,?,?,?,?,?,?,?)")
        try {
            fun handleFit(bytes: ByteArray) {
                val pts = Tracks.parseFit(bytes)
                if (pts.isEmpty()) return
                val t = pts.first().ts
                val k = starts.binarySearch(t).let { if (it >= 0) it else -it - 1 }
                var best = -1
                var bestD = Long.MAX_VALUE
                for (j in (k - 2)..(k + 1)) {
                    if (j !in acts.indices || j in derived) continue
                    val dlt = abs(starts[j] - t)
                    if (dlt <= TOLERANCE_S && dlt < bestD) { best = j; bestD = dlt }
                }
                if (best < 0) return
                val id = "${GarminExport.DEVICE}:${starts[best]}"
                val hrs = pts.mapNotNull { it.hr?.takeIf { h -> h > 0 } }
                val preview = Tracks.routePreview(pts)
                db.transaction {
                    db.exec("DELETE FROM track_points WHERE activity_id = ?", listOf(id))
                    for (p in pts) point.run(listOf(id, p.ts, p.lat, p.lon, p.ele, p.hr?.toLong(), p.cadence?.toLong(), p.speed, p.dist))
                }
                derived[best] = Derived(
                    distM = pts.lastOrNull { it.dist != null }?.dist,
                    durS = (pts.last().ts - pts.first().ts).toDouble().takeIf { it > 0 },
                    ascentM = Tracks.ascent(pts),
                    preview = if (preview.isEmpty()) null else Json.write(preview.map { listOf(it.lat, it.lon) }),
                    paceCv = Tracks.paceVariation(pts),
                    avgHr = hrs.takeIf { it.isNotEmpty() }?.average(),
                    maxHr = hrs.maxOrNull(),
                )
            }

            var seen = 0
            fun scan(name: String, open: () -> java.io.InputStream) {
                if (name.endsWith(".fit", true)) {
                    handleFit(open().use { it.readBytes() })
                    return
                }
                open().use { raw ->
                    ZipInputStream(raw.buffered()).use { zis ->
                        while (true) {
                            val ze: ZipEntry = zis.nextEntry ?: break
                            if (ze.isDirectory || !ze.name.endsWith(".fit", true)) continue
                            handleFit(zis.readBytes())
                            if (++seen % 25 == 0) onProgress(ImportProgress("Strecken lesen ($seen)", 0.05f + 0.6f * minOf(1f, derived.size / maxOf(1f, acts.size.toFloat()))))
                        }
                    }
                }
            }
            for (e in entries) {
                val n = baseName(e.name)
                if (n.endsWith(".fit", true) || (n.endsWith(".zip", true) && n.contains("Uploaded", true))) scan(n) { zip.getInputStream(e) }
            }
        } finally {
            point.close()
        }

        // ---- units of the JSON summaries, measured against the .fit tracks ----
        val ratios = mapOf("distance" to ArrayList<Double>(), "duration" to ArrayList<Double>(), "elevation" to ArrayList<Double>())
        for ((i, d) in derived.entries.sortedBy { it.key }.take(60)) {
            val a = acts[i]
            fun add(k: String, json: Double?, fit: Double?) { if (json != null && fit != null && json > 0 && fit > 0) ratios[k]!!.add(json / fit) }
            add("distance", a.n("distance"), d.distM)
            add("duration", a.n("duration"), d.durS)
            add("elevation", a.n("elevationGain"), d.ascentM)
        }
        val scale = ratios.mapValues { (k, v) -> if (v.size >= 3) pow10(median(v)) else DEFAULT_SCALES[k]!! }

        // ---- write activities ---------------------------------------------
        onProgress(ImportProgress("Aktivitäten speichern", 0.7f))
        val upsert = db.prepare(
            """INSERT INTO activities (id, device_id, kind, sport, name, start_ts, end_ts, duration_s, distance_m, avg_hr, max_hr, min_hr,
               avg_pace_s_km, ascent_m, descent_m, calories, avg_cadence, te_aerobic, te_anaerobic, recovery_h, summary_json, updated_at)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
             ON CONFLICT(id) DO UPDATE SET kind=excluded.kind, sport=excluded.sport, name=COALESCE(excluded.name, activities.name),
               end_ts=excluded.end_ts, duration_s=excluded.duration_s, distance_m=excluded.distance_m, avg_hr=excluded.avg_hr,
               max_hr=excluded.max_hr, min_hr=excluded.min_hr, avg_pace_s_km=excluded.avg_pace_s_km,
               ascent_m=COALESCE(excluded.ascent_m, activities.ascent_m), descent_m=excluded.descent_m, calories=excluded.calories,
               avg_cadence=excluded.avg_cadence, te_aerobic=excluded.te_aerobic, te_anaerobic=excluded.te_anaerobic,
               recovery_h=excluded.recovery_h, summary_json=excluded.summary_json, updated_at=excluded.updated_at""",
        )
        var tracks = 0
        try {
            db.transaction {
                for ((i, a) in acts.withIndex()) {
                    val d = derived[i]
                    val dist = a.n("distance")?.div(scale["distance"]!!) ?: d?.distM
                    val dur = a.n("duration")?.div(scale["duration"]!!) ?: d?.durS
                    val elapsed = a.n("elapsedDuration")?.div(scale["duration"]!!) ?: dur
                    val ascent = a.n("elevationGain")?.div(scale["elevation"]!!) ?: d?.ascentM
                    val descent = a.n("elevationLoss")?.div(scale["elevation"]!!)
                    val kind = KINDS[((a.m["activityType"] ?: a.m["sportType"]) as? String ?: "").lowercase()] ?: 0L
                    val cad = a.n("avgDoubleCadence") ?: a.n("avgRunCadence")?.times(2)
                    fun te(k: String) = a.n(k)?.let { if (it > 10) it / 10 else it }
                    val pace = if (dist != null && dur != null && dist > 0 && dur > 0) dur / (dist / 1000) else null
                    val summary = LinkedHashMap<String, Any?>()
                    fun put(k: String, v: Double?, unit: String) { if (v != null) summary[k] = mapOf("value" to v, "unit" to unit) }
                    put("distanceMeters", dist, "meters"); put("activeSeconds", dur, "seconds")
                    put("averageHR", a.n("avgHr"), "bpm"); put("maxHR", a.n("maxHr"), "bpm"); put("minHR", a.n("minHr"), "bpm")
                    put("ascentMeters", ascent, "meters"); put("descentMeters", descent, "meters")
                    put("caloriesBurnt", a.n("calories"), "kcal"); put("averageCadence", cad, "spm")
                    put("aerobicTrainingEffect", te("aerobicTrainingEffect"), ""); put("anaerobicTrainingEffect", te("anaerobicTrainingEffect"), "")
                    val id = "${GarminExport.DEVICE}:${a.startS}"
                    upsert.run(
                        listOf(
                            id, GarminExport.DEVICE, kind, Runs.sportOf(kind), null, a.startS, a.startS + (elapsed ?: 0.0).toLong(), dur, dist,
                            a.n("avgHr") ?: d?.avgHr, a.n("maxHr") ?: d?.maxHr, a.n("minHr"), pace, ascent, descent,
                            a.n("calories"), cad, te("aerobicTrainingEffect"), te("anaerobicTrainingEffect"), null,
                            Json.write(summary), System.currentTimeMillis() / 1000,
                        ),
                    )
                    if (d != null) {
                        db.exec("UPDATE activities SET has_track = 1, route_preview = ?, pace_cv = ? WHERE id = ?", listOf(d.preview, d.paceCv, id))
                        tracks++
                    }
                }
            }
        } finally {
            upsert.close()
        }
        classifyAllRuns(db)

        // ---- daily values --------------------------------------------------
        onProgress(ImportProgress("Tageswerte", 0.8f))
        var samples = 0
        val metric = db.prepare("INSERT OR REPLACE INTO metric_samples (metric, device_id, ts, value) VALUES (?,?,?,?)")
        val daily = db.prepare("INSERT OR REPLACE INTO daily_summary (date, kind, data) VALUES (?,?,?)")
        val hrv = HashMap<String, MutableMap<String, Any?>>()
        try {
            db.transaction {
                for ((pattern, kind) in DAILY) {
                    for (e in entries.filter { pattern.matches(baseName(it.name)) }) {
                        zip.getInputStream(e).use { s ->
                            Json.forEachAt(InputStreamReader(s, Charsets.UTF_8).buffered(), 1) { v ->
                                val d = v.asMap() ?: return@forEachAt
                                val day = calDate(d["calendarDate"]) ?: return@forEachAt
                                var k = kind
                                if (kind == "garmin_vo2max") (d["sport"] as? String)?.let { k = "${kind}_${it.lowercase()}" }
                                daily.run(listOf(day, k, Json.write(d)))
                                samples++
                                when (kind) {
                                    "garmin_uds" -> {
                                        val hr = (d["restingHeartRate"] ?: d["currentDayRestingHeartRate"]).asDouble()
                                        val ts = d["restingHeartRateTimestamp"].asDouble()?.let { isoToS(it) } ?: noonUtc(day)
                                        if (hr != null && hr > 20 && hr < 150 && ts != null) metric.run(listOf("resting_hr", GarminExport.DEVICE, ts, hr))
                                    }
                                    "garmin_sleep" -> {
                                        val score = d["sleepScores"].asMap()?.get("overallScore").asDouble()
                                        val ts = isoToS(d["sleepEndTimestampGMT"])
                                        if (score != null && score > 0 && score <= 100 && ts != null) metric.run(listOf("garmin_sleep_score", GarminExport.DEVICE, ts, score))
                                    }
                                    "garmin_readiness" -> d["hrvWeeklyAverage"].asDouble()?.let { hrv.getOrPut(day) { HashMap() }["weekly"] = it }
                                    "garmin_health" -> d["metrics"].asList()?.forEach { mm ->
                                        val m = mm.asMap() ?: return@forEach
                                        if (!((m["type"] as? String) ?: "").uppercase().contains("HRV")) return@forEach
                                        hrv.getOrPut(day) { HashMap() }.apply {
                                            put("last", m["value"].asDouble()); put("low", m["baselineLowerLimit"].asDouble())
                                            put("high", m["baselineUpperLimit"].asDouble()); put("status", hrvStatus(m["status"] as? String))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                for ((day, r) in hrv) {
                    val ts = noonUtc(day) ?: continue
                    db.exec(
                        "INSERT OR REPLACE INTO hrv_summary (device_id, ts, weekly_avg, last_night_avg, last_night_5min_high, baseline_low_upper, baseline_balanced_lower, baseline_balanced_upper, status) VALUES (?,?,?,?,?,?,?,?,?)",
                        listOf(GarminExport.DEVICE, ts, (r["weekly"] as? Double)?.roundToInt(), (r["last"] as? Double)?.roundToInt(), null, null, (r["low"] as? Double)?.roundToInt(), (r["high"] as? Double)?.roundToInt(), r["status"]),
                    )
                }
            }
        } finally {
            metric.close()
            daily.close()
        }
        db.exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('last_import', ?)", listOf((System.currentTimeMillis() / 1000).toString()))
        onProgress(ImportProgress("Fertig", 1f))
        return ImportResult(acts.size, tracks, samples + acts.size, 0, System.currentTimeMillis() - t0)
    }
}
