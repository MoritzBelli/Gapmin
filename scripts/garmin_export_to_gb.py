#!/usr/bin/env python3
"""Garmin-Connect-Datenexport → Gadgetbridge-ähnlicher Export (ZIP), den die Gapmin-App importieren kann.

Garmin: Konto → Datenverwaltung → „Export Ihrer Daten“. Das ZIP vorher entpacken (der Ordner enthält DI_CONNECT/…).
Die Zip-Archive darin (z. B. UploadedFiles_*.zip mit den .fit-Dateien) müssen NICHT entpackt werden.

Übernommen wird, was der Export hergibt und die App kennt:
  * Aktivitäten (summarizedActivities) samt Strecke, wenn die passende .fit-Datei im Export liegt
  * Ruhepuls pro Tag (UDSFile)
  * Garmin-Schlafscore pro Nacht (sleepData)
  * HRV-Wochenschnitt, HRV-Status und Baseline (TrainingReadinessDTO, healthStatusData)
  * alle Tageswerte als Rohdaten in die Tabelle daily_summary der App (Schritte, Stress, Body Battery, Schlafsummen,
    Trainingsbereitschaft, Trainingsbelastung, VO2max, Endurance-/Hill-Score, Rennprognosen, Akklimatisierung)
Nicht im Export und damit nicht übernommen: Schlafphasen-Verlauf, Stress- und Body-Battery-Kurven. Deshalb berechnet
die App für alte Nächte keinen eigenen Schlafscore.

Es werden nur Zähler ausgegeben, keine Messwerte.

Aufruf:
    pip install fitdecode
    python scripts/garmin_export_to_gb.py "C:\\Users\\Moritz\\Downloads\\garmin_export" garmin-history.zip [--device-id AA:BB:CC:DD:EE:FF]

--device-id: Bluetooth-Kennung der Uhr aus Gadgetbridge. Mit derselben Kennung erkennt die App Aktivitäten, die in Export
und Gadgetbridge vorkommen, als dieselben (kein Duplikat). Ohne Angabe wird "GARMIN-EXPORT" verwendet.
"""

from __future__ import annotations

import argparse
import bisect
import io
import json
import math
import re
import sqlite3
import statistics
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

PHONE_FILES = "/storage/emulated/0/Android/data/nodomain.freeyourgadget.gadgetbridge/files"
MATCH_TOLERANCE_S = 120

# Gadgetbridge ActivityKind (siehe android/.../core/Runs.kt)
KIND_BY_TYPE = {
    "running": 0x10, "street_running": 0x10, "track_running": 0x10,
    "trail_running": 0x040000F2,
    "treadmill_running": 0x100, "indoor_running": 0x100,
    "walking": 0x20, "casual_walking": 0x20, "speed_walking": 0x20,
    "cycling": 0x80, "road_biking": 0x80, "mountain_biking": 0x80, "gravel_cycling": 0x80,
    "indoor_cycling": 0x800, "virtual_ride": 0x800,
}

# HRV-Status aus healthStatusData → App-Code (1 schlecht … 4 ausgeglichen). Unbekanntes bleibt leer.
def hrv_status_code(status: str | None) -> int | None:
    s = (status or "").upper()
    if "POOR" in s:
        return 1
    if "UNBALANCED" in s or "OUT_OF_RANGE" in s or "ABOVE" in s:
        return 3
    if "LOW" in s or "BELOW" in s:
        return 2
    if "IN_RANGE" in s or "BALANCED" in s or "NORMAL" in s:
        return 4
    return None


