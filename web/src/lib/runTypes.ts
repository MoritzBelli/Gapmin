import type { RunCategory } from '../types'

/** German label and data colour (CSS token) per run category. */
export const RUN_TYPES: Record<RunCategory, { label: string; color: string }> = {
  easy: { label: 'Locker', color: 'var(--run-easy)' },
  recovery: { label: 'Regeneration', color: 'var(--run-recovery)' },
  long: { label: 'Langer Lauf', color: 'var(--run-long)' },
  tempo: { label: 'Tempo', color: 'var(--run-tempo)' },
  intervals: { label: 'Intervalle', color: 'var(--run-intervals)' },
  trail: { label: 'Trail', color: 'var(--run-trail)' },
}
