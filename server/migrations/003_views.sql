-- Analysis views shared by both variants. Readable SQL for the dashboard and the local AI.

-- Weekly running volume
CREATE OR REPLACE VIEW weekly_running AS
SELECT date_trunc('week', start_time AT TIME ZONE 'Europe/Berlin')::date AS week,
       count(*)                                  AS runs,
       round((sum(distance_m) / 1000)::numeric, 1) AS km,
       round((sum(duration_s) / 3600)::numeric, 2) AS hours,
       round(avg(avg_hr)::numeric, 0)            AS avg_hr,
       round((sum(duration_s) / nullif(sum(distance_m) / 1000, 0))::numeric, 0) AS avg_pace_s_km,
       round(sum(ascent_m)::numeric, 0)          AS ascent_m
FROM activities
WHERE sport IN ('running', 'trail_running', 'treadmill', 'indoor_running', 'indoor_track', 'cross_country_running')
GROUP BY 1;

-- One row per local day: the main table for long-term trends
CREATE OR REPLACE VIEW daily_summary AS
SELECT d.day::date                                                   AS day,
       d.steps,
       round(d.hr_avg::numeric, 1)                                   AS hr_avg,
       rhr.last                                                      AS resting_hr,
       round(st.avg::numeric, 1)                                     AS stress_avg,
       bb.max                                                        AS body_battery_max,
       bb.min                                                        AS body_battery_min,
       round(rr.avg::numeric, 1)                                     AS respiration_avg,
       round(sp.avg::numeric, 1)                                     AS spo2_avg,
       s.score                                                       AS sleep_score,
       s.garmin_score                                                AS garmin_sleep_score,
       round((s.tst_min / 60)::numeric, 2)                           AS sleep_h,
       round(s.hrv_avg::numeric, 0)                                  AS hrv_night,
       hs.weekly_avg                                                 AS hrv_weekly,
       (SELECT round((sum(distance_m) / 1000)::numeric, 2) FROM activities a
         WHERE (a.start_time AT TIME ZONE 'Europe/Berlin')::date = d.day::date)  AS activity_km
FROM daily_activity d
LEFT JOIN daily_metrics rhr ON rhr.day = d.day AND rhr.device_id = d.device_id AND rhr.metric = 'resting_hr'
LEFT JOIN daily_metrics st  ON st.day  = d.day AND st.device_id  = d.device_id AND st.metric  = 'stress'
LEFT JOIN daily_metrics bb  ON bb.day  = d.day AND bb.device_id  = d.device_id AND bb.metric  = 'body_battery'
LEFT JOIN daily_metrics rr  ON rr.day  = d.day AND rr.device_id  = d.device_id AND rr.metric  = 'respiration'
LEFT JOIN daily_metrics sp  ON sp.day  = d.day AND sp.device_id  = d.device_id AND sp.metric  = 'spo2'
LEFT JOIN sleep_nights s    ON s.night = d.day::date
LEFT JOIN LATERAL (SELECT weekly_avg FROM hrv_summary h
                   WHERE (h.time AT TIME ZONE 'Europe/Berlin')::date = d.day::date ORDER BY time DESC LIMIT 1) hs ON TRUE;

-- Simple daily load: minutes × avg HR / 180 (placeholder until a proper TRIMP from minute HR)
CREATE OR REPLACE VIEW daily_load AS
SELECT (start_time AT TIME ZONE 'Europe/Berlin')::date AS day,
       round(sum(duration_s / 60 * coalesce(avg_hr, 0) / 180)::numeric, 1) AS load
FROM activities
GROUP BY 1;