SCHEMA = """
CREATE TABLE DEVICE (_id INTEGER PRIMARY KEY, NAME TEXT NOT NULL, MANUFACTURER TEXT NOT NULL,
  IDENTIFIER TEXT NOT NULL UNIQUE, TYPE INTEGER NOT NULL, TYPE_NAME TEXT NOT NULL, MODEL TEXT,
  ALIAS TEXT, PARENT_FOLDER TEXT);
CREATE TABLE BASE_ACTIVITY_SUMMARY (_id INTEGER PRIMARY KEY, NAME TEXT, START_TIME INTEGER NOT NULL,
  END_TIME INTEGER NOT NULL, ACTIVITY_KIND INTEGER NOT NULL, BASE_LONGITUDE INTEGER, BASE_LATITUDE INTEGER,
  BASE_ALTITUDE INTEGER, GPX_TRACK TEXT, RAW_DETAILS_PATH TEXT, DEVICE_ID INTEGER NOT NULL,
  USER_ID INTEGER NOT NULL, SUMMARY_DATA TEXT, RAW_SUMMARY_DATA BLOB);
CREATE TABLE GARMIN_HEART_RATE_RESTING_SAMPLE (TIMESTAMP INTEGER NOT NULL, DEVICE_ID INTEGER NOT NULL,
  USER_ID INTEGER NOT NULL, HEART_RATE INTEGER NOT NULL, PRIMARY KEY (TIMESTAMP, DEVICE_ID)) WITHOUT ROWID;
CREATE TABLE GAPMIN_DAILY (DATE TEXT NOT NULL, KIND TEXT NOT NULL, DATA TEXT, PRIMARY KEY (DATE, KIND));
CREATE TABLE GARMIN_SLEEP_STATS_SAMPLE (TIMESTAMP INTEGER NOT NULL, DEVICE_ID INTEGER NOT NULL,
  USER_ID INTEGER NOT NULL, SLEEP_SCORE INTEGER NOT NULL, PRIMARY KEY (TIMESTAMP, DEVICE_ID)) WITHOUT ROWID;
CREATE TABLE GARMIN_HRV_SUMMARY_SAMPLE (TIMESTAMP INTEGER NOT NULL, DEVICE_ID INTEGER NOT NULL,
  USER_ID INTEGER NOT NULL, WEEKLY_AVERAGE INTEGER, LAST_NIGHT_AVERAGE INTEGER, LAST_NIGHT5_MIN_HIGH INTEGER,
  BASELINE_LOW_UPPER INTEGER, BASELINE_BALANCED_LOWER INTEGER, BASELINE_BALANCED_UPPER INTEGER, STATUS_NUM INTEGER,
  PRIMARY KEY (TIMESTAMP, DEVICE_ID)) WITHOUT ROWID;
"""


# ---------------------------------------------------------------------------
# Hilfsfunktionen
# ---------------------------------------------------------------------------

def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def iso_to_s(v) -> int | None:
    """ISO-String ('2025-01-02T06:30:00.0', optional 'Z') oder Epoch in s/ms → Unix-Sekunden (UTC)."""
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return int(v / 1000) if v > 1e11 else int(v)
    s = re.sub(r"\.\d+", "", str(v).strip().replace("Z", "+00:00"))
    try:
        d = datetime.fromisoformat(s)
    except ValueError:
        return None
    if d.tzinfo is None:
        d = d.replace(tzinfo=timezone.utc)
    return int(d.timestamp())


def noon_utc(date_str: str) -> int | None:
    try:
        d = datetime.strptime(date_str[:10], "%Y-%m-%d").replace(hour=12, tzinfo=timezone.utc)
    except (TypeError, ValueError):
        return None
    return int(d.timestamp())


def cal_date(v) -> str | None:
    """calendarDate als 'YYYY-MM-DD'. Im Export mal String, mal Zahl (Epoch in ms/s oder YYYYMMDD)."""
    if v is None:
        return None
    if isinstance(v, str):
        return v[:10] if re.match(r"\d{4}-\d{2}-\d{2}", v) else cal_date(num(v))
    x = num(v)
    if x is None:
        return None
    if 10_000_000 < x < 100_000_000:
        t = str(int(x))
        return f"{t[:4]}-{t[4:6]}-{t[6:]}"
    s = x / 1000 if x > 1e11 else x
    return datetime.fromtimestamp(s, timezone.utc).strftime("%Y-%m-%d")


# Dateimuster → kind in daily_summary
DAILY_SOURCES = [
    ("UDSFile_*.json", "garmin_uds"),
    ("*sleepData.json", "garmin_sleep"),
    ("TrainingReadinessDTO_*.json", "garmin_readiness"),
    ("*healthStatusData.json", "garmin_health"),
    ("EnduranceScore_*.json", "garmin_endurance"),
    ("HillScore_*.json", "garmin_hill"),
    ("MetricsAcuteTrainingLoad_*.json", "garmin_load"),
    ("TrainingHistory_*.json", "garmin_training_status"),
    ("MetricsMaxMetData_*.json", "garmin_vo2max"),
    ("RunRacePredictions_*.json", "garmin_race"),
    ("MetricsHeatAltitudeAcclimation_*.json", "garmin_acclimation"),
]


