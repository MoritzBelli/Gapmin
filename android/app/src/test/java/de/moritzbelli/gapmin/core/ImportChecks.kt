package de.moritzbelli.gapmin.core

import java.io.File

/** Import chain checks against the demo export (run from JUnit and from a plain main). */
object ImportChecks {
    private fun expect(ok: Boolean, msg: String) { if (!ok) throw AssertionError(msg) }

    fun run(demo: File, tmp: File, log: (String) -> Unit = ::println) {
        val db = JdbcSql.open(null)
        Schema.migrate(db)
        val imp = GadgetbridgeImport(db, { JdbcSql.open(it, readOnly = true) }, tmp)
        val first = imp.run(demo)
        log("first import $first")
        expect(first.activities > 50, "activities ${first.activities}")
        expect(first.tracks == first.activities, "tracks ${first.tracks}")
        val n = db.one("SELECT COUNT(*) FROM activities").lng()!!
        val p = db.one("SELECT SUM(route_preview IS NOT NULL) FROM activities").lng()!!
        expect(p == n, "previews $p/$n")
        val few = db.one("SELECT COUNT(*) FROM activities WHERE json_array_length(route_preview) < 20").lng()!!
        expect(few == 0L, "collapsed routes $few")
        val cats = db.query("SELECT category, COUNT(*) FROM activities GROUP BY 1").associate { it[0].str() to it[1].lng() }
        log("categories $cats")
        expect((cats["intervals"] ?: 0) > 0, "no intervals")
        expect((cats["trail"] ?: 0) > 0, "no trail")

        val m = db.query("SELECT metric, COUNT(*) FROM metric_samples GROUP BY 1").associate { it[0].str() to it[1].lng()!! }
        log("metrics $m")
        for (k in listOf("stress", "body_battery", "spo2", "respiration", "hrv", "resting_hr", "sleep_stage", "garmin_sleep_score"))
            expect((m[k] ?: 0) > 0, "metric $k")
        expect(db.one("SELECT COUNT(*) FROM minute_samples").lng()!! > 100_000, "minute samples")

        val nights = Repo.listNights(db)
        expect(nights.size > 140, "nights ${nights.size}")
        val last = nights.first()
        log("last night ${last.night} score=${last.score} ${last.label} tst=${Fmt.hm(last.tstMin)} factors=${last.factors} garmin=${last.garminScore}")
        expect(last.tstMin > 300, "tst")
        expect(nights.all { it.score in 0..100 }, "score range")
        log("GSS avg ${nights.map { it.score }.average()} | Garmin avg ${nights.mapNotNull { it.garminScore }.average()}")

        // sub-scores reproduce the stored component scores
        for (x in nights.take(30)) {
            val parts = Sleep.parts(
                Sleep.PartInput(
                    x.tstMin, x.efficiency, x.wasoMin, x.awakeningsLong, x.solMin, x.deepMin, x.lightMin, x.remMin,
                    x.physiology.hrvAvg, x.physiology.hrAvg, x.physiology.stressAvg, x.physiology.respAvg, x.physiology.spo2Avg,
                    if (x.regularitySd != null) 7 else 0, x.regularitySd,
                    Baselines(x.baselineNights, x.lnHrvMean, x.lnHrvSd, x.hrMean, x.respMean),
                ),
            )
            val comp = Sleep.componentScores(parts)
            for (c in listOf(Component.DURATION, Component.CONTINUITY, Component.RECOVERY, Component.STAGES, Component.BREATHING)) {
                val a = comp[c]; val b = x.components[c]
                expect((a == null) == (b == null) && (a == null || kotlin.math.abs(a - b!!) < 1e-6), "parts ${x.night} $c $a vs $b")
            }
        }

        val days = Health.listDays(db, 100_000)
        val d0 = days.first()
        log("health days ${days.size}, ${d0.date}: rhr=${d0.restingHr} stress=${d0.stressAvg} bb=${d0.bbMin}-${d0.bbMax} steps=${d0.steps} hrv=${d0.hrvNight} im=${d0.imWeighted}")
        expect(days.size > 140, "health days ${days.size}")
        expect(days.count { it.stressAvg != null } > 140 && days.count { it.bbMax != null } > 140, "stress / body battery per day")
        expect(days.count { it.restingHr != null } > 100 && days.count { it.steps != null } > 140, "resting hr / steps per day")
        expect(days.all { s -> listOfNotNull(s.stressRest, s.stressLow, s.stressMed, s.stressHigh).sum().let { it == 0.0 || kotlin.math.abs(it - 1) < 1e-9 } }, "stress shares")
        val series = Health.daySeries(db, days[1].date)
        expect(series.stress.isNotEmpty() && series.bb.isNotEmpty() && series.hr.isNotEmpty(), "day series")
        expect(Stat.desc(listOf(1.0, 2.0, 3.0, 4.0))!!.median == 2.5, "median")

        val runs = Repo.listRuns(db)
        log("runs ${runs.size}, first ${runs.first().name} ${Fmt.km(runs.first().distanceM)} km ${Fmt.pace(runs.first().paceSPerKm)} /km, preview ${runs.first().preview.size}")
        expect(Repo.track(db, runs.first().id).size > 100, "track points")

        val before = db.one("SELECT (SELECT COUNT(*) FROM metric_samples) + (SELECT COUNT(*) FROM activities)").lng()
        val again = imp.run(demo)
        val after = db.one("SELECT (SELECT COUNT(*) FROM metric_samples) + (SELECT COUNT(*) FROM activities)").lng()
        log("second import $again")
        expect(before == after, "idempotent $before $after")
        expect(again.tracks == 0, "tracks parsed once")
        db.close()
    }
}

fun main(args: Array<String>) {
    ImportChecks.run(File(args[0]), File(args[1]))
    println("OK")
}
