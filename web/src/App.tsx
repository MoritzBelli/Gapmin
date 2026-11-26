import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react'
import { flushSync } from 'react-dom'
import { RunDetail } from './components/RunDetail'
import { RunTile } from './components/RunTile'
import { formatKm, formatMonth, monthKey } from './lib/format'
import { RUN_TYPES } from './lib/runTypes'
import type { RunManifest, RunSummary, RunType } from './types'
import './App.css'

/** Where runs.json and the GPX files live. Swap for the backend API later. */
const DATA_ROOT = `${import.meta.env.BASE_URL}sample-data/`

type SortKey = 'date' | 'distance' | 'pace'
const SORTS: Record<SortKey, { label: string; cmp: (a: RunSummary, b: RunSummary) => number }> = {
  date: { label: 'Neueste', cmp: (a, b) => b.start.localeCompare(a.start) },
  distance: { label: 'Längste', cmp: (a, b) => b.distance_m - a.distance_m },
  pace: { label: 'Schnellste', cmp: (a, b) => a.avg_pace_s_per_km - b.avg_pace_s_per_km },
}

export default function App() {
  const [runs, setRuns] = useState<RunSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState<RunType | 'all'>('all')
  const [sort, setSort] = useState<SortKey>('date')
  const [openId, setOpenId] = useState<string | null>(null)

  useEffect(() => {
    fetch(`${DATA_ROOT}runs.json`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((m: RunManifest) => setRuns(m.runs))
      .catch((e) => setError(String(e)))
  }, [])

  const visible = useMemo(() => {
    if (!runs) return []
    return runs.filter((r) => filter === 'all' || r.type === filter).sort(SORTS[sort].cmp)
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
    () => (Object.keys(RUN_TYPES) as RunType[]).filter((t) => runs?.some((r) => r.type === t)),
    [runs],
  )

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand"><span className="brand-mark" aria-hidden />gapmin</div>
        <nav className="nav segmented" aria-label="Bereiche">
          <button className="on" aria-current="page">Läufe</button>
          <button disabled>Gesundheit</button>
          <button disabled>Trends</button>
        </nav>
      </header>

      <main>
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

        {error && <p className="state">Daten konnten nicht geladen werden: {error}</p>}
        {!runs && !error && <p className="state">Lade Läufe …</p>}

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
                  <RunTile key={r.id} run={r} index={i} dataRoot={DATA_ROOT} onOpen={open} />
                ))}
              </div>
            </section>
          ))}
        </div>
      </main>

      {openIdx >= 0 && (
        <RunDetail run={visible[openIdx]} dataRoot={DATA_ROOT} onClose={close}
          onPrev={openIdx > 0 ? prev : undefined} onNext={openIdx < visible.length - 1 ? next : undefined} />
      )}
    </div>
  )
}