def num(v) -> float | None:
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    return x if math.isfinite(x) else None


def entry(value, unit):
    return {"value": value, "unit": unit}


def pow10(ratio: float) -> float:
    return 10.0 ** round(math.log10(ratio))


# ---------------------------------------------------------------------------
# FIT-Dateien
# ---------------------------------------------------------------------------

def _fitdecode():
    try:
        import fitdecode  # type: ignore
    except ImportError:
        sys.exit("Bitte zuerst installieren:  pip install fitdecode")
    return fitdecode


def _utc_s(dt) -> int:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp())


def fit_start_s(data: bytes) -> int | None:
    """Startzeit einer FIT-Datei: erster Record, sonst file_id.time_created. Liest nur den Anfang."""
    fitdecode = _fitdecode()
    created = None
    try:
        with fitdecode.FitReader(io.BytesIO(data), check_crc=fitdecode.CrcCheck.DISABLED) as r:
            for fr in r:
                if fr.frame_type != fitdecode.FIT_FRAME_DATA:
                    continue
                if fr.name == "file_id" and fr.has_field("time_created"):
                    created = _utc_s(fr.get_value("time_created"))
                elif fr.name == "record" and fr.has_field("timestamp"):
                    return _utc_s(fr.get_value("timestamp"))
                elif fr.name == "session" and fr.has_field("start_time"):
                    return _utc_s(fr.get_value("start_time"))
    except Exception:
        return created
    return created


def fit_session(data: bytes) -> dict:
    """Gesamtwerte aus der session-Nachricht (Meter, Sekunden, …) – dient zum Ableiten der JSON-Einheiten."""
    fitdecode = _fitdecode()
    out: dict = {}
    try:
        with fitdecode.FitReader(io.BytesIO(data), check_crc=fitdecode.CrcCheck.DISABLED) as r:
            for fr in r:
                if fr.frame_type == fitdecode.FIT_FRAME_DATA and fr.name == "session":
                    for k in ("total_distance", "total_timer_time", "total_elapsed_time", "total_ascent", "total_descent", "total_calories"):
                        if fr.has_field(k):
                            out[k] = num(fr.get_value(k))
    except Exception:
        pass
    return out


class FitSource:
    """Alle .fit-Dateien im Export: lose Dateien und Einträge in Zip-Archiven."""

    def __init__(self, root: Path):
        self.items: list[tuple[str, Path, str | None]] = []  # (Dateiname, Datei/Zip, Name im Zip)
        for p in sorted(root.rglob("*")):
            if p.is_file() and p.suffix.lower() == ".fit":
                self.items.append((p.name, p, None))
            elif p.is_file() and p.suffix.lower() == ".zip":
                try:
                    with zipfile.ZipFile(p) as z:
                        for n in z.namelist():
                            if n.lower().endswith(".fit"):
                                self.items.append((n.rsplit("/", 1)[-1], p, n))
                except zipfile.BadZipFile:
                    print(f"  übersprungen (kein gültiges Zip): {p.name}")

    def read(self, i: int) -> bytes:
        _, path, member = self.items[i]
        if member is None:
            return path.read_bytes()
        with zipfile.ZipFile(path) as z:
            return z.read(member)

    def index(self) -> list[tuple[int, int]]:
        """Sortierte Liste (Startzeit, Index)."""
        out = []
        by_zip: dict[Path, zipfile.ZipFile] = {}
        try:
            for i, (_, path, member) in enumerate(self.items):
                if member is None:
                    data = path.read_bytes()
                else:
                    z = by_zip.get(path) or by_zip.setdefault(path, zipfile.ZipFile(path))
                    data = z.read(member)
                t = fit_start_s(data)
                if t:
                    out.append((t, i))
                if (i + 1) % 500 == 0:
                    print(f"  {i + 1}/{len(self.items)} FIT-Dateien gelesen")
        finally:
            for z in by_zip.values():
                z.close()
        out.sort()
        return out


