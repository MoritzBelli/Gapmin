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
