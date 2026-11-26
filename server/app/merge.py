"""Merge an uploaded app database (SQLite, schema web/src/db/schema.ts) into PostgreSQL.

Idempotent: every table is upserted on its primary key. Large time-series tables are
only read from shortly before what the server already has, so a sync after a daily
import moves a few thousand rows, not the whole history.
"""

from __future__ import annotations

import json
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path

import psycopg

from .db import refresh_aggregates

OVERLAP_S = 3 * 86400


@dataclass
class Table:
    name: str
    sqlite_cols: list[str]
    pg_cols: list[str]
    key: list[str]
    # per pg column: None = as is, "ts" = unix seconds → timestamptz, "json" = text → jsonb,
    # "bool" = 0/1 → boolean, "date" = text → date
    conv: dict[str, str]
    incremental: str | None = None  # sqlite column holding unix seconds, for incremental reads
    group: str | None = None        # partition column for the incremental watermark
    overlap_s: int = OVERLAP_S      # re-read window for late-arriving samples


TABLES = [
    Table("devices", ["id", "name", "manufacturer", "model"], ["id", "name", "manufacturer", "model"], ["id"], {}),
    Table(
        "activities",
        ["id", "device_id", "kind", "sport", "category", "name", "start_ts", "end_ts", "duration_s", "distance_m", "avg_hr",
         "max_hr", "min_hr", "avg_pace_s_km", "ascent_m", "descent_m", "calories", "avg_cadence", "te_aerobic",
         "te_anaerobic", "recovery_h", "pace_cv", "has_track", "route_preview", "summary_json", "updated_at"],
        ["id", "device_id", "kind", "sport", "category", "name", "start_time", "end_time", "duration_s", "distance_m", "avg_hr",
         "max_hr", "min_hr", "avg_pace_s_km", "ascent_m", "descent_m", "calories", "avg_cadence", "te_aerobic",
         "te_anaerobic", "recovery_h", "pace_cv", "has_track", "route_preview", "summary", "updated_at"],
        ["id"],
        {"start_time": "ts", "end_time": "ts", "updated_at": "ts", "has_track": "bool", "route_preview": "json", "summary": "json",
         "name": "name"},
    ),
    Table(
        "track_points",
        ["activity_id", "ts", "lat", "lon", "ele", "hr", "cadence", "speed", "dist_m"],
        ["activity_id", "time", "lat", "lon", "ele", "hr", "cadence", "speed", "dist_m"],
        ["activity_id", "time"], {"time": "ts"}, incremental="ts", group="activity_id", overlap_s=0,
    ),
    Table(
        "minute_samples",
        ["device_id", "ts", "hr", "steps", "intensity", "kind", "distance_cm", "active_kcal"],
        ["device_id", "time", "hr", "steps", "intensity", "kind", "distance_cm", "active_kcal"],
        ["device_id", "time"], {"time": "ts"}, incremental="ts", group="device_id",
    ),
    Table(
        "metric_samples",
        ["metric", "device_id", "ts", "value"], ["metric", "device_id", "time", "value"],
        ["metric", "device_id", "time"], {"time": "ts"}, incremental="ts", group="metric",
    ),
    Table(
        "hrv_summary",
        ["device_id", "ts", "weekly_avg", "last_night_avg", "last_night_5min_high", "baseline_low_upper",
         "baseline_balanced_lower", "baseline_balanced_upper", "status"],
        ["device_id", "time", "weekly_avg", "last_night_avg", "last_night_5min_high", "baseline_low_upper",
         "baseline_balanced_lower", "baseline_balanced_upper", "status"],
        ["device_id", "time"], {"time": "ts"},
    ),
]

SLEEP_COLS = [
    "night", "device_id", "bed_ts", "onset_ts", "wake_ts", "out_ts", "tib_min", "tst_min", "sol_min", "waso_min",
    "awakenings", "awakenings_long", "efficiency", "deep_min", "light_min", "rem_min", "awake_min", "hr_avg", "hr_min",
    "hrv_avg", "stress_avg", "resp_avg", "spo2_avg", "bb_gain", "midpoint_min", "regularity_sd", "score",
    "score_duration", "score_continuity", "score_recovery", "score_stages", "score_regularity", "score_breathing",
    "coverage", "garmin_score", "details", "updated_at",
]
TABLES.append(
    Table(
        "sleep_nights", SLEEP_COLS,
        [c.replace("_ts", "_time") if c.endswith("_ts") else c for c in SLEEP_COLS],
        ["night"],
        {"bed_time": "ts", "onset_time": "ts", "wake_time": "ts", "out_time": "ts", "updated_at": "ts", "details": "json",
         "night": "date"},
    )
)


