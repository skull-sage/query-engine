import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ErrorAlert } from '../components/Alert.jsx'
import Button from '../components/Button.jsx'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import Spinner from '../components/Spinner.jsx'
import StatusBadge from '../components/StatusBadge.jsx'
import Tabs from '../components/Tabs.jsx'
import { isAbortError } from '../api/engineClient.js'
import { paramListToMap } from '../api/yaml.js'
import { AUTHOR } from '../config.js'
import { useDomain } from '../context/DomainContext.jsx'
import { useAbortableRequests } from '../hooks/useAbortableRequests.js'
import QueryWizardModal from '../features/query-wizard/QueryWizardModal.jsx'
import RunQueryModal from '../features/run-query/RunQueryModal.jsx'
import { formatDateTime, formatRelative } from '../utils/format.js'

const TAB_KEYS = ['all', 'draft', 'published']

/**
 * The domain's main view: every query, each in exactly one state (drafted, simulated or published), filtered by
 * the All / Draft / Published tabs. A query that was published and is being edited is a draft marked offline. Drafting, simulating and publishing happen in
 * QueryWizardModal; running a published query happens in RunQueryModal. The list reloads in the
 * background whenever a dialog closes, so it reflects whatever the dialog changed.
 */
export default function QueryListPage() {
  const { domain, client } = useDomain()
  const run = useAbortableRequests()
  const [searchParams, setSearchParams] = useSearchParams()
  const tab = TAB_KEYS.includes(searchParams.get('tab')) ? searchParams.get('tab') : 'all'

  const [list, setList] = useState({ status: 'loading', items: [], error: null, refreshing: false, refreshError: null })
  const [search, setSearch] = useState('')
  const [dialog, setDialog] = useState(null) // { kind: 'wizard', intent } | { kind: 'run', queryName } | { kind: 'discard', item } | { kind: 'take-offline', item }
  const [discarding, setDiscarding] = useState(false)
  const [actionError, setActionError] = useState(null)

  // ---- loading: newest request wins, older ones are cancelled -------------------------------
  const listRequest = useRef({ seq: 0, controller: null })
  const load = useCallback(
    (silent) => {
      listRequest.current.controller?.abort()
      const controller = new AbortController()
      const seq = ++listRequest.current.seq
      listRequest.current.controller = controller
      setList((s) => (silent ? { ...s, refreshing: true, refreshError: null } : { ...s, status: 'loading', error: null, refreshError: null }))
      client
        .list({}, { signal: controller.signal })
        .then((res) => {
          if (seq !== listRequest.current.seq) return
          setList({ status: 'ready', items: res.items || [], error: null, refreshing: false, refreshError: null })
        })
        .catch((error) => {
          if (isAbortError(error) || seq !== listRequest.current.seq) return
          // Keep showing what we had if a background refresh fails.
          setList((s) => (silent && s.status === 'ready' ? { ...s, refreshing: false, refreshError: error } : { ...s, status: 'error', error, refreshing: false }))
        })
    },
    [client],
  )

  useEffect(() => {
    load(false)
    const request = listRequest.current
    return () => request.controller?.abort()
  }, [load])

  // ---- derived list ---------------------------------------------------------------------------
  const counts = useMemo(
    () => ({
      all: list.items.length,
      draft: list.items.filter((i) => i.draft).length,
      published: list.items.filter((i) => i.published).length,
    }),
    [list.items],
  )
  const knownQueries = useMemo(() => new Map(list.items.map((i) => [i.queryName, i])), [list.items])
  const needle = search.trim().toLowerCase()
  const visible = list.items.filter(
    (i) => (tab === 'all' || (tab === 'draft' ? i.draft : i.published)) && (!needle || i.queryName.toLowerCase().includes(needle)),
  )

  // ---- actions --------------------------------------------------------------------------------
  function closeDialog() {
    setDialog(null)
    load(true)
  }

  function onRowAction(action, item) {
    setActionError(null)
    const fallbackEntry = item.published ? { sql: item.published.sql, params: paramListToMap(item.published.params), fetchSize: item.published.fetchSize } : null
    switch (action) {
      case 'edit':
      case 'simulate':
      case 'publish':
        setDialog({ kind: 'wizard', intent: { kind: 'edit', queryName: item.queryName, step: action === 'edit' ? 'draft' : action, fallbackEntry } })
        break
      case 'take-offline':
        // Editing a published query takes it offline, so ask first. Nothing changes on the server until the first save.
        setDialog({ kind: 'take-offline', item })
        break
      case 'run':
        setDialog({ kind: 'run', queryName: item.queryName })
        break
      case 'discard':
        setDialog({ kind: 'discard', item })
        break
      default:
    }
  }

  function discard(item) {
    setDiscarding(true)
    run((signal) => client.discard(item.queryName, { signal }))
      .catch((error) => {
        if (error.code !== 'DRAFT_NOT_FOUND') setActionError(error)
      })
      .finally(() => {
        setDiscarding(false)
        closeDialog()
      })
  }

  function setTab(next) {
    setSearchParams(next === 'all' ? {} : { tab: next }, { replace: true })
  }

  // ---- render ---------------------------------------------------------------------------------
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold text-slate-900">{domain.label} queries</h1>
          <p className="mt-1 max-w-2xl text-sm text-slate-500">{domain.description}</p>
        </div>
        <Button variant="primary" onClick={() => setDialog({ kind: 'wizard', intent: { kind: 'new' } })} disabled={list.status !== 'ready'}>
          New query
        </Button>
      </div>

      <div className="rounded-xl bg-white shadow-sm ring-1 ring-slate-200">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-4 py-3">
          <Tabs
            label="Filter queries"
            value={tab}
            onChange={setTab}
            tabs={[
              { key: 'all', label: 'All', count: list.status === 'ready' ? counts.all : undefined },
              { key: 'draft', label: 'Draft', count: list.status === 'ready' ? counts.draft : undefined },
              { key: 'published', label: 'Published', count: list.status === 'ready' ? counts.published : undefined },
            ]}
          />
          <div className="flex items-center gap-2">
            <label className="sr-only" htmlFor="query-search">
              Search queries
            </label>
            <input
              id="query-search"
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search by name"
              className="w-48 rounded-md border-0 py-1.5 px-3 text-sm text-slate-900 ring-1 ring-inset ring-slate-300 placeholder:text-slate-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600"
            />
            <Button variant="ghost" size="sm" onClick={() => load(true)} disabled={list.status === 'loading' || list.refreshing} aria-label="Refresh list">
              {list.refreshing ? <Spinner className="h-3.5 w-3.5" /> : '↻'} Refresh
            </Button>
          </div>
        </div>

        {(list.refreshError || actionError) && (
          <div className="space-y-2 border-b border-slate-100 px-4 py-3">
            {list.refreshError && (
              <ErrorAlert error={list.refreshError} title="Couldn't refresh, showing the last loaded list" />
            )}
            {actionError && <ErrorAlert error={actionError} title="Couldn't discard the draft" />}
          </div>
        )}

        {list.status === 'loading' && <ListSkeleton />}

        {list.status === 'error' && (
          <div className="p-4">
            <ErrorAlert
              error={list.error}
              title={`Couldn't load ${domain.label} queries`}
              actions={<Button size="sm" onClick={() => load(false)}>Try again</Button>}
            />
          </div>
        )}

        {list.status === 'ready' &&
          (visible.length === 0 ? (
            <EmptyState tab={tab} search={needle} domain={domain} onNew={() => setDialog({ kind: 'wizard', intent: { kind: 'new' } })} />
          ) : (
            <ul className={`divide-y divide-slate-100 transition-opacity ${list.refreshing ? 'opacity-60' : ''}`}>
              {visible.map((item) => (
                <QueryRow key={item.queryName} item={item} onAction={onRowAction} />
              ))}
            </ul>
          ))}
      </div>

      {dialog?.kind === 'wizard' && (
        <QueryWizardModal
          intent={dialog.intent}
          knownQueries={knownQueries}
          onClose={closeDialog}
          onRun={(queryName) => {
            setDialog({ kind: 'run', queryName })
            load(true)
          }}
        />
      )}
      {dialog?.kind === 'run' && <RunQueryModal queryName={dialog.queryName} onClose={closeDialog} />}
      {dialog?.kind === 'take-offline' && (
        <ConfirmDialog
          title={`Edit ${dialog.item.queryName}?`}
          confirmLabel="Continue to edit"
          onCancel={() => setDialog(null)}
          onConfirm={() => {
            const published = dialog.item.published
            setDialog({
              kind: 'wizard',
              intent: {
                kind: 'edit',
                queryName: dialog.item.queryName,
                step: 'draft',
                unpublish: true,
                fallbackEntry: { sql: published.sql, params: paramListToMap(published.params), fetchSize: published.fetchSize },
              },
            })
          }}
        >
          <p>
            This query is published and in use. Saving a change takes it <strong>offline</strong>: callers get a &ldquo;being edited&rdquo; answer until
            you simulate it again and publish it again.
          </p>
          <p className="mt-2">Nothing changes until you save. If you close the editor before saving, the query stays live.</p>
        </ConfirmDialog>
      )}
      {dialog?.kind === 'discard' && (
        <ConfirmDialog
          title={`Discard the draft for ${dialog.item.queryName}?`}
          confirmLabel="Discard draft"
          busy={discarding}
          onCancel={() => setDialog(null)}
          onConfirm={() => discard(dialog.item)}
        >
          {dialog.item.draft?.previouslyPublished
            ? 'This query was published and is offline while it is being edited. Discarding deletes it completely: callers will get "not found". To keep it, publish a corrected version instead.'
            : 'The draft is deleted. This query was never published, so it disappears from the list.'}
        </ConfirmDialog>
      )}
    </div>
  )
}

