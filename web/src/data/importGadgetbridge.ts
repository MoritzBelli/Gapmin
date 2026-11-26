/**
 * Import a Gadgetbridge export (ZIP backup or the bare "Gadgetbridge" SQLite file)
 * into the app database. Incremental and idempotent: every source table keeps a
 * watermark in `meta`, rows are upserted, tracks are parsed once.
 *
 * Table/column names follow Gadgetbridge's GBDaoGenerator (greenDAO); lookups are
 * case/underscore-insensitive so small naming differences between versions don't break.
 */
import JSZip from 'jszip'
import type { Database, SqlValue } from 'sql.js'
import { getSql } from '../db/sqljs'
import { classifyRun, defaultRunName, isRun, sportOf } from './runs'
import { ascent, paceVariation, parseFit, parseGpx, routePreview, type TrackPoint } from './tracks'
import { recomputeSleep } from './sleepJob'

export interface ImportProgress { step: string; fraction: number }
export interface ImportResult {
  activities: number
  tracks: number
  samples: number
  nights: number
  ms: number
}

type FileLookup = (phonePath: string | null) => Promise<Uint8Array | null>

const SQLITE_MAGIC = 'SQLite format 3'
const MARGIN_S = 2 * 86400 // re-read the last two days so late-synced samples are not missed
const norm = (s: string) => s.replace(/_/g, '').toLowerCase()
const isSqlite = (b: Uint8Array) => new TextDecoder().decode(b.subarray(0, 15)) === SQLITE_MAGIC

