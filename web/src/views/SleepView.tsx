import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { WEIGHTS, type Component } from '../analysis/sleep'
import type { SleepNight } from '../types'

const COMPONENTS: { key: Component; col: keyof SleepNight; label: string; hint: string }[] = [
  { key: 'duration', col: 'score_duration', label: 'Dauer', hint: '7–9 h Schlaf (AASM, NSF)' },
  { key: 'continuity', col: 'score_continuity', label: 'Kontinuität', hint: 'Effizienz, Wachzeit, Aufwachen (NSF 2017)' },
  { key: 'recovery', col: 'score_recovery', label: 'Erholung', hint: 'HRV, Schlafpuls, Stress vs. dein Normal' },
  { key: 'stages', col: 'score_stages', label: 'Phasen', hint: 'Tief-, REM-, Leichtschlaf-Anteile' },
  { key: 'regularity', col: 'score_regularity', label: 'Regelmäßigkeit', hint: 'Gleiche Schlafzeiten (NSF 2023)' },
  { key: 'breathing', col: 'score_breathing', label: 'Atmung', hint: 'Atemfrequenz und SpO2' },
]

const STAGES = [
  { stage: 1, label: 'Wach', color: 'var(--stage-awake)' },
  { stage: 4, label: 'REM', color: 'var(--stage-rem)' },
  { stage: 2, label: 'Leicht', color: 'var(--stage-light)' },
  { stage: 3, label: 'Tief', color: 'var(--stage-deep)' },
]

export const scoreColor = (s: number) => (s >= 80 ? 'var(--good)' : s >= 60 ? 'var(--zone-4)' : 'var(--bad)')
const hm = (min: number) => `${Math.floor(min / 60)}:${String(Math.round(min % 60)).padStart(2, '0')}`
const clock = (ts: number) => new Date(ts * 1000).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })
const dayLabel = (night: string) =>
  new Date(`${night}T12:00:00`).toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long' })
const signed = (x: number, d = 0) => (Math.abs(x) < 0.5 * 10 ** -d ? '±0' : `${x > 0 ? '+' : '−'}${fmt(Math.abs(x), d)}`)
const fmt = (x: number | null | undefined, d = 0) => (x === null || x === undefined ? '–' : x.toLocaleString('de-DE', { maximumFractionDigits: d, minimumFractionDigits: d }))

