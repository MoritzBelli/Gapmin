"""End-to-end: upload an app database twice and check the merge.

Needs a PostgreSQL in DATABASE_URL (wiped!) and an app database file in GAPMIN_TEST_DB,
which `GAPMIN_EXPORT_DB=/tmp/gapmin-app.sqlite npx vitest run` in web/ produces.
"""

import os
import sqlite3
from pathlib import Path

import psycopg
import pytest
from fastapi.testclient import TestClient

DB_FILE = Path(os.environ.get("GAPMIN_TEST_DB", "/tmp/gapmin-app.sqlite"))
pytestmark = pytest.mark.skipif(not DB_FILE.exists(), reason="no app database to upload")

os.environ.setdefault("GAPMIN_TOKEN", "test-token")


@pytest.fixture(scope="module")
def client():
    from app.db import dsn

    with psycopg.connect(dsn(), autocommit=True) as c:
        c.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;")
    from app.main import app

    with TestClient(app) as cl:
        yield cl


AUTH = {"Authorization": "Bearer test-token"}


def test_requires_token(client):
    assert client.post("/api/sync", content=b"x").status_code == 401


def test_rejects_non_sqlite(client):
    assert client.post("/api/sync", content=b"hello", headers=AUTH).status_code == 400


def test_sync_and_idempotency(client):
    data = DB_FILE.read_bytes()
    first = client.post("/api/sync", content=data, headers=AUTH)
    assert first.status_code == 200, first.text
    r1 = first.json()
    print("first", r1)

    src = sqlite3.connect(DB_FILE)
    expected = {t: src.execute(f"SELECT count(*) FROM {t}").fetchone()[0]
                for t in ("activities", "sleep_nights", "minute_samples", "metric_samples", "track_points")}
    summary = client.get("/api/summary", headers=AUTH).json()
    assert summary["activities"] == expected["activities"]
    assert summary["nights"] == expected["sleep_nights"]
    assert summary["minute_samples"] == expected["minute_samples"]
    assert summary["metric_samples"] == expected["metric_samples"]

    second = client.post("/api/sync", content=data, headers=AUTH).json()
    print("second", second)
    # only the overlap window and small tables are touched again
    assert second["rows"] < r1["rows"] / 10
    assert client.get("/api/summary", headers=AUTH).json()["minute_samples"] == expected["minute_samples"]


def test_names_and_views(client):
    from app.db import connect

    with connect() as conn:
        assert conn.execute("SELECT count(*) FROM activities WHERE name LIKE '~%'").fetchone()[0] == 0
        weeks = conn.execute("SELECT count(*), sum(km) FROM weekly_running").fetchone()
        assert weeks[0] > 10 and weeks[1] > 100
    rows = client.get("/api/daily", params={"start": "2026-07-01", "end": "2026-11-30"}, headers=AUTH).json()
    assert len(rows) > 100
    last = rows[-2]
    print("daily", last)
    assert last["steps"] > 0 and last["sleep_score"] is not None and last["resting_hr"] is not None
