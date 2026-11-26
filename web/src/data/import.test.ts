import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { migrate, query } from '../db/appDb'
import { getSql } from '../db/sqljs'
import { importGadgetbridge } from './importGadgetbridge'

const DEMO = resolve(__dirname, '../../public/demo/gadgetbridge-demo.zip')

describe('Gadgetbridge import', async () => {
  const SQL = await getSql()
  const db = new SQL.Database()
  migrate(db)
  const zip = new Uint8Array(readFileSync(DEMO))
  const first = await importGadgetbridge(db, zip)

  it('imports activities with tracks and previews', () => {
    expect(first.activities).toBeGreaterThan(50)
    expect(first.tracks).toBe(first.activities)
    const [r] = query<{ n: number; p: number }>(db, 'SELECT COUNT(*) n, SUM(route_preview IS NOT NULL) p FROM activities')
    expect(r.p).toBe(r.n)
    const [few] = query<{ n: number }>(db, "SELECT COUNT(*) n FROM activities WHERE json_array_length(route_preview) < 20")
    expect(few.n).toBe(0) // closed loops must not collapse to start/end
    const cats = query<{ category: string; n: number }>(db, 'SELECT category, COUNT(*) n FROM activities GROUP BY 1')
    console.log('categories', cats)
    expect(cats.find((c) => c.category === 'intervals')?.n).toBeGreaterThan(0)
    expect(cats.find((c) => c.category === 'trail')?.n).toBeGreaterThan(0)
  })

  it('imports all-day samples', () => {
    const m = query<{ metric: string; n: number }>(db, 'SELECT metric, COUNT(*) n FROM metric_samples GROUP BY 1')
    console.log('metrics', m)
    for (const k of ['stress', 'body_battery', 'spo2', 'respiration', 'hrv', 'resting_hr', 'sleep_stage', 'garmin_sleep_score']) {
      expect(m.find((x) => x.metric === k)?.n, k).toBeGreaterThan(0)
    }
    const [mins] = query<{ n: number }>(db, 'SELECT COUNT(*) n FROM minute_samples')
    expect(mins.n).toBeGreaterThan(100_000)
  })

  it('computes sleep nights with a score', () => {
    const nights = query<Record<string, number | string>>(db, 'SELECT * FROM sleep_nights ORDER BY night')
    expect(nights.length).toBeGreaterThan(140)
    const last = nights.at(-1)!
    console.log('last night', { ...last, details: undefined })
    console.log('factors', JSON.parse(String(last.details)).factors)
    const scores = nights.map((n) => Number(n.score))
    const garmin = nights.map((n) => Number(n.garmin_score))
    const avg = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length
    console.log('GSS avg', avg(scores).toFixed(1), 'min', Math.min(...scores), 'max', Math.max(...scores), '| Garmin avg', avg(garmin).toFixed(1))
    expect(Math.min(...scores)).toBeGreaterThanOrEqual(0)
    expect(Math.max(...scores)).toBeLessThanOrEqual(100)
    expect(Number(last.tst_min)).toBeGreaterThan(300)
  })

  it('exports the app database for server tests', async () => {
    const out = process.env.GAPMIN_EXPORT_DB
    if (!out) return
    const { writeFileSync } = await import('node:fs')
    writeFileSync(out, db.export())
  })

  it('is idempotent on re-import', async () => {
    const before = query<{ n: number }>(db, 'SELECT (SELECT COUNT(*) FROM metric_samples) + (SELECT COUNT(*) FROM activities) n')[0].n
    const again = await importGadgetbridge(db, zip)
    const after = query<{ n: number }>(db, 'SELECT (SELECT COUNT(*) FROM metric_samples) + (SELECT COUNT(*) FROM activities) n')[0].n
    expect(after).toBe(before)
    expect(again.tracks).toBe(0) // tracks parsed only once
    console.log('first import', first, 'second import', again)
  })
})
