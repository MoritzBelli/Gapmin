import { useRef, useState } from 'react'
import type { Database } from 'sql.js'
import { persist, resetAppDb } from '../db/appDb'
import { importGadgetbridge, type ImportProgress } from '../data/importGadgetbridge'
import { getSetting, setSetting, stats } from '../data/repo'
import { pingNas, syncToNas } from '../data/sync'

interface Props {
  db: Database
  onChanged: () => void
}

const when = (ts: number | null) =>
  ts ? new Date(ts * 1000).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' }) : 'nie'

export function SettingsView({ db, onChanged }: Props) {
  const file = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState<ImportProgress | null>(null)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [url, setUrl] = useState(getSetting(db, 'nas_url'))
  const [token, setToken] = useState(getSetting(db, 'nas_token'))
  const [, force] = useState(0)
  const s = stats(db)

  async function runImport(bytes: Uint8Array) {
    setMsg(null)
    setBusy({ step: 'Starte', fraction: 0 })
    try {
      const r = await importGadgetbridge(db, bytes, setBusy)
      await persist()
      setMsg({ ok: true, text: `${r.activities} Aktivitäten, ${r.samples.toLocaleString('de-DE')} Messwerte, ${r.nights} Nächte in ${(r.ms / 1000).toFixed(1)} s` })
      onChanged()
      if (getSetting(db, 'nas_url') && getSetting(db, 'auto_sync') !== '0') void doSync(true)
    } catch (e) {
      setMsg({ ok: false, text: (e as Error).message })
    } finally {
      setBusy(null)
    }
  }

  async function onFile(f: File | undefined) {
    if (f) await runImport(new Uint8Array(await f.arrayBuffer()))
  }

  async function loadDemo() {
    const res = await fetch(`${import.meta.env.BASE_URL}demo/gadgetbridge-demo.zip`)
    await runImport(new Uint8Array(await res.arrayBuffer()))
  }

  async function saveNas() {
    setSetting(db, 'nas_url', url.trim())
    setSetting(db, 'nas_token', token.trim())
    await persist()
    const ok = await pingNas(url.trim())
    setMsg({ ok, text: ok ? 'Server erreichbar' : 'Server nicht erreichbar, Einstellungen trotzdem gespeichert' })
  }

  async function doSync(silent = false) {
    if (!silent) setMsg({ ok: true, text: 'Synchronisiere …' })
    const r = await syncToNas(db)
    await persist()
    setMsg({ ok: r.ok, text: r.ok ? `NAS synchronisiert (${r.rows?.toLocaleString('de-DE') ?? 0} Zeilen)` : r.message })
    force((x) => x + 1)
  }

  async function wipe() {
    if (!confirm('Alle Daten in der App löschen? Dein Gadgetbridge-Export und der NAS bleiben unverändert.')) return
    await resetAppDb()
    location.reload()
  }

  return (
    <div className="settings">
      <section className="hero">
        <div><p className="label">App</p><h1>Daten</h1></div>
        <dl className="totals">
          <div><dt className="label">Aktivitäten</dt><dd className="num">{s.activities}</dd></div>
          <div><dt className="label">Nächte</dt><dd className="num">{s.nights}</dd></div>
          <div><dt className="label">Messwerte</dt><dd className="num">{Math.round(s.samples / 1000)}<span className="unit">k</span></dd></div>
        </dl>
      </section>

      {msg && <p className={msg.ok ? 'notice' : 'notice error'}>{msg.text}</p>}
      {busy && (
        <div className="progress" aria-live="polite">
          <span>{busy.step}</span>
          <div className="bar"><i style={{ width: `${Math.round(busy.fraction * 100)}%` }} /></div>
        </div>
      )}

      <div className="settings-grid">
        <section className="card">
          <header className="card-head"><h3>Gadgetbridge-Import</h3><span className="label">zuletzt {when(s.lastImport)}</span></header>
          <p className="hint">
            In Gadgetbridge: Einstellungen → Automatisierungen → Auto-Export als ZIP. Wähle hier die ZIP-Datei
            oder die Datenbankdatei „Gadgetbridge“. Es werden nur neue Daten übernommen.
          </p>
          <input ref={file} type="file" hidden accept=".zip,.db,application/zip,application/octet-stream,*/*"
            onChange={(e) => onFile(e.target.files?.[0])} />
          <div className="actions">
            <button className="btn primary" disabled={!!busy} onClick={() => file.current?.click()}>Export importieren</button>
            <button className="btn" disabled={!!busy} onClick={loadDemo}>Demo-Daten laden</button>
          </div>
        </section>

        <section className="card">
          <header className="card-head"><h3>NAS-Server</h3><span className="label">zuletzt {when(s.lastSync)}</span></header>
          <label className="field"><span className="label">Adresse</span>
            <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="http://192.168.1.20:8085" inputMode="url" />
          </label>
          <label className="field"><span className="label">Token</span>
            <input value={token} onChange={(e) => setToken(e.target.value)} type="password" placeholder="GAPMIN_TOKEN aus der .env" />
          </label>
          <div className="actions">
            <button className="btn" onClick={saveNas}>Speichern & testen</button>
            <button className="btn primary" disabled={!url} onClick={() => doSync()}>Jetzt synchronisieren</button>
          </div>
          <p className="hint">Nach jedem Import wird automatisch synchronisiert, wenn der NAS erreichbar ist (z. B. über WireGuard).</p>
        </section>

        <section className="card">
          <header className="card-head"><h3>Speicher</h3></header>
          <p className="hint">Alle Daten liegen lokal auf diesem Gerät in einer SQLite-Datenbank.</p>
          <div className="actions"><button className="btn danger" onClick={wipe}>App-Daten löschen</button></div>
        </section>
      </div>
    </div>
  )
}
