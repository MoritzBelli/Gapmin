import { useEffect, type CSSProperties } from 'react'
import { formatDate, formatDuration, formatKm, formatPace, formatTime } from '../lib/format'
import { RUN_TYPES } from '../lib/runTypes'
import type { RunSummary } from '../types'
import { RouteShape } from './RouteShape'

interface Props {
  run: RunSummary
  dataRoot: string
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function RunDetail({ run, dataRoot, onClose, onPrev, onNext }: Props) {
  const t = RUN_TYPES[run.type]

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      if (e.key === 'ArrowLeft') onPrev?.()
      if (e.key === 'ArrowRight') onNext?.()
    }
    window.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden'
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
    }
  }, [onClose, onPrev, onNext])

  const stats: [string, string, string][] = [
    ['Zeit', formatDuration(run.duration_s), ''],
    ['Ø Pace', formatPace(run.avg_pace_s_per_km), '/km'],
    ['Ø Herzfrequenz', String(run.avg_hr), 'bpm'],
    ['Max. Herzfrequenz', String(run.max_hr), 'bpm'],
    ['Anstieg', String(run.elevation_gain_m), 'm'],
  ]

  return (
    <div className="detail-backdrop" onClick={onClose}>
      <div className="detail" role="dialog" aria-modal="true" aria-label={run.name}
        style={{ '--type': t.color } as CSSProperties} onClick={(e) => e.stopPropagation()}>
        <div className="detail-route">
          <RouteShape key={run.id} url={dataRoot + run.gpx} lazy={false} draw={false} strokeWidth={0.55}
            style={{ viewTransitionName: 'active-route' }} />
        </div>
        <div className="detail-info">
          <div className="detail-top">
            <span className="type-tag"><i />{t.label}</span>
            <button className="icon-btn" onClick={onClose} aria-label="Schließen">
              <svg viewBox="0 0 24 24" width="18" height="18"><path d="M6 6l12 12M18 6L6 18" /></svg>
            </button>
          </div>
          <h2>{run.name}</h2>
          <p className="detail-when">{formatDate(run.start)} · {formatTime(run.start)}</p>

          <div className="detail-hero num">
            {formatKm(run.distance_m)}<span className="unit">km</span>
          </div>

          <dl className="detail-stats">
            {stats.map(([k, v, u]) => (
              <div key={k}>
                <dt className="label">{k}</dt>
                <dd className="num">{v}{u && <span className="unit">{u}</span>}</dd>
              </div>
            ))}
          </dl>

          <div className="detail-nav segmented">
            <button onClick={onPrev} disabled={!onPrev}>← Neuer</button>
            <button onClick={onNext} disabled={!onNext}>Älter →</button>
          </div>
        </div>
      </div>
    </div>
  )
}
