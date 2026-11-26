export type RunType =
  | 'Easy Run'
  | 'Long Run'
  | 'Tempo Run'
  | 'Intervals'
  | 'Recovery Run'
  | 'Trail Run'

/** Summary of one run, as delivered by `runs.json` (later: the backend API). */
export interface RunSummary {
  id: string
  name: string
  type: RunType
  start: string // ISO 8601
  distance_m: number
  duration_s: number
  avg_pace_s_per_km: number
  avg_hr: number
  max_hr: number
  elevation_gain_m: number
  gpx: string // path relative to the data root
}

export interface RunManifest {
  generated: string
  runs: RunSummary[]
}

/** A route reduced to an SVG-ready shape inside a 100×100 box. */
export interface RouteShape {
  path: string
  start: [number, number]
  end: [number, number]
}
