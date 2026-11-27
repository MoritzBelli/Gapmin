package de.moritzbelli.gapmin.core

import java.io.File
import java.util.zip.ZipFile

/**
 * Import a Gadgetbridge export (ZIP backup or the bare "Gadgetbridge" SQLite file)
 * into the app database. Incremental and idempotent: every source table keeps a
 * watermark in `meta`, rows are upserted, tracks are parsed once.
 *
 * Table/column names follow Gadgetbridge's GBDaoGenerator (greenDAO); lookups are
 * case/underscore-insensitive so small naming differences between versions don't break.
 */
data class ImportProgress(val step: String, val fraction: Float)
data class ImportResult(val activities: Int, val tracks: Int, val samples: Int, val nights: Int, val ms: Long)

class GadgetbridgeImport(
    private val db: Sql,
    /** Opens a SQLite file read-only. */
    private val openSqlite: (File) -> Sql,
    private val tmpDir: File,
) {
    private companion object {
        const val MARGIN_S = 2 * 86400L // re-read the last two days so late-synced samples are not missed
        val DB_NAME = Regex("(^|/)(gadgetbridge(\\.db)?|[^/]+\\.(db|sqlite))$", RegexOption.IGNORE_CASE)
        fun norm(s: String) = s.replace("_", "").lowercase()
        fun isSqlite(b: ByteArray) = b.size >= 15 && String(b, 0, 15, Charsets.ISO_8859_1) == "SQLite format 3"
        fun head(f: File, n: Int = 16): ByteArray = f.inputStream().use { s -> ByteArray(n).let { b -> val r = s.read(b); if (r < n) b.copyOf(maxOf(r, 0)) else b } }
    }

    fun run(export: File, onProgress: (ImportProgress) -> Unit = {}): ImportResult {
        val t0 = System.currentTimeMillis()
        onProgress(ImportProgress("Export lesen", 0.02f))
        tmpDir.mkdirs()
        val h = head(export)
        var zip: ZipFile? = null
        var dbFile: File? = null
        var lookup: (String?) -> ByteArray? = { null }
        try {
            if (h.size >= 2 && h[0] == 0x50.toByte() && h[1] == 0x4b.toByte()) {
                val z = ZipFile(export)
                zip = z
                val files = z.entries().toList().filter { !it.isDirectory }
                // Data export from Garmin Connect (not a Gadgetbridge backup)
                if (GarminExport.isExport(files.map { it.name })) return GarminExportImport(db).run(z, onProgress, t0)
                // Database: prefer the canonical name, otherwise sniff the SQLite header
                val candidates = files.filter { DB_NAME.containsMatchIn(it.name) }
                    .sortedByDescending { Regex("gadgetbridge$", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                for (e in candidates) {
                    val out = File(tmpDir, "gb-import.sqlite")
                    z.getInputStream(e).use { inp -> out.outputStream().use { inp.copyTo(it) } }
                    if (isSqlite(head(out))) { dbFile = out; break }
                }
                val byRel = HashMap<String, java.util.zip.ZipEntry>()
                val byBase = HashMap<String, java.util.zip.ZipEntry>()
                for (e in files) {
                    byRel[e.name.replace(Regex("^.*?files/"), "")] = e
                    byBase[e.name.substringAfterLast('/')] = e
                }
                lookup = { phonePath ->
                    if (phonePath.isNullOrEmpty()) null
                    else {
                        val rel = phonePath.replace(Regex("^.*?/files/"), "")
                        val e = byRel[rel] ?: byBase[phonePath.substringAfterLast('/')]
                        e?.let { z.getInputStream(it).use { s -> s.readBytes() } }
                    }
                }
            } else if (isSqlite(h)) {
                dbFile = export
            }
            val f = dbFile ?: throw IllegalArgumentException("Keine Gadgetbridge-Datenbank im Export gefunden")
            val gb = openSqlite(f)
            try {
                return importFrom(gb, lookup, onProgress, t0)
            } finally {
                gb.close()
            }
        } finally {
            zip?.close()
            if (dbFile != null && dbFile != export) dbFile.delete()
        }
    }

    private fun importFrom(gb: Sql, lookup: (String?) -> ByteArray?, onProgress: (ImportProgress) -> Unit, t0: Long): ImportResult {
        val tables = gb.query("SELECT name FROM sqlite_master WHERE type='table'").map { it[0].str()!! }.toSet()
        fun columns(table: String): Map<String, String> =
            gb.query("PRAGMA table_info($table)").associate { norm(it[1].str()!!) to it[1].str()!! }
        fun metaGet(k: String) = db.one("SELECT value FROM meta WHERE key = ?", listOf(k)).str()
        fun metaSet(k: String, v: Any) = db.exec("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", listOf(k, v.toString()))

        // ---- devices -------------------------------------------------------
        val devices = HashMap<Long, String>()
        if ("DEVICE" in tables) {
            val c = columns("DEVICE")
            gb.forEach("SELECT ${c["id"] ?: "_id"}, ${c["identifier"]}, ${c["name"] ?: "NULL"}, ${c["manufacturer"] ?: "NULL"}, ${c["model"] ?: "NULL"} FROM DEVICE") { r ->
                val ident = r[1].str() ?: return@forEach
                devices[r[0].lng() ?: return@forEach] = ident
                db.exec("INSERT OR REPLACE INTO devices (id, name, manufacturer, model) VALUES (?,?,?,?)", listOf(ident, r[2].str(), r[3].str(), r[4].str()))
            }
        }
        fun dev(id: Any?) = id.lng()?.let { devices[it] } ?: "device-${id.lng() ?: id}"

        // ---- activities ----------------------------------------------------
        onProgress(ImportProgress("Aktivitäten", 0.08f))
        var activities = 0
        var tracks = 0
        if ("BASE_ACTIVITY_SUMMARY" in tables) {
            val c = columns("BASE_ACTIVITY_SUMMARY")
            fun col(k: String) = c[k] ?: "NULL"
            val res = gb.query(
                "SELECT ${col("name")}, ${col("starttime")}, ${col("endtime")}, ${col("activitykind")}, ${col("gpxtrack")}, " +
                    "${col("rawdetailspath")}, ${col("deviceid")}, ${col("summarydata")} FROM BASE_ACTIVITY_SUMMARY ORDER BY 2",
            )
            val known = db.query("SELECT id, has_track FROM activities").associate { it[0].str()!! to (it[1].lng() ?: 0L) }
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
            val point = db.prepare("INSERT OR REPLACE INTO track_points VALUES (?,?,?,?,?,?,?,?,?)")
            try {
                for ((i, r) in res.withIndex()) {
                    val startS = toSeconds(r[1].lng() ?: continue)
                    val endS = toSeconds(r[2].lng() ?: startS)
                    val id = "${dev(r[6])}:$startS"
                    val sum = summary(r[7].str())
                    val sport = Runs.sportOf(r[3].lng())

                    var pts: List<TrackPoint> = emptyList()
                    if (known[id] != 1L) {
                        for (path in listOf(r[4].str(), r[5].str())) {
                            val bytes = lookup(path) ?: continue
                            pts = if (path!!.endsWith(".fit", true) || Tracks.isFit(bytes)) Tracks.parseFit(bytes) else Tracks.parseGpx(String(bytes))
                            if (pts.isNotEmpty()) break
                        }
                    }
                    val hrs = pts.mapNotNull { it.hr?.takeIf { h -> h > 0 } }
                    val distance = sum("distanceMeters") ?: pts.lastOrNull()?.dist
                    val duration = sum("activeSeconds") ?: (endS - startS).toDouble()
                    val avgHr = sum("averageHR") ?: hrs.takeIf { it.isNotEmpty() }?.average()
                    val pace = sum("averageKMPaceSeconds") ?: if (distance != null && distance > 0 && duration > 0) duration / (distance / 1000) else null
                    db.transaction {
                        upsert.run(
                            listOf(
                                id, dev(r[6]), r[3].lng(), sport, r[0].str()?.takeIf { it.isNotEmpty() },
                                startS, endS, duration, distance,
                                avgHr, sum("maxHR") ?: hrs.maxOrNull(), sum("minHR"),
                                pace, sum("ascentMeters") ?: sum("elevationGain") ?: Tracks.ascent(pts),
                                sum("descentMeters") ?: sum("elevationLoss"),
                                sum("caloriesBurnt"), sum("averageCadence"),
                                sum("aerobicTrainingEffect"), sum("anaerobicTrainingEffect"), sum("recoveryTime"),
                                r[7].str(), System.currentTimeMillis() / 1000,
                            ),
                        )
                        if (pts.isNotEmpty()) {
                            db.exec("DELETE FROM track_points WHERE activity_id = ?", listOf(id))
                            for (p in pts) point.run(listOf(id, p.ts, p.lat, p.lon, p.ele, p.hr?.toLong(), p.cadence?.toLong(), p.speed, p.dist))
                            val preview = Tracks.routePreview(pts)
                            db.exec(
                                "UPDATE activities SET has_track = 1, route_preview = ?, pace_cv = ? WHERE id = ?",
                                listOf(if (preview.isEmpty()) null else Json.write(preview.map { listOf(it.lat, it.lon) }), Tracks.paceVariation(pts), id),
                            )
                        }
                    }
                    activities++
                    if (pts.isNotEmpty()) tracks++
                    if (i % 10 == 0) onProgress(ImportProgress("Aktivitäten", 0.08f + 0.32f * i / res.size))
                }
            } finally {
                upsert.close()
                point.close()
            }
            classifyAllRuns(db)
        }

        // ---- time series ---------------------------------------------------
        var samples = 0
        var earliestSleepTs = Long.MAX_VALUE
        val stmts = ArrayList<Stmt>()
        val metricStmt = db.prepare("INSERT OR REPLACE INTO metric_samples (metric, device_id, ts, value) VALUES (?,?,?,?)").also { stmts.add(it) }
        fun metric(name: String, valid: (Double) -> Boolean = { true }): (String, Long, List<Any?>) -> Unit = { d, ts, v ->
            val x = v[0].num()
            if (x != null && valid(x)) metricStmt.run(listOf(name, d, ts, x))
        }

        class Series(val table: String, val cols: List<String>, val write: (String, Long, List<Any?>) -> Unit)
        val series = ArrayList<Series>()
        val minute = db.prepare("INSERT OR REPLACE INTO minute_samples VALUES (?,?,?,?,?,?,?,?)").also { stmts.add(it) }
        series.add(
            Series("GARMIN_ACTIVITY_SAMPLE", listOf("heartrate", "steps", "rawintensity", "rawkind", "distancecm", "activecalories")) { d, ts, v ->
                val h = v[0].lng() ?: 0
                minute.run(listOf(d, ts, if (h in 1..254) h else null, v[1].lng(), v[2].lng(), v[3].lng(), v[4].lng(), v[5].lng()))
            },
        )
        series.add(Series("GARMIN_STRESS_SAMPLE", listOf("stress"), metric("stress") { it in 0.0..100.0 }))
        series.add(Series("GARMIN_BODY_ENERGY_SAMPLE", listOf("energy"), metric("body_battery") { it in 0.0..100.0 }))
        series.add(Series("GARMIN_SPO2_SAMPLE", listOf("spo2"), metric("spo2") { it > 50 && it <= 100 }))
        series.add(Series("GARMIN_RESPIRATORY_RATE_SAMPLE", listOf("respiratoryrate"), metric("respiration") { it > 0 && it < 80 }))
        series.add(Series("GARMIN_HRV_VALUE_SAMPLE", listOf("value"), metric("hrv") { it > 0 && it < 400 }))
        series.add(Series("GARMIN_HEART_RATE_RESTING_SAMPLE", listOf("heartrate"), metric("resting_hr") { it > 20 && it < 150 }))
        series.add(Series("GARMIN_SLEEP_STATS_SAMPLE", listOf("sleepscore"), metric("garmin_sleep_score") { it > 0 && it <= 100 }))
        val stage = metric("sleep_stage")
        series.add(
            Series("GARMIN_SLEEP_STAGE_SAMPLE", listOf("stage")) { d, ts, v ->
                stage(d, ts, v)
                if (ts < earliestSleepTs) earliestSleepTs = ts
            },
        )
        val moderate = metric("intensity_moderate")
        val vigorous = metric("intensity_vigorous")
        series.add(Series("GARMIN_INTENSITY_MINUTES_SAMPLE", listOf("moderate", "vigorous")) { d, ts, v -> moderate(d, ts, listOf(v[0])); vigorous(d, ts, listOf(v[1])) })
        val hrvSum = db.prepare("INSERT OR REPLACE INTO hrv_summary VALUES (?,?,?,?,?,?,?,?,?)").also { stmts.add(it) }
        series.add(
            Series(
                "GARMIN_HRV_SUMMARY_SAMPLE",
                listOf("weeklyaverage", "lastnightaverage", "lastnight5minhigh", "baselinelowupper", "baselinebalancedlower", "baselinebalancedupper", "statusnum"),
            ) { d, ts, v -> hrvSum.run(listOf(d, ts) + v.map { it.num() }) },
        )

        try {
            for ((si, s) in series.withIndex()) {
                onProgress(ImportProgress("Gesundheitsdaten", 0.4f + 0.45f * si / series.size))
                if (s.table !in tables) continue
                val c = columns(s.table)
                val cols = s.cols.map { c[it] ?: "NULL" }
                val tsCol = c["timestamp"] ?: "TIMESTAMP"
                val wmKey = "wm:${s.table}"
                val wm = metaGet(wmKey)?.toLongOrNull() ?: 0L
                // the watermark is stored in seconds; source units may be seconds or milliseconds
                val unitMs = (gb.one("SELECT MAX($tsCol) FROM ${s.table}").lng() ?: 0L) > 100_000_000_000L
                val from = maxOf(0L, wm - MARGIN_S) * (if (unitMs) 1000 else 1)
                var maxTs = wm
                db.transaction {
                    gb.forEach("SELECT $tsCol, ${c["deviceid"] ?: "DEVICE_ID"}, ${cols.joinToString(", ")} FROM ${s.table} WHERE $tsCol >= ? ORDER BY $tsCol", listOf(from)) { r ->
                        val raw = r[0].lng() ?: return@forEach
                        val ts = if (unitMs) raw / 1000 else raw
                        s.write(dev(r[1]), ts, r.drop(2))
                        if (ts > maxTs) maxTs = ts
                        samples++
                    }
                }
                metaSet(wmKey, maxTs)
            }
        } finally {
            stmts.forEach { it.close() }
        }

        // ---- daily records (Garmin Connect data export, see scripts/garmin_export_to_gb.py) ----
        if ("GAPMIN_DAILY" in tables) {
            onProgress(ImportProgress("Tageswerte", 0.86f))
            val daily = db.prepare("INSERT OR REPLACE INTO daily_summary (date, kind, data) VALUES (?,?,?)")
            try {
                db.transaction {
                    gb.forEach("SELECT DATE, KIND, DATA FROM GAPMIN_DAILY") { r ->
                        val date = r[0].str() ?: return@forEach
                        val kind = r[1].str() ?: return@forEach
                        daily.run(listOf(date, kind, r[2].str()))
                        samples++
                    }
                }
            } finally {
                daily.close()
            }
        }

        // ---- derived data --------------------------------------------------
        onProgress(ImportProgress("Schlaf auswerten", 0.88f))
        val nights = if (earliestSleepTs < Long.MAX_VALUE) SleepJob.recompute(db, earliestSleepTs) else 0
        metaSet("last_import", System.currentTimeMillis() / 1000)
        onProgress(ImportProgress("Fertig", 1f))
        return ImportResult(activities, tracks, samples, nights, System.currentTimeMillis() - t0)
    }

    private fun toSeconds(t: Long) = if (t > 100_000_000_000L) t / 1000 else t

    /** Gadgetbridge summary JSON: { key: { value, unit, group? } } */
    private fun summary(json: String?): (String) -> Double? {
        val obj = Json.parse(json).asMap() ?: emptyMap()
        return { key ->
            val e = obj[key]
            (if (e is Map<*, *>) e["value"] else e).asDouble()
        }
    }
}

fun classifyAllRuns(db: Sql) {
    val hrMax = db.one("SELECT MAX(max_hr) FROM activities").num()
    val rows = db.query("SELECT id, sport, distance_m, duration_s, avg_hr, pace_cv, start_ts, name FROM activities")
    val st = db.prepare("UPDATE activities SET category = ?, name = ? WHERE id = ?")
    try {
        db.transaction {
            for (r in rows) {
                val sport = r[1].str()
                if (!Runs.isRun(sport)) continue
                val cat = Runs.classify(sport!!, r[2].num(), r[3].num(), r[4].num(), r[5].num(), hrMax)
                // keep user-given names; otherwise (re)generate from category
                val name = r[7].str()
                val keep = if (!name.isNullOrEmpty() && !name.startsWith("~")) name else null
                st.run(listOf(cat.key, keep ?: "~${Runs.defaultName(cat, r[6].lng() ?: 0)}", r[0]))
            }
        }
    } finally {
        st.close()
    }
}
