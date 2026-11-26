#!/usr/bin/env python3
"""Generate synthetic running activities (GPX + manifest) for the Gapmin dashboard.

The output mimics what the real pipeline will later produce from the Gadgetbridge
export: one GPX file per run plus a `runs.json` manifest with summary metrics.

Usage:
    python3 scripts/generate_sample_data.py [--count 60] [--seed 42] [--out web/public/sample-data]
"""

from __future__ import annotations

import argparse
import json
import math
import random
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

EARTH_R = 6_371_000.0
# Arbitrary home base for test data (southern Germany).
HOME_LAT, HOME_LON = 48.3705, 10.8978


@dataclass
class Point:
    lat: float
    lon: float
    ele: float
    time: datetime
    hr: int


def offset(lat: float, lon: float, dx: float, dy: float) -> tuple[float, float]:
    """Shift a coordinate by dx/dy metres (east/north)."""
    dlat = dy / EARTH_R
    dlon = dx / (EARTH_R * math.cos(math.radians(lat)))
    return lat + math.degrees(dlat), lon + math.degrees(dlon)


def haversine(a: tuple[float, float], b: tuple[float, float]) -> float:
    la1, lo1, la2, lo2 = map(math.radians, (*a, *b))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * EARTH_R * math.asin(math.sqrt(h))


def wobble(rng: random.Random, n_terms: int = 4):
    """Smooth random function f(t) for t in [0, 1], built from a few sines."""
    terms = [(rng.uniform(1, 7), rng.uniform(0, 2 * math.pi), rng.uniform(0.02, 0.12)) for _ in range(n_terms)]
    return lambda t: sum(a * math.sin(2 * math.pi * f * t + p) for f, p, a in terms)


# --- Route shapes -----------------------------------------------------------
# Each returns a list of (x, y) in arbitrary units relative to the start; the
# caller rescales it to the exact target distance.


def rot(pts, a):
    c, s = math.cos(a), math.sin(a)
    return [(x * c - y * s, x * s + y * c) for x, y in pts]


def chaikin(pts, iterations=2, closed=False):
    """Corner-cutting: turns sharp street corners into rounded ones."""
    for _ in range(iterations):
        out = [] if closed else [pts[0]]
        rng_ = range(len(pts)) if closed else range(len(pts) - 1)
        for i in rng_:
            (x0, y0), (x1, y1) = pts[i], pts[(i + 1) % len(pts)]
            out += [(0.8 * x0 + 0.2 * x1, 0.8 * y0 + 0.2 * y1), (0.2 * x0 + 0.8 * x1, 0.2 * y0 + 0.8 * y1)]
        if not closed:
            out.append(pts[-1])
        pts = out
    return pts


def densify(pts, step):
    out = [pts[0]]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        d = math.dist((x0, y0), (x1, y1))
        k = max(1, int(d / step))
        out += [(x0 + (x1 - x0) * j / k, y0 + (y1 - y0) * j / k) for j in range(1, k + 1)]
    return out


def jitter(rng, pts, amount):
    return [(x + rng.gauss(0, amount), y + rng.gauss(0, amount)) for x, y in pts]


def street_route(rng, waypoints):
    """Connect waypoints along an (axis-aligned) street grid with the odd diagonal."""
    path = [waypoints[0]]
    for (x0, y0), (x1, y1) in zip(waypoints, waypoints[1:]):
        r = rng.random()
        if r < 0.2:  # diagonal street / path
            path.append((x1, y1))
        elif r < 0.6:  # L-shape, x first
            path += [(x1, y0), (x1, y1)]
        elif r < 0.85:  # L-shape, y first
            path += [(x0, y1), (x1, y1)]
        else:  # staircase through blocks
            xm, ym = x0 + (x1 - x0) * rng.uniform(0.3, 0.7), y0 + (y1 - y0) * rng.uniform(0.3, 0.7)
            path += [(xm, y0), (xm, ym), (x1, ym), (x1, y1)]
    return path


