-- Plain PostgreSQL fallback (no TimescaleDB): same view names, computed on the fly.

CREATE OR REPLACE VIEW daily_activity AS
SELECT date_trunc('day', time AT TIME ZONE 'Europe/Berlin') AT TIME ZONE 'Europe/Berlin' AS day,
       device_id,
       sum(steps)                        AS steps,
       avg(hr) FILTER (WHERE hr > 0)     AS hr_avg,
       min(hr) FILTER (WHERE hr > 0)     AS hr_min,
       max(hr)                           AS hr_max,
       sum(active_kcal)                  AS active_kcal,
       sum(distance_cm) / 100.0          AS distance_m,
       count(*) FILTER (WHERE hr > 0)    AS hr_minutes
FROM minute_samples
GROUP BY 1, 2;

CREATE OR REPLACE VIEW daily_metrics AS
SELECT date_trunc('day', time AT TIME ZONE 'Europe/Berlin') AT TIME ZONE 'Europe/Berlin' AS day,
       metric,
       device_id,
       avg(value) AS avg,
       min(value) AS min,
       max(value) AS max,
       (array_agg(value ORDER BY time DESC))[1] AS last,
       count(*)   AS n
FROM metric_samples
GROUP BY 1, 2, 3;
