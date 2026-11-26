import type { RunCategory } from './data/runs'

export type { RunCategory }

/** One run as shown in the gallery (from the local database). */
export interface RunSummary {
  id: string
  name: string
  category: RunCategory
  start: string // ISO 8601
  distance_m: number
  duration_s: number
  avg_pace_s_per_km: number
  avg_hr: number | null
  max_hr: number | null
  elevation_gain_m: number | null
  te_aerobic: number | null
  preview: [number, number][] // [lat, lon]
}

/** A route reduced to an SVG-ready shape inside a 100×100 box. */
export interface RouteShape {
  path: string
  start: [number, number]
  end: [number, number]
}

export interface NightDetails {
  hypnogram: [number, number, number][] // [offset min from bed, duration min, stage]
  remLatencyMin: number | null
  deepFirstHalfShare: number | null
  remSecondHalfShare: number | null
  physiology: {
    hrAvg: number | null; hrMin: number | null; hrMinTs: number | null; hrDayAvg: number | null; hrDip: number | null
    hrvAvg: number | null; hrvMax: number | null; stressAvg: number | null
    respAvg: number | null; respMin: number | null; respMax: number | null
    spo2Avg: number | null; spo2Min: number | null; spo2Below90: number | null; bbGain: number | null
  }
  baselines: { nights: number; lnHrvMean: number | null; lnHrvSd: number | null; hrMean: number | null; respMean: number | null }
  factors: string[]
  label: string
  provisional: boolean
}

export interface SleepNight {
  night: string
  bed_ts: number; onset_ts: number; wake_ts: number; out_ts: number
  tib_min: number; tst_min: number; sol_min: number; waso_min: number
  awakenings: number; awakenings_long: number; efficiency: number
  deep_min: number; light_min: number; rem_min: number; awake_min: number
  hr_avg: number | null; hr_min: number | null; hrv_avg: number | null; stress_avg: number | null
  resp_avg: number | null; spo2_avg: number | null; bb_gain: number | null
  midpoint_min: number; regularity_sd: number | null
  score: number
  score_duration: number | null; score_continuity: number | null; score_recovery: number | null
  score_stages: number | null; score_regularity: number | null; score_breathing: number | null
  coverage: number; garmin_score: number | null
  details: NightDetails
}
