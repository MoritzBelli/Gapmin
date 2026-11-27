package de.moritzbelli.gapmin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

/** Training, run, rhythm and HRV analyses (docs/research/training.md) on known values and on the demo export. */
class AnalysisTest {
    @Test
    fun formulas() {
        // Minetti 2002: 3,6 J/kg/m on the flat, uphill costs more, a gentle downhill less
        assertEquals(3.6, RunAnalysis.minettiCost(0.0), 1e-9)
        assertTrue(RunAnalysis.minettiCost(0.1) > 5.0)
        assertTrue(RunAnalysis.minettiCost(-0.1) < 3.6)
        // Riegel: 10 km from 20:00 over 5 km = 20 · 2^1,06 min
        assertEquals(2502.0, Training.riegel(1200.0, 5000.0, 10000.0), 1.0)
        // Daniels' tables: 5 km in 20:00 ≈ VDOT 49,8
        assertEquals(49.8, Training.vdot(5000.0, 1200.0)!!, 0.5)
        // Banister weighting is 0 at rest and grows exponentially
        assertEquals(0.0, Training.trimpWeight(0.0), 1e-9)
        assertEquals(0.64 * Math.exp(1.92), Training.trimpWeight(1.0), 1e-9)
        // polarization index: 80/5/15 % is polarised (Treff 2019)
        val tid = Tid(DoubleArray(5), doubleArrayOf(80.0, 5.0, 15.0))
        assertEquals(Math.log10(0.8 / 0.05 * 0.15 * 100), tid.pi!!, 1e-9)
        assertEquals("Polarisiert", tid.label)
    }

    @Test
    fun syntheticRun() {
        // 10 km at a steady 5:00 /km (3,333 m/s), 1 Hz, flat, HR rising from 140 to 150
        val pts = (0..3000).map { t -> TrackSample(1_000_000L + t, t * 10.0 / 3, null, 140.0 + t / 300.0, 100.0) }
        val m = RunAnalysis.analyze(pts)!!
        assertEquals(10, m.splits.size)
        for (s in m.splits) assertEquals(300.0, s, 1.0)
        assertEquals(300.0, m.bests[1000]!!, 1.0)
        assertEquals(1500.0, m.bests[5000]!!, 1.0)
        assertEquals(3000.0, m.bests[10000]!!, 1.0)
        assertEquals(300.0, m.gapSPerKm!!, 1.0)
        // heart rate drifts up at the same speed: positive decoupling
        assertTrue(m.decoupling!! > 0)
        val back = RunMetrics.parse(m.toJson())!!
        assertEquals(m.bests, back.bests)
        assertEquals(m.splits.size, back.splits.size)
    }

    @Test
    fun garminDaily() {
        val db = JdbcSql.open(null)
        Schema.migrate(db)
        fun put(date: String, kind: String, json: String) = db.exec("INSERT INTO daily_summary (date, kind, data) VALUES (?,?,?)", listOf(date, kind, json))
        put("2025-05-01", "garmin_vo2max_running", """{"calendarDate":"2025-05-01","sport":"RUNNING","vo2MaxPreciseValue":52.4,"fitnessAge":31}""")
        put("2025-05-02", "garmin_vo2max_running", """{"calendarDate":"2025-05-02","generic":{"vo2MaxValue":53.0}}""")
        put("2025-05-02", "garmin_readiness", """{"calendarDate":"2025-05-02","score":71}""")
        put("2025-05-02", "garmin_load", """{"calendarDate":"2025-05-02","dailyTrainingLoadAcute":420,"dailyTrainingLoadChronic":380}""")
        put("2025-05-02", "garmin_race", """{"calendarDate":"2025-05-02","raceTime5K":1260,"raceTimeMarathon":12600000}""")
        put("2025-05-02", "garmin_training_status", """{"calendarDate":"2025-05-02","trainingStatusFeedbackPhrase":"PRODUCTIVE_3"}""")
        val g = Garmin.load(db)
        assertEquals(listOf(52.4, 53.0), g.vo2max.map { it.value })
        assertEquals(31.0, g.fitnessAge!!.value, 1e-9)
        assertEquals(71.0, g.readiness.last().value, 1e-9)
        assertEquals(420.0, g.acuteLoad!!.value, 1e-9)
        assertEquals(1260.0, g.races[5000]!!.value, 1e-9)
        assertEquals(12600.0, g.races[42195]!!.value, 1e-9)
        assertEquals("Produktiv", g.trainingStatus!!.second)
    }

    @Test
    fun demoAnalyses() {
        val demo = listOf("../../web/public/demo/gadgetbridge-demo.zip", "../web/public/demo/gadgetbridge-demo.zip").map(::File).first { it.exists() }
        val db = JdbcSql.open(null)
        Schema.migrate(db)
        GadgetbridgeImport(db, { JdbcSql.open(it, readOnly = true) }, File(System.getProperty("java.io.tmpdir"), "gapmin-analysis")).run(demo)
        val n = RunAnalysis.update(db)
        assertTrue("run metrics $n", n > 50)
        assertEquals(0, RunAnalysis.update(db))

        val runs = Repo.listRuns(db)
        assertTrue(runs.count { it.metrics != null } > runs.size * 0.9)
        for (r in runs) {
            val m = r.metrics ?: continue
            assertEquals(r.name, (r.distanceM / 1000).toInt().toDouble(), m.splits.size.toDouble(), 1.0)
            val b1 = m.bests[1000]
            val b5 = m.bests[5000]
            if (b1 != null && b5 != null) assertTrue("${r.name}: 5 km faster than 5 × 1 km", b5 >= b1 * 5 * 0.98)
        }

        val days = Health.listDays(db, 100_000)
        val nights = Repo.listNights(db)
        val last = runs.maxOf { it.startTs } / 86400
        val ins = Insights.compute(db, days, nights, LocalDate.ofEpochDay(last).toEpochDay())
        val t = ins.training
        assertNotNull(t)
        t!!
        assertTrue(t.hr.maxFromData && t.hr.restFromData)
        assertTrue(t.days.all { it.ctl.isFinite() && it.atl.isFinite() && it.load >= 0 })
        assertTrue("CTL ${t.today?.ctl}", (t.today?.ctl ?: 0.0) > 10)
        assertNotNull(t.today?.acwr)
        assertTrue(abs(t.tid28.z3.sum() - t.tid28.z5.sum()) < t.tid28.z3.sum() * 0.2 + 1)
        assertNotNull("VO2max", t.vo2Now)
        assertTrue(t.vo2Now!! in 30.0..75.0)
        assertTrue(t.predictions.size == 4 && t.predictions.zipWithNext().all { (a, b) -> b.seconds > a.seconds })

        assertTrue(ins.hrv.any { it.status != null })
        val sri = ins.sri.values
        assertTrue(sri.isNotEmpty() && sri.all { it in -100.0..100.0 })
        val rh = Rhythm.stats(nights, 0)
        assertNotNull(rh.sjlMin)
        assertNotNull(rh.balance7)
        assertTrue(ins.garmin.isEmpty)
    }
}
