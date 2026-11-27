package de.moritzbelli.gapmin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Import straight from a Garmin Connect data export ZIP (synthetic: same structure as the real export, invented values). */
class GarminExportZipTest {
    // three tiny FIT activities, 30 records each, starting 2025-05-01/02/03 06:30:00 UTC
    private val fits = listOf(
        "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"
    ).map { Base64.getDecoder().decode(it) }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bo.toByteArray()
    }

    private fun exportZip(dir: File): File {
        val startMs = listOf(1746081000000L, 1746167400000L, 1746253800000L) // FIT starts
        val acts = startMs.mapIndexed { i, ms ->
            // activity time is 2 s later than the first record, units like the real export: distance in cm, duration in ms
            """{"activityId":${9000 + i},"activityType":"running","startTimeGmt":${ms + 2000}.0,"distance":90000.0,"duration":300000.0,""" +
                """"elapsedDuration":305000.0,"elevationGain":1500.0,"avgHr":150.0,"maxHr":170.0,"calories":80.0,"avgDoubleCadence":170.0,""" +
                """"aerobicTrainingEffect":3.1,"splits":[{"startIndex":0,"endIndex":3}]}"""
        }
        val inner = zip(fits.mapIndexed { i, b -> "user@example.com_${1000000000 + i}.fit" to b })
        val outer = zip(
            listOf(
                "DI_CONNECT/DI-Connect-Fitness/user@example.com_0_summarizedActivities.json" to
                    ("""[{"summarizedActivitiesExport":[""" + acts.joinToString(",") + "]}]").toByteArray(),
                "DI_CONNECT/DI-Connect-Uploaded-Files/UploadedFiles_0-_Part1.zip" to inner,
                "DI_CONNECT/DI-Connect-Aggregator/UDSFile_2025-05-01_2025-05-06.json" to
                    ("""[{"calendarDate":"2025-05-01","totalSteps":9000,"activeKilocalories":420.0,"moderateIntensityMinutes":30,"vigorousIntensityMinutes":10,""" +
                        """"minHeartRate":45,"maxHeartRate":160,"restingHeartRate":52,"restingHeartRateTimestamp":1746100000000,""" +
                        """"allDayStress":{"aggregatorList":[{"type":"TOTAL","averageStressLevel":30,"maxStressLevel":90,"restDuration":3600,"lowDuration":7200,"mediumDuration":3600,"stressDuration":0}]},""" +
                        """"bodyBattery":{"chargedValue":60,"drainedValue":55,"bodyBatteryStatList":[{"bodyBatteryStatType":"HIGHEST","statsValue":95},{"bodyBatteryStatType":"LOWEST","statsValue":20}]},""" +
                        """"respiration":{"avgWakingRespirationValue":15.0,"lowestRespirationValue":10.0,"highestRespirationValue":22.0}}]""").toByteArray(),
                "DI_CONNECT/DI-Connect-Wellness/2025-05-01_2025-05-06_123_sleepData.json" to
                    ("""[{"retro":true},{"calendarDate":"2025-05-01","sleepStartTimestampGMT":"2025-04-30T21:30:00.0","sleepEndTimestampGMT":"2025-05-01T05:30:00.0",""" +
                        """"deepSleepSeconds":4800,"lightSleepSeconds":14400,"remSleepSeconds":5400,"awakeSleepSeconds":1800,"awakeCount":4,""" +
                        """"avgSleepStress":18.0,"averageRespiration":14.0,"lowestRespiration":11.0,"highestRespiration":18.0,"sleepScores":{"overallScore":77}}]""").toByteArray(),
                "DI_CONNECT/DI-Connect-Wellness/2025-05-01_2025-05-06_123_healthStatusData.json" to
                    """[{"calendarDate":"2025-05-01","metrics":[{"type":"HRV","value":60.0,"baselineLowerLimit":50.0,"baselineUpperLimit":80.0,"status":"IN_RANGE"}]}]""".toByteArray(),
                "DI_CONNECT/DI-Connect-Metrics/TrainingReadinessDTO_20250501_20250506_1.json" to
                    """[{"calendarDate":"2025-05-01","hrvWeeklyAverage":62.0,"level":"HIGH"}]""".toByteArray(),
                "DI_CONNECT/DI-Connect-Metrics/EnduranceScore_20250501_20250506_1.json" to
                    """[{"calendarDate":1746057600000,"classification":3}]""".toByteArray(),
                "customer_data/customer.json" to "{}".toByteArray(),
            ),
        )
        return File(dir, "garmin-export.zip").also { it.writeBytes(outer) }
    }

    @Test
    fun importsGarminExportZip() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "gapmin-garmin-zip-test").also { it.mkdirs() }
        val src = exportZip(tmp)
        val db = JdbcSql.open(null)
        Schema.migrate(db)
        val imp = GadgetbridgeImport(db, { JdbcSql.open(it, readOnly = true) }, tmp)
        repeat(2) { // second run must change nothing
            val r = imp.run(src)
            assertEquals(3, r.activities)
            assertEquals(3, r.tracks)
            assertEquals(3L, db.one("SELECT COUNT(*) FROM activities").lng())
            assertEquals(3L, db.one("SELECT COUNT(*) FROM activities WHERE has_track = 1").lng())
            assertEquals(90L, db.one("SELECT COUNT(*) FROM track_points").lng())
            assertEquals("GARMIN-EXPORT:1746081002", db.one("SELECT id FROM activities ORDER BY start_ts LIMIT 1").str())
            assertEquals(900.0, db.one("SELECT distance_m FROM activities LIMIT 1").num()!!, 1e-6) // cm -> m, measured against the FIT
            assertEquals(300.0, db.one("SELECT duration_s FROM activities LIMIT 1").num()!!, 1e-6) // ms -> s
            assertEquals(15.0, db.one("SELECT ascent_m FROM activities LIMIT 1").num()!!, 1e-6)
            assertEquals("running", db.one("SELECT sport FROM activities LIMIT 1").str())
            assertEquals(52.0, db.one("SELECT value FROM metric_samples WHERE metric = 'resting_hr'").num()!!, 1e-6)
            assertEquals(77.0, db.one("SELECT value FROM metric_samples WHERE metric = 'garmin_sleep_score'").num()!!, 1e-6)
            val h = db.query("SELECT weekly_avg, last_night_avg, baseline_balanced_lower, baseline_balanced_upper, status FROM hrv_summary").single()
            assertEquals(listOf(62L, 60L, 50L, 80L, 4L), h.map { it.lng() })
            val kinds = db.query("SELECT kind FROM daily_summary ORDER BY kind").map { it[0].str() }
            assertEquals(listOf("garmin_endurance", "garmin_health", "garmin_readiness", "garmin_sleep", "garmin_uds"), kinds)
            assertEquals("2025-05-01", db.one("SELECT date FROM daily_summary WHERE kind = 'garmin_endurance'").str())
            assertTrue(db.one("SELECT data FROM daily_summary WHERE kind = 'garmin_uds'").str()!!.contains("\"totalSteps\":9000"))

            // sleep night from the totals (no stage timeline): scored like a real night, without hypnogram
            val nights = Repo.listNights(db)
            assertEquals(1, nights.size)
            val n = nights.single()
            assertEquals("2025-05-01", n.night)
            assertEquals(410.0, n.tstMin, 1e-6)
            assertEquals(80.0, n.deepMin, 1e-6)
            assertEquals(77, n.garminScore)
            assertEquals(60.0, n.physiology.hrvAvg!!, 1e-6)
            assertTrue(n.hypnogram.isEmpty())
            assertTrue(n.score in 1..100)

            // health day: values that have no samples come from the export's daily record
            val day = Health.listDays(db, 100_000).single { it.date == "2025-05-01" }
            assertEquals(9000.0, day.steps!!, 1e-6)
            assertEquals(420.0, day.activeKcal!!, 1e-6)
            assertEquals(30.0, day.imModerate!!, 1e-6)
            assertEquals(10.0, day.imVigorous!!, 1e-6)
            assertEquals(30.0, day.stressAvg!!, 1e-6)
            assertEquals(90.0, day.stressMax!!, 1e-6)
            assertEquals(3600.0 / 14400, day.stressRest!!, 1e-6)
            assertEquals(95.0, day.bbMax!!, 1e-6)
            assertEquals(20.0, day.bbMin!!, 1e-6)
            assertEquals(60.0, day.bbCharged!!, 1e-6)
            assertEquals(55.0, day.bbDrained!!, 1e-6)
            assertEquals(15.0, day.respAvg!!, 1e-6)
            assertEquals(52.0, day.restingHr!!, 1e-6)
        }
    }
}
