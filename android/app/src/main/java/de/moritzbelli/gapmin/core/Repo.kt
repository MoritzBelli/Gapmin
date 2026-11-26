package de.moritzbelli.gapmin.core

/** Read models for the UI. */
data class RunSummary(
    val id: String,
    val name: String,
    val category: RunCategory,
    val startTs: Long,
    val distanceM: Double,
    val durationS: Double,
    val paceSPerKm: Double,
    val avgHr: Int?,
    val maxHr: Int?,
    val ascentM: Int?,
    val teAerobic: Double?,
    val calories: Int?,
    val cadence: Int?,
    val preview: List<LatLon>,
)

data class TrackSample(val t: Long, val dist: Double?, val speed: Double?, val hr: Double?, val ele: Double?)

data class HypnoSeg(val offsetMin: Int, val durMin: Int, val stage: Int)

data class SleepNight(
    val night: String,
    val bedTs: Long, val onsetTs: Long, val wakeTs: Long, val outTs: Long,
    val tibMin: Double, val tstMin: Double, val solMin: Double, val wasoMin: Double,
    val awakenings: Int, val awakeningsLong: Int, val efficiency: Double,
    val deepMin: Double, val lightMin: Double, val remMin: Double, val awakeMin: Double,
    val midpointMin: Double, val regularitySd: Double?,
    val score: Int,
    val components: Map<Component, Double?>,
    val coverage: Double,
    val garminScore: Int?,
    // details
    val hypnogram: List<HypnoSeg>,
    val remLatencyMin: Double?,
    val deepFirstHalfShare: Double?,
    val physiology: NightPhysiology,
    val baselineNights: Int,
    val lnHrvMean: Double?,
    val hrMean: Double?,
    val factors: List<String>,
    val label: String,
    val provisional: Boolean,
)

data class Stats(val activities: Int, val nights: Int, val samples: Long, val lastImport: Long?, val lastSync: Long?)

object Repo {
    fun listRuns(db: Sql): List<RunSummary> =
        db.query(
            """SELECT id, name, category, sport, start_ts, distance_m, duration_s, avg_pace_s_km, avg_hr, max_hr, ascent_m,
                      te_aerobic, route_preview, calories, avg_cadence FROM activities ORDER BY start_ts DESC""",
        ).filter { Runs.isRun(it[3].str()) }.map { r ->
            RunSummary(
                id = r[0].str()!!,
                name = (r[1].str() ?: "Lauf").removePrefix("~"),
                category = RunCategory.of(r[2].str()),
                startTs = r[4].lng() ?: 0,
                distanceM = r[5].num() ?: 0.0,
                durationS = r[6].num() ?: 0.0,
                paceSPerKm = r[7].num() ?: 0.0,
                avgHr = r[8].num()?.let { Math.round(it).toInt() },
                maxHr = r[9].num()?.let { Math.round(it).toInt() },
                ascentM = r[10].num()?.let { Math.round(it).toInt() },
                teAerobic = r[11].num(),
                calories = r[13].num()?.let { Math.round(it).toInt() },
                cadence = r[14].num()?.let { Math.round(it).toInt() },
                preview = Json.parse(r[12].str()).asList()?.mapNotNull { p ->
                    val l = p.asList() ?: return@mapNotNull null
                    val lat = l.getOrNull(0).asDouble() ?: return@mapNotNull null
                    val lon = l.getOrNull(1).asDouble() ?: return@mapNotNull null
                    LatLon(lat, lon)
                } ?: emptyList(),
            )
        }

    fun track(db: Sql, activityId: String): List<TrackSample> =
        db.query("SELECT ts, dist_m, speed, hr, ele FROM track_points WHERE activity_id = ? ORDER BY ts", listOf(activityId))
            .map { TrackSample(it[0].lng()!!, it[1].num(), it[2].num(), it[3].num(), it[4].num()) }

