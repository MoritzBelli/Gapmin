package de.moritzbelli.gapmin.core

/**
 * Values Garmin computes itself and ships only in the Garmin Connect data export (daily_summary): VO2max, training readiness,
 * acute/chronic load, training status, race predictions, endurance and hill score. Field names are looked up from a list of
 * known candidates (also one or two levels deep), so a changed export format just leaves a value empty.
 */
data class GarminValue(val date: String, val value: Double)

data class GarminDaily(
    val vo2max: List<GarminValue> = emptyList(),
    val fitnessAge: GarminValue? = null,
    val readiness: List<GarminValue> = emptyList(),
    val acuteLoad: GarminValue? = null,
    val chronicLoad: GarminValue? = null,
    val enduranceScore: GarminValue? = null,
    val hillScore: GarminValue? = null,
    /** Distance (m) → predicted time (s). */
    val races: Map<Int, GarminValue> = emptyMap(),
    /** Date and German label of the latest training status. */
    val trainingStatus: Pair<String, String>? = null,
) {
    val isEmpty: Boolean
        get() = vo2max.isEmpty() && readiness.isEmpty() && acuteLoad == null && enduranceScore == null &&
            hillScore == null && races.isEmpty() && trainingStatus == null
}

object Garmin {
    private fun find(m: Map<String, Any?>, keys: List<String>, depth: Int = 2): Any? {
        for (k in keys) m[k]?.let { return it }
        if (depth == 0) return null
        for (v in m.values) {
            val sub = v.asMap() ?: v.asList()?.firstOrNull().asMap() ?: continue
            find(sub, keys, depth - 1)?.let { return it }
        }
        return null
    }

    private fun num(m: Map<String, Any?>, keys: List<String>, range: ClosedFloatingPointRange<Double>) =
        find(m, keys).asDouble()?.takeIf { it in range }

    private fun rows(db: Sql, kind: String): List<Pair<String, Map<String, Any?>>> =
        db.query("SELECT date, data FROM daily_summary WHERE kind = ? ORDER BY date", listOf(kind))
            .mapNotNull { r -> Json.parse(r[1].str()).asMap()?.let { r[0].str()!! to it } }

    private val STATUS = listOf(
        "UNPRODUCTIVE" to "Unproduktiv", "PRODUCTIVE" to "Produktiv", "MAINTAINING" to "Erhaltend", "RECOVERY" to "Erholung",
        "DETRAINING" to "Leistungsabbau", "PEAKING" to "Leistungshoch", "OVERREACHING" to "Überlastet", "STRAINED" to "Strapaziert",
        "NO_STATUS" to "Kein Status",
    )

    fun load(db: Sql): GarminDaily {
        val vo2 = ArrayList<GarminValue>()
        var age: GarminValue? = null
        val kinds = db.query("SELECT DISTINCT kind FROM daily_summary WHERE kind LIKE 'garmin_vo2max%'").mapNotNull { it[0].str() }
        // running VO2max first; other sports only if there is no running value
        val vo2Kind = kinds.firstOrNull { it.endsWith("running") } ?: kinds.firstOrNull()
        if (vo2Kind != null) for ((d, m) in rows(db, vo2Kind)) {
            num(m, listOf("vo2MaxPreciseValue", "vo2MaxValue", "vo2Max", "vo2max"), 15.0..95.0)?.let { vo2.add(GarminValue(d, it)) }
            num(m, listOf("fitnessAge"), 10.0..110.0)?.let { age = GarminValue(d, it) }
        }

        val readiness = rows(db, "garmin_readiness").mapNotNull { (d, m) ->
            num(m, listOf("score", "trainingReadinessScore", "readinessScore"), 0.0..100.0)?.let { GarminValue(d, it) }
        }

        var acute: GarminValue? = null
        var chronic: GarminValue? = null
        for ((d, m) in rows(db, "garmin_load")) {
            num(m, listOf("dailyTrainingLoadAcute", "acuteTrainingLoad", "acuteLoad"), 0.0..10_000.0)?.let { acute = GarminValue(d, it) }
            num(m, listOf("dailyTrainingLoadChronic", "chronicTrainingLoad", "chronicLoad"), 0.0..10_000.0)?.let { chronic = GarminValue(d, it) }
        }

        fun lastScore(kind: String) = rows(db, kind).mapNotNull { (d, m) ->
            num(m, listOf("overallScore", "enduranceScore", "hillScore", "score"), 0.0..20_000.0)?.let { GarminValue(d, it) }
        }.lastOrNull()

        val races = HashMap<Int, GarminValue>()
        val raceKeys = listOf(5000 to "raceTime5K", 10000 to "raceTime10K", 21097 to "raceTimeHalf", 42195 to "raceTimeMarathon")
        for ((d, m) in rows(db, "garmin_race")) {
            for ((dist, key) in raceKeys) {
                var s = find(m, listOf(key)).asDouble() ?: continue
                if (s > dist * 2.0) s /= 1000 // milliseconds
                if (s in dist * 0.12..dist * 1.2) races[dist] = GarminValue(d, s)
            }
        }

        var status: Pair<String, String>? = null
        for ((d, m) in rows(db, "garmin_training_status")) {
            val phrase = (find(m, listOf("trainingStatusFeedbackPhrase", "trainingStatus2FeedbackPhrase", "feedbackPhrase")) as? String)?.uppercase()
                ?: continue
            STATUS.firstOrNull { phrase.startsWith(it.first) }?.let { status = d to it.second }
        }

        return GarminDaily(
            vo2max = vo2, fitnessAge = age, readiness = readiness, acuteLoad = acute, chronicLoad = chronic,
            enduranceScore = lastScore("garmin_endurance"), hillScore = lastScore("garmin_hill"), races = races, trainingStatus = status,
        )
    }
}
