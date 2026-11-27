package de.moritzbelli.gapmin.core

/**
 * App database schema (SQLite). Same tables as the former web app, so an existing
 * gapmin.sqlite keeps working, and the NAS mirrors them in PostgreSQL (server/migrations).
 *
 * Conventions: timestamps are UTC unix seconds (`*_ts`), distances in metres,
 * durations in seconds unless the column name says otherwise (`*_min`).
 */
object Schema {
    const val VERSION = 1

    val SQL = """
CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT
);

CREATE TABLE IF NOT EXISTS devices (
  id           TEXT PRIMARY KEY,   -- Bluetooth identifier (MAC) from Gadgetbridge
  name         TEXT,
  manufacturer TEXT,
  model        TEXT
);

CREATE TABLE IF NOT EXISTS activities (
  id             TEXT PRIMARY KEY, -- "<device>:<start_ts>"
  device_id      TEXT,
  kind           INTEGER,          -- Gadgetbridge ActivityKind code
  sport          TEXT,             -- running | trail_running | cycling | walking | other ...
  category       TEXT,             -- runs: easy | recovery | long | tempo | intervals | trail
  name           TEXT,
  start_ts       INTEGER NOT NULL,
  end_ts         INTEGER,
  duration_s     REAL,
  distance_m     REAL,
  avg_hr         REAL,
  max_hr         REAL,
  min_hr         REAL,
  avg_pace_s_km  REAL,
  ascent_m       REAL,
  descent_m      REAL,
  calories       REAL,
  avg_cadence    REAL,
  te_aerobic     REAL,
  te_anaerobic   REAL,
  recovery_h     REAL,
  pace_cv        REAL,             -- variation of 30 s pace (detects intervals)
  has_track      INTEGER NOT NULL DEFAULT 0,
  route_preview  TEXT,             -- JSON [[lat, lon], ...] simplified, for gallery tiles
  summary_json   TEXT,             -- raw Gadgetbridge summary (all fields)
  updated_at     INTEGER
);
CREATE INDEX IF NOT EXISTS activities_start ON activities(start_ts);

CREATE TABLE IF NOT EXISTS track_points (
  activity_id TEXT    NOT NULL,
  ts          INTEGER NOT NULL,
  lat REAL, lon REAL, ele REAL,
  hr INTEGER, cadence INTEGER, speed REAL, dist_m REAL,
  PRIMARY KEY (activity_id, ts)
) WITHOUT ROWID;

-- All-day minute data (Garmin activity samples)
CREATE TABLE IF NOT EXISTS minute_samples (
  device_id   TEXT    NOT NULL,
  ts          INTEGER NOT NULL,
  hr          INTEGER,
  steps       INTEGER,
  intensity   INTEGER,
  kind        INTEGER,
  distance_cm INTEGER,
  active_kcal INTEGER,
  PRIMARY KEY (device_id, ts)
) WITHOUT ROWID;

-- Single-value time series: stress, body_battery, spo2, respiration, hrv,
-- resting_hr, sleep_stage, garmin_sleep_score, intensity_moderate, intensity_vigorous
CREATE TABLE IF NOT EXISTS metric_samples (
  metric    TEXT    NOT NULL,
  device_id TEXT    NOT NULL,
  ts        INTEGER NOT NULL,
  value     REAL,
  PRIMARY KEY (metric, device_id, ts)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS hrv_summary (
  device_id               TEXT    NOT NULL,
  ts                      INTEGER NOT NULL,
  weekly_avg              REAL,
  last_night_avg          REAL,
  last_night_5min_high    REAL,
  baseline_low_upper      REAL,
  baseline_balanced_lower REAL,
  baseline_balanced_upper REAL,
  status                  INTEGER,
  PRIMARY KEY (device_id, ts)
) WITHOUT ROWID;

-- Daily records that have no time series behind them, e.g. from a Garmin Connect data export
-- (kind: garmin_uds | garmin_sleep | garmin_readiness | garmin_health | garmin_load | ...; data = raw JSON of that day)
CREATE TABLE IF NOT EXISTS daily_summary (
  date TEXT NOT NULL,                 -- local calendar date YYYY-MM-DD
  kind TEXT NOT NULL,
  data TEXT,
  PRIMARY KEY (date, kind)
) WITHOUT ROWID;

-- One row per night, computed by the app (see docs/research/schlaf-score.md)
CREATE TABLE IF NOT EXISTS sleep_nights (
  night            TEXT PRIMARY KEY,  -- local date of waking up, YYYY-MM-DD
  device_id        TEXT,
  bed_ts           INTEGER,
  onset_ts         INTEGER,
  wake_ts          INTEGER,
  out_ts           INTEGER,
  tib_min          REAL,
  tst_min          REAL,
  sol_min          REAL,
  waso_min         REAL,
  awakenings       INTEGER,
  awakenings_long  INTEGER,
  efficiency       REAL,
  deep_min         REAL,
  light_min        REAL,
  rem_min          REAL,
  awake_min        REAL,
  hr_avg           REAL,
  hr_min           REAL,
  hrv_avg          REAL,
  stress_avg       REAL,
  resp_avg         REAL,
  spo2_avg         REAL,
  bb_gain          REAL,
  midpoint_min     REAL,              -- sleep midpoint, minutes after local midnight
  regularity_sd    REAL,              -- SD of onset/wake time over 7 nights (min)
  score            INTEGER,           -- Gapmin Sleep Score
  score_duration   REAL,
  score_continuity REAL,
  score_recovery   REAL,
  score_stages     REAL,
  score_regularity REAL,
  score_breathing  REAL,
  coverage         REAL,              -- share of weights backed by data (0-1)
  garmin_score     INTEGER,
  details          TEXT,              -- JSON: hypnogram, sub-metrics, factors
  updated_at       INTEGER
);
"""

    fun migrate(db: Sql) {
        for (stmt in SQL.split(';')) {
            val s = stmt.lines().filterNot { it.trim().startsWith("--") }.joinToString("\n")
                .replace(Regex("--[^\n]*"), "").trim()
            if (s.isNotEmpty()) db.exec(s)
        }
        db.exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', ?)", listOf(VERSION.toString()))
    }
}
