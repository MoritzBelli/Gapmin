import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { loadRouteShape } from '../lib/gpx'
import type { RouteShape as Shape } from '../types'

interface Props {
  url: string
  /** Only fetch once the element is near the viewport (gallery). */
  lazy?: boolean
  /** Animate the line being drawn when it first appears. */
  draw?: boolean
  strokeWidth?: number
  style?: CSSProperties
}

/**
 * Renders a GPX track as a minimal line drawing.
 * Colour comes from the CSS variable `--route-color` on any ancestor.
 */
export function RouteShape({ url, lazy = true, draw = true, strokeWidth = 1.4, style }: Props) {
  const ref = useRef<SVGSVGElement>(null)
  const [visible, setVisible] = useState(!lazy)
  const [shape, setShape] = useState<Shape | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (visible || !ref.current) return
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          setVisible(true)
          io.disconnect()
        }
      },
      { rootMargin: '600px 0px' },
    )
    io.observe(ref.current)
    return () => io.disconnect()
  }, [visible])

  useEffect(() => {
    if (!visible) return
    let alive = true
    loadRouteShape(url)
      .then((s) => alive && setShape(s))
      .catch(() => alive && setFailed(true))
    return () => {
      alive = false
    }
  }, [url, visible])

  return (
    <svg ref={ref} className="route" viewBox="0 0 100 100" role="img" aria-label="Streckenverlauf" style={style}>
      {shape && (
        <g style={{ '--sw': strokeWidth } as CSSProperties}>
          <path d={shape.path} pathLength={1} className={draw ? 'route-line draw' : 'route-line'} />
          <circle cx={shape.end[0]} cy={shape.end[1]} r={strokeWidth * 1.25} className="route-end" />
          <circle cx={shape.start[0]} cy={shape.start[1]} r={strokeWidth * 1.25} className="route-start" />
        </g>
      )}
      {failed && <text x="50" y="52" textAnchor="middle" className="route-error">keine GPS-Daten</text>}
    </svg>
  )
}
