import type { RunType } from '../types'

/** German label and data colour (CSS token) per run type. */
export const RUN_TYPES: Record<RunType, { label: string; color: string }> = {
  'Easy Run': { label: 'Locker', color: 'var(--run-easy)' },
  'Recovery Run': { label: 'Regeneration', color: 'var(--run-recovery)' },
  'Long Run': { label: 'Langer Lauf', color: 'var(--run-long)' },
  'Tempo Run': { label: 'Tempo', color: 'var(--run-tempo)' },
  Intervals: { label: 'Intervalle', color: 'var(--run-intervals)' },
  'Trail Run': { label: 'Trail', color: 'var(--run-trail)' },
}
