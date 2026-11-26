/** Classification of runs into categories and German default names. */

export type RunCategory = 'easy' | 'recovery' | 'long' | 'tempo' | 'intervals' | 'trail'

// Gadgetbridge ActivityKind codes (model/ActivityKind.java)
const KINDS: Record<number, string> = {
  0x10: 'running', 0x20: 'walking', 0x80: 'cycling', 0x100: 'treadmill', 0x800: 'indoor_cycling',
  0x04000001: 'indoor_track', 0x04000067: 'cross_country_running', 0x040000b0: 'indoor_running',
  0x040000b1: 'running', 0x040000b2: 'walking', 0x040000b3: 'cycling', 0x040000f2: 'trail_running',
}

export function sportOf(kind: number): string {
  return KINDS[kind] ?? 'other'
}

export const isRun = (sport: string) =>
  ['running', 'trail_running', 'treadmill', 'indoor_running', 'indoor_track', 'cross_country_running'].includes(sport)

export interface RunFacts {
  sport: string
  distance_m: number | null
  duration_s: number | null
  avg_hr: number | null
  pace_cv: number | null
}

/**
 * Heuristic, relative to the athlete's own runs:
 * trail by sport, intervals by pace variation, long by distance/duration,
 * tempo / recovery by average HR relative to the highest max HR seen.
 */
export function classifyRun(r: RunFacts, hrMax: number | null): RunCategory {
  if (r.sport === 'trail_running' || r.sport === 'cross_country_running') return 'trail'
  if (r.pace_cv !== null && r.pace_cv > 0.16) return 'intervals'
  const km = (r.distance_m ?? 0) / 1000
  const min = (r.duration_s ?? 0) / 60
  if (km >= 15 || min >= 85) return 'long'
  if (r.avg_hr && hrMax) {
    const rel = r.avg_hr / hrMax
    if (rel >= 0.85) return 'tempo'
    if (rel <= 0.74 && km < 9) return 'recovery'
  }
  return 'easy'
}

export function defaultRunName(category: RunCategory, startTs: number): string {
  const h = new Date(startTs * 1000).getHours()
  const part = h < 11 ? 'Morgenlauf' : h < 14 ? 'Mittagslauf' : h < 18 ? 'Nachmittagslauf' : 'Abendlauf'
  switch (category) {
    case 'easy': return `Lockerer ${part}`
    case 'recovery': return 'Regenerationslauf'
    case 'long': return 'Langer Lauf'
    case 'tempo': return 'Tempolauf'
    case 'intervals': return 'Intervalle'
    case 'trail': return 'Traillauf'
  }
}
