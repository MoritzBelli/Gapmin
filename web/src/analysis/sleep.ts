/**
 * Night detection, sleep metrics and the Gapmin Sleep Score (GSS).
 * Rationale, thresholds and sources: docs/research/schlaf-score.md
 *
 * Pure functions only; database access lives in src/data/sleepJob.ts.
 */

// Garmin sleep levels as stored by Gadgetbridge
export const STAGE = { UNMEASURABLE: 0, AWAKE: 1, LIGHT: 2, DEEP: 3, REM: 4 } as const
export type Stage = (typeof STAGE)[keyof typeof STAGE]

export interface StageEvent { ts: number; stage: number }
export interface Sample { ts: number; value: number }

export interface Segment { start: number; end: number; stage: number }

/** Split stage events into sleep sessions (gap > 4 h starts a new one). */
export function splitSessions(events: StageEvent[], maxGapS = 4 * 3600): StageEvent[][] {
  const sorted = [...events].sort((a, b) => a.ts - b.ts)
  const out: StageEvent[][] = []
  for (const e of sorted) {
    const cur = out.at(-1)
    if (!cur || e.ts - cur[cur.length - 1].ts > maxGapS) out.push([e])
    else cur.push(e)
  }
  return out
}

/** Turn change events into contiguous segments; a single gap longer than 90 min is treated as unknown. */
export function toSegments(events: StageEvent[], maxSegS = 90 * 60): Segment[] {
  const segs: Segment[] = []
  for (let i = 0; i < events.length - 1; i++) {
    const start = events[i].ts
    const end = Math.min(events[i + 1].ts, start + maxSegS)
    const stage = events[i].stage === STAGE.UNMEASURABLE ? STAGE.AWAKE : events[i].stage
    const prev = segs.at(-1)
    if (prev && prev.stage === stage && prev.end === start) prev.end = end
    else if (end > start) segs.push({ start, end, stage })
  }
  return segs
}

const isSleep = (s: number) => s === STAGE.LIGHT || s === STAGE.DEEP || s === STAGE.REM
const minutes = (s: number) => s / 60

export interface NightMetrics {
  bed: number; onset: number; wake: number; out: number
  tibMin: number; tstMin: number; solMin: number; wasoMin: number
  awakenings: number; awakeningsLong: number; efficiency: number
  deepMin: number; lightMin: number; remMin: number; awakeMin: number
  remLatencyMin: number | null
  deepFirstHalfShare: number | null  // share of deep sleep in 1st half of the sleep period
  remSecondHalfShare: number | null  // share of REM in 2nd half
  segments: Segment[]
}

export function nightMetrics(events: StageEvent[]): NightMetrics | null {
  const segs = toSegments(events)
  const sleepSegs = segs.filter((s) => isSleep(s.stage))
  if (!sleepSegs.length) return null
  const bed = events[0].ts
  const out = events[events.length - 1].ts
  const onset = sleepSegs[0].start
  const wake = sleepSegs[sleepSegs.length - 1].end

  let deep = 0, light = 0, rem = 0, awake = 0, waso = 0, awakenings = 0, awakeningsLong = 0
  for (const s of segs) {
    const d = s.end - s.start
    if (s.stage === STAGE.DEEP) deep += d
    else if (s.stage === STAGE.LIGHT) light += d
    else if (s.stage === STAGE.REM) rem += d
    else {
      awake += d
      if (s.start >= onset && s.end <= wake) {
        waso += d
        awakenings++
        if (d > 5 * 60) awakeningsLong++
      }
    }
  }
  const tst = deep + light + rem
  const tib = Math.max(out - bed, wake - bed)
  const mid = onset + (wake - onset) / 2
  const share = (stage: number, first: boolean) => {
    let inHalf = 0, total = 0
    for (const s of segs) {
      if (s.stage !== stage) continue
      total += s.end - s.start
      const a = first ? s.start : Math.max(s.start, mid)
      const b = first ? Math.min(s.end, mid) : s.end
      if (b > a) inHalf += b - a
    }
    return total > 0 ? inHalf / total : null
  }
  const firstRem = segs.find((s) => s.stage === STAGE.REM)

  return {
    bed, onset, wake, out,
    tibMin: minutes(tib), tstMin: minutes(tst), solMin: minutes(onset - bed), wasoMin: minutes(waso),
    awakenings, awakeningsLong, efficiency: tib > 0 ? tst / tib : 0,
    deepMin: minutes(deep), lightMin: minutes(light), remMin: minutes(rem), awakeMin: minutes(awake),
    remLatencyMin: firstRem ? minutes(firstRem.start - onset) : null,
    deepFirstHalfShare: share(STAGE.DEEP, true),
    remSecondHalfShare: share(STAGE.REM, false),
    segments: segs,
  }
}