function QueryRow({ item, onAction }) {
  const { queryName, published, draft } = item
  const draftStatus = draft ? (draft.status === 'VALIDATED' && draft.simulated ? 'SIMULATED' : draft.status) : null
  const heldByOther = draft && draft.author !== AUTHOR

  return (
    <li className="flex flex-col gap-3 px-4 py-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0 space-y-1.5">
        <p className="truncate font-mono text-sm font-semibold text-slate-900">{queryName}</p>
        {published && (
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500">
            <StatusBadge status="PUBLISHED" />
            <span title={formatDateTime(published.publishedAt)}>
              {formatRelative(published.publishedAt)} by {published.publishedBy}
            </span>
            <span aria-hidden="true">·</span>
            <span className="flex flex-wrap gap-1">
              {(published.allowedRoles || []).map((r) => (
                <span key={r} className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-slate-700">
                  {r}
                </span>
              ))}
            </span>
          </div>
        )}
        {draft && (
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500">
            {draft.previouslyPublished && <StatusBadge status="OFFLINE" />}
            <StatusBadge status={draftStatus} />
            <span title={formatDateTime(draft.updatedAt)}>
              saved {formatRelative(draft.updatedAt)} by {draft.author}
            </span>
            {heldByOther && <span className="rounded bg-amber-50 px-1.5 py-0.5 text-amber-800 ring-1 ring-amber-200">held by {draft.author}</span>}
          </div>
        )}
      </div>

      <div className="flex shrink-0 flex-wrap items-center gap-2">
        {draft && <DraftActions draftStatus={draftStatus} onAction={(a) => onAction(a, item)} />}
        {published && (
          <Button size="sm" variant="primary" onClick={() => onAction('run', item)}>
            Run
          </Button>
        )}
        {published && (
          <Button size="sm" onClick={() => onAction('take-offline', item)}>
            Edit
          </Button>
        )}
        {draft && (
          <Button size="sm" variant="ghost" onClick={() => onAction('discard', item)} aria-label={`Discard the draft for ${queryName}`}>
            Discard
          </Button>
        )}
      </div>
    </li>
  )
}

