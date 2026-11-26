/** Read models for the UI. */
import type { Database } from 'sql.js'
import { query } from '../db/appDb'
import type { RunSummary, SleepNight } from '../types'
import { isRun } from './runs'

export function listRuns(db: Database): RunSummary[] {
  return query<Record<string, unknown>>(
    db,
    `SELECT id, name, category, sport, start_ts, distance_m, duration_s, avg_pace_s_km, avg_hr, max_hr, ascent_m,
            te_aerobic, route_preview FROM activities ORDER BY start_ts DESC`,
  )
    .filter((r) => isRun(String(r.sport)))
    .map((r) => ({
      id: String(r.id),
      name: String(r.name ?? 'Lauf').replace(/^~/, ''),
      category: (r.category ?? 'easy') as RunSummary['category'],
      start: new Date(Number(r.start_ts) * 1000).toISOString(),
      distance_m: Number(r.distance_m ?? 0),
      duration_s: Number(r.duration_s ?? 0),
      avg_pace_s_per_km: Number(r.avg_pace_s_km ?? 0),
      avg_hr: r.avg_hr === null ? null : Math.round(Number(r.avg_hr)),
      max_hr: r.max_hr === null ? null : Math.round(Number(r.max_hr)),
      elevation_gain_m: r.ascent_m === null ? null : Math.round(Number(r.ascent_m)),
      te_aerobic: r.te_aerobic === null ? null : Number(r.te_aerobic),
      preview: r.route_preview ? JSON.parse(String(r.route_preview)) : [],
    }))
}

export function listNights(db: Database, limit = 400): SleepNight[] {
  return query<Record<string, unknown>>(db, 'SELECT * FROM sleep_nights ORDER BY night DESC LIMIT ?', [limit]).map(
    (r) => ({ ...r, details: JSON.parse(String(r.details ?? '{}')) }) as unknown as SleepNight,
  )
}

export function stats(db: Database) {
  const one = (sql: string) => Number(db.exec(sql)[0]?.values[0]?.[0] ?? 0)
  return {
    activities: one('SELECT COUNT(*) FROM activities'),
    nights: one('SELECT COUNT(*) FROM sleep_nights'),
    samples: one('SELECT (SELECT COUNT(*) FROM metric_samples) + (SELECT COUNT(*) FROM minute_samples)'),
    lastImport: one("SELECT value FROM meta WHERE key = 'last_import'") || null,
    lastSync: one("SELECT value FROM meta WHERE key = 'last_sync'") || null,
  }
}

export function getSetting(db: Database, key: string): string {
  return String(db.exec('SELECT value FROM meta WHERE key = ?', [`setting:${key}`])[0]?.values[0]?.[0] ?? '')
}

export function setSetting(db: Database, key: string, value: string) {
  db.run('INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)', [`setting:${key}`, value])
}
