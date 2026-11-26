import { useCallback, useMemo, useState, type CSSProperties } from 'react'
import { flushSync } from 'react-dom'
import { RunDetail } from '../components/RunDetail'
import { RunTile } from '../components/RunTile'
import { formatKm, formatMonth, monthKey } from '../lib/format'
import { RUN_TYPES } from '../lib/runTypes'
import type { RunCategory, RunSummary } from '../types'

type SortKey = 'date' | 'distance' | 'pace'
const SORTS: Record<SortKey, { label: string; cmp: (a: RunSummary, b: RunSummary) => number }> = {
  date: { label: 'Neueste', cmp: (a, b) => b.start.localeCompare(a.start) },
  distance: { label: 'Längste', cmp: (a, b) => b.distance_m - a.distance_m },
  pace: { label: 'Schnellste', cmp: (a, b) => a.avg_pace_s_per_km - b.avg_pace_s_per_km },
}

export function RunsView({ runs }: { runs: RunSummary[] }) {
  const [filter, setFilter] = useState<RunCategory | 'all'>('all')
  const [sort, setSort] = useState<SortKey>('date')
  const [openId, setOpenId] = useState<string | null>(null)

  const visible = useMemo(() => {
    return runs.filter((r) => filter === 'all' || r.category === filter).sort(SORTS[sort].cmp)
  }, [runs, filter, sort])

  // Month groups only make sense when sorted chronologically.
  const groups = useMemo(() => {
    if (sort !== 'date') return [{ key: 'all', title: null as string | null, runs: visible }]
    const out: { key: string; title: string | null; runs: RunSummary[] }[] = []
    for (const r of visible) {
      const k = monthKey(r.start)
      if (out.at(-1)?.key !== k) out.push({ key: k, title: formatMonth(r.start), runs: [] })
      out.at(-1)!.runs.push(r)
    }
    return out
  }, [visible, sort])

  const totals = useMemo(() => {
    const dist = visible.reduce((s, r) => s + r.distance_m, 0)
    const time = visible.reduce((s, r) => s + r.duration_s, 0)
    return { count: visible.length, dist, hours: time / 3600 }
  }, [visible])

  const openIdx = visible.findIndex((r) => r.id === openId)

  // Shared-element transition: the tile's route morphs into the detail route.
  const open = useCallback((run: RunSummary, el: HTMLElement) => {
    const route = el.querySelector<SVGElement>('.route')
    if (!document.startViewTransition || !route) return setOpenId(run.id)
    route.style.viewTransitionName = 'active-route'
    document.startViewTransition(() => {
      route.style.viewTransitionName = ''
      flushSync(() => setOpenId(run.id))
    })
  }, [])

  const close = useCallback(() => {
    const id = openId
    const route = document.querySelector<SVGElement>(`.tile[data-run="${id}"] .route`)
    if (!document.startViewTransition || !route) return setOpenId(null)
    const vt = document.startViewTransition(() => {
      flushSync(() => setOpenId(null))
      route.style.viewTransitionName = 'active-route'
    })
    vt.finished.finally(() => (route.style.viewTransitionName = ''))
  }, [openId])
  const prev = useCallback(() => setOpenId(visible[openIdx - 1]?.id ?? null), [visible, openIdx])
  const next = useCallback(() => setOpenId(visible[openIdx + 1]?.id ?? null), [visible, openIdx])

  const typesPresent = useMemo(
    () => (Object.keys(RUN_TYPES) as RunCategory[]).filter((t) => runs.some((r) => r.category === t)),
    [runs],
  )

  return (
    <>
        <section className="hero">
          <div>
            <p className="label">Aktivitäten</p>
            <h1>Läufe</h1>
          </div>
          <dl className="totals">
            <div><dt className="label">Läufe</dt><dd className="num">{totals.count}</dd></div>
            <div><dt className="label">Distanz</dt><dd className="num">{formatKm(totals.dist, 0)}<span className="unit">km</span></dd></div>
            <div><dt className="label">Zeit</dt><dd className="num">{totals.hours.toLocaleString('de-DE', { maximumFractionDigits: 0 })}<span className="unit">h</span></dd></div>
          </dl>
        </section>

        <div className="toolbar">
          <div className="chips" role="radiogroup" aria-label="Lauftyp">
            <button role="radio" aria-checked={filter === 'all'} className="chip" onClick={() => setFilter('all')}>Alle</button>
            {typesPresent.map((t) => (
              <button key={t} role="radio" aria-checked={filter === t} className="chip"
                style={{ '--type': RUN_TYPES[t].color } as CSSProperties} onClick={() => setFilter(t)}>
                <i />{RUN_TYPES[t].label}
              </button>
            ))}
          </div>
          <div className="segmented" aria-label="Sortierung">
            {(Object.keys(SORTS) as SortKey[]).map((k) => (
              <button key={k} aria-pressed={sort === k} onClick={() => setSort(k)}>{SORTS[k].label}</button>
            ))}
          </div>
        </div>

        {!runs.length && <p className="state">Noch keine Läufe. Importiere deinen Gadgetbridge-Export unter Einstellungen.</p>}

        <div key={filter + sort} className="gallery">
          {groups.map((g) => (
            <section key={g.key} className="month">
              {g.title && (
                <h3 className="month-title">
                  <span>{g.title}</span>
                  <span className="month-sum">{g.runs.length} Läufe · {formatKm(g.runs.reduce((s, r) => s + r.distance_m, 0), 0)} km</span>
                </h3>
              )}
              <div className="grid">
                {g.runs.map((r, i) => (
                  <RunTile key={r.id} run={r} index={i} onOpen={open} />
                ))}
              </div>
            </section>
          ))}
        </div>

      {openIdx >= 0 && (
        <RunDetail run={visible[openIdx]} onClose={close}
          onPrev={openIdx > 0 ? prev : undefined} onNext={openIdx < visible.length - 1 ? next : undefined} />
      )}
    </>
  )
}