def match_fits(starts: list[int], fit_index: list[tuple[int, int]]) -> dict[int, int]:
    """Aktivität (Position in starts) → FIT-Index; jede Datei höchstens einmal, nächste Startzeit gewinnt."""
    times = [t for t, _ in fit_index]
    cands = []
    for ai, s in enumerate(starts):
        k = bisect.bisect_left(times, s)
        for j in (k - 1, k):
            if 0 <= j < len(times) and abs(times[j] - s) <= MATCH_TOLERANCE_S:
                cands.append((abs(times[j] - s), ai, j))
    cands.sort()
    used_a, used_f, result = set(), set(), {}
    for _, ai, j in cands:
        if ai in used_a or j in used_f:
            continue
        used_a.add(ai)
        used_f.add(j)
        result[ai] = fit_index[j][1]
    return result


# ---------------------------------------------------------------------------
# Einheiten der JSON-Zusammenfassung ableiten
# ---------------------------------------------------------------------------

DEFAULT_SCALES = {"distance": 100.0, "duration": 1000.0, "elevation": 100.0}


def infer_scales(acts: list[dict], matches: dict[int, int], fits: FitSource, sample: int = 40) -> dict[str, float]:
    """Die JSON-Einheiten (z. B. cm, ms) stehen nirgends dokumentiert. Stattdessen werden sie an den
    Gesamtwerten der zugehörigen .fit-Dateien gemessen: Verhältnis JSON/FIT, gerundet auf Zehnerpotenz."""
    ratios: dict[str, list[float]] = {"distance": [], "duration": [], "elevation": []}
    for ai in list(matches)[:sample]:
        a = acts[ai]
        ses = fit_session(fits.read(matches[ai]))
        for key, jk, fk in (("distance", "distance", "total_distance"), ("duration", "duration", "total_timer_time"),
                            ("elevation", "elevationGain", "total_ascent")):
            j, f = num(a.get(jk)), ses.get(fk)
            if j and f and j > 0 and f > 0:
                ratios[key].append(j / f)
    scales = {}
    for key, vals in ratios.items():
        if len(vals) >= 3:
            scales[key] = pow10(statistics.median(vals))
        else:
            scales[key] = DEFAULT_SCALES[key]
            print(f"  Hinweis: Einheit für '{key}' nicht messbar ({len(vals)} Vergleiche), Annahme 1/{DEFAULT_SCALES[key]:g}")
    print("  Einheiten (JSON ÷ Meter/Sekunden): " + ", ".join(f"{k} {v:g}" for k, v in scales.items()))
    return scales


# ---------------------------------------------------------------------------
# Umwandlung
# ---------------------------------------------------------------------------

def collect_activities(root: Path) -> list[dict]:
    acts = []
    for p in sorted(root.rglob("*summarizedActivities*.json")):
        for block in load_json(p):
            acts.extend(block.get("summarizedActivitiesExport", []) if isinstance(block, dict) else [])
    out = []
    for a in acts:
        s = iso_to_s(a.get("startTimeGmt") if a.get("startTimeGmt") is not None else a.get("beginTimestamp"))
        if s:
            a["_start_s"] = s
            out.append(a)
    out.sort(key=lambda a: a["_start_s"])
    return out


def activity_summary(a: dict, scales: dict[str, float]) -> tuple[dict, float]:
    dist = num(a.get("distance"))
    dur = num(a.get("duration"))
    dist_m = dist / scales["distance"] if dist else None
    dur_s = dur / scales["duration"] if dur else None
    elapsed = num(a.get("elapsedDuration"))
    elapsed_s = elapsed / scales["duration"] if elapsed else dur_s
    summ: dict = {}

    def put(key, value, unit):
        if value is not None:
            summ[key] = entry(value, unit)

    put("distanceMeters", dist_m, "meters")
    put("activeSeconds", dur_s, "seconds")
    put("averageHR", num(a.get("avgHr")), "bpm")
    put("maxHR", num(a.get("maxHr")), "bpm")
    put("minHR", num(a.get("minHr")), "bpm")
    if dist_m and dur_s and dist_m > 0:
        put("averageKMPaceSeconds", dur_s / (dist_m / 1000), "seconds_km")
    for jk, ok in (("elevationGain", "ascentMeters"), ("elevationLoss", "descentMeters")):
        v = num(a.get(jk))
        put(ok, v / scales["elevation"] if v is not None else None, "meters")
    put("caloriesBurnt", num(a.get("calories")), "kcal")
    cad = num(a.get("avgDoubleCadence"))
    if cad is None and num(a.get("avgRunCadence")):
        cad = num(a.get("avgRunCadence")) * 2
    put("averageCadence", cad, "spm")
    for jk, ok in (("aerobicTrainingEffect", "aerobicTrainingEffect"), ("anaerobicTrainingEffect", "anaerobicTrainingEffect")):
        v = num(a.get(jk))
        if v is not None:
            put(ok, v / 10 if v > 10 else v, "")  # manche Exporte speichern Zehntel
    return summ, (elapsed_s or dur_s or 0.0)