export async function importGadgetbridge(
  db: Database,
  data: Uint8Array,
  onProgress: (p: ImportProgress) => void = () => {},
): Promise<ImportResult> {
  const t0 = performance.now()
  onProgress({ step: 'Export lesen', fraction: 0.02 })

  let dbBytes: Uint8Array | null = null
  let lookup: FileLookup = async () => null

  if (data[0] === 0x50 && data[1] === 0x4b) {
    const zip = await JSZip.loadAsync(data)
    const files = Object.values(zip.files).filter((f) => !f.dir)
    // Database: prefer the canonical name, otherwise sniff the SQLite header
    const candidates = files.sort((a, b) => +/gadgetbridge$/i.test(b.name) - +/gadgetbridge$/i.test(a.name))
    for (const f of candidates) {
      if (!/(^|\/)(gadgetbridge(\.db)?|[^/]+\.(db|sqlite))$/i.test(f.name)) continue
      const bytes = await f.async('uint8array')
      if (isSqlite(bytes)) { dbBytes = bytes; break }
    }
    const byRel = new Map<string, JSZip.JSZipObject>()
    const byBase = new Map<string, JSZip.JSZipObject>()
    for (const f of files) {
      const rel = f.name.replace(/^.*?files\//, '')
      byRel.set(rel, f)
      byBase.set(f.name.split('/').pop()!, f)
    }
    lookup = async (phonePath) => {
      if (!phonePath) return null
      const rel = phonePath.replace(/^.*?\/files\//, '')
      const f = byRel.get(rel) ?? byBase.get(phonePath.split('/').pop()!)
      return f ? f.async('uint8array') : null
    }
  } else if (isSqlite(data)) {
    dbBytes = data
  }
  if (!dbBytes) throw new Error('Keine Gadgetbridge-Datenbank im Export gefunden')

  const SQL = await getSql()
  const gb = new SQL.Database(dbBytes)
  try {
    return await run(db, gb, lookup, onProgress, t0)
  } finally {
    gb.close()
  }
}

async function run(db: Database, gb: Database, lookup: FileLookup, onProgress: (p: ImportProgress) => void, t0: number) {
  const tables = new Set(
    gb.exec("SELECT name FROM sqlite_master WHERE type='table'")[0]?.values.map((r) => String(r[0])) ?? [],
  )
  const columns = (table: string) => {
    const info = gb.exec(`PRAGMA table_info(${table})`)[0]?.values ?? []
    return new Map(info.map((r) => [norm(String(r[1])), String(r[1])]))
  }
  const meta = {
    get: (k: string) => db.exec('SELECT value FROM meta WHERE key = ?', [k])[0]?.values[0]?.[0] as string | undefined,
    set: (k: string, v: string | number) => db.run('INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)', [k, String(v)]),
  }

  // ---- devices ------------------------------------------------------------
  const devices = new Map<number, string>()
  if (tables.has('DEVICE')) {
    const c = columns('DEVICE')
    const rows = gb.exec(`SELECT ${c.get('id') ?? '_id'}, ${c.get('identifier')}, ${c.get('name')}, ${c.get('manufacturer')}, ${c.get('model') ?? 'NULL'} FROM DEVICE`)[0]?.values ?? []
    for (const [id, ident, name, manu, model] of rows) {
      devices.set(Number(id), String(ident))
      db.run('INSERT OR REPLACE INTO devices (id, name, manufacturer, model) VALUES (?,?,?,?)', [String(ident), name, manu, model])
    }
  }
  const dev = (id: unknown) => devices.get(Number(id)) ?? `device-${id}`

  // ---- activities -----------------------------------------------------------
  onProgress({ step: 'Aktivitäten', fraction: 0.08 })
  let activities = 0, tracks = 0
  if (tables.has('BASE_ACTIVITY_SUMMARY')) {
    const c = columns('BASE_ACTIVITY_SUMMARY')
    const col = (k: string) => c.get(k) ?? 'NULL'
    const res = gb.exec(
      `SELECT ${col('name')}, ${col('starttime')}, ${col('endtime')}, ${col('activitykind')}, ${col('gpxtrack')},
              ${col('rawdetailspath')}, ${col('deviceid')}, ${col('summarydata')} FROM BASE_ACTIVITY_SUMMARY ORDER BY 2`,
    )[0]?.values ?? []
    const known = new Map(
      (db.exec('SELECT id, has_track FROM activities')[0]?.values ?? []).map((r) => [String(r[0]), Number(r[1])]),
    )
    for (let i = 0; i < res.length; i++) {
      const [name, start, end, kind, gpx, raw, deviceId, summaryJson] = res[i]
      const startS = toSeconds(Number(start)), endS = toSeconds(Number(end))
      const id = `${dev(deviceId)}:${startS}`
      const sum = parseSummary(summaryJson as string | null)
      const sport = sportOf(Number(kind))

      let pts: TrackPoint[] = []
      const hasTrack = known.get(id) === 1
      if (!hasTrack) {
        for (const path of [gpx, raw] as (string | null)[]) {
          const bytes = await lookup(path)
          if (!bytes) continue
          pts = /\.fit$/i.test(path ?? '') || isFit(bytes) ? parseFit(bytes) : parseGpx(new TextDecoder().decode(bytes))
          if (pts.length) break
        }
      }

      const hrs = pts.map((p) => p.hr).filter((v): v is number => !!v)
      const distance = sum('distanceMeters') ?? (pts.length ? pts[pts.length - 1].dist : null)
      const duration = sum('activeSeconds') ?? (endS - startS)
      const avgHr = sum('averageHR') ?? (hrs.length ? hrs.reduce((a, b) => a + b, 0) / hrs.length : null)
      const row = {
        id, device: dev(deviceId), kind: Number(kind), sport, name: (name as string | null) || null,
        start: startS, end: endS, duration, distance,
        avg_hr: avgHr, max_hr: sum('maxHR') ?? (hrs.length ? Math.max(...hrs) : null), min_hr: sum('minHR'),
        pace: sum('averageKMPaceSeconds') ?? (distance && duration ? duration / (distance / 1000) : null),
        ascent: sum('ascentMeters') ?? sum('elevationGain') ?? ascent(pts),
        descent: sum('descentMeters') ?? sum('elevationLoss'),
        calories: sum('caloriesBurnt'), cadence: sum('averageCadence'),
        te_a: sum('aerobicTrainingEffect'), te_an: sum('anaerobicTrainingEffect'), recovery: sum('recoveryTime'),
        summary: summaryJson as string | null,
      }
      db.run(
        `INSERT INTO activities (id, device_id, kind, sport, name, start_ts, end_ts, duration_s, distance_m, avg_hr, max_hr, min_hr,
           avg_pace_s_km, ascent_m, descent_m, calories, avg_cadence, te_aerobic, te_anaerobic, recovery_h, summary_json, updated_at)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
         ON CONFLICT(id) DO UPDATE SET kind=excluded.kind, sport=excluded.sport, name=COALESCE(excluded.name, activities.name),
           end_ts=excluded.end_ts, duration_s=excluded.duration_s, distance_m=excluded.distance_m, avg_hr=excluded.avg_hr,
           max_hr=excluded.max_hr, min_hr=excluded.min_hr, avg_pace_s_km=excluded.avg_pace_s_km,
           ascent_m=COALESCE(excluded.ascent_m, activities.ascent_m), descent_m=excluded.descent_m, calories=excluded.calories,
           avg_cadence=excluded.avg_cadence, te_aerobic=excluded.te_aerobic, te_anaerobic=excluded.te_anaerobic,
           recovery_h=excluded.recovery_h, summary_json=excluded.summary_json, updated_at=excluded.updated_at`,
        [row.id, row.device, row.kind, row.sport, row.name, row.start, row.end, row.duration, row.distance, row.avg_hr,
          row.max_hr, row.min_hr, row.pace, row.ascent, row.descent, row.calories, row.cadence, row.te_a, row.te_an,
          row.recovery, row.summary, Math.floor(Date.now() / 1000)],
      )
      activities++

      if (pts.length) {
        db.run('DELETE FROM track_points WHERE activity_id = ?', [id])
        const st = db.prepare('INSERT OR REPLACE INTO track_points VALUES (?,?,?,?,?,?,?,?,?)')
        for (const p of pts) st.run([id, p.ts, p.lat, p.lon, p.ele, p.hr, p.cadence, p.speed, p.dist])
        st.free()
        const preview = routePreview(pts)
        db.run('UPDATE activities SET has_track = 1, route_preview = ?, pace_cv = ? WHERE id = ?', [
          preview.length ? JSON.stringify(preview) : null, paceVariation(pts), id,
        ])
        tracks++
      }
      if (i % 10 === 0) onProgress({ step: 'Aktivitäten', fraction: 0.08 + 0.32 * (i / res.length) })
    }
    classifyAllRuns(db)
  }

  // ---- time series -------------------------------------------------------
  let samples = 0
  let earliestSleepTs = Infinity

  const series: { table: string; cols: string[]; write: (dev: string, ts: number, v: unknown[]) => void }[] = []
  const metric = (name: string, valid: (v: number) => boolean = () => true) => {
    const st = db.prepare('INSERT OR REPLACE INTO metric_samples (metric, device_id, ts, value) VALUES (?,?,?,?)')
    stmts.push(st)
    return (d: string, ts: number, v: unknown[]) => {
      const x = Number(v[0])
      if (v[0] !== null && Number.isFinite(x) && valid(x)) st.run([name, d, ts, x])
    }
  }
  const stmts: { free: () => void }[] = []

  const minute = db.prepare('INSERT OR REPLACE INTO minute_samples VALUES (?,?,?,?,?,?,?,?)')
  stmts.push(minute)
  series.push({
    table: 'GARMIN_ACTIVITY_SAMPLE',
    cols: ['heartrate', 'steps', 'rawintensity', 'rawkind', 'distancecm', 'activecalories'],
    write: (d, ts, [hr, steps, intensity, kind, dist, kcal]) => {
      const h = Number(hr)
      minute.run([d, ts, h > 0 && h < 255 ? h : null, steps, intensity, kind, dist, kcal] as SqlValue[])
    },
  })
  series.push({ table: 'GARMIN_STRESS_SAMPLE', cols: ['stress'], write: metric('stress', (x) => x >= 0 && x <= 100) })
  series.push({ table: 'GARMIN_BODY_ENERGY_SAMPLE', cols: ['energy'], write: metric('body_battery', (x) => x >= 0 && x <= 100) })
  series.push({ table: 'GARMIN_SPO2_SAMPLE', cols: ['spo2'], write: metric('spo2', (x) => x > 50 && x <= 100) })
  series.push({ table: 'GARMIN_RESPIRATORY_RATE_SAMPLE', cols: ['respiratoryrate'], write: metric('respiration', (x) => x > 0 && x < 80) })
  series.push({ table: 'GARMIN_HRV_VALUE_SAMPLE', cols: ['value'], write: metric('hrv', (x) => x > 0 && x < 400) })
  series.push({ table: 'GARMIN_HEART_RATE_RESTING_SAMPLE', cols: ['heartrate'], write: metric('resting_hr', (x) => x > 20 && x < 150) })
  series.push({ table: 'GARMIN_SLEEP_STATS_SAMPLE', cols: ['sleepscore'], write: metric('garmin_sleep_score', (x) => x > 0 && x <= 100) })
  const stage = metric('sleep_stage')
  series.push({
    table: 'GARMIN_SLEEP_STAGE_SAMPLE', cols: ['stage'],
    write: (d, ts, v) => { stage(d, ts, v); if (ts < earliestSleepTs) earliestSleepTs = ts },
  })
  const moderate = metric('intensity_moderate'), vigorous = metric('intensity_vigorous')
  series.push({
    table: 'GARMIN_INTENSITY_MINUTES_SAMPLE', cols: ['moderate', 'vigorous'],
    write: (d, ts, [m, v]) => { moderate(d, ts, [m]); vigorous(d, ts, [v]) },
  })
  const hrvSum = db.prepare('INSERT OR REPLACE INTO hrv_summary VALUES (?,?,?,?,?,?,?,?,?)')
  stmts.push(hrvSum)
  series.push({
    table: 'GARMIN_HRV_SUMMARY_SAMPLE',
    cols: ['weeklyaverage', 'lastnightaverage', 'lastnight5minhigh', 'baselinelowupper', 'baselinebalancedlower', 'baselinebalancedupper', 'statusnum'],
    write: (d, ts, v) => hrvSum.run([d, ts, ...(v as (number | null)[])]),
  })

  for (let si = 0; si < series.length; si++) {
    const s = series[si]
    onProgress({ step: 'Gesundheitsdaten', fraction: 0.4 + 0.45 * (si / series.length) })
    if (!tables.has(s.table)) continue
    const c = columns(s.table)
    const cols = s.cols.map((k) => c.get(k) ?? 'NULL')
    const tsCol = c.get('timestamp') ?? 'TIMESTAMP'
    const wmKey = `wm:${s.table}`
    const wm = Number(meta.get(wmKey) ?? 0)
    // the watermark is stored in seconds; source units may be seconds or milliseconds
    const unitMs = Number(gb.exec(`SELECT MAX(${tsCol}) FROM ${s.table}`)[0]?.values[0]?.[0] ?? 0) > 1e11
    const from = Math.max(0, wm - MARGIN_S) * (unitMs ? 1000 : 1)
    const stmt = gb.prepare(`SELECT ${tsCol}, ${c.get('deviceid') ?? 'DEVICE_ID'}, ${cols.join(', ')} FROM ${s.table} WHERE ${tsCol} >= ? ORDER BY ${tsCol}`)
    stmt.bind([from])
    db.run('BEGIN')
    let maxTs = wm
    while (stmt.step()) {
      const r = stmt.get()
      const ts = unitMs ? Math.floor(Number(r[0]) / 1000) : Number(r[0])
      s.write(dev(r[1]), ts, r.slice(2))
      if (ts > maxTs) maxTs = ts
      samples++
    }
    db.run('COMMIT')
    stmt.free()
    meta.set(wmKey, maxTs)
  }
  for (const st of stmts) st.free()

  // ---- derived data ----------------------------------------------------------
  onProgress({ step: 'Schlaf auswerten', fraction: 0.88 })
  const nights = earliestSleepTs < Infinity ? recomputeSleep(db, earliestSleepTs) : 0
  meta.set('last_import', Math.floor(Date.now() / 1000))
  onProgress({ step: 'Fertig', fraction: 1 })
  return { activities, tracks, samples, nights, ms: Math.round(performance.now() - t0) }
}

export function classifyAllRuns(db: Database) {
  const hrMax = Number(db.exec('SELECT MAX(max_hr) FROM activities')[0]?.values[0]?.[0]) || null
  const rows = db.exec('SELECT id, sport, distance_m, duration_s, avg_hr, pace_cv, start_ts, name FROM activities')[0]?.values ?? []
  const st = db.prepare('UPDATE activities SET category = ?, name = ? WHERE id = ?')
  for (const [id, sport, distance_m, duration_s, avg_hr, pace_cv, start_ts, name] of rows) {
    if (!isRun(String(sport))) continue
    const cat = classifyRun(
      { sport: String(sport), distance_m: distance_m as number, duration_s: duration_s as number, avg_hr: avg_hr as number, pace_cv: pace_cv as number },
      hrMax,
    )
    // keep user-given names; otherwise (re)generate from category
    const keep = name && !String(name).startsWith('~') ? String(name) : null
    st.run([cat, keep ?? `~${defaultRunName(cat, Number(start_ts))}`, id])
  }
  st.free()
}

function toSeconds(t: number) {
  return t > 1e11 ? Math.floor(t / 1000) : t
}

function isFit(b: Uint8Array) {
  return b.length > 12 && b[8] === 0x2e && b[9] === 0x46 && b[10] === 0x49 && b[11] === 0x54 // ".FIT"
}

/** Gadgetbridge summary JSON: { key: { value, unit, group? } } */
function parseSummary(json: string | null) {
  let obj: Record<string, { value?: unknown } | unknown> = {}
  try { obj = json ? JSON.parse(json) : {} } catch { /* ignore */ }
  return (key: string): number | null => {
    const e = obj[key] as { value?: unknown } | undefined
    const v = e && typeof e === 'object' ? e.value : e
    const n = typeof v === 'string' ? parseFloat(v) : (v as number)
    return typeof n === 'number' && Number.isFinite(n) ? n : null
  }
}
