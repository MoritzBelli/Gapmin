#!/usr/bin/env python3
"""Build a synthetic Gadgetbridge export (ZIP) for a Garmin Forerunner.

The ZIP mirrors Gadgetbridge's "Export ZIP":
    database/Gadgetbridge          SQLite DB, Garmin tables as defined in GBDaoGenerator.java
    files/<MAC>/ACTIVITY/<id>.gpx  one track per run (real exports usually hold .fit files)

Contents: ~60 runs plus ~150 days of all-day health data (minute HR/steps, stress,
Body Battery, SpO2, respiration, HRV, sleep stages, sleep score, resting HR, HRV status).

Usage:
    python3 scripts/make_demo_export.py [--days 150] [--seed 42] [--out web/public/demo/gadgetbridge-demo.zip]
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sqlite3
import tempfile
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from routes import build_run, to_gpx

MAC = "C4:5A:11:26:5F:03"
PHONE_FILES = "/storage/emulated/0/Android/data/nodomain.freeyourgadget.gadgetbridge/files"
KIND_RUNNING = 0x00000010
KIND_TRAIL_RUN = 0x040000F2
END = datetime(2026, 11, 26, tzinfo=timezone.utc)

SCHEMA = """
CREATE TABLE DEVICE (_id INTEGER PRIMARY KEY, NAME TEXT NOT NULL, MANUFACTURER TEXT NOT NULL,
  IDENTIFIER TEXT NOT NULL UNIQUE, TYPE INTEGER NOT NULL, TYPE_NAME TEXT NOT NULL, MODEL TEXT,
  ALIAS TEXT, PARENT_FOLDER TEXT);
CREATE TABLE USER (_id INTEGER PRIMARY KEY, NAME TEXT NOT NULL, BIRTHDAY INTEGER NOT NULL, GENDER INTEGER NOT NULL);
CREATE TABLE BASE_ACTIVITY_SUMMARY (_id INTEGER PRIMARY KEY, NAME TEXT, START_TIME INTEGER NOT NULL,
  END_TIME INTEGER NOT NULL, ACTIVITY_KIND INTEGER NOT NULL, BASE_LONGITUDE INTEGER, BASE_LATITUDE INTEGER,
  BASE_ALTITUDE INTEGER, GPX_TRACK TEXT, RAW_DETAILS_PATH TEXT, DEVICE_ID INTEGER NOT NULL,
  USER_ID INTEGER NOT NULL, SUMMARY_DATA TEXT, RAW_SUMMARY_DATA BLOB);
CREATE TABLE GARMIN_ACTIVITY_SAMPLE (TIMESTAMP INTEGER NOT NULL, DEVICE_ID INTEGER NOT NULL, USER_ID INTEGER NOT NULL,
  RAW_INTENSITY INTEGER NOT NULL, STEPS INTEGER NOT NULL, RAW_KIND INTEGER NOT NULL, HEART_RATE INTEGER NOT NULL,
  DISTANCE_CM INTEGER NOT NULL, ACTIVE_CALORIES INTEGER NOT NULL, PRIMARY KEY (TIMESTAMP, DEVICE_ID)) WITHOUT ROWID;
