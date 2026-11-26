/** Upload the app database to the Gapmin server on the NAS, which merges it into PostgreSQL. */
import type { Database } from 'sql.js'
import { getSetting } from './repo'

export interface SyncResult { ok: boolean; message: string; rows?: number }

export async function syncToNas(db: Database): Promise<SyncResult> {
  const base = getSetting(db, 'nas_url').replace(/\/+$/, '')
  const token = getSetting(db, 'nas_token')
  if (!base) return { ok: false, message: 'Keine Server-Adresse eingetragen' }
  const bytes = db.export()
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), 120_000)
  try {
    const res = await fetch(`${base}/api/sync`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: new Blob([bytes as BlobPart]),
      signal: ctrl.signal,
    })
    if (!res.ok) return { ok: false, message: `Server antwortet ${res.status}: ${(await res.text()).slice(0, 200)}` }
    const json = await res.json()
    db.run("INSERT OR REPLACE INTO meta (key, value) VALUES ('last_sync', ?)", [String(Math.floor(Date.now() / 1000))])
    return { ok: true, message: 'Synchronisiert', rows: json.rows }
  } catch (e) {
    return { ok: false, message: ctrl.signal.aborted ? 'Zeitüberschreitung' : `NAS nicht erreichbar (${(e as Error).message})` }
  } finally {
    clearTimeout(timer)
  }
}

export async function pingNas(url: string): Promise<boolean> {
  try {
    const res = await fetch(`${url.replace(/\/+$/, '')}/api/health`, { signal: AbortSignal.timeout(4000) })
    return res.ok
  } catch {
    return false
  }
}