def _cast(kind: str | None, placeholder: str = "%s") -> str:
    return {
        "ts": f"to_timestamp({placeholder})",
        "json": f"{placeholder}::jsonb",
        "bool": f"({placeholder})::int::boolean",
        "date": f"{placeholder}::date",
        "name": f"nullif(ltrim({placeholder}, '~'), '')",  # app marks auto-generated names with '~'
    }.get(kind or "", placeholder)


def merge_sqlite(path: Path, conn: psycopg.Connection, client: str = "") -> dict:
    t0 = time.monotonic()
    src = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    existing = {r[0] for r in src.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    total, per_table, earliest = 0, {}, None

    for t in TABLES:
        if t.name not in existing:
            continue
        where, params = "", []
        if t.incremental:
            # watermark per group (device / metric), with overlap for late-arriving samples
            gcol_pg = t.group or None
            if gcol_pg:
                marks = dict(conn.execute(
                    f"SELECT {gcol_pg}, extract(epoch FROM max(time))::bigint FROM {t.name} GROUP BY 1").fetchall())
            else:
                marks = {None: conn.execute(f"SELECT extract(epoch FROM max(time))::bigint FROM {t.name}").fetchone()[0]}
            if any(v is not None for v in marks.values()):
                if gcol_pg:
                    clauses = [f"({t.group} = ? AND {t.incremental} >= ?)" for _ in marks]
                    known = list(marks)
                    clauses.append(f"{t.group} NOT IN ({','.join('?' * len(known))})")
                    where = "WHERE " + " OR ".join(clauses)
                    for g, v in marks.items():
                        params += [g, (v or 0) - t.overlap_s]
                    params += known
                else:
                    where = f"WHERE {t.incremental} >= ?"
                    params = [(marks[None] or 0) - t.overlap_s]

        rows = src.execute(f"SELECT {', '.join(t.sqlite_cols)} FROM {t.name} {where}", params).fetchall()
        if not rows:
            per_table[t.name] = 0
            continue
        if t.incremental:
            idx = t.sqlite_cols.index(t.incremental)
            m = min(r[idx] for r in rows)
            earliest = m if earliest is None else min(earliest, m)

        tmp = f"tmp_{t.name}"
        conn.execute(f"CREATE TEMP TABLE {tmp} ({', '.join(f'{c} text' for c in t.pg_cols)}) ON COMMIT DROP")
        with conn.cursor().copy(f"COPY {tmp} ({', '.join(t.pg_cols)}) FROM STDIN") as cp:
            for r in rows:
                cp.write_row([None if v is None else (json.dumps(v) if isinstance(v, (dict, list)) else str(v)) for v in r])

        target_types = dict(conn.execute(
            "SELECT column_name, format_type(atttypid, atttypmod) FROM information_schema.columns c "
            "JOIN pg_attribute a ON a.attname = c.column_name AND a.attrelid = %s::regclass "
            "WHERE table_name = %s", (t.name, t.name)).fetchall())
        select = []
        for c in t.pg_cols:
            kind = t.conv.get(c)
            if kind == "ts":
                expr = f"to_timestamp({c}::double precision)"
            elif kind:
                expr = _cast(kind, c)
            else:
                expr = f"{c}::{target_types[c]}"
            select.append(expr)
        updates = ", ".join(f"{c} = EXCLUDED.{c}" for c in t.pg_cols if c not in t.key)
        conflict = f"DO UPDATE SET {updates}" if updates else "DO NOTHING"
        cur = conn.execute(
            f"INSERT INTO {t.name} ({', '.join(t.pg_cols)}) SELECT {', '.join(select)} FROM {tmp} "
            f"ON CONFLICT ({', '.join(t.key)}) {conflict}"
        )
        per_table[t.name] = cur.rowcount
        total += cur.rowcount

    src.close()
    ms = int((time.monotonic() - t0) * 1000)
    conn.execute("INSERT INTO sync_log (bytes, rows, ms, client) VALUES (%s, %s, %s, %s)",
                 (path.stat().st_size, total, ms, client))
    conn.commit()
    refresh_aggregates(conn, earliest)
    return {"rows": total, "tables": per_table, "ms": ms}
