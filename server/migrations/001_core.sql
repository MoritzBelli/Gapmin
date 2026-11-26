-- Gapmin NAS database: mirror of the app's SQLite schema (web/src/db/schema.ts)
-- with real timestamps. Timestamps are UTC (timestamptz); local-day logic uses Europe/Berlin.

CREATE TABLE IF NOT EXISTS devices (
  id           text PRIMARY KEY,
  name         text,
  manufacturer text,
  model        text
);

CREATE TABLE IF NOT EXISTS activities (
  id            text PRIMARY KEY,
  device_id     text,
  kind          integer,
  sport         text,
  category      text,
  name          text,
  start_time    timestamptz NOT NULL,
  end_time      timestamptz,
  duration_s    double precision,
  distance_m    double precision,
  avg_hr        double precision,
  max_hr        double precision,
  min_hr        double precision,
  avg_pace_s_km double precision,
  ascent_m      double precision,
  descent_m     double precision,
  calories      double precision,
  avg_cadence   double precision,
  te_aerobic    double precision,
  te_anaerobic  double precision,
  recovery_h    double precision,
  pace_cv       double precision,
  has_track     boolean NOT NULL DEFAULT false,
  route_preview jsonb,
  summary       jsonb,
  updated_at    timestamptz
);
CREATE INDEX IF NOT EXISTS activities_start ON activities (start_time DESC);
CREATE INDEX IF NOT EXISTS activities_sport ON activities (sport, start_time DESC);

CREATE TABLE IF NOT EXISTS track_points (
  activity_id text        NOT NULL,
  time        timestamptz NOT NULL,
  lat double precision, lon double precision, ele real,
  hr smallint, cadence smallint, speed real, dist_m real,
  PRIMARY KEY (activity_id, time)
);

CREATE TABLE IF NOT EXISTS minute_samples (
  device_id   text        NOT NULL,
  time        timestamptz NOT NULL,
  hr          smallint,
  steps       integer,
  intensity   integer,
  kind        integer,
  distance_cm integer,
  active_kcal integer,
  PRIMARY KEY (device_id, time)
);

CREATE TABLE IF NOT EXISTS metric_samples (
  metric    text        NOT NULL,
  device_id text        NOT NULL,
  time      timestamptz NOT NULL,
  value     double precision,
  PRIMARY KEY (metric, device_id, time)
);

CREATE TABLE IF NOT EXISTS hrv_summary (
  device_id               text        NOT NULL,
  time                    timestamptz NOT NULL,
  weekly_avg              real,
  last_night_avg          real,
  last_night_5min_high    real,
  baseline_low_upper      real,
  baseline_balanced_lower real,
  baseline_balanced_upper real,
  status                  smallint,
  PRIMARY KEY (device_id, time)
);

CREATE TABLE IF NOT EXISTS sleep_nights (
  night            date PRIMARY KEY,
  device_id        text,
  bed_time         timestamptz,
  onset_time       timestamptz,
  wake_time        timestamptz,
  out_time         timestamptz,
  tib_min          real,
  tst_min          real,
  sol_min          real,
  waso_min         real,
  awakenings       integer,
  awakenings_long  integer,
  efficiency       real,
  deep_min         real,
  light_min        real,
  rem_min          real,
  awake_min        real,
  hr_avg           real,
  hr_min           real,
  hrv_avg          real,
  stress_avg       real,
  resp_avg         real,
  spo2_avg         real,
  bb_gain          real,
  midpoint_min     real,
  regularity_sd    real,
  score            smallint,
  score_duration   real,
  score_continuity real,
  score_recovery   real,
  score_stages     real,
  score_regularity real,
  score_breathing  real,
  coverage         real,
  garmin_score     smallint,
  details          jsonb,
  updated_at       timestamptz
);

CREATE TABLE IF NOT EXISTS sync_log (
  id         bigserial PRIMARY KEY,
  received   timestamptz NOT NULL DEFAULT now(),
  bytes      bigint,
  rows       bigint,
  ms         integer,
  client     text
);
