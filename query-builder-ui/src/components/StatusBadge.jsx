const STYLES = {
  DRAFT: 'bg-amber-100 text-amber-800',
  VALIDATED: 'bg-blue-100 text-blue-800',
  SIMULATED: 'bg-violet-100 text-violet-800',
  PUBLISHED: 'bg-emerald-100 text-emerald-800',
  OFFLINE: 'bg-orange-100 text-orange-800',
  DISCARDED: 'bg-slate-100 text-slate-600',
}

const LABELS = {
  DRAFT: 'Draft · has errors',
  VALIDATED: 'Draft · validated',
  SIMULATED: 'Draft · simulated',
  PUBLISHED: 'Published',
  OFFLINE: 'Offline · being edited',
}

/** Small colored pill for a lifecycle status (DRAFT, VALIDATED, SIMULATED, PUBLISHED, ...). */
export default function StatusBadge({ status, label }) {
  const style = STYLES[status] || 'bg-slate-100 text-slate-700'
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium whitespace-nowrap ${style}`}>
      {label || LABELS[status] || status}
    </span>
  )
}