export function SleepView({ nights }: { nights: SleepNight[] }) {
  const [sel, setSel] = useState(0)
  const n = nights[sel]
  const avg = (k: number) => {
    const xs = nights.slice(0, k).map((x) => x.score)
    return xs.length ? Math.round(xs.reduce((a, b) => a + b, 0) / xs.length) : null
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') setSel((s) => Math.min(nights.length - 1, s + 1))
      if (e.key === 'ArrowRight') setSel((s) => Math.max(0, s - 1))
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [nights.length])

  if (!n) return <p className="state">Noch keine Schlafdaten. Importiere deinen Gadgetbridge-Export unter Einstellungen.</p>
  const d = n.details
  const p = d.physiology
  const pct = (m: number) => (n.tst_min ? Math.round((m / n.tst_min) * 100) : 0)
  const hrvVsNormal = p.hrvAvg && d.baselines.lnHrvMean ? Math.round((p.hrvAvg / Math.exp(d.baselines.lnHrvMean) - 1) * 100) : null
  const hrVsNormal = p.hrAvg && d.baselines.hrMean ? p.hrAvg - d.baselines.hrMean : null

  return (
    <div className="sleep" key={n.night}>
      <section className="hero">
        <div>
          <p className="label">Schlaf</p>
          <h1>{sel === 0 ? 'Letzte Nacht' : dayLabel(n.night)}</h1>
        </div>
        <dl className="totals">
          <div><dt className="label">Ø 7 Nächte</dt><dd className="num">{avg(7) ?? '–'}</dd></div>
          <div><dt className="label">Ø 28 Nächte</dt><dd className="num">{avg(28) ?? '–'}</dd></div>
        </dl>
      </section>

      <NightStrip nights={nights} sel={sel} onSelect={setSel} />

      <div className="sleep-grid">
        <section className="card score-card" style={{ '--score': scoreColor(n.score) } as CSSProperties}>
          <ScoreRing score={n.score} />
          <div className="score-meta">
            <span className="score-label">{d.label}</span>
            <span className="label">{sel === 0 ? dayLabel(n.night) : `${clock(n.onset_ts)} – ${clock(n.wake_ts)}`}</span>
            {n.garmin_score !== null && (
              <span className="garmin-score">Garmin-Score <b>{n.garmin_score}</b></span>
            )}
            {d.factors.length > 0 && (
              <ul className="factors">{d.factors.map((f) => <li key={f}>{f}</li>)}</ul>
            )}
            {d.provisional && <p className="hint">Vorläufig: Deine persönliche Baseline braucht 7 Nächte.</p>}
          </div>
        </section>

        <section className="card hypno-card">
          <header className="card-head">
            <h3>Schlafphasen</h3>
            <span className="label">{clock(n.bed_ts)} – {clock(n.out_ts)}</span>
          </header>
          <Hypnogram night={n} />
          <div className="stage-legend">
            {STAGES.slice().reverse().map((s) => {
              const min = s.stage === 3 ? n.deep_min : s.stage === 2 ? n.light_min : s.stage === 4 ? n.rem_min : n.awake_min
              return (
                <div key={s.stage} style={{ '--c': s.color } as CSSProperties}>
                  <i /><span className="label">{s.label}</span>
                  <b className="num">{hm(min)}</b>
                  {s.stage !== 1 && <small>{pct(min)} %</small>}
                </div>
              )
            })}
          </div>
        </section>

        <section className="card">
          <header className="card-head"><h3>Zusammensetzung</h3><span className="label">Gewicht</span></header>
          <ul className="components">
            {COMPONENTS.map((c) => {
              const v = n[c.col] as number | null
              return (
                <li key={c.key} title={c.hint}>
                  <span>{c.label}</span>
                  <div className="bar"><i style={{ width: `${v ?? 0}%`, background: v === null ? 'var(--surface-3)' : scoreColor(v) }} /></div>
                  <b className="num">{v === null ? '–' : Math.round(v)}</b>
                  <small>{Math.round(WEIGHTS[c.key] * 100)} %</small>
                </li>
              )
            })}
          </ul>
          {n.coverage < 0.999 && <p className="hint">Datenabdeckung {Math.round(n.coverage * 100)} %, fehlende Teile werden herausgerechnet.</p>}
        </section>

        <MetricGroup title="Dauer & Timing" items={[
          ['Schlaf', hm(n.tst_min), 'h'],
          ['Im Bett', hm(n.tib_min), 'h'],
          ['Eingeschlafen', clock(n.onset_ts), ''],
          ['Aufgewacht', clock(n.wake_ts), ''],
          ['Schlafmitte', hm(((n.midpoint_min % 1440) + 1440) % 1440), 'Uhr'],
          ['Regelmäßigkeit', n.regularity_sd === null ? '–' : `±${Math.round(n.regularity_sd)}`, 'min'],
        ]} />
        <MetricGroup title="Kontinuität" items={[
          ['Effizienz', fmt(n.efficiency * 100), '%'],
          ['Einschlafzeit', fmt(n.sol_min), 'min'],
          ['Wach nach Einschlafen', fmt(n.waso_min), 'min'],
          ['Aufwachen', String(n.awakenings), `davon ${n.awakenings_long} länger als 5 min`],
          ['REM-Latenz', fmt(d.remLatencyMin), 'min'],
          ['Tiefschlaf 1. Hälfte', d.deepFirstHalfShare === null ? '–' : fmt(d.deepFirstHalfShare * 100), '%'],
        ]} />
        <MetricGroup title="Körper" items={[
          ['Schlafpuls Ø', fmt(p.hrAvg), hrVsNormal === null ? 'bpm' : `bpm · ${signed(hrVsNormal, 1)} vs. Normal`],
          ['Tiefster Puls', fmt(p.hrMin), p.hrMinTs ? `bpm · ${clock(p.hrMinTs)}` : 'bpm'],
          ['HRV Ø', fmt(p.hrvAvg), hrvVsNormal === null ? 'ms' : `ms · ${signed(hrvVsNormal)} % vs. Normal`],
          ['Pulsabfall vs. Tag', p.hrDip === null ? '–' : fmt(p.hrDip * 100), '%'],
          ['Stress Ø', fmt(p.stressAvg), ''],
          ['Body Battery', p.bbGain === null ? '–' : `+${fmt(p.bbGain)}`, ''],
          ['Atmung Ø', fmt(p.respAvg, 1), '/min'],
          ['SpO2 Ø', fmt(p.spo2Avg), p.spo2Min ? `% · min ${fmt(p.spo2Min)}` : '%'],
        ]} />
      </div>

      <details className="method">
        <summary>Wie wird der Score berechnet?</summary>
        <p>
          Der Gapmin-Schlafscore gewichtet sechs Bereiche nach wissenschaftlicher Evidenz: Dauer, Kontinuität und
          Erholung je 25 %, Schlafphasen und Regelmäßigkeit je 10 %, Atmung 5 %. Phasen zählen bewusst wenig, weil
          Handgelenk-Geräte sie nur mäßig genau erkennen. Erholung und Atmung werden gegen deine eigenen
          28-Nächte-Werte bewertet. Details und Quellen: docs/research/schlaf-score.md.
        </p>
      </details>
    </div>
  )
}

