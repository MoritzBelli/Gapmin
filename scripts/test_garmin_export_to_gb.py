#!/usr/bin/env python3
"""Test für garmin_export_to_gb.py mit einem synthetischen Garmin-Export (Struktur laut echtem Export, Werte erfunden).

    pip install fitdecode fit-tool
    python scripts/test_garmin_export_to_gb.py
"""

from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import garmin_export_to_gb as conv  # noqa: E402

from fit_tool.fit_file_builder import FitFileBuilder  # noqa: E402
from fit_tool.profile.messages.file_id_message import FileIdMessage  # noqa: E402
from fit_tool.profile.messages.record_message import RecordMessage  # noqa: E402
from fit_tool.profile.messages.session_message import SessionMessage  # noqa: E402
from fit_tool.profile.profile_type import FileType, Manufacturer  # noqa: E402

BASE = datetime(2025, 5, 1, 6, 30, 0, tzinfo=timezone.utc)


def ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def make_fit(start: datetime, dist_m: float, secs: int, ascent: int) -> bytes:
    b = FitFileBuilder(auto_define=True, min_string_size=50)
    f = FileIdMessage()
    f.type = FileType.ACTIVITY
    f.manufacturer = Manufacturer.GARMIN.value
    f.time_created = ms(start)
    b.add(f)
    for i in range(0, secs, 10):
        r = RecordMessage()
        r.timestamp = ms(start + timedelta(seconds=i))
        r.position_lat = 48.37 + i * 1e-6
        r.position_long = 10.89 + i * 1e-6
        r.heart_rate = 140 + (i // 60) % 20
        r.distance = dist_m * i / secs
        b.add(r)
    s = SessionMessage()
    s.timestamp = ms(start + timedelta(seconds=secs))
    s.start_time = ms(start)
    s.total_distance = dist_m
    s.total_timer_time = float(secs)
    s.total_elapsed_time = float(secs)
    s.total_ascent = ascent
    b.add(s)
    return b.build().to_bytes()


def build_export(root: Path, n: int = 6) -> list[tuple[datetime, float, int]]:
    """Synthetischer Export: Aktivitäten (distance in cm, duration in ms – wie im echten Export), FIT-Zip, Tagesdaten."""
    fitdir = root / "DI_CONNECT" / "DI-Connect-Uploaded-Files"
    fitdir.mkdir(parents=True)
    fitdir2 = root / "DI_CONNECT" / "DI-Connect-Fitness"
    fitdir2.mkdir(parents=True)
    acts, truth = [], []
    with zipfile.ZipFile(fitdir / "UploadedFiles_0-_Part1.zip", "w") as z:
        for i in range(n):
            start = BASE + timedelta(days=i * 2)
            dist, secs, asc = 8000.0 + 500 * i, 2400 + 60 * i, 40 + i
            truth.append((start, dist, secs))
            # FIT-Datei beginnt 3 s nach der Aktivitätszeit (wie bei Timer-Start), eine Aktivität (letzte) hat keine FIT
            if i < n - 1:
                z.writestr(f"user@example.com_{1000000000 + i}.fit", make_fit(start + timedelta(seconds=3), dist, secs, asc))
            acts.append({
                "activityId": 9000 + i, "activityType": "running" if i % 2 == 0 else "trail_running", "name": f"Lauf {i}",
                "startTimeGmt": float(ms(start)), "beginTimestamp": ms(start),
                "distance": dist * 100, "duration": secs * 1000.0, "elapsedDuration": secs * 1000.0 + 5000,
                "elevationGain": asc * 100.0, "elevationLoss": asc * 100.0 - 300,
                "avgHr": 150.0, "maxHr": 175.0, "minHr": 90.0, "calories": 600.0,
                "avgDoubleCadence": 168.0, "aerobicTrainingEffect": 3.2, "anaerobicTrainingEffect": 0.4,
            })
    (fitdir2 / "user@example.com_0_summarizedActivities.json").write_text(json.dumps([{"summarizedActivitiesExport": acts}]))

    wl = root / "DI_CONNECT" / "DI-Connect-Wellness"
    wl.mkdir(parents=True)
    days = [BASE + timedelta(days=d) for d in range(5)]
    (root / "DI_CONNECT" / "DI-Connect-Aggregator").mkdir(parents=True)
    (root / "DI_CONNECT" / "DI-Connect-Aggregator" / "UDSFile_2025-05-01_2025-05-06.json").write_text(json.dumps([
        {"calendarDate": d.strftime("%Y-%m-%d"), "restingHeartRate": 50 + i, "restingHeartRateTimestamp": ms(d)} for i, d in enumerate(days)
    ]))
    (wl / "2025-05-01_2025-05-06_123456_sleepData.json").write_text(json.dumps(
        [{"retro": True}] + [{"calendarDate": d.strftime("%Y-%m-%d"), "sleepEndTimestampGMT": d.strftime("%Y-%m-%dT%H:%M:%S.0"), "deepSleepSeconds": 4800, "sleepScores": {"overallScore": 70 + i}} for i, d in enumerate(days)]
    ))
    (wl / "2025-05-01_2025-05-06_123456_healthStatusData.json").write_text(json.dumps([
        {"calendarDate": d.strftime("%Y-%m-%d"), "metrics": [
            {"type": "HRV", "value": 60.0 + i, "baselineLowerLimit": 50.0, "baselineUpperLimit": 80.0, "status": "IN_RANGE"},
            {"type": "HR", "value": 50.0, "status": "IN_RANGE"}]} for i, d in enumerate(days)
    ]))
    met = root / "DI_CONNECT" / "DI-Connect-Metrics"
    met.mkdir(parents=True)
    (met / "TrainingReadinessDTO_20250501_20250506_1.json").write_text(json.dumps([
        {"calendarDate": d.strftime("%Y-%m-%d"), "hrvWeeklyAverage": 62.0 + i} for i, d in enumerate(days)
    ]))
    return truth


class ConverterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.root = self.tmp / "garmin_export"
        self.truth = build_export(self.root)
        self.out = self.tmp / "out.zip"
        conv.build(self.root, self.out, "AA:BB:CC:DD:EE:FF", keep_names=False)
        self.z = zipfile.ZipFile(self.out)
        (self.tmp / "gb.sqlite").write_bytes(self.z.read("database/Gadgetbridge"))
        self.db = sqlite3.connect(self.tmp / "gb.sqlite")

    def test_activities_units_and_tracks(self):
        rows = self.db.execute("SELECT START_TIME, END_TIME, ACTIVITY_KIND, RAW_DETAILS_PATH, SUMMARY_DATA, NAME FROM BASE_ACTIVITY_SUMMARY ORDER BY START_TIME").fetchall()
        self.assertEqual(len(rows), 6)
        for (start, dist, secs), (st, en, kind, path, summ, name) in zip(self.truth, rows):
            s = json.loads(summ)
            self.assertEqual(st, ms(start))
            self.assertAlmostEqual(s["distanceMeters"]["value"], dist, places=3)  # cm → m
            self.assertAlmostEqual(s["activeSeconds"]["value"], secs, places=3)  # ms → s
            self.assertIsNone(name)
            self.assertGreater(en, st)
        self.assertEqual([r[2] for r in rows][:2], [0x10, 0x040000F2])
        # fünf mit Strecke, die letzte ohne FIT
        with_track = [r[3] for r in rows if r[3]]
        self.assertEqual(len(with_track), 5)
        self.assertIsNone(rows[-1][3])
        names = set(self.z.namelist())
        for p in with_track:
            rel = p.split("/files/", 1)[1]
            self.assertIn("files/" + rel, names)
            self.assertTrue(self.z.read("files/" + rel)[8:12] == b".FIT")

    def test_health_series(self):
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM GARMIN_HEART_RATE_RESTING_SAMPLE").fetchone()[0], 5)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM GARMIN_SLEEP_STATS_SAMPLE").fetchone()[0], 5)  # retro-Eintrag ohne Score fällt weg
        w, last, low, high, status = self.db.execute(
            "SELECT WEEKLY_AVERAGE, LAST_NIGHT_AVERAGE, BASELINE_BALANCED_LOWER, BASELINE_BALANCED_UPPER, STATUS_NUM FROM GARMIN_HRV_SUMMARY_SAMPLE ORDER BY TIMESTAMP LIMIT 1"
        ).fetchone()
        self.assertEqual((w, last, low, high, status), (62, 60, 50, 80, 4))

    def test_daily_summary(self):
        rows = self.db.execute("SELECT KIND, COUNT(*) FROM GAPMIN_DAILY GROUP BY KIND").fetchall()
        self.assertEqual(dict(rows), {"garmin_uds": 5, "garmin_sleep": 5, "garmin_readiness": 5, "garmin_health": 5})
        data = json.loads(self.db.execute("SELECT DATA FROM GAPMIN_DAILY WHERE KIND='garmin_sleep' AND DATE='2025-05-01'").fetchone()[0])
        self.assertEqual(data["deepSleepSeconds"], 4800)

    def test_cal_date(self):
        self.assertEqual(conv.cal_date("2025-05-01"), "2025-05-01")
        self.assertEqual(conv.cal_date(20250501), "2025-05-01")
        self.assertEqual(conv.cal_date(ms(BASE)), "2025-05-01")
        self.assertEqual(conv.cal_date(int(BASE.timestamp())), "2025-05-01")
        self.assertIsNone(conv.cal_date(None))

    def test_importer_column_names(self):
        """Spalten, die GadgetbridgeImport.kt nach Normalisierung (ohne _, klein) erwartet."""
        def norm(table):
            return {r[1].replace("_", "").lower() for r in self.db.execute(f"PRAGMA table_info({table})")}
        self.assertTrue({"name", "starttime", "endtime", "activitykind", "gpxtrack", "rawdetailspath", "deviceid", "summarydata"} <= norm("BASE_ACTIVITY_SUMMARY"))
        self.assertTrue({"identifier", "name", "manufacturer", "model"} <= norm("DEVICE"))
        self.assertTrue({"date", "kind", "data"} <= norm("GAPMIN_DAILY"))
        self.assertTrue({"timestamp", "deviceid", "heartrate"} <= norm("GARMIN_HEART_RATE_RESTING_SAMPLE"))
        self.assertTrue({"timestamp", "deviceid", "sleepscore"} <= norm("GARMIN_SLEEP_STATS_SAMPLE"))
        self.assertTrue({"weeklyaverage", "lastnightaverage", "lastnight5minhigh", "baselinelowupper", "baselinebalancedlower",
                         "baselinebalancedupper", "statusnum"} <= norm("GARMIN_HRV_SUMMARY_SAMPLE"))

    def test_iso_parser(self):
        self.assertEqual(conv.iso_to_s("2025-05-01T06:30:00.0"), int(BASE.timestamp()))
        self.assertEqual(conv.iso_to_s("2025-05-01T06:30:00Z"), int(BASE.timestamp()))
        self.assertEqual(conv.iso_to_s(ms(BASE)), int(BASE.timestamp()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
