import { App as CapApp } from '@capacitor/app'
import { Capacitor } from '@capacitor/core'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { flushSync } from 'react-dom'
import type { Database } from 'sql.js'
import { openAppDb } from './db/appDb'
import { listNights, listRuns } from './data/repo'
import { RunsView } from './views/RunsView'
import { SettingsView } from './views/SettingsView'
import { SleepView } from './views/SleepView'
import './App.css'
import './views/views.css'

type Tab = 'runs' | 'sleep' | 'settings'
const TABS: { id: Tab; label: string }[] = [
  { id: 'runs', label: 'Läufe' },
  { id: 'sleep', label: 'Schlaf' },
  { id: 'settings', label: 'Daten' },
]

export default function App() {
  const [db, setDb] = useState<Database | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [version, setVersion] = useState(0)
  const [tab, setTab] = useState<Tab>(() => (location.hash.slice(1) as Tab) || 'runs')

  useEffect(() => {
    openAppDb()
      .then((d) => {
        setDb(d)
        if (!d.exec('SELECT 1 FROM activities LIMIT 1').length && !location.hash) setTab('settings')
      })
      .catch((e) => setError(String(e)))
  }, [])

  const go = useCallback((t: Tab) => {
    history.replaceState(null, '', `#${t}`)
    const apply = () => flushSync(() => setTab(t))
    if (document.startViewTransition) document.startViewTransition(apply)
    else apply()
    window.scrollTo({ top: 0 })
  }, [])

  // Android back button: close an open overlay, then return to Läufe, then leave the app
  useEffect(() => {
    if (!Capacitor.isNativePlatform()) return
    const h = CapApp.addListener('backButton', () => {
      if (document.querySelector('.detail-backdrop')) {
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
      } else if (tab !== 'runs') go('runs')
      else void CapApp.exitApp()
    })
    return () => void h.then((x) => x.remove())
  }, [tab, go])

  const runs = useMemo(() => (db ? listRuns(db) : []), [db, version])
  const nights = useMemo(() => (db ? listNights(db) : []), [db, version])

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand"><span className="brand-mark" aria-hidden />gapmin</div>
        <nav className="nav segmented" aria-label="Bereiche">
          {TABS.map((t) => (
            <button key={t.id} aria-current={tab === t.id ? 'page' : undefined} className={tab === t.id ? 'on' : ''} onClick={() => go(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>
      </header>
      <main>
        {error && <p className="state">Datenbank konnte nicht geöffnet werden: {error}</p>}
        {!db && !error && <p className="state">Lade …</p>}
        {db && tab === 'runs' && <RunsView runs={runs} />}
        {db && tab === 'sleep' && <SleepView nights={nights} />}
        {db && tab === 'settings' && <SettingsView db={db} onChanged={() => setVersion((v) => v + 1)} />}
      </main>
    </div>
  )
}
