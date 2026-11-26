-- TimescaleDB variant: hypertables, compression and continuous aggregates.
-- Applied instead of 002_aggregates.plain.sql when the extension is available.

CREATE EXTENSION IF NOT EXISTS timescaledb;

SELECT create_hypertable('minute_samples', by_range('time', INTERVAL '30 days'), if_not_exists => TRUE, migrate_data => TRUE);
SELECT create_hypertable('metric_samples', by_range('time', INTERVAL '30 days'), if_not_exists => TRUE, migrate_data => TRUE);
SELECT create_hypertable('track_points',   by_range('time', INTERVAL '90 days'), if_not_exists => TRUE, migrate_data => TRUE);

ALTER TABLE minute_samples SET (timescaledb.compress, timescaledb.compress_segmentby = 'device_id');
ALTER TABLE metric_samples SET (timescaledb.compress, timescaledb.compress_segmentby = 'metric, device_id');
ALTER TABLE track_points   SET (timescaledb.compress, timescaledb.compress_segmentby = 'activity_id');
SELECT add_compression_policy('minute_samples', INTERVAL '45 days', if_not_exists => TRUE);
SELECT add_compression_policy('metric_samples', INTERVAL '45 days', if_not_exists => TRUE);
SELECT add_compression_policy('track_points',   INTERVAL '90 days', if_not_exists => TRUE);

-- Daily activity per local (Europe/Berlin) day
CREATE MATERIALIZED VIEW IF NOT EXISTS daily_activity
WITH (timescaledb.continuous) AS
SELECT time_bucket(INTERVAL '1 day', time, 'Europe/Berlin') AS day,
       device_id,
       sum(steps)                        AS steps,
       avg(hr) FILTER (WHERE hr > 0)     AS hr_avg,
       min(hr) FILTER (WHERE hr > 0)     AS hr_min,
       max(hr)                           AS hr_max,
       sum(active_kcal)                  AS active_kcal,
       sum(distance_cm) / 100.0          AS distance_m,
       count(*) FILTER (WHERE hr > 0)    AS hr_minutes
FROM minute_samples
GROUP BY 1, 2
WITH NO DATA;

CREATE MATERIALIZED VIEW IF NOT EXISTS daily_metrics
WITH (timescaledb.continuous) AS
SELECT time_bucket(INTERVAL '1 day', time, 'Europe/Berlin') AS day,
       metric,
       device_id,
       avg(value)        AS avg,
       min(value)        AS min,
       max(value)        AS max,
       last(value, time) AS last,
       count(*)          AS n
FROM metric_samples
GROUP BY 1, 2, 3
WITH NO DATA;

SELECT add_continuous_aggregate_policy('daily_activity', start_offset => INTERVAL '60 days', end_offset => INTERVAL '1 hour',
                                       schedule_interval => INTERVAL '1 hour', if_not_exists => TRUE);
SELECT add_continuous_aggregate_policy('daily_metrics',  start_offset => INTERVAL '60 days', end_offset => INTERVAL '1 hour',
                                       schedule_interval => INTERVAL '1 hour', if_not_exists => TRUE);
