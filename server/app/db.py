"""Connection handling and migrations."""

from __future__ import annotations

import logging
import os
from pathlib import Path

import psycopg

log = logging.getLogger("gapmin.db")
MIGRATIONS = Path(__file__).resolve().parent.parent / "migrations"


def dsn() -> str:
    return os.environ.get("DATABASE_URL", "postgresql://gapmin:gapmin@localhost:5432/gapmin")


def connect() -> psycopg.Connection:
    return psycopg.connect(dsn(), autocommit=False)


def timescale_available(conn: psycopg.Connection) -> bool:
    if os.environ.get("GAPMIN_TIMESCALE", "auto") == "off":
        return False
    row = conn.execute("SELECT 1 FROM pg_available_extensions WHERE name = 'timescaledb'").fetchone()
    return row is not None


def migrate() -> dict:
    """Apply migrations once each. `NNN_name.timescale.sql` / `.plain.sql` pick the variant."""
    with connect() as conn:
        conn.execute("CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, applied timestamptz DEFAULT now())")
        done = {r[0] for r in conn.execute("SELECT name FROM schema_migrations")}
        ts = timescale_available(conn)
        variant = "timescale" if ts else "plain"
        applied = []
        for f in sorted(MIGRATIONS.glob("*.sql")):
            parts = f.name.split(".")
            if len(parts) == 3 and parts[1] != variant:
                continue
            key = parts[0]
            if key in done:
                continue
            log.info("applying %s", f.name)
            conn.execute(f.read_text())
            conn.execute("INSERT INTO schema_migrations (name) VALUES (%s)", (key,))
            applied.append(f.name)
        conn.commit()
    return {"timescale": ts, "applied": applied}


def refresh_aggregates(conn: psycopg.Connection, since_epoch: int | None) -> None:
    """Continuous aggregates are refreshed by policy; after a sync we refresh the touched range right away."""
    if not timescale_available(conn):
        return
    conn.commit()
    conn.autocommit = True
    try:
        for view in ("daily_activity", "daily_metrics"):
            conn.execute(
                f"CALL refresh_continuous_aggregate('{view}', to_timestamp(%s) - interval '2 days', now())",
                (since_epoch or 0,),
            )
    finally:
        conn.autocommit = False