"""
TIME_TABLES = {
    # table: extra column DDL
    "GARMIN_STRESS_SAMPLE": "STRESS INTEGER NOT NULL",
    "GARMIN_BODY_ENERGY_SAMPLE": "ENERGY INTEGER NOT NULL",
    "GARMIN_SPO2_SAMPLE": "SPO2 INTEGER NOT NULL",
    "GARMIN_SLEEP_STAGE_SAMPLE": "STAGE INTEGER NOT NULL",
    "GARMIN_HRV_VALUE_SAMPLE": "VALUE INTEGER NOT NULL",
    "GARMIN_RESPIRATORY_RATE_SAMPLE": "RESPIRATORY_RATE REAL NOT NULL",
    "GARMIN_HEART_RATE_RESTING_SAMPLE": "HEART_RATE INTEGER NOT NULL",
    "GARMIN_SLEEP_STATS_SAMPLE": "SLEEP_SCORE INTEGER NOT NULL",
    "GARMIN_INTENSITY_MINUTES_SAMPLE": "MODERATE INTEGER, VIGOROUS INTEGER",
    "GARMIN_HRV_SUMMARY_SAMPLE": (
        "WEEKLY_AVERAGE INTEGER, LAST_NIGHT_AVERAGE INTEGER, LAST_NIGHT5_MIN_HIGH INTEGER, "
        "BASELINE_LOW_UPPER INTEGER, BASELINE_BALANCED_LOWER INTEGER, BASELINE_BALANCED_UPPER INTEGER, STATUS_NUM INTEGER"
    ),
}
# Garmin FIT sleep levels as stored by Gadgetbridge: 1 awake, 2 light, 3 deep, 4 REM
AWAKE, LIGHT, DEEP, REM = 1, 2, 3, 4


def ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def entry(value, unit, group=None):
    e = {"value": value, "unit": unit}
    if group:
        e["group"] = group
    return e


def make_runs(rng: random.Random, days: int):
    runs, day = [], END - timedelta(days=1)
    while day > END - timedelta(days=days):
        start = day.replace(hour=rng.choice([6, 7, 7, 12, 17, 18, 19]), minute=rng.randrange(0, 60, 5))
        meta, pts = build_run(rng, len(runs), start)
        runs.append((meta, pts))
        day -= timedelta(days=rng.choice([1, 1, 2, 2, 2, 3]))
    return runs


def sleep_night(rng: random.Random, bed: datetime, wake: datetime, quality: float):
    """Stage change events for one night (90-min cycles, more deep early, more REM late)."""
    events, t = [], bed
    events.append((t, AWAKE))
    t += timedelta(minutes=rng.randint(5, 20))
    total = (wake - bed).total_seconds() / 60
    while t < wake - timedelta(minutes=10):
        frac = (t - bed).total_seconds() / 60 / total
        for stage, base in ((LIGHT, 25), (DEEP, 30 * (1 - frac) * quality + 3), (LIGHT, 15), (REM, 8 + 25 * frac)):
            dur = max(3, int(rng.gauss(base, base * 0.25)))
            events.append((t, stage))
            t += timedelta(minutes=dur)
            if rng.random() < 0.3 * (1.35 - quality):
                events.append((t, AWAKE))
                t += timedelta(minutes=rng.choice([1, 2, 3, 4, 6, 8, 12, 18]))
            if t >= wake:
                break
    events.append((wake, AWAKE))
    return [(a, s) for a, s in events if a <= wake]


def build(days: int, seed: int, out: Path) -> None:
    rng = random.Random(seed)
    runs = make_runs(rng, days)
    run_by_day = {}
    for meta, pts in runs:
        run_by_day.setdefault(pts[0].time.date(), []).append((meta, pts))

    tmp = Path(tempfile.mkdtemp())
    db_path = tmp / "Gadgetbridge"
    db = sqlite3.connect(db_path)
    db.executescript(SCHEMA)
    for table, cols in TIME_TABLES.items():
        db.execute(
            f"CREATE TABLE {table} (TIMESTAMP INTEGER NOT NULL, DEVICE_ID INTEGER NOT NULL, USER_ID INTEGER NOT NULL, "
            f"{cols}, PRIMARY KEY (TIMESTAMP, DEVICE_ID)) WITHOUT ROWID"
        )
    db.execute("INSERT INTO USER VALUES (1, 'Mo', 0, 1)")
    db.execute(
        "INSERT INTO DEVICE VALUES (1, 'Forerunner 265', 'Garmin', ?, 0, 'GARMIN_FORERUNNER_265', '3990', NULL, NULL)",
        (MAC,),
    )

    files: dict[str, str] = {}
    # ---- activities -------------------------------------------------------
    for i, (meta, pts) in enumerate(sorted(runs, key=lambda r: r[1][0].time)):
        rel = f"{MAC}/ACTIVITY/{pts[0].time:%Y}/{pts[0].time:%Y-%m-%d-%H-%M-%S}.gpx"
        files[rel] = to_gpx(meta, pts)
        hr = [p.hr for p in pts]
        summary = {
            "distanceMeters": entry(meta["distance_m"], "meters"),
            "activeSeconds": entry(meta["duration_s"], "seconds"),
            "averageHR": entry(meta["avg_hr"], "bpm", "HeartRateZones"),
            "maxHR": entry(max(hr), "bpm", "HeartRateZones"),
            "minHR": entry(min(hr), "bpm", "HeartRateZones"),
            "ascentMeters": entry(meta["elevation_gain_m"], "meters", "Elevation"),
            "averageKMPaceSeconds": entry(round(meta["avg_pace_s_per_km"]), "seconds_km", "Pace"),
            "averageCadence": entry(rng.randint(164, 182), "spm", "Cadence"),
            "caloriesBurnt": entry(round(meta["distance_m"] / 1000 * rng.uniform(62, 72)), "calories"),
            "aerobicTrainingEffect": entry(round(rng.uniform(2.3, 4.6), 1), "", "TrainingEffect"),
            "anaerobicTrainingEffect": entry(round(rng.uniform(0.0, 3.2) if meta["type"] in ("Intervals", "Tempo Run") else rng.uniform(0, 1), 1), "", "TrainingEffect"),
            "recoveryTime": entry(rng.randint(8, 60), "hours"),
            "internal_hasGps": entry("true", "string"),
        }
        kind = KIND_TRAIL_RUN if meta["type"] == "Trail Run" else KIND_RUNNING
        db.execute(
            "INSERT INTO BASE_ACTIVITY_SUMMARY VALUES (?, NULL, ?, ?, ?, NULL, NULL, NULL, ?, ?, 1, 1, ?, NULL)",
            (i + 1, ms(pts[0].time), ms(pts[-1].time), kind, f"{PHONE_FILES}/{rel}",
             f"{PHONE_FILES}/{rel.replace('.gpx', '.fit')}", json.dumps(summary)),
        )

    # ---- all-day health data ---------------------------------------------
    first_day = (END - timedelta(days=days)).date()
    fitness = 0.0  # crude fatigue model: hard days push HRV down / RHR up
    hrv_hist: list[int] = []
    bb = 70.0
    for d in range(days):
        day = first_day + timedelta(days=d)
        day0 = datetime(day.year, day.month, day.day, tzinfo=timezone.utc)
        load = sum(m["duration_s"] / 60 * (m["avg_hr"] - 100) / 50 for m, _ in run_by_day.get(day, []))
        fitness = fitness * 0.8 + load
        quality = min(1.1, max(0.45, rng.gauss(0.8, 0.15) - fitness / 900))

        # Night ending this morning (local time is UTC+1; weekends later and longer)
        weekend = day.weekday() in (5, 6)
        bed = day0 - timedelta(hours=rng.gauss(1.0, 0.7) - (0.9 if weekend else 0))
        wake = day0 + timedelta(hours=rng.gauss(6.6, 0.55) + (1.3 if weekend else 0))
        if rng.random() < 0.08:  # occasional short night
            bed += timedelta(hours=rng.uniform(1, 2))
        events = sleep_night(rng, bed, wake, quality)
        db.executemany("INSERT OR IGNORE INTO GARMIN_SLEEP_STAGE_SAMPLE VALUES (?,1,1,?)", [(ms(t), s) for t, s in events])
        sleep_h = (wake - bed).total_seconds() / 3600
        score = int(max(35, min(98, 45 + quality * 40 + (sleep_h - 6.5) * 6 + rng.gauss(0, 4))))
        db.execute("INSERT INTO GARMIN_SLEEP_STATS_SAMPLE VALUES (?,1,1,?)", (ms(wake), score))

        hrv_night = int(max(28, rng.gauss(58, 4) - fitness / 60 + (quality - 0.85) * 20))
        hrv_hist.append(hrv_night)
        t = bed + timedelta(minutes=20)
        vals = []
        while t < wake:
            v = max(15, int(rng.gauss(hrv_night, 9)))
            vals.append(v)
            db.execute("INSERT INTO GARMIN_HRV_VALUE_SAMPLE VALUES (?,1,1,?)", (ms(t), v))
            t += timedelta(minutes=5)
        weekly = round(sum(hrv_hist[-7:]) / len(hrv_hist[-7:]))
        status = 4 if 50 <= weekly <= 66 else 3 if weekly < 50 else 4
        db.execute(
            "INSERT INTO GARMIN_HRV_SUMMARY_SAMPLE VALUES (?,1,1,?,?,?,?,?,?,?)",
            (ms(wake), weekly, hrv_night, max(vals) if vals else hrv_night, 46, 50, 66, status),
        )
        rhr = int(round(rng.gauss(47, 1.2) + fitness / 120 + (0.85 - quality) * 6))
        db.execute("INSERT INTO GARMIN_HEART_RATE_RESTING_SAMPLE VALUES (?,1,1,?)", (ms(day0), rhr))

        # Minute samples for the whole day
        runs_today = run_by_day.get(day, [])
        windows = [(p[0].time, p[-1].time, m) for m, p in runs_today]
        samples, stress_rows, bb_rows, resp_rows, spo2_rows = [], [], [], [], []
        moderate = vigorous = 0
        for minute in range(24 * 60):
            ts = day0 + timedelta(minutes=minute)
            asleep = ts < wake
            run = next((m for a, b, m in windows if a <= ts <= b), None)
            if run:
                hr = int(rng.gauss(run["avg_hr"], 6))
                steps, intensity, kind = rng.randint(160, 182), 120, 3
                dist = int(steps * rng.uniform(95, 125))
                kcal = rng.randint(10, 15)
                if hr > 150:
                    vigorous += 1
                else:
                    moderate += 1
            elif asleep:
                hr = int(rng.gauss(rhr + 3, 2.5))
                steps, intensity, kind, dist, kcal = 0, 2, 1, 0, 0
            else:
                h = ts.hour + ts.minute / 60
                busy = 1 if 8 <= h <= 9 or 12 <= h <= 13 or 17 <= h <= 18.5 else 0.3
                steps = max(0, int(rng.expovariate(1 / (35 * busy)))) if rng.random() < 0.55 else 0
                hr = int(rng.gauss(rhr + 18 + steps / 6, 5))
                intensity, kind = min(100, steps), 0
                dist, kcal = int(steps * 72), 1 if steps > 40 else 0
                if steps > 100:
                    moderate += 1
            samples.append((int(ts.timestamp()), intensity, steps, kind, max(35, hr), dist, kcal))

            if minute % 3 == 0:
                if asleep:
                    stress = int(max(0, rng.gauss(12, 5)))
                elif run:
                    stress = -2  # Garmin: "activity", no stress value
                else:
                    stress = int(max(0, min(99, rng.gauss(30 + fitness / 20, 14))))
                stress_rows.append((ms(ts), stress))
                # Body Battery: charges while asleep (by sleep quality), drains by stress/activity
                if asleep:
                    bb += 0.55 * quality
                elif run:
                    bb -= 0.9
                else:
                    bb -= 0.06 + max(0, stress - 25) * 0.006
                bb = max(5.0, min(100.0, bb))
                if minute % 15 == 0:
                    bb_rows.append((ms(ts), int(bb)))
            if minute % 15 == 0:
                resp_rows.append((ms(ts), round(rng.gauss(14.2 if asleep else 16.5, 0.8), 1)))
            if asleep and minute % 60 == 0:
                spo2_rows.append((ms(ts), int(min(100, rng.gauss(95.5, 1.4)))))

        db.executemany("INSERT INTO GARMIN_ACTIVITY_SAMPLE VALUES (?,1,1,?,?,?,?,?,?)", samples)
        db.executemany("INSERT INTO GARMIN_STRESS_SAMPLE VALUES (?,1,1,?)", stress_rows)
        db.executemany("INSERT INTO GARMIN_BODY_ENERGY_SAMPLE VALUES (?,1,1,?)", bb_rows)
        db.executemany("INSERT INTO GARMIN_RESPIRATORY_RATE_SAMPLE VALUES (?,1,1,?)", resp_rows)
        db.executemany("INSERT INTO GARMIN_SPO2_SAMPLE VALUES (?,1,1,?)", spo2_rows)
        db.execute(
            "INSERT INTO GARMIN_INTENSITY_MINUTES_SAMPLE VALUES (?,1,1,?,?)",
            (ms(day0 + timedelta(hours=23, minutes=59)), moderate, vigorous),
        )

    db.commit()
    db.execute("VACUUM")
    db.close()

    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr("metadata.json", json.dumps({"appVersionName": "0.94.0", "demo": True, "created": END.isoformat()}))
        z.write(db_path, "database/Gadgetbridge")
        for rel, text in files.items():
            z.writestr(f"files/{rel}", text)
    print(f"Wrote {out} ({out.stat().st_size / 1e6:.1f} MB): {len(runs)} runs, {days} days of health data")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--days", type=int, default=150)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", type=Path, default=Path(__file__).resolve().parent.parent / "web/public/demo/gadgetbridge-demo.zip")
    args = ap.parse_args()
    build(args.days, args.seed, args.out)


if __name__ == "__main__":
    main()