function DraftActions({ draftStatus, onAction }) {
  if (draftStatus === 'DRAFT') {
    return (
      <Button size="sm" variant="primary" onClick={() => onAction('edit')}>
        Fix draft
      </Button>
    )
  }
  return (
    <>
      <Button size="sm" onClick={() => onAction('edit')}>
        Edit
      </Button>
      <Button size="sm" variant={draftStatus === 'SIMULATED' ? 'secondary' : 'primary'} onClick={() => onAction('simulate')}>
        Simulate
      </Button>
      {draftStatus === 'SIMULATED' && (
        <Button size="sm" variant="success" onClick={() => onAction('publish')}>
          Publish
        </Button>
      )}
    </>
  )
}

function EmptyState({ tab, search, domain, onNew }) {
  let title
  let body
  if (search) {
    title = `No queries match "${search}"`
    body = 'Try a different name, or clear the search.'
  } else if (tab === 'draft') {
    title = 'No open drafts'
    body = 'Drafts appear here once saved, until they are published or discarded.'
  } else if (tab === 'published') {
    title = 'Nothing published yet'
    body = `Published ${domain.label} queries are what client apps can call.`
  } else {
    title = `No ${domain.label} queries yet`
    body = 'Draft one, simulate it against real data, then publish it.'
  }
  return (
    <div className="px-6 py-14 text-center">
      <p className="text-sm font-semibold text-slate-900">{title}</p>
      <p className="mt-1 text-sm text-slate-500">{body}</p>
      {!search && tab !== 'published' && (
        <div className="mt-4">
          <Button variant="primary" onClick={onNew}>
            New query
          </Button>
        </div>
      )}
    </div>
  )
}

function ListSkeleton() {
  return (
    <ul className="divide-y divide-slate-100" aria-busy="true" aria-label="Loading queries">
      {[0, 1, 2].map((i) => (
        <li key={i} className="flex items-center justify-between gap-4 px-4 py-4">
          <div className="w-full max-w-md space-y-2">
            <div className="h-4 w-2/3 animate-pulse rounded bg-slate-200" />
            <div className="h-3 w-1/2 animate-pulse rounded bg-slate-100" />
          </div>
          <div className="h-7 w-24 animate-pulse rounded bg-slate-100" />
        </li>
      ))}
    </ul>
  )
}
