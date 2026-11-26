/** Compute `sleep_nights` from stored samples (see analysis/sleep.ts for the maths). */
import type { Database, SqlValue } from 'sql.js'
import {
  clockSpread, nightMetrics, nightPhysiology, scoreNight, splitSessions,
  type Baselines, type Sample, type StageEvent,
} from '../analysis/sleep'

const MIN_NIGHT_S = 3 * 3600
const BASELINE_NIGHTS = 28

const rows = (db: Database, sql: string, params: SqlValue[] = []) => db.exec(sql, params)[0]?.values ?? []
const samples = (db: Database, metric: string, a: number, b: number): Sample[] =>
  rows(db, 'SELECT ts, value FROM metric_samples WHERE metric = ? AND ts BETWEEN ? AND ? ORDER BY ts', [metric, a, b])
    .map(([ts, value]) => ({ ts: Number(ts), value: Number(value) }))
const minuteHr = (db: Database, a: number, b: number): Sample[] =>
  rows(db, 'SELECT ts, hr FROM minute_samples WHERE hr IS NOT NULL AND ts BETWEEN ? AND ? ORDER BY ts', [a, b])
    .map(([ts, hr]) => ({ ts: Number(ts), value: Number(hr) }))

/** Local calendar date (YYYY-MM-DD) and minutes after local midnight. */
export function localClock(ts: number) {
  const d = new Date(ts * 1000)
  const date = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return { date, minutes: d.getHours() * 60 + d.getMinutes() + d.getSeconds() / 60 }
}

/** Recompute all nights whose events start at or after `fromTs` (minus a day of context). Returns count. */
export function recomputeSleep(db: Database, fromTs = 0): number {
  const start = fromTs - 36 * 3600
  const events: StageEvent[] = rows(db, "SELECT ts, value FROM metric_samples WHERE metric = 'sleep_stage' AND ts >= ? ORDER BY ts", [start])
    .map(([ts, v]) => ({ ts: Number(ts), stage: Number(v) }))
  const device = String(rows(db, "SELECT device_id FROM metric_samples WHERE metric = 'sleep_stage' LIMIT 1")[0]?.[0] ?? '')
  const sessions = splitSessions(events).filter((s) => s[s.length - 1].ts - s[0].ts >= MIN_NIGHT_S)

  const upsert = db.prepare(`INSERT OR REPLACE INTO sleep_nights (
      night, device_id, bed_ts, onset_ts, wake_ts, out_ts, tib_min, tst_min, sol_min, waso_min, awakenings, awakenings_long,
      efficiency, deep_min, light_min, rem_min, awake_min, hr_avg, hr_min, hrv_avg, stress_avg, resp_avg, spo2_avg, bb_gain,
      midpoint_min, regularity_sd, score, score_duration, score_continuity, score_recovery, score_stages, score_regularity,
      score_breathing, coverage, garmin_score, details, updated_at
    ) VALUES (${new Array(37).fill('?').join(',')})`)

  let count = 0
  db.run('BEGIN')
  for (const ev of sessions) {
    const m = nightMetrics(ev)
    if (!m || m.tstMin < 120) continue
    const { date: night } = localClock(m.wake)

    const p = nightPhysiology(m, {
      hr: minuteHr(db, m.onset, m.wake),
      hrDay: minuteHr(db, m.bed - 14 * 3600, m.bed - 2 * 3600),
      hrv: samples(db, 'hrv', m.bed, m.out),
      stress: samples(db, 'stress', m.onset, m.wake),
      resp: samples(db, 'respiration', m.onset, m.wake),
      spo2: samples(db, 'spo2', m.bed, m.out),
      bb: samples(db, 'body_battery', m.bed - 3600, m.out + 3600),
    })

    // Personal baselines from the previous nights (computed earlier in this loop or in past imports)
    const prev = rows(db, `SELECT hrv_avg, hr_avg, resp_avg, onset_ts, wake_ts FROM sleep_nights WHERE night < ? ORDER BY night DESC LIMIT ${BASELINE_NIGHTS}`, [night])
    const lnHrv = prev.map((r) => r[0]).filter((v): v is number => typeof v === 'number' && v > 0).map(Math.log)
    const stat = (xs: number[]) => {
      if (!xs.length) return { mean: null, sd: null }
      const mu = xs.reduce((a, b) => a + b, 0) / xs.length
      return { mean: mu, sd: Math.sqrt(xs.reduce((a, b) => a + (b - mu) ** 2, 0) / Math.max(1, xs.length - 1)) }
    }
    const hrv = stat(lnHrv)
    const nums = (i: number) => prev.map((r) => r[i]).filter((v): v is number => typeof v === 'number')
    const b: Baselines = {
      nights: lnHrv.length,
      lnHrvMean: hrv.mean, lnHrvSd: hrv.sd,
      hrMean: stat(nums(1)).mean,
      respMean: stat(nums(2)).mean,
    }

    const last6 = prev.slice(0, 6)
    const onsets = [m.onset, ...last6.map((r) => Number(r[3]))].map((t) => localClock(t).minutes)
    const wakes = [m.wake, ...last6.map((r) => Number(r[4]))].map((t) => localClock(t).minutes)
    const so = clockSpread(onsets), sw = clockSpread(wakes)
    const spread = so !== null && sw !== null ? (so + sw) / 2 : null
    let mid = localClock(m.onset + (m.wake - m.onset) / 2).minutes
    if (mid > 720) mid -= 1440 // e.g. 23:30 → -30

    const r = scoreNight(m, p, b, { nights: onsets.length, spreadMin: spread, midpointMin: mid })
    const garmin = samples(db, 'garmin_sleep_score', m.wake - 3 * 3600, m.wake + 8 * 3600)[0]?.value ?? null

    const details = {
      hypnogram: m.segments.map((s) => [Math.round((s.start - m.bed) / 60), Math.round((s.end - s.start) / 60), s.stage]),
      remLatencyMin: m.remLatencyMin,
      deepFirstHalfShare: m.deepFirstHalfShare,
      remSecondHalfShare: m.remSecondHalfShare,
      physiology: p,
      baselines: b,
      factors: r.factors,
      label: r.label,
      provisional: r.provisional,
    }
    upsert.run([
      night, device, m.bed, m.onset, m.wake, m.out, m.tibMin, m.tstMin, m.solMin, m.wasoMin, m.awakenings, m.awakeningsLong,
      m.efficiency, m.deepMin, m.lightMin, m.remMin, m.awakeMin, p.hrAvg, p.hrMin, p.hrvAvg, p.stressAvg, p.respAvg, p.spo2Avg,
      p.bbGain, mid, spread, r.score, r.components.duration, r.components.continuity, r.components.recovery,
      r.components.stages, r.components.regularity, r.components.breathing, r.coverage, garmin, JSON.stringify(details),
      Math.floor(Date.now() / 1000),
    ])
    count++
  }
  db.run('COMMIT')
  upsert.free()
  return count
}
