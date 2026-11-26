import type { RouteShape } from '../types'

type LatLon = [number, number]

/**
 * Project lat/lon to a square SVG box (equirectangular, corrected for latitude),
 * keep the aspect ratio and centre the route. Points are thinned to `maxPoints`.
 */
export function toRouteShape(points: LatLon[], size = 100, padding = 8, maxPoints = 400): RouteShape | null {
  if (points.length < 2) return null
  const step = Math.max(1, Math.floor(points.length / maxPoints))
  const thinned = points.filter((_, i) => i % step === 0 || i === points.length - 1)

  const lat0 = (thinned.reduce((s, p) => s + p[0], 0) / thinned.length) * (Math.PI / 180)
  const k = Math.cos(lat0)
  const xy = thinned.map(([lat, lon]) => [lon * k, -lat] as [number, number])

  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity
  for (const [x, y] of xy) {
    if (x < minX) minX = x
    if (x > maxX) maxX = x
    if (y < minY) minY = y
    if (y > maxY) maxY = y
  }
  const w = maxX - minX || 1e-9
  const h = maxY - minY || 1e-9
  const inner = size - 2 * padding
  const scale = inner / Math.max(w, h)
  const ox = padding + (inner - w * scale) / 2
  const oy = padding + (inner - h * scale) / 2

  const proj = xy.map(([x, y]) => [ox + (x - minX) * scale, oy + (y - minY) * scale] as [number, number])
  const path = proj.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(2)} ${y.toFixed(2)}`).join('')
  return { path, start: proj[0], end: proj[proj.length - 1] }
}
