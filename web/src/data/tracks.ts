/** Parse GPS tracks from GPX or FIT files and derive route previews. */
import { Decoder, Stream } from '@garmin/fitsdk'

export interface TrackPoint {
  ts: number            // unix seconds
  lat: number | null
  lon: number | null
  ele: number | null
  hr: number | null
  cadence: number | null
  speed: number | null  // m/s
  dist: number | null   // cumulative metres
}

const num = (s: string | undefined) => (s === undefined ? null : Number.isFinite(+s) ? +s : null)

export function parseGpx(xml: string): TrackPoint[] {
  const out: TrackPoint[] = []
  const re = /<trkpt\b([^>]*)>([\s\S]*?)<\/trkpt>/g
  let m: RegExpExecArray | null
  while ((m = re.exec(xml))) {
    const attrs = m[1], body = m[2]
    const lat = num(/lat="([^"]+)"/.exec(attrs)?.[1])
    const lon = num(/lon="([^"]+)"/.exec(attrs)?.[1])
    const time = /<time>([^<]+)<\/time>/.exec(body)?.[1]
    if (!time) continue
    out.push({
      ts: Math.round(Date.parse(time) / 1000),
      lat, lon,
      ele: num(/<ele>([^<]+)<\/ele>/.exec(body)?.[1]),
      hr: num(/<(?:\w+:)?hr>([^<]+)<\/(?:\w+:)?hr>/.exec(body)?.[1]),
      cadence: num(/<(?:\w+:)?cad>([^<]+)<\/(?:\w+:)?cad>/.exec(body)?.[1]),
      speed: null,
      dist: null,
    })
  }
  return withDistance(out)
}

const SEMI = 180 / 2 ** 31

export function parseFit(bytes: Uint8Array): TrackPoint[] {
  const stream = Stream.fromByteArray(Array.from(bytes))
  const decoder = new Decoder(stream)
  if (!decoder.isFIT()) return []
  const { messages } = decoder.read({ convertDateTimesToDates: true, mergeHeartRates: true })
  const recs = (messages.recordMesgs ?? []) as Record<string, unknown>[]
  const out: TrackPoint[] = recs
    .filter((r) => r.timestamp instanceof Date)
    .map((r) => ({
      ts: Math.round((r.timestamp as Date).getTime() / 1000),
      lat: typeof r.positionLat === 'number' ? r.positionLat * SEMI : null,
      lon: typeof r.positionLong === 'number' ? r.positionLong * SEMI : null,
      ele: (r.enhancedAltitude ?? r.altitude ?? null) as number | null,
      hr: (r.heartRate ?? null) as number | null,
      cadence: (r.cadence ?? null) as number | null,
      speed: (r.enhancedSpeed ?? r.speed ?? null) as number | null,
      dist: (r.distance ?? null) as number | null,
    }))
  return out.some((p) => p.dist !== null) ? out : withDistance(out)
}

export function haversine(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371000, toRad = Math.PI / 180
  const dLat = (lat2 - lat1) * toRad, dLon = (lon2 - lon1) * toRad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) * Math.sin(dLon / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(h))
}

function withDistance(pts: TrackPoint[]): TrackPoint[] {
  let d = 0
  let prev: TrackPoint | null = null
  for (const p of pts) {
    if (prev && prev.lat !== null && prev.lon !== null && p.lat !== null && p.lon !== null) {
      const step = haversine(prev.lat, prev.lon, p.lat, p.lon)
      d += step
      if (p.speed === null && p.ts > prev.ts) p.speed = step / (p.ts - prev.ts)
    }
    p.dist = d
    prev = p
  }
  return pts
}

/** Ramer-Douglas-Peucker on locally projected metres; returns [[lat, lon], ...]. */
export function routePreview(pts: TrackPoint[], maxPoints = 250): [number, number][] {
  const geo = pts.filter((p) => p.lat !== null && p.lon !== null) as (TrackPoint & { lat: number; lon: number })[]
  if (geo.length < 2) return []
  const k = Math.cos((geo[0].lat * Math.PI) / 180) * 111320
  const xy = geo.map((p) => [p.lon * k, p.lat * 110540])
  // Simplify with growing tolerance, but never coarser than 8 m (repeated track laps would
  // otherwise collapse); if still too many points, thin the result evenly.
  let keep: boolean[] = []
  for (const eps of [2, 3, 5, 8]) {
    keep = rdp(xy, eps)
    if (keep.filter(Boolean).length <= maxPoints) break
  }
  let kept = geo.filter((_, i) => keep[i])
  if (kept.length > maxPoints) {
    const step = kept.length / maxPoints
    kept = Array.from({ length: maxPoints }, (_, i) => kept[Math.min(kept.length - 1, Math.round(i * step))])
    kept.push(geo[geo.length - 1])
  }
  return kept.map((p) => [round5(p.lat), round5(p.lon)])
}
const round5 = (x: number) => Math.round(x * 1e5) / 1e5

function rdp(pts: number[][], eps: number): boolean[] {
  const keep = new Array(pts.length).fill(false)
  keep[0] = keep[pts.length - 1] = true
  const stack: [number, number][] = [[0, pts.length - 1]]
  while (stack.length) {
    const [a, b] = stack.pop()!
    const [ax, ay] = pts[a], [bx, by] = pts[b]
    const dx = bx - ax, dy = by - ay
    const len = Math.hypot(dx, dy)
    let maxD = -1, idx = -1
    for (let i = a + 1; i < b; i++) {
      // closed loops: start == end, so measure plain distance to that point
      const d = len < 1e-6
        ? Math.hypot(pts[i][0] - ax, pts[i][1] - ay)
        : Math.abs(dy * pts[i][0] - dx * pts[i][1] + bx * ay - by * ax) / len
      if (d > maxD) { maxD = d; idx = i }
    }
    if (maxD > eps && idx > 0) {
      keep[idx] = true
      stack.push([a, idx], [idx, b])
    }
  }
  return keep
}

/** Coefficient of variation of 30-second average speed while moving (high for interval sessions). */
export function paceVariation(pts: TrackPoint[]): number | null {
  if (pts.length < 20) return null
  const buckets = new Map<number, { d: number; t: number }>()
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1], b = pts[i]
    if (a.dist === null || b.dist === null || b.ts <= a.ts) continue
    const key = Math.floor(b.ts / 30)
    const e = buckets.get(key) ?? { d: 0, t: 0 }
    e.d += b.dist - a.dist
    e.t += b.ts - a.ts
    buckets.set(key, e)
  }
  const speeds = [...buckets.values()].filter((e) => e.t >= 15).map((e) => e.d / e.t).filter((v) => v > 1.2)
  if (speeds.length < 10) return null
  const mu = speeds.reduce((a, b) => a + b, 0) / speeds.length
  const sd = Math.sqrt(speeds.reduce((a, b) => a + (b - mu) ** 2, 0) / speeds.length)
  return sd / mu
}

/** Total ascent with a small hysteresis to ignore GPS/barometer noise. */
export function ascent(pts: TrackPoint[], threshold = 3): number | null {
  const e = pts.map((p) => p.ele).filter((v): v is number => v !== null)
  if (e.length < 2) return null
  let gain = 0, ref = e[0]
  for (const v of e) {
    if (v - ref >= threshold) { gain += v - ref; ref = v }
    else if (v < ref) ref = v
  }
  return gain
}
