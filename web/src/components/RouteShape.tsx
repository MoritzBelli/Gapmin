import { useId, useMemo, type CSSProperties } from 'react'
import { toRouteShape } from '../lib/route'

interface Props {
  points: [number, number][]
  /** Animate the line being drawn when it first appears. */
  draw?: boolean
  strokeWidth?: number
  style?: CSSProperties
}

/**
 * Renders a route as a minimal neon line drawing.
 * Colour comes from the CSS variable `--route-color` on any ancestor.
 */
export function RouteShape({ points, draw = true, strokeWidth = 1.4, style }: Props) {
  const shape = useMemo(() => toRouteShape(points), [points])
  const glowId = `glow${useId().replace(/:/g, '')}`
  return (
    <svg className="route" viewBox="0 0 100 100" role="img" aria-label="Streckenverlauf" style={style}>
      {shape ? (
        <g style={{ '--sw': strokeWidth } as CSSProperties}>
          <defs>
            <filter id={glowId} x="-25%" y="-25%" width="150%" height="150%">
              <feGaussianBlur stdDeviation={strokeWidth * 1.4} />
            </filter>
          </defs>
          <path d={shape.path} pathLength={1} filter={`url(#${glowId})`} className={draw ? 'route-glow draw' : 'route-glow'} />
          <path d={shape.path} pathLength={1} className={draw ? 'route-line draw' : 'route-line'} />
          <circle cx={shape.end[0]} cy={shape.end[1]} r={strokeWidth * 1.25} className="route-end" />
          <circle cx={shape.start[0]} cy={shape.start[1]} r={strokeWidth * 1.25} className="route-start" />
        </g>
      ) : (
        <text x="50" y="52" textAnchor="middle" className="route-error">keine GPS-Daten</text>
      )}
    </svg>
  )
}