def build(root: Path, out_zip: Path, device_ident: str, keep_names: bool) -> None:
    print("Aktivitäten lesen …")
    acts = collect_activities(root)
    print(f"  {len(acts)} Aktivitäten im Export")
    fits = FitSource(root)
    print(f"  {len(fits.items)} FIT-Dateien gefunden, Startzeiten lesen …")
    fit_index = fits.index() if fits.items else []
    matches = match_fits([a["_start_s"] for a in acts], fit_index)
    print(f"  {len(matches)} Aktivitäten mit passender FIT-Datei")
    scales = infer_scales(acts, matches, fits)

    tmp = Path(tempfile.mkdtemp())
    db_path = tmp / "Gadgetbridge"
    db = sqlite3.connect(db_path)
    db.executescript(SCHEMA)
    db.execute("INSERT INTO DEVICE VALUES (1, 'Garmin (Export)', 'Garmin', ?, 0, 'GARMIN_EXPORT', NULL, NULL, NULL)", (device_ident,))

    used_names: dict[str, int] = {}
    entries: list[tuple[str, int]] = []  # (Pfad im Zip, FIT-Index)
    for ai, a in enumerate(acts):
        summ, elapsed_s = activity_summary(a, scales)
        start_s = a["_start_s"]
        raw_path = None
        if ai in matches:
            fname = fits.items[matches[ai]][0]
            n = used_names.get(fname, 0)
            used_names[fname] = n + 1
            if n:
                fname = f"{n}_{fname}"
            rel = f"{device_ident}/ACTIVITY/{fname}"
            raw_path = f"{PHONE_FILES}/{rel}"
            entries.append((f"files/{rel}", matches[ai]))
        kind = KIND_BY_TYPE.get(str(a.get("activityType") or a.get("sportType") or "").lower(), 0)
        name = (a.get("name") or None) if keep_names else None
        db.execute(
            "INSERT INTO BASE_ACTIVITY_SUMMARY (NAME, START_TIME, END_TIME, ACTIVITY_KIND, RAW_DETAILS_PATH, DEVICE_ID, USER_ID, SUMMARY_DATA) "
            "VALUES (?,?,?,?,?,1,1,?)",
            (name, start_s * 1000, int((start_s + elapsed_s) * 1000), kind, raw_path, json.dumps(summ)),
        )

    n_rhr = n_sleep = n_hrv = 0
    print("Tagesdaten …")
    # Ruhepuls (UDSFile)
    seen = set()
    for p in sorted(root.rglob("UDSFile_*.json")):
        for d in load_json(p):
            hr = num(d.get("restingHeartRate")) or num(d.get("currentDayRestingHeartRate"))
            ts = iso_to_s(d.get("restingHeartRateTimestamp")) if num(d.get("restingHeartRateTimestamp")) else None
            ts = ts or noon_utc(str(d.get("calendarDate") or ""))
            if hr and ts and ts not in seen:
                seen.add(ts)
                db.execute("INSERT OR REPLACE INTO GARMIN_HEART_RATE_RESTING_SAMPLE VALUES (?,1,1,?)", (ts, int(hr)))
                n_rhr += 1
    # Schlafscore (sleepData)
    for p in sorted(root.rglob("*sleepData.json")):
        for d in load_json(p):
            score = num((d.get("sleepScores") or {}).get("overallScore"))
            ts = iso_to_s(d.get("sleepEndTimestampGMT"))
            if score and ts:
                db.execute("INSERT OR REPLACE INTO GARMIN_SLEEP_STATS_SAMPLE VALUES (?,1,1,?)", (ts, int(score)))
                n_sleep += 1
    # HRV: Wochenschnitt aus der Trainingsbereitschaft, Status/Baseline aus healthStatusData
    hrv: dict[str, dict] = {}
    for p in sorted(root.rglob("TrainingReadinessDTO_*.json")):
        for d in load_json(p):
            day, w = str(d.get("calendarDate") or "")[:10], num(d.get("hrvWeeklyAverage"))
            if day and w:
                hrv.setdefault(day, {})["weekly"] = w
    statuses, types = set(), set()
    for p in sorted(root.rglob("*healthStatusData.json")):
        for d in load_json(p):
            day = str(d.get("calendarDate") or "")[:10]
            for m in d.get("metrics") or []:
                t = str(m.get("type") or "")
                types.add(t)
                if "HRV" not in t.upper():
                    continue
                statuses.add(str(m.get("status")))
                row = hrv.setdefault(day, {})
                row.update(last=num(m.get("value")), low=num(m.get("baselineLowerLimit")), high=num(m.get("baselineUpperLimit")),
                           status=hrv_status_code(m.get("status")))
    for day, r in sorted(hrv.items()):
        ts = noon_utc(day)
        if not ts:
            continue
        iv = lambda k: int(r[k]) if r.get(k) is not None else None  # noqa: E731
        db.execute("INSERT OR REPLACE INTO GARMIN_HRV_SUMMARY_SAMPLE VALUES (?,1,1,?,?,NULL,NULL,?,?,?)",
                   (ts, iv("weekly"), iv("last"), iv("low"), iv("high"), r.get("status")))
        n_hrv += 1
    # Alle Tageswerte roh (JSON) für die Tabelle daily_summary; pro Tag und kind gewinnt der letzte Eintrag
    daily_counts: dict[str, int] = {}
    for pattern, kind in DAILY_SOURCES:
        days: dict[str, str] = {}
        for p in sorted(root.rglob(pattern)):
            data = load_json(p)
            for d in data if isinstance(data, list) else []:
                if not isinstance(d, dict):
                    continue
                day = cal_date(d.get("calendarDate"))
                if not day:
                    continue
                k = kind
                if kind == "garmin_vo2max" and d.get("sport"):
                    k = f"{kind}_{str(d['sport']).lower()}"
                days[f"{day}|{k}"] = json.dumps(d, separators=(",", ":"), ensure_ascii=False)
        for key, blob in days.items():
            day, k = key.split("|")
            db.execute("INSERT OR REPLACE INTO GAPMIN_DAILY VALUES (?,?,?)", (day, k, blob))
        daily_counts[kind] = len(days)
    db.commit()
    db.close()
    print("  Tageswerte (daily_summary): " + ", ".join(f"{k.removeprefix('garmin_')} {n}" for k, n in daily_counts.items() if n))
    print(f"  Ruhepuls: {n_rhr} Tage · Schlafscore: {n_sleep} Nächte · HRV: {n_hrv} Tage")
    print(f"  Gefundene Gesundheits-Typen (healthStatusData): {', '.join(sorted(t for t in types if t)) or '–'}")
    print(f"  HRV-Status-Werte: {', '.join(sorted(statuses)) or '–'}")

    print(f"ZIP schreiben: {out_zip}")
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(db_path, "database/Gadgetbridge")
        for i, (name, fi) in enumerate(entries):
            z.writestr(name, fits.read(fi))
            if (i + 1) % 500 == 0:
                print(f"  {i + 1}/{len(entries)} Strecken")
    db_path.unlink()
    print(f"Fertig: {len(acts)} Aktivitäten, davon {len(entries)} mit Strecke.")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("export", type=Path, help="entpackter Garmin-Export (Ordner mit DI_CONNECT/)")
    ap.add_argument("out", type=Path, help="Ausgabe, z. B. garmin-history.zip")
    ap.add_argument("--device-id", default="GARMIN-EXPORT", help="Kennung der Uhr aus Gadgetbridge (verhindert doppelte Aktivitäten)")
    ap.add_argument("--keep-names", action="store_true", help="Garmin-Aktivitätsnamen übernehmen (sonst vergibt Gapmin eigene)")
    args = ap.parse_args()
    if not args.export.is_dir():
        sys.exit(f"Ordner nicht gefunden: {args.export}")
    build(args.export, args.out, args.device_id, args.keep_names)


if __name__ == "__main__":
    main()
