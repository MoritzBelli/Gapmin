import type { CSSProperties, MouseEvent } from 'react'
import { formatDate, formatDuration, formatKm, formatPace, formatTime } from '../lib/format'
import { RUN_TYPES } from '../lib/runTypes'
import type { RunSummary } from '../types'
import { RouteShape } from './RouteShape'

interface Props {
  run: RunSummary
  dataRoot: string
  index: number
  onOpen: (run: RunSummary, el: HTMLElement) => void
}

export function RunTile({ run, dataRoot, index, onOpen }: Props) {
  const t = RUN_TYPES[run.type]
  return (
    <button
      className="tile"
      data-run={run.id}
      style={{ '--type': t.color, '--i': Math.min(index, 12) } as CSSProperties}
      onClick={(e: MouseEvent<HTMLButtonElement>) => onOpen(run, e.currentTarget)}
    >
      <div className="tile-route">
        <span className="type-tag"><i />{t.label}</span>
        <RouteShape url={dataRoot + run.gpx} strokeWidth={1.2} />
      </div>
      <div className="tile-when">{formatDate(run.start)} · {formatTime(run.start)}</div>
      <div className="tile-distance num">
        {formatKm(run.distance_m)}<span className="unit">km</span>
      </div>
      <dl className="tile-stats">
        <div><dt className="label">Zeit</dt><dd>{formatDuration(run.duration_s)}</dd></div>
        <div><dt className="label">Pace</dt><dd>{formatPace(run.avg_pace_s_per_km)}</dd></div>
        <div><dt className="label">HF</dt><dd>{run.avg_hr}</dd></div>
      </dl>
    </button>
  )
}