function ScoreRing({ score }: { score: number }) {
  const r = 52, c = 2 * Math.PI * r
  return (
    <svg className="ring" viewBox="0 0 120 120" aria-label={`Schlafscore ${score}`}>
      <circle cx="60" cy="60" r={r} className="ring-track" />
      <circle cx="60" cy="60" r={r} className="ring-glow" strokeDasharray={`${(score / 100) * c} ${c}`} />
      <circle cx="60" cy="60" r={r} className="ring-value" strokeDasharray={`${(score / 100) * c} ${c}`} />
      <text x="60" y="60" className="ring-num" dominantBaseline="central" textAnchor="middle">{score}</text>
    </svg>
  )
}

function Hypnogram({ night }: { night: SleepNight }) {
  const total = Math.max(1, (night.out_ts - night.bed_ts) / 60)
  const W = 600, H = 150, rowH = H / 4
  const row = (stage: number) => STAGES.findIndex((s) => s.stage === stage)
  const segs = night.details.hypnogram
  const hours: { x: number; label: string }[] = []
  const first = new Date(night.bed_ts * 1000)
  first.setMinutes(0, 0, 0)
  for (let t = first.getTime() / 1000 + 3600; t < night.out_ts; t += 3600) {
    hours.push({ x: ((t - night.bed_ts) / 60 / total) * W, label: new Date(t * 1000).getHours().toString() })
  }
  let path = ''
  segs.forEach(([off, dur, st], i) => {
    const x0 = (off / total) * W, x1 = ((off + dur) / total) * W, y = row(st) * rowH + rowH / 2
    path += `${i ? 'L' : 'M'}${x0.toFixed(1)} ${y}L${x1.toFixed(1)} ${y}`
  })
  return (
    <svg className="hypno" viewBox={`0 -4 ${W} ${H + 24}`} preserveAspectRatio="none" role="img" aria-label="Hypnogramm">
      {STAGES.map((s, i) => <line key={s.stage} x1="0" x2={W} y1={i * rowH + rowH / 2} y2={i * rowH + rowH / 2} className="hypno-grid" />)}
      <path d={path} className="hypno-step" />
      {segs.map(([off, dur, st], i) => (
        <rect key={i} x={(off / total) * W} width={Math.max(1.5, (dur / total) * W)} y={row(st) * rowH + rowH * 0.18}
          height={rowH * 0.64} rx="3" style={{ fill: STAGES[row(st)]?.color }} className="hypno-seg" />
      ))}
      {hours.map((h) => <text key={h.x} x={h.x} y={H + 16} className="hypno-tick" textAnchor="middle">{h.label}</text>)}
    </svg>
  )
}

function NightStrip({ nights, sel, onSelect }: { nights: SleepNight[]; sel: number; onSelect: (i: number) => void }) {
  const ref = useRef<HTMLDivElement>(null)
  const list = useMemo(() => nights.slice(0, 120), [nights])
  useEffect(() => {
    ref.current?.scrollTo({ left: ref.current.scrollWidth })
  }, [])
  return (
    <div className="strip" ref={ref} role="listbox" aria-label="Nächte">
      {[...list].reverse().map((n) => {
        const i = nights.indexOf(n)
        const wd = new Date(`${n.night}T12:00:00`)
        return (
          <button key={n.night} role="option" aria-selected={i === sel} className="strip-bar" onClick={() => onSelect(i)}
            title={`${dayLabel(n.night)}: ${n.score}`} style={{ '--h': `${Math.max(6, n.score)}%`, '--c': scoreColor(n.score) } as CSSProperties}>
            <i />
            <span>{wd.getDate() === 1 || i === sel ? wd.toLocaleDateString('de-DE', { day: 'numeric', month: 'short' }) : ''}</span>
          </button>
        )
      })}
    </div>
  )
}

function MetricGroup({ title, items }: { title: string; items: [string, string, string][] }) {
  return (
    <section className="card">
      <header className="card-head"><h3>{title}</h3></header>
      <dl className="metrics">
        {items.map(([k, v, u]) => (
          <div key={k}><dt className="label">{k}</dt><dd><span className="num">{v}</span>{u && <span className="unit">{u}</span>}</dd></div>
        ))}
      </dl>
    </section>
  )
}
