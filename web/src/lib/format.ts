const LOCALE = 'de-DE'

export const formatKm = (m: number, digits = 2) =>
  (m / 1000).toLocaleString(LOCALE, { minimumFractionDigits: digits, maximumFractionDigits: digits })

export function formatDuration(s: number): string {
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = Math.round(s % 60)
  const mm = String(m).padStart(h ? 2 : 1, '0')
  const ss = String(sec).padStart(2, '0')
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

export function formatPace(sPerKm: number): string {
  const m = Math.floor(sPerKm / 60)
  const s = Math.round(sPerKm % 60)
  return s === 60 ? `${m + 1}:00` : `${m}:${String(s).padStart(2, '0')}`
}

export const formatDate = (iso: string) =>
  new Date(iso).toLocaleDateString(LOCALE, { weekday: 'short', day: 'numeric', month: 'short' })

export const formatTime = (iso: string) =>
  new Date(iso).toLocaleTimeString(LOCALE, { hour: '2-digit', minute: '2-digit' })

export const formatMonth = (iso: string) =>
  new Date(iso).toLocaleDateString(LOCALE, { month: 'long', year: 'numeric' })

export const monthKey = (iso: string) => iso.slice(0, 7)