def organic(rng, n, closed, wiggle):
    """Smooth path by integrating a slowly varying heading (parks, rivers, trails)."""
    w1, w2 = wobble(rng, 3), wobble(rng, 6)
    hf = [(rng.uniform(10, 25), rng.uniform(0, 2 * math.pi)) for _ in range(3)]
    heading, x, y, pts = rng.uniform(0, 2 * math.pi), 0.0, 0.0, []
    for i in range(n):
        t = i / (n - 1)
        bends = sum(math.sin(2 * math.pi * f * t + p) for f, p in hf) * 0.03 * max(0.0, wiggle - 0.8)
        turn = (2 * math.pi / n if closed else 0) + ((w1(t) + w2(t)) * wiggle * 150 + bends * 60) / n
        heading += turn
        x, y = x + math.cos(heading), y + math.sin(heading)
        pts.append((x, y))
    if closed:  # pull the end back onto the start
        ex, ey = pts[-1]
        pts = [(px - ex * i / (n - 1), py - ey * i / (n - 1)) for i, (px, py) in enumerate(pts)]
    return pts


def shape_loop(rng: random.Random, n: int):
    if rng.random() < 0.4:  # park / lake loop
        return organic(rng, n, closed=True, wiggle=rng.uniform(0.4, 1.0))
    k = rng.randint(4, 8)
    rx, ry = rng.uniform(0.6, 1.4), rng.uniform(0.6, 1.4)
    a0 = rng.uniform(0, 2 * math.pi)
    wps = []
    for i in range(k):
        a = a0 + 2 * math.pi * i / k + rng.uniform(-0.3, 0.3)
        r = rng.uniform(0.6, 1.0)
        wps.append((rx * r * math.cos(a), ry * r * math.sin(a)))
    wps.append(wps[0])
    path = chaikin(street_route(rng, wps), 2)
    path = rot(path, rng.choice([0, 0, rng.uniform(-0.5, 0.5)]))
    x0, y0 = path[0]
    return jitter(rng, densify([(x - x0, y - y0) for x, y in path], 0.02), 0.002)


