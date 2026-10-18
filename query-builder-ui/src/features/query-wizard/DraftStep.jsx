import Alert, { ErrorAlert } from '../../components/Alert.jsx'
import YamlEditor from '../../components/YamlEditor.jsx'

const PARAM_TYPES = ['String', 'int', 'long', 'decimal', 'boolean', 'date', 'datetime']

/**
 * Step 1: edit the query entry as YAML and save it as a draft on the domain service. Shows the
 * server's validation result for the last save, and why the next steps are locked.
 */
export default function DraftStep({ state, derived, onYamlChange, nameWarning }) {
  const { draft, stale, notice, draftError, draftBusy } = state
  const params = draft?.validatedQuery?.params || []

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
        <SaveStatus derived={derived} hasDraft={!!draft} busy={draftBusy} />
      </div>

      <YamlEditor value={state.yamlText} onChange={onYamlChange} readOnly={draftBusy} />
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

      {draft && !derived.dirty && draft.status === 'DRAFT' && draft.errors?.length > 0 && (
        <Alert variant="warning" title="Saved, but the query has validation errors">
          <ul className="list-disc space-y-0.5 pl-5">
            {draft.errors.map((e, i) => (
              <li key={i}>
                <span className="font-mono text-xs">{e.code}</span>
                {e.param && <span className="font-mono text-xs"> ({e.param})</span>}: {e.message}
              </li>
            ))}
          </ul>
        </Alert>
      )}

      {draft && !derived.dirty && derived.validated && (
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

function SaveStatus({ derived, hasDraft, busy }) {
  if (busy) return <span className="text-xs text-slate-500">Saving…</span>
  if (!hasDraft) return <span className="text-xs text-slate-500">Not saved yet</span>
  if (derived.dirty) {
    return <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">Unsaved changes</span>
  }
  return <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">Saved</span>
}
