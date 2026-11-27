package de.moritzbelli.gapmin.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Import of a converted Garmin Connect export (scripts/garmin_export_to_gb.py): activities, daily series and daily records. */
class GarminExportImportTest {
    private fun source(dir: File): File {
        val f = File(dir, "garmin-export.sqlite").also { it.delete() }
        val gb = JdbcSql.open(f)
        gb.exec("CREATE TABLE DEVICE (_id INTEGER PRIMARY KEY, NAME TEXT, MANUFACTURER TEXT, IDENTIFIER TEXT, TYPE INTEGER, TYPE_NAME TEXT, MODEL TEXT)")
        gb.exec("INSERT INTO DEVICE VALUES (1, 'Garmin (Export)', 'Garmin', 'GARMIN-EXPORT', 0, 'GARMIN_EXPORT', NULL)")
        gb.exec(
            "CREATE TABLE BASE_ACTIVITY_SUMMARY (_id INTEGER PRIMARY KEY, NAME TEXT, START_TIME INTEGER, END_TIME INTEGER, ACTIVITY_KIND INTEGER, " +
                "GPX_TRACK TEXT, RAW_DETAILS_PATH TEXT, DEVICE_ID INTEGER, SUMMARY_DATA TEXT)",
        )
        gb.exec(
            "INSERT INTO BASE_ACTIVITY_SUMMARY (NAME, START_TIME, END_TIME, ACTIVITY_KIND, DEVICE_ID, SUMMARY_DATA) VALUES (NULL, 1746081000000, 1746083400000, 16, 1, " +
                "'{\"distanceMeters\":{\"value\":8000.0,\"unit\":\"meters\"},\"activeSeconds\":{\"value\":2400.0,\"unit\":\"seconds\"},\"averageHR\":{\"value\":150.0,\"unit\":\"bpm\"}}')",
        )
        gb.exec("CREATE TABLE GARMIN_HEART_RATE_RESTING_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, HEART_RATE INTEGER)")
        gb.exec("INSERT INTO GARMIN_HEART_RATE_RESTING_SAMPLE VALUES (1746100000, 1, 1, 52)")
        gb.exec("CREATE TABLE GAPMIN_DAILY (DATE TEXT, KIND TEXT, DATA TEXT, PRIMARY KEY (DATE, KIND))")
        gb.exec("INSERT INTO GAPMIN_DAILY VALUES ('2025-05-01', 'garmin_uds', '{\"totalSteps\":9000}')")
        gb.exec("INSERT INTO GAPMIN_DAILY VALUES ('2025-05-01', 'garmin_sleep', '{\"deepSleepSeconds\":4800}')")
        gb.close()
        return f
    }

    @Test
    fun convertedExportImportsAndIsIdempotent() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "gapmin-garmin-test").also { it.mkdirs() }
        val db = JdbcSql.open(null)
        Schema.migrate(db)
        val imp = GadgetbridgeImport(db, { JdbcSql.open(it, readOnly = true) }, tmp)
        val src = source(tmp)
        repeat(2) {
            imp.run(src)
            assertEquals(1L, db.one("SELECT COUNT(*) FROM activities").lng())
            assertEquals("GARMIN-EXPORT:1746081000", db.one("SELECT id FROM activities").str())
            assertEquals(8000.0, db.one("SELECT distance_m FROM activities").num()!!, 1e-6)
            assertEquals(1L, db.one("SELECT COUNT(*) FROM metric_samples WHERE metric = 'resting_hr'").lng())
            assertEquals(2L, db.one("SELECT COUNT(*) FROM daily_summary").lng())
            assertEquals("{\"totalSteps\":9000}", db.one("SELECT data FROM daily_summary WHERE kind = 'garmin_uds'").str())
        }
    }
}