def shape_out_and_back(rng: random.Random, n: int):
    if rng.random() < 0.5:  # along a river / road
        out = organic(rng, n // 2, closed=False, wiggle=rng.uniform(0.3, 0.9))
    else:
        heading, x, y, wps = rng.uniform(0, 2 * math.pi), 0.0, 0.0, [(0.0, 0.0)]
        for _ in range(rng.randint(3, 6)):
            heading += rng.uniform(-0.6, 0.6)
            d = rng.uniform(0.5, 1.5)
            x, y = x + d * math.cos(heading), y + d * math.sin(heading)
            wps.append((x, y))
        out = densify(chaikin(street_route(rng, wps), 2), 0.02)
    back = jitter(rng, list(reversed(out)), 0.002 * max(abs(c) for p in out for c in p))
    return out + back


def shape_figure_eight(rng: random.Random, n: int):
    a = organic(rng, n // 2, closed=True, wiggle=rng.uniform(0.3, 0.7))
    b = organic(rng, n // 2, closed=True, wiggle=rng.uniform(0.3, 0.7))
    # Mirror the second loop so they sit on opposite sides of the start.
    ax, ay = sum(p[0] for p in a) / len(a), sum(p[1] for p in a) / len(a)
    bx, by = sum(p[0] for p in b) / len(b), sum(p[1] for p in b) / len(b)
    ang = math.atan2(ay, ax) + math.pi - math.atan2(by, bx)
    return a + rot(b, ang)


def shape_track(rng: random.Random, n: int):
    """Street warm-up to a 400 m track, laps, same way back."""
    heading, x, y, wps = rng.uniform(0, 2 * math.pi), 0.0, 0.0, [(0.0, 0.0)]
    for _ in range(rng.randint(2, 4)):
        heading += rng.uniform(-0.8, 0.8)
        d = rng.uniform(1.0, 2.0)
        x, y = x + d * math.cos(heading), y + d * math.sin(heading)
        wps.append((x, y))
    approach = densify(chaikin(street_route(rng, wps), 2), 0.05)
    cx, cy = approach[-1]
    # 400 m track: 84.4 m straights, 36.5 m radius -> in units where approach ~ 1.5-2.5 km
    unit = 1 / 400  # 1 unit = 400 m roughly matches approach scale above (~4 units)
    straight, radius = 84.4 * unit * 4, 36.5 * unit * 4
    track_rot = rng.uniform(0, math.pi)
    laps, pts = rng.randint(6, 10), []
    per = 2 * straight + 2 * math.pi * radius
    for _ in range(laps):
        for i in range(60):
            d = i / 60 * per
            if d < straight:
                px, py = -straight / 2 + d, -radius
            elif d < straight + math.pi * radius:
                ang = (d - straight) / radius - math.pi / 2
                px, py = straight / 2 + radius * math.cos(ang), radius * math.sin(ang)
            elif d < 2 * straight + math.pi * radius:
                px, py = straight / 2 - (d - straight - math.pi * radius), radius
            else:
                ang = (d - 2 * straight - math.pi * radius) / radius + math.pi / 2
                px, py = -straight / 2 + radius * math.cos(ang), radius * math.sin(ang)
            pts.append((px, py))
    pts = jitter(rng, rot(pts, track_rot), 0.004)
    pts = [(cx + px, cy + py - radius) for px, py in pts]
    return approach + pts + list(reversed(approach))


def shape_meander(rng: random.Random, n: int):
    """Trail run: twisty forest paths, usually a loop."""
    return jitter(rng, organic(rng, n, closed=rng.random() < 0.8, wiggle=rng.uniform(1.2, 2.2)), 0.05)


SHAPES = {
    "loop": (shape_loop, 0.40),
    "out_and_back": (shape_out_and_back, 0.20),
    "figure_eight": (shape_figure_eight, 0.10),
    "track": (shape_track, 0.12),
    "trail": (shape_meander, 0.18),
}

RUN_TYPES = {
    # name: (distance range km, pace range s/km, hr range)
    "Easy Run": ((5, 10), (330, 380), (128, 145)),
    "Long Run": ((14, 26), (335, 375), (135, 150)),
    "Tempo Run": ((8, 12), (265, 295), (155, 168)),
    "Intervals": ((7, 11), (250, 300), (150, 172)),
    "Recovery Run": ((4, 7), (370, 420), (118, 132)),
    "Trail Run": ((8, 18), (380, 470), (138, 156)),
}


def scale_to_length(xy: list[tuple[float, float]], target_m: float):
    length = sum(math.dist(xy[i], xy[i + 1]) for i in range(len(xy) - 1))
    k = target_m / length if length else 1
    return [(x * k, y * k) for x, y in xy]


def build_run(rng: random.Random, idx: int, start: datetime) -> tuple[dict, list[Point]]:
    shape_name = rng.choices(list(SHAPES), weights=[w for _, w in SHAPES.values()])[0]
    if shape_name == "track":
        run_type = "Intervals"
    elif shape_name == "trail":
        run_type = "Trail Run"
    else:
        run_type = rng.choices(
            ["Easy Run", "Long Run", "Tempo Run", "Recovery Run"], weights=[0.5, 0.2, 0.15, 0.15]
        )[0]
    (dmin, dmax), (pmin, pmax), (hmin, hmax) = RUN_TYPES[run_type]
    distance_m = rng.uniform(dmin, dmax) * 1000
    base_pace = rng.uniform(pmin, pmax)
    base_hr = rng.uniform(hmin, hmax)

    n = max(150, int(distance_m / 20))
    xy = scale_to_length(SHAPES[shape_name][0](rng, n), distance_m)

    # Start somewhere within ~3 km of home (or further away for trail runs).
    spread = 15_000 if run_type == "Trail Run" else 3_000
    slat, slon = offset(HOME_LAT, HOME_LON, rng.uniform(-spread, spread), rng.uniform(-spread, spread))

    ele_w = wobble(rng, 4)
    ele_amp = 120 if run_type == "Trail Run" else 15
    base_ele = rng.uniform(470, 520) if run_type != "Trail Run" else rng.uniform(600, 900)
    pace_w = wobble(rng, 3)

    points: list[Point] = []
    t = start
    prev = None
    for i, (x, y) in enumerate(xy):
        frac = i / (len(xy) - 1)
        lat, lon = offset(slat, slon, x, y)
        ele = base_ele + ele_w(frac) * ele_amp * 5
        if prev is not None:
            d = haversine((prev.lat, prev.lon), (lat, lon))
            pace = base_pace * (1 + pace_w(frac) * 0.8)
            if run_type == "Intervals":
                # Alternate fast reps and slow recoveries along the track section.
                pace *= 0.82 if int(frac * 16) % 2 == 0 else 1.25
            t = t + timedelta(seconds=d * pace / 1000)
        warm = min(1.0, frac * 8)
        hr = int(base_hr * (0.78 + 0.22 * warm) + pace_w(frac) * 40 + rng.uniform(-2, 2))
        p = Point(lat, lon, ele, t, hr)
        points.append(p)
        prev = p

    total_d = sum(haversine((points[i].lat, points[i].lon), (points[i + 1].lat, points[i + 1].lon)) for i in range(len(points) - 1))
    duration = (points[-1].time - points[0].time).total_seconds()
    ascent = sum(max(0.0, points[i + 1].ele - points[i].ele) for i in range(len(points) - 1))
    hrs = [p.hr for p in points]

    hour = start.hour
    daypart = "Morgenlauf" if hour < 11 else "Mittagslauf" if hour < 14 else "Nachmittagslauf" if hour < 18 else "Abendlauf"
    names = {
        "Easy Run": f"Lockerer {daypart}",
        "Recovery Run": "Regenerationslauf",
        "Long Run": "Langer Lauf",
        "Tempo Run": "Tempodauerlauf",
        "Intervals": f"Bahnintervalle {rng.choice(['8×400 m', '6×800 m', '5×1000 m', '10×400 m'])}",
        "Trail Run": rng.choice(["Waldrunde", "Trail am Hang", "Höhenweg"]),
    }
    run_id = f"{start:%Y%m%d-%H%M}"
    meta = {
        "id": run_id,
        "name": names[run_type],
        "type": run_type,
        "shape": shape_name,
        "start": start.isoformat(),
        "distance_m": round(total_d, 1),
        "duration_s": round(duration),
        "avg_pace_s_per_km": round(duration / (total_d / 1000), 1),
        "avg_hr": round(sum(hrs) / len(hrs)),
        "max_hr": max(hrs),
        "elevation_gain_m": round(ascent),
        "gpx": f"gpx/{run_id}.gpx",
    }
    return meta, points


def to_gpx(meta: dict, points: list[Point]) -> str:
    rows = []
    for p in points:
        rows.append(
            f'      <trkpt lat="{p.lat:.6f}" lon="{p.lon:.6f}"><ele>{p.ele:.1f}</ele>'
            f"<time>{p.time.strftime('%Y-%m-%dT%H:%M:%SZ')}</time>"
            f"<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>{p.hr}</gpxtpx:hr>"
            f"</gpxtpx:TrackPointExtension></extensions></trkpt>"
        )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<gpx version="1.1" creator="Gapmin sample data" xmlns="http://www.topografix.com/GPX/1/1" '
        'xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">\n'
        f"  <metadata><name>{meta['name']}</name><time>{meta['start']}</time></metadata>\n"
        f"  <trk><name>{meta['name']}</name><type>running</type>\n    <trkseg>\n"
        + "\n".join(rows)
        + "\n    </trkseg>\n  </trk>\n</gpx>\n"
    )


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--count", type=int, default=60)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", type=Path, default=Path(__file__).resolve().parent.parent / "web/public/sample-data")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    (args.out / "gpx").mkdir(parents=True, exist_ok=True)
    for old in (args.out / "gpx").glob("*.gpx"):
        old.unlink()

    # Fixed anchor so the data is reproducible; ~3-4 runs per week going back from it.
    day = datetime(2026, 11, 25, tzinfo=timezone.utc)
    runs = []
    for i in range(args.count):
        day -= timedelta(days=rng.choice([1, 1, 2, 2, 2, 3]))
        start = day.replace(hour=rng.choice([6, 7, 7, 12, 17, 18, 19]), minute=rng.randrange(0, 60, 5))
        meta, points = build_run(rng, i, start)
        (args.out / meta["gpx"]).write_text(to_gpx(meta, points), encoding="utf-8")
        runs.append(meta)

    runs.sort(key=lambda r: r["start"], reverse=True)
    (args.out / "runs.json").write_text(json.dumps({"generated": "sample", "runs": runs}, indent=2), encoding="utf-8")
    total_km = sum(r["distance_m"] for r in runs) / 1000
    print(f"Wrote {len(runs)} runs ({total_km:.0f} km) to {args.out}")


if __name__ == "__main__":
    main()
