import { useLocation, useMatch, useNavigate } from 'react-router-dom'
import { DOMAINS, findDomain } from '../domains.js'
import { useRequestLeave } from '../context/LeaveGuard.jsx'

/**
 * Full-width app header with the domain switcher. Switching domains navigates to /:domainKey,
 * which remounts the workspace for the new domain; unsaved wizard edits get a confirm first.
 */
export default function AppHeader() {
  const match = useMatch('/:domainKey/*')
  const { pathname } = useLocation()
  const current = findDomain(match?.params.domainKey)
  const navigate = useNavigate()
  const requestLeave = useRequestLeave()

  function onChange(e) {
    const key = e.target.value
    if (!key || key === current?.key) return
    requestLeave(() => navigate(`/${key}`))
  }

  const onStreamDemo = pathname.endsWith('/streaming-demo')
  const onBatchDemo = pathname.endsWith('/plain-batch-demo')
  const navItems = current
    ? [
        { label: 'Queries', to: `/${current.key}`, active: !onStreamDemo && !onBatchDemo },
        ...(current.streamDemo ? [{ label: 'Streaming demo', to: `/${current.key}/streaming-demo`, active: onStreamDemo }] : []),
        ...(current.batchDemo ? [{ label: 'Plain batch demo', to: `/${current.key}/plain-batch-demo`, active: onBatchDemo }] : []),
      ]
    : []

  return (
    <header className="sticky top-0 z-40 border-b border-slate-200 bg-white/90 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between gap-4 px-4 sm:px-6 lg:px-8">
        <div className="flex items-center gap-2">
          <span className="flex h-7 w-7 items-center justify-center rounded-md bg-indigo-600 text-xs font-bold text-white" aria-hidden="true">
            Q
          </span>
          <span className="text-base font-semibold text-slate-900">Query Builder</span>
          {navItems.length > 0 && (
            <nav aria-label="Sections" className="ml-4 flex items-center gap-1">
              {navItems.map((item) => (
                <button
                  key={item.to}
                  type="button"
                  aria-current={item.active ? 'page' : undefined}
                  onClick={() => !item.active && requestLeave(() => navigate(item.to))}
                  className={`rounded-md px-3 py-1.5 text-sm font-medium focus-visible:outline focus-visible:outline-2 focus-visible:outline-indigo-600 ${
                    item.active ? 'bg-indigo-50 text-indigo-700' : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
                  }`}
                >
                  {item.label}
                </button>
              ))}
            </nav>
          )}
        </div>

        <div className="flex items-center gap-3">
          <label htmlFor="domain-select" className="text-sm font-medium text-slate-600">
            Domain
          </label>
          <select
            id="domain-select"
            value={current?.key || ''}
            onChange={onChange}
            className="rounded-md border-0 py-1.5 pl-3 pr-8 text-sm font-medium text-slate-900 ring-1 ring-inset ring-slate-300 focus:ring-2 focus:ring-inset focus:ring-indigo-600"
          >
            {!current && (
              <option value="" disabled>
                Select a domain…
              </option>
            )}
            {DOMAINS.map((d) => (
              <option key={d.key} value={d.key}>
                {d.label}
              </option>
            ))}
          </select>
          {current && (
            <span className="hidden font-mono text-xs text-slate-500 md:inline" title="The service this workspace talks to">
              {current.service} · {current.serviceUrl}
            </span>
          )}
        </div>
      </div>
    </header>
  )
}
