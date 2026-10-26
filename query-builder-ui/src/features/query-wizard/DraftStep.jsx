import { useEffect, useRef } from 'react'
import Alert, { ErrorAlert } from '../../components/Alert.jsx'
import YamlEditor from '../../components/YamlEditor.jsx'
import { PARAM_TYPES } from '../../sql/checkDefinition.js'
import DefinitionProblems, { ProblemList, problemCount } from './DefinitionProblems.jsx'

/**
 * Step 1: edit the query entry as YAML and save it as a draft on the domain service. An invalid definition is
 * announced twice: live under the editor while it is typed (the browser's copy of the rules), and after a save
 * the server stored as invalid (the server's own list). Either way the editor is outlined in red, the header
 * says how many problems there are, and the next steps stay locked with the reason.
 *
 * `definition` is the result of useDefinitionChecks for the editor text.
 */
export default function DraftStep({ state, derived, definition, onYamlChange, nameWarning }) {
  const { draft, stale, notice, draftError, draftBusy } = state
  const params = draft?.validatedQuery?.params || []
  const warnings = draft?.warnings || []

  // After a save the server has the last word: show its list instead of the live one when it stored the draft
  // as invalid and the editor still matches what was saved.
  const serverProblems = draft && !derived.dirty && draft.status === 'DRAFT' ? draft.errors || [] : []
  const showServer = serverProblems.length > 0
  const liveCount = showServer ? 0 : definition?.problems.length ?? 0
  const invalid = showServer || liveCount > 0

  // A save that comes back invalid brings its list into view: the dialog body scrolls and the list can be below the fold.
  const serverRef = useRef(null)
  useEffect(() => {
    if (!showServer) return
    const calm = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    serverRef.current?.scrollIntoView?.({ block: 'nearest', behavior: calm ? 'auto' : 'smooth' })
  }, [showServer, draft])

  return (
    <div className="space-y-4">
      {notice && <Alert variant="info">{notice}</Alert>}
      {stale && (
        <Alert variant="warning" title="The saved draft no longer exists">
          {stale}
        </Alert>
      )}

      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-slate-600">
          Declare <code className="font-mono text-xs">queryName</code> as <code className="font-mono text-xs">module.name</code>, the SQL
          template with <code className="font-mono text-xs">:param</code> placeholders, and each param&apos;s type.
        </p>
        <SaveStatus derived={derived} hasDraft={!!draft} busy={draftBusy} problems={showServer ? serverProblems.length : liveCount} />
      </div>

      <YamlEditor value={state.yamlText} onChange={onYamlChange} readOnly={draftBusy} invalid={invalid} />
      {!showServer && <DefinitionProblems checks={definition} />}
      <p className="text-xs text-slate-500">
        Param types: {PARAM_TYPES.map((t, i) => (
          <span key={t}>
            <code className="font-mono">{t}</code>
            {i < PARAM_TYPES.length - 1 ? ', ' : ''}
          </span>
        ))}
        . Optional <code className="font-mono">fetchSize</code> sets how many rows are read from the database at a time when a client
        streams the result. Allowed roles are chosen when you publish.
      </p>

      {nameWarning && <Alert variant="warning">{nameWarning}</Alert>}
      <ErrorAlert error={draftError} title={draftError?.code === 'DRAFT_LOCKED' ? 'Draft is held by someone else' : undefined} />

      {showServer && (
        <div ref={serverRef}>
          <ProblemList problems={serverProblems} title={`Saved, but the query definition is invalid: ${problemCount(serverProblems.length)}`}>
            The draft is stored as invalid, so Simulate and Publish stay locked. Fix these and save again.
          </ProblemList>
        </div>
      )}

      {draft && !derived.dirty && derived.validated && warnings.length > 0 && (
        <Alert variant="warning" title="Draft saved, but the SQL was not checked against the database">
          <ul className="list-disc space-y-0.5 pl-5">
            {warnings.map((w, i) => (
              <li key={`${w.code}-${i}`}>{w.message}</li>
            ))}
          </ul>
        </Alert>
      )}

      {draft && !derived.dirty && derived.validated && warnings.length === 0 && (
        <Alert variant="success" title="Draft saved and validated">
          {params.length === 0 ? (
            'This query takes no params.'
          ) : (
            <span className="flex flex-wrap items-center gap-1.5">
              Params:
              {params.map((p) => (
                <span key={p.name} className="rounded bg-white/70 px-1.5 py-0.5 font-mono text-xs ring-1 ring-emerald-200">
                  {p.name}: {p.type}
                </span>
              ))}
            </span>
          )}
        </Alert>
      )}
    </div>
  )
}

function SaveStatus({ derived, hasDraft, busy, problems }) {
  if (busy) return <span className="text-xs text-slate-500">Saving…</span>
  return (
    <span className="flex items-center gap-2">
      {problems > 0 && (
        <span role="status" className="rounded-full bg-rose-100 px-2 py-0.5 text-xs font-semibold text-rose-800 ring-1 ring-rose-300">
          Invalid · {problemCount(problems)}
        </span>
      )}
      {!hasDraft ? (
        <span className="text-xs text-slate-500">Not saved yet</span>
      ) : derived.dirty ? (
        <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">Unsaved changes</span>
      ) : (
        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">Saved</span>
      )}
    </span>
  )
}