// ---------------------------------------------------------------------------
// Physiology during sleep

const mean = (xs: number[]) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null)
const within = (xs: Sample[], a: number, b: number) => xs.filter((x) => x.ts >= a && x.ts <= b)

export interface NightPhysiology {
  hrAvg: number | null
  hrMin: number | null        // lowest 5-minute average
  hrMinTs: number | null
  hrDayAvg: number | null     // preceding daytime average (for dipping)
  hrDip: number | null        // 1 - sleep/day
  hrvAvg: number | null
  hrvMax: number | null
  stressAvg: number | null
  respAvg: number | null; respMin: number | null; respMax: number | null
  spo2Avg: number | null; spo2Min: number | null; spo2Below90: number | null
  bbGain: number | null
}

export function nightPhysiology(
  m: NightMetrics,
  d: { hr: Sample[]; hrDay: Sample[]; hrv: Sample[]; stress: Sample[]; resp: Sample[]; spo2: Sample[]; bb: Sample[] },
): NightPhysiology {
  const hr = within(d.hr, m.onset, m.wake).filter((s) => s.value > 25 && s.value < 220)
  let hrMin: number | null = null, hrMinTs: number | null = null
  for (let i = 0; i + 5 <= hr.length; i++) {
    const avg = mean(hr.slice(i, i + 5).map((s) => s.value))!
    if (hrMin === null || avg < hrMin) { hrMin = avg; hrMinTs = hr[i + 2].ts }
  }
  const hrAvg = mean(hr.map((s) => s.value))
  const hrDayAvg = mean(d.hrDay.filter((s) => s.value > 25 && s.value < 220).map((s) => s.value))

  const hrv = within(d.hrv, m.bed, m.out).map((s) => s.value).filter((v) => v > 0)
  const stress = within(d.stress, m.onset, m.wake).map((s) => s.value).filter((v) => v >= 0 && v <= 100)
  const resp = within(d.resp, m.onset, m.wake).map((s) => s.value).filter((v) => v > 3 && v < 60)
  const spo2 = within(d.spo2, m.bed, m.out).map((s) => s.value).filter((v) => v >= 50 && v <= 100)

  const nearest = (t: number) => {
    let best: Sample | null = null
    for (const s of d.bb) if (Math.abs(s.ts - t) <= 3600 && (!best || Math.abs(s.ts - t) < Math.abs(best.ts - t))) best = s
    return best
  }
  const bbStart = nearest(m.onset), bbEnd = nearest(m.wake)

  return {
    hrAvg, hrMin, hrMinTs, hrDayAvg,
    hrDip: hrAvg !== null && hrDayAvg ? 1 - hrAvg / hrDayAvg : null,
    hrvAvg: mean(hrv), hrvMax: hrv.length ? Math.max(...hrv) : null,
    stressAvg: mean(stress),
    respAvg: mean(resp), respMin: resp.length ? Math.min(...resp) : null, respMax: resp.length ? Math.max(...resp) : null,
    spo2Avg: mean(spo2), spo2Min: spo2.length ? Math.min(...spo2) : null,
    spo2Below90: spo2.length ? spo2.filter((v) => v < 90).length / spo2.length : null,
    bbGain: bbStart && bbEnd ? bbEnd.value - bbStart.value : null,
  }
}

// ---------------------------------------------------------------------------
// Score

/** Linear 0 → 100 between `bad` and `good` (works for either direction), clamped. */
export const ramp = (x: number, bad: number, good: number) =>
  Math.max(0, Math.min(100, ((x - bad) / (good - bad)) * 100))

/** Piecewise-linear interpolation through (x, y) knots sorted by x. */
function interp(x: number, knots: [number, number][]): number {
  if (x <= knots[0][0]) return knots[0][1]
  for (let i = 1; i < knots.length; i++) {
    const [x0, y0] = knots[i - 1], [x1, y1] = knots[i]
    if (x <= x1) return y0 + ((x - x0) / (x1 - x0)) * (y1 - y0)
  }
  return knots[knots.length - 1][1]
}

