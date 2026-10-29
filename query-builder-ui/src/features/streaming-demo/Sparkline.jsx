const W = 600
const H = 90
const PAD = { top: 6, right: 6, bottom: 16, left: 6 }

/**
 * A small time-series chart drawn in SVG (no chart library). `points` are { t, v } in ms and
 * the unit of `v`; `xMax` is the shared right edge in ms so every chart in a panel lines up.
 * `events` draws vertical marks; `limit` draws a dashed line (e.g. a memory limit).
 */
export default function Sparkline({ title, unit, points, xMax, yMax, events = [], limit: rawLimit = null, color = '#4f46e5', note = null }) {
  const valid = points.filter((p) => p.v != null)
  const last = valid.length ? valid[valid.length - 1].v : null
  const peak = valid.reduce((m, p) => Math.max(m, p.v), 0)
  // A limit far above the data (an unconstrained container reports the whole host's memory)
  // would flatten the line to nothing, so it is only drawn when it is within reach of the data.
  const limit = rawLimit != null && rawLimit <= Math.max(peak, 1) * 3 ? rawLimit : null
  const top = Math.max(yMax ?? 0, peak * 1.1, limit ?? 0, 1e-6)
  const span = Math.max(xMax, 1000)
  const x = (t) => PAD.left + (t / span) * (W - PAD.left - PAD.right)
  const y = (v) => PAD.top + (1 - v / top) * (H - PAD.top - PAD.bottom)
  const path = valid.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`).join(' ')
  const area = valid.length > 1 ? `${path} L${x(valid[valid.length - 1].t).toFixed(1)},${y(0)} L${x(valid[0].t).toFixed(1)},${y(0)} Z` : null

  return (
    <figure className="rounded-md bg-slate-50 p-3 ring-1 ring-slate-200">
      <figcaption className="mb-1 flex items-baseline justify-between gap-2 text-xs">
        <span className="font-medium text-slate-800">{title}</span>
        <span className="tabular-nums text-slate-600">
          {last == null ? 'no data' : `now ${format(last)} ${unit}`}
          {valid.length > 0 && ` · peak ${format(peak)}`}
        </span>
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} className="h-24 w-full" role="img" aria-label={`${title}: ${last == null ? 'no data' : `now ${format(last)} ${unit}, peak ${format(peak)}`}`}>
        <line x1={PAD.left} x2={W - PAD.right} y1={y(0)} y2={y(0)} stroke="#cbd5e1" strokeWidth="1" />
        {limit != null && (
          <g>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(limit)} y2={y(limit)} stroke="#e11d48" strokeWidth="1" strokeDasharray="4 3" />
            <text x={W - PAD.right} y={y(limit) - 2} textAnchor="end" fontSize="9" fill="#e11d48">
              limit {format(limit)}
            </text>
          </g>
        )}
        {events.map((e, i) => (
          <line key={i} x1={x(e.t)} x2={x(e.t)} y1={PAD.top} y2={y(0)} stroke="#f59e0b" strokeWidth="1" strokeDasharray="2 2">
            <title>{e.label}</title>
          </line>
        ))}
        {area && <path d={area} fill={color} opacity="0.12" />}
        {path && <path d={path} fill="none" stroke={color} strokeWidth="1.5" />}
        <text x={PAD.left} y={H - 3} fontSize="9" fill="#64748b">
          0 s
        </text>
        <text x={W - PAD.right} y={H - 3} textAnchor="end" fontSize="9" fill="#64748b">
          {(span / 1000).toFixed(0)} s
        </text>
      </svg>
      {note && <p className="mt-1 text-[11px] text-slate-500">{note}</p>}
    </figure>
  )
}

function format(v) {
  if (v >= 100) return Math.round(v).toLocaleString()
  if (v >= 10) return v.toFixed(1)
  return v.toFixed(2)
}
