"""Gapmin NAS API.

POST /api/sync      app uploads its SQLite database; merged into PostgreSQL
GET  /api/health    liveness + database check
GET  /api/summary   quick counts (for checking a sync)
GET  /api/daily     long-term daily summary (date range)
"""

from __future__ import annotations

import hmac
import logging
import os
import tempfile
from contextlib import asynccontextmanager
from datetime import date, timedelta
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware

from .db import connect, migrate
from .merge import merge_sqlite

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("gapmin")

TOKEN = os.environ.get("GAPMIN_TOKEN", "")
MAX_UPLOAD = int(os.environ.get("GAPMIN_MAX_UPLOAD_MB", "1024")) * 1024 * 1024


@asynccontextmanager
async def lifespan(_: FastAPI):
    info = migrate()
    log.info("database ready (timescale=%s, applied=%s)", info["timescale"], info["applied"])
    if not TOKEN:
        log.warning("GAPMIN_TOKEN is not set: the API accepts requests without authentication")
    yield


app = FastAPI(title="Gapmin", version="0.1.0", lifespan=lifespan)
app.add_middleware(  # the Android app's WebView origin differs from the NAS address
    CORSMiddleware, allow_origins=["*"], allow_methods=["GET", "POST"], allow_headers=["*"],
)


def auth(authorization: str = Header(default="")) -> None:
    if not TOKEN:
        return
    supplied = authorization.removeprefix("Bearer ").strip()
    if not hmac.compare_digest(supplied, TOKEN):
        raise HTTPException(401, "Ungültiger Token")


@app.get("/api/health")
def health():
    with connect() as conn:
        conn.execute("SELECT 1")
    return {"ok": True}


@app.post("/api/sync", dependencies=[Depends(auth)])
async def sync(request: Request):
    with tempfile.NamedTemporaryFile(suffix=".sqlite", delete=False) as f:
        path = Path(f.name)
        size = 0
        async for chunk in request.stream():
            size += len(chunk)
            if size > MAX_UPLOAD:
                path.unlink(missing_ok=True)
                raise HTTPException(413, "Datei zu groß")
            f.write(chunk)
    try:
        with open(path, "rb") as fh:
            if fh.read(15) != b"SQLite format 3":
                raise HTTPException(400, "Keine SQLite-Datenbank")
        with connect() as conn:
            result = merge_sqlite(path, conn, client=request.headers.get("user-agent", "")[:200])
        log.info("sync: %s bytes, %s rows in %s ms", size, result["rows"], result["ms"])
        return result
    finally:
        path.unlink(missing_ok=True)


@app.get("/api/summary", dependencies=[Depends(auth)])
def summary():
    with connect() as conn:
        one = lambda sql: conn.execute(sql).fetchone()[0]  # noqa: E731
        return {
            "activities": one("SELECT count(*) FROM activities"),
            "nights": one("SELECT count(*) FROM sleep_nights"),
            "minute_samples": one("SELECT count(*) FROM minute_samples"),
            "metric_samples": one("SELECT count(*) FROM metric_samples"),
            "last_sync": one("SELECT max(received) FROM sync_log"),
        }


@app.get("/api/daily", dependencies=[Depends(auth)])
def daily(start: date | None = None, end: date | None = None):
    end = end or date.today()
    start = start or end - timedelta(days=90)
    with connect() as conn:
        cur = conn.execute("SELECT * FROM daily_summary WHERE day BETWEEN %s AND %s ORDER BY day", (start, end))
        cols = [d.name for d in cur.description]
        return [dict(zip(cols, r)) for r in cur.fetchall()]