export interface Baselines {
  nights: number             // nights available in the baseline window
  lnHrvMean: number | null
  lnHrvSd: number | null
  hrMean: number | null
  respMean: number | null
}

export interface Regularity {
  nights: number
  spreadMin: number | null   // robust SD of onset/wake clock times
  midpointMin: number        // this night's midpoint, minutes after local midnight
}

export const WEIGHTS = {
  duration: 0.25,
  continuity: 0.25,
  recovery: 0.25,
  stages: 0.10,
  regularity: 0.10,
  breathing: 0.05,
} as const
export type Component = keyof typeof WEIGHTS

export interface ScoreResult {
  score: number
  label: 'Hervorragend' | 'Gut' | 'Ausreichend' | 'Schlecht'
  components: Record<Component, number | null>
  coverage: number
  provisional: boolean       // baseline not yet established
  factors: string[]
}

const fmtH = (min: number) => `${Math.floor(min / 60)}:${String(Math.round(min % 60)).padStart(2, '0')} h`

export function scoreNight(m: NightMetrics, p: NightPhysiology, b: Baselines, r: Regularity): ScoreResult {
  const notes: Record<Component, string> = {} as Record<Component, string>

  // Dauer (AASM/NSF: 7-9 h)
  const tstH = m.tstMin / 60
  const duration = tstH < 7 ? ramp(tstH, 4, 7) : tstH <= 9 ? 100 : interp(tstH, [[9, 100], [11, 80]])
  notes.duration = tstH < 7 ? `Kurz geschlafen (${fmtH(m.tstMin)})` : `Lang geschlafen (${fmtH(m.tstMin)})`

  // Kontinuität (NSF 2017)
  const se = ramp(m.efficiency * 100, 70, 90)
  const waso = ramp(-m.wasoMin, -60, -10)
  const wake = interp(m.awakeningsLong, [[1, 100], [2, 70], [3, 40], [4, 10]])
  const sol = ramp(-m.solMin, -60, -15)
  const continuity = 0.4 * se + 0.3 * waso + 0.2 * wake + 0.1 * sol
  notes.continuity =
    m.wasoMin > 20 ? `${Math.round(m.wasoMin)} min wach nach dem Einschlafen`
      : m.awakeningsLong > 1 ? `${m.awakeningsLong}× länger wach`
        : `Schlafeffizienz ${Math.round(m.efficiency * 100)} %`

  // Autonome Erholung (individuelle Baseline, Plews: SWC ≈ 0,5 SD)
  const personal = b.nights >= 7
  let hrvScore: number | null = null
  if (p.hrvAvg !== null) {
    if (personal && b.lnHrvMean !== null && b.lnHrvSd) {
      const z = (Math.log(p.hrvAvg) - b.lnHrvMean) / Math.max(b.lnHrvSd, 0.03)
      hrvScore = interp(z, [[-2, 0], [-1, 45], [0, 80], [0.5, 100]])
      const pct = Math.round((p.hrvAvg / Math.exp(b.lnHrvMean) - 1) * 100)
      notes.recovery = pct < 0 ? `HRV ${-pct} % unter deinem Normal` : `HRV ${pct} % über deinem Normal`
    } else {
      hrvScore = ramp(p.hrvAvg, 20, 60) // grob, bis eine Baseline existiert
    }
  }
  let hrScore: number | null = null
  if (p.hrAvg !== null) {
    if (personal && b.hrMean !== null) {
      const delta = p.hrAvg - b.hrMean
      hrScore = interp(delta, [[0, 100], [3, 70], [6, 35], [9, 0]])
      if (delta > 3) notes.recovery = `Schlafpuls ${delta.toFixed(0)} bpm über Normal`
    } else {
      hrScore = ramp(-p.hrAvg, -75, -50)
    }
  }
  const stressScore = p.stressAvg !== null ? ramp(-p.stressAvg, -40, -10) : null
  const recovery = weighted([[hrvScore, 0.5], [hrScore, 0.3], [stressScore, 0.2]])
  notes.recovery ??= p.stressAvg !== null ? `Stress im Schlaf ${Math.round(p.stressAvg)}` : 'Erholung'

  // Phasen (geringe Messgenauigkeit, daher Bänder)
  const pct = (x: number) => (m.tstMin > 0 ? (x / m.tstMin) * 100 : 0)
  const band = (x: number, lo: number, hi: number, k: number) => Math.max(0, 100 - k * (x < lo ? lo - x : x > hi ? x - hi : 0))
  const deepPct = pct(m.deepMin), remPct = pct(m.remMin), lightPct = pct(m.lightMin)
  const stages = (band(deepPct, 13, 23, 6) + band(remPct, 18, 26, 5) + band(lightPct, 0, 65, 4)) / 3
  notes.stages =
    deepPct < 13 ? `Wenig Tiefschlaf (${Math.round(deepPct)} %)`
      : remPct < 18 ? `Wenig REM-Schlaf (${Math.round(remPct)} %)`
        : 'Schlafphasen'

  // Regelmäßigkeit (Windred 2024, NSF 2023)
  const regularity = r.nights >= 3 && r.spreadMin !== null ? ramp(-r.spreadMin, -90, -20) : null
  notes.regularity = r.spreadMin !== null ? `Schlafzeiten schwanken um ±${Math.round(r.spreadMin)} min` : 'Regelmäßigkeit'

  // Atmung & SpO2
  let respScore: number | null = null
  if (p.respAvg !== null) {
    respScore = personal && b.respMean !== null
      ? interp(Math.abs(p.respAvg - b.respMean), [[1, 100], [3, 40], [4, 0]])
      : p.respAvg >= 11.8 && p.respAvg <= 19.2 ? 100 : 50
  }
  const spo2Score = p.spo2Avg !== null ? interp(p.spo2Avg, [[90, 0], [92, 40], [95, 100]]) : null
  const breathing = weighted([[respScore, 0.6], [spo2Score, 0.4]])
  notes.breathing =
    p.respAvg !== null && b.respMean !== null && p.respAvg - b.respMean >= 3 ? `Atemfrequenz erhöht (${p.respAvg.toFixed(1)}/min)`
      : p.spo2Avg !== null && p.spo2Avg < 93 ? `Niedrige Sauerstoffsättigung (${Math.round(p.spo2Avg)} %)`
        : 'Atmung'

  const components: Record<Component, number | null> = { duration, continuity, recovery, stages, regularity, breathing }
  let sumW = 0, sum = 0
  const losses: [Component, number][] = [] // weighted points lost per component
  for (const k of Object.keys(WEIGHTS) as Component[]) {
    const v = components[k]
    if (v === null) continue
    sumW += WEIGHTS[k]
    sum += WEIGHTS[k] * v
    losses.push([k, WEIGHTS[k] * (100 - v)])
  }
  const score = sumW ? Math.round(sum / sumW) : 0
  const factors = losses
    .filter(([k, l]) => l >= 2 && (components[k] ?? 100) < 85)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 2)
    .map(([k]) => notes[k])

  return {
    score,
    label: score >= 90 ? 'Hervorragend' : score >= 80 ? 'Gut' : score >= 60 ? 'Ausreichend' : 'Schlecht',
    components,
    coverage: sumW,
    provisional: !personal,
    factors,
  }
}

function weighted(parts: [number | null, number][]): number | null {
  let w = 0, s = 0
  for (const [v, k] of parts) if (v !== null) { w += k; s += v * k }
  return w ? s / w : null
}

/** Robust spread (MAD × 1.4826) of clock times in minutes, handling the midnight wrap. */
export function clockSpread(minutesOfDay: number[]): number | null {
  if (minutesOfDay.length < 2) return null
  // unwrap around the circular mean so 23:50 and 00:10 are 20 min apart
  const ang = minutesOfDay.map((m) => (m / 1440) * 2 * Math.PI)
  const c = Math.atan2(mean(ang.map(Math.sin))!, mean(ang.map(Math.cos))!)
  const centre = ((c / (2 * Math.PI)) * 1440 + 1440) % 1440
  const dev = minutesOfDay.map((m) => {
    let d = m - centre
    if (d > 720) d -= 1440
    if (d < -720) d += 1440
    return d
  })
  const med = median(dev)
  return median(dev.map((d) => Math.abs(d - med))) * 1.4826
}

function median(xs: number[]): number {
  const s = [...xs].sort((a, b) => a - b)
  const m = Math.floor(s.length / 2)
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2
}