    fun listNights(db: Sql, limit: Int = 400): List<SleepNight> {
        val cols = listOf(
            "night", "bed_ts", "onset_ts", "wake_ts", "out_ts", "tib_min", "tst_min", "sol_min", "waso_min", "awakenings",
            "awakenings_long", "efficiency", "deep_min", "light_min", "rem_min", "awake_min", "midpoint_min", "regularity_sd",
            "score", "score_duration", "score_continuity", "score_recovery", "score_stages", "score_regularity", "score_breathing",
            "coverage", "garmin_score", "details",
        )
        return db.query("SELECT ${cols.joinToString()} FROM sleep_nights ORDER BY night DESC LIMIT ?", listOf(limit)).map { r ->
            val d = Json.parse(r[27].str()).asMap() ?: emptyMap()
            val p = d["physiology"].asMap() ?: emptyMap()
            val b = d["baselines"].asMap() ?: emptyMap()
            fun pd(k: String) = p[k].asDouble()
            SleepNight(
                night = r[0].str()!!,
                bedTs = r[1].lng() ?: 0, onsetTs = r[2].lng() ?: 0, wakeTs = r[3].lng() ?: 0, outTs = r[4].lng() ?: 0,
                tibMin = r[5].num() ?: 0.0, tstMin = r[6].num() ?: 0.0, solMin = r[7].num() ?: 0.0, wasoMin = r[8].num() ?: 0.0,
                awakenings = r[9].lng()?.toInt() ?: 0, awakeningsLong = r[10].lng()?.toInt() ?: 0, efficiency = r[11].num() ?: 0.0,
                deepMin = r[12].num() ?: 0.0, lightMin = r[13].num() ?: 0.0, remMin = r[14].num() ?: 0.0, awakeMin = r[15].num() ?: 0.0,
                midpointMin = r[16].num() ?: 0.0, regularitySd = r[17].num(),
                score = r[18].lng()?.toInt() ?: 0,
                components = Component.entries.withIndex().associate { (i, c) -> c to r[19 + i].num() },
                coverage = r[25].num() ?: 0.0,
                garminScore = r[26].lng()?.toInt(),
                hypnogram = d["hypnogram"].asList()?.mapNotNull { s ->
                    val l = s.asList() ?: return@mapNotNull null
                    HypnoSeg(l[0].asDouble()?.toInt() ?: 0, l[1].asDouble()?.toInt() ?: 0, l[2].asDouble()?.toInt() ?: 0)
                } ?: emptyList(),
                remLatencyMin = d["remLatencyMin"].asDouble(),
                deepFirstHalfShare = d["deepFirstHalfShare"].asDouble(),
                physiology = NightPhysiology(
                    hrAvg = pd("hrAvg"), hrMin = pd("hrMin"), hrMinTs = pd("hrMinTs")?.toLong(), hrDayAvg = pd("hrDayAvg"), hrDip = pd("hrDip"),
                    hrvAvg = pd("hrvAvg"), hrvMax = pd("hrvMax"), stressAvg = pd("stressAvg"),
                    respAvg = pd("respAvg"), respMin = pd("respMin"), respMax = pd("respMax"),
                    spo2Avg = pd("spo2Avg"), spo2Min = pd("spo2Min"), spo2Below90 = pd("spo2Below90"), bbGain = pd("bbGain"),
                ),
                baselineNights = b["nights"].asDouble()?.toInt() ?: 0,
                lnHrvMean = b["lnHrvMean"].asDouble(),
                hrMean = b["hrMean"].asDouble(),
                factors = d["factors"].asList()?.mapNotNull { it as? String } ?: emptyList(),
                label = d["label"] as? String ?: "",
                provisional = d["provisional"] as? Boolean ?: false,
            )
        }
    }

    fun stats(db: Sql) = Stats(
        activities = db.one("SELECT COUNT(*) FROM activities").lng()?.toInt() ?: 0,
        nights = db.one("SELECT COUNT(*) FROM sleep_nights").lng()?.toInt() ?: 0,
        samples = db.one("SELECT (SELECT COUNT(*) FROM metric_samples) + (SELECT COUNT(*) FROM minute_samples)").lng() ?: 0,
        lastImport = db.one("SELECT value FROM meta WHERE key = 'last_import'").lng(),
        lastSync = db.one("SELECT value FROM meta WHERE key = 'last_sync'").lng(),
    )

    fun getSetting(db: Sql, key: String): String =
        db.one("SELECT value FROM meta WHERE key = ?", listOf("setting:$key")).str() ?: ""

    fun setSetting(db: Sql, key: String, value: String) =
        db.exec("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", listOf("setting:$key", value))
}
