/**
 * Persists the app database file.
 * Android (Capacitor): app-private storage via @capacitor/filesystem.
 * Browser (development): IndexedDB.
 */
import { Capacitor } from '@capacitor/core'
import { Directory, Filesystem } from '@capacitor/filesystem'
import { del, get, set } from 'idb-keyval'

const FILE = 'gapmin.sqlite'

function toBase64(bytes: Uint8Array): string {
  let s = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) s += String.fromCharCode(...bytes.subarray(i, i + chunk))
  return btoa(s)
}

function fromBase64(b64: string): Uint8Array {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

export async function loadDbFile(): Promise<Uint8Array | null> {
  if (Capacitor.isNativePlatform()) {
    try {
      const r = await Filesystem.readFile({ path: FILE, directory: Directory.Data })
      return typeof r.data === 'string' ? fromBase64(r.data) : new Uint8Array(await r.data.arrayBuffer())
    } catch {
      return null
    }
  }
  return (await get<Uint8Array>(FILE)) ?? null
}

export async function saveDbFile(bytes: Uint8Array): Promise<void> {
  if (Capacitor.isNativePlatform()) {
    await Filesystem.writeFile({ path: FILE, directory: Directory.Data, data: toBase64(bytes) })
    return
  }
  await set(FILE, bytes)
}

export async function deleteDbFile(): Promise<void> {
  if (Capacitor.isNativePlatform()) {
    await Filesystem.deleteFile({ path: FILE, directory: Directory.Data }).catch(() => {})
    return
  }
  await del(FILE)
}
