import { useEffect, useMemo, useRef, useState } from 'react'
import Alert, { ErrorAlert } from '../../components/Alert.jsx'
import Button from '../../components/Button.jsx'
import CopyButton from '../../components/CopyButton.jsx'
import Spinner from '../../components/Spinner.jsx'
import { useDomain } from '../../context/DomainContext.jsx'
import Sparkline from './Sparkline.jsx'
import { LOG_BATCHES as LOG_BATCHES_SHOWN, useDownstreamDemo } from './useDownstreamDemo.js'
import { samplesToCsv, useResourceMonitor } from './useResourceMonitor.js'
import { useServiceAvailability } from './useServiceAvailability.js'

const BATCH_SIZES = [10, 50, 100, 500, 1000]
const DELAYS_MS = [0, 50, 100, 250, 500]
const COOLDOWN_MS = 5000 // keep sampling briefly after a run so the drop back to idle is visible

const PHASE_LABEL = {
  idle: 'Ready',
  preparing: 'Preparing the demo query',
  streaming: 'Streaming',
  done: 'Finished',
  failed: 'Failed',
  stopped: 'Stopped',
}

/**
 * Downstream demo: a deliberately slow consumer of /data-query, with a live view of what the run
 * costs. Nothing happens until "Run query" is pressed. Top: the run controls. Below, two columns:
 * what the run is doing (left) and the resources it is using (right).
 */
export default function DownstreamDemoPage() {
  const { domain, client } = useDomain()
  const demo = domain.streamDemo
  const { run, runKey, rowsRef, settings, updateSettings, start, stop } = useDownstreamDemo({ client, demo })
  const availability = useServiceAvailability(client)

  const active = run.phase === 'preparing' || run.phase === 'streaming'
  const sampling = useCooldown(active, COOLDOWN_MS)
  const monitor = useResourceMonitor({ active: sampling, runKey, rowsRef })

  const { mark } = monitor
  useEffect(() => {
    if (run.phase !== 'idle') mark(PHASE_LABEL[run.phase].toLowerCase())
  }, [run.phase, mark])

  const now = useTicker(run.phase === 'streaming')
  const elapsedMs = run.startedAt == null ? 0 : (run.endedAt ?? now) - run.startedAt
  const elapsedSec = Math.max(elapsedMs / 1000, 0.001)
  const pct = Math.min(100, (run.rows / demo.expectedRows) * 100)
  const consumerRate = settings.delayMs === 0 ? null : Math.round((settings.batchSize / settings.delayMs) * 1000)

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold text-slate-900">Downstream demo</h1>
        <p className="mt-1 max-w-3xl text-sm text-slate-600">
          Calls <code className="font-mono text-xs">{demo.queryName}</code> on {domain.service} as a slow downstream system: it reads a batch of
          rows, pauses, then reads more. The service sends rows only as fast as they are read, so its memory stays flat and its database
          cursor advances at this pace.
        </p>
      </div>

      <StartBlock
        demo={demo}
        settings={settings}
        updateSettings={updateSettings}
        phase={run.phase}
        active={active}
        onRun={start}
        onStop={stop}
        consumerRate={consumerRate}
      />
      <ErrorAlert error={run.error} title={run.error?.code} />

      <div className="grid gap-6 lg:grid-cols-2">
        <div className="space-y-6">
          <ProgressCard run={run} demo={demo} active={active} pct={pct} elapsedMs={elapsedMs} elapsedSec={elapsedSec} consumerRate={consumerRate} />
          {run.phase === 'done' && <Checks run={run} demo={demo} />}
          {run.phase === 'stopped' && (
            <Alert variant="warning" title="Stopped before the end">
              Received {run.rows.toLocaleString()} of {demo.expectedRows.toLocaleString()} rows. Stopping cancels the request and the service
              releases its database connection.
            </Alert>
          )}
          <RecentBatches log={run.log} />
          <AvailabilityCard availability={availability} service={domain.service} />
        </div>
        <ResourcePanel monitor={monitor} service={domain.service} />
      </div>

      <ReviewAids domain={domain} demo={demo} />
    </div>
  )
}

function StartBlock({ demo, settings, updateSettings, phase, active, onRun, onStop, consumerRate }) {
  return (
    <section className="rounded-lg bg-white p-5 shadow-sm ring-1 ring-slate-200" aria-label="Run settings">
      <div className="flex flex-wrap items-end gap-x-6 gap-y-4">
        <Select label="Rows per batch" value={settings.batchSize} options={BATCH_SIZES} onChange={(v) => updateSettings({ batchSize: v })} />
        <Select label="Pause per batch (ms)" value={settings.delayMs} options={DELAYS_MS} onChange={(v) => updateSettings({ delayMs: v })} />
        <div className="pb-1.5 text-xs text-slate-500">
          Consumer pace: {consumerRate == null ? 'unthrottled' : `up to ${consumerRate.toLocaleString()} rows/s`}. Changes apply from the next batch, also during a run.
        </div>
        <div className="ml-auto flex items-center gap-2">
          {active ? (
            <Button variant="danger" onClick={onStop}>
              Stop
            </Button>
          ) : (
            <Button variant="primary" onClick={onRun}>
              {phase === 'idle' ? 'Run query' : 'Run again'}
            </Button>
          )}
        </div>
      </div>
      <p className="mt-3 text-xs text-slate-500">
        If <code className="font-mono">{demo.queryName}</code> is not published yet, running it first drafts, simulates and publishes it, then starts
        the stream ({demo.expectedRows.toLocaleString()} rows expected).
      </p>
    </section>
  )
}

function ProgressCard({ run, demo, active, pct, elapsedMs, elapsedSec, consumerRate }) {
  return (
    <section className="rounded-lg bg-white p-5 shadow-sm ring-1 ring-slate-200" aria-label="Run progress">
      <div className="mb-2 flex items-center justify-between text-sm">
        <span className="flex items-center gap-2 font-medium text-slate-800">
          {active && <Spinner className="h-4 w-4 text-indigo-600" />}
          {PHASE_LABEL[run.phase]}
          {run.prepared === 'created' && run.phase !== 'preparing' && (
            <span className="text-xs font-normal text-slate-500">(demo query was created and published)</span>
          )}
        </span>
        <span className="tabular-nums text-slate-600">
          {run.rows.toLocaleString()} / {demo.expectedRows.toLocaleString()} rows ({pct.toFixed(1)}%)
        </span>
      </div>
      <div
        className="h-3 overflow-hidden rounded-full bg-slate-100"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={demo.expectedRows}
        aria-valuenow={run.rows}
        aria-label="Rows received"
      >
        <div className="h-full rounded-full bg-indigo-600 transition-[width] duration-100" style={{ width: `${pct}%` }} />
      </div>
      <dl className="mt-5 grid grid-cols-2 gap-4 text-sm">
        <Stat label="Elapsed" value={formatDuration(elapsedMs)} />
        <Stat label="Batches" value={run.batches.toLocaleString()} />
        <Stat label="Observed rate" value={`${Math.round(run.rows / elapsedSec).toLocaleString()} rows/s`} />
        <Stat label="Consumer pace" value={consumerRate == null ? 'unthrottled' : `up to ${consumerRate.toLocaleString()} rows/s`} />
      </dl>
    </section>
  )
}

function Checks({ run, demo }) {
  const countOk = run.rows === demo.expectedRows
  const orderOk = run.outOfOrder === 0 && run.unparsable === 0
  return (
    <Alert variant={countOk && orderOk ? 'success' : 'error'} title={countOk && orderOk ? 'All rows received intact' : 'The result does not match what was expected'}>
      <ul className="list-disc space-y-0.5 pl-5">
        <li>
          Row count: {run.rows.toLocaleString()} received, {demo.expectedRows.toLocaleString()} expected ({countOk ? 'match' : 'MISMATCH'})
        </li>
        <li>
          Order: {orderOk ? `${demo.idField} ran in sequence with no gaps or repeats` : `${run.outOfOrder} rows out of sequence, ${run.unparsable} lines not valid JSON`}
        </li>
      </ul>
    </Alert>
  )
}

function RecentBatches({ log }) {
  const box = useRef(null)
  // Keep the newest batch in view as batches are appended.
  useEffect(() => {
    if (box.current) box.current.scrollTop = box.current.scrollHeight
  }, [log])
  return (
    <section className="rounded-lg bg-white p-5 shadow-sm ring-1 ring-slate-200" aria-label="Recent batches">
      <h2 className="text-sm font-semibold text-slate-900">Recent batches</h2>
      <p className="mt-1 text-xs text-slate-500">The last {LOG_BATCHES_SHOWN} batches: the id range, then the ids in it.</p>
      <pre
        ref={box}
        tabIndex={0}
        className="mt-2 max-h-80 min-h-[8rem] overflow-y-auto whitespace-pre-wrap break-words rounded bg-slate-50 p-3 font-mono text-xs text-slate-700"
        aria-live="off"
        aria-label="Recent batches log"
      >
        {log.length ? log.join('\n\n') : 'No batches yet.'}
      </pre>
    </section>
  )
}

const AVAILABILITY = {
  checking: { label: 'Checking', dot: 'bg-slate-400', text: 'text-slate-700' },
  up: { label: 'Up', dot: 'bg-emerald-500', text: 'text-emerald-700' },
  slow: { label: 'Slow', dot: 'bg-amber-500', text: 'text-amber-700' },
  down: { label: 'Not answering', dot: 'bg-rose-500', text: 'text-rose-700' },
}

function AvailabilityCard({ availability, service }) {
  const v = AVAILABILITY[availability.status]
  const slowest = Math.max(100, ...availability.history.filter((h) => h != null))
  return (
    <section className="rounded-lg bg-white p-5 shadow-sm ring-1 ring-slate-200" aria-label="Service availability">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-900">Service availability</h2>
        <span className={`flex items-center gap-2 text-sm font-medium ${v.text}`} role="status">
          <span className={`h-2.5 w-2.5 rounded-full ${v.dot}`} aria-hidden="true" />
          {v.label}
        </span>
      </div>
      <p className="mt-1 text-xs text-slate-500">
        {service} is asked for its role list every 2 s, a call that does not use the streaming pool. It should stay fast during a stream.
      </p>
      <dl className="mt-3 grid grid-cols-2 gap-4 text-sm">
        <Stat label="Last response" value={availability.latencyMs == null ? 'none yet' : `${Math.round(availability.latencyMs)} ms`} />
        <Stat label="Failed checks in a row" value={availability.failures} />
      </dl>
      <div className="mt-3 flex h-8 items-end gap-0.5" aria-hidden="true">
        {availability.history.map((h, i) => (
          <span
            key={i}
            className={`w-2 rounded-sm ${h == null ? 'bg-rose-400' : h > 1000 ? 'bg-amber-400' : 'bg-emerald-400'}`}
            style={{ height: h == null ? '100%' : `${Math.max(8, (h / slowest) * 100)}%` }}
          />
        ))}
      </div>
      {availability.status === 'down' && availability.error && <p className="mt-2 text-xs text-rose-700">{availability.error.message}</p>}
    </section>
  )
}

function ResourcePanel({ monitor, service }) {
  const { samples, events, docker } = monitor
  const appName = `${service}-app`
  const dbName = `${service}-postgres`
  const xMax = samples.length ? samples[samples.length - 1].t : 0

  const series = (pick) => samples.map((s) => ({ t: s.t, v: pick(s) }))
  const appMemLimit = useMemo(() => {
    for (let i = samples.length - 1; i >= 0; i--) {
      const lim = samples[i].containers[appName]?.memLimitMiB
      if (lim) return lim
    }
    return null
  }, [samples, appName])
  const csv = useMemo(() => samplesToCsv(samples, [appName, dbName]), [samples, appName, dbName])
  const hasHeap = samples.some((s) => s.heapMB != null)
  const stale = samples.length > 0 && Object.values(samples[samples.length - 1].containers).some((c) => c.ageMs > 5000)

  return (
    <section className="space-y-3" aria-label="Resource usage">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-900">Resource usage</h2>
        {samples.length > 0 && <CopyButton text={csv} label="Copy CSV" />}
      </div>
      <p className="text-xs text-slate-500">
        Sampled every second while a run is active, plus 5 s after it. Service and database figures come from <code className="font-mono">docker stats</code> through the dev server;
        browser figures are measured in this page. Amber lines mark events (run phases, tab hidden or visible).
      </p>
      {samples.length === 0 && <Alert variant="info">Run the query to start collecting samples.</Alert>}
      {docker.state !== 'ok' && docker.state !== 'unknown' && (
        <Alert variant="warning" title="No container figures">
          {docker.reason}. The browser and throughput charts still work.
        </Alert>
      )}
      {stale && <Alert variant="warning">Container figures are older than 5 s; docker stats may have stalled.</Alert>}

      <Sparkline title={`${appName} CPU`} unit="%" points={series((s) => s.containers[appName]?.cpu ?? null)} xMax={xMax} yMax={100} events={events} color="#4f46e5" note="100% is one full core." />
      <Sparkline
        title={`${appName} memory`}
        unit="MiB"
        points={series((s) => s.containers[appName]?.memMiB ?? null)}
        xMax={xMax}
        limit={appMemLimit}
        events={events}
        color="#7c3aed"
        note="Should stay flat while rows stream. A steady climb means something is buffering."
      />
      <Sparkline title={`${dbName} CPU`} unit="%" points={series((s) => s.containers[dbName]?.cpu ?? null)} xMax={xMax} yMax={100} events={events} color="#0891b2" />
      <Sparkline title={`${dbName} memory`} unit="MiB" points={series((s) => s.containers[dbName]?.memMiB ?? null)} xMax={xMax} events={events} color="#0e7490" />
      <Sparkline title="Rows received per second" unit="rows/s" points={series((s) => s.rowsPerSec)} xMax={xMax} events={events} color="#059669" note="Drops to zero when the consumer or the stream stalls." />
      <Sparkline
        title="Browser event-loop lag"
        unit="ms"
        points={series((s) => s.lagMs)}
        xMax={xMax}
        events={events}
        color="#d97706"
        note="Worst delay of a 100 ms timer in each second. Large values mean the page was busy or the tab was throttled."
      />
      {hasHeap ? (
        <Sparkline title="Browser JS heap" unit="MB" points={series((s) => s.heapMB)} xMax={xMax} events={events} color="#be123c" />
      ) : (
        samples.length > 0 && <p className="text-xs text-slate-500">JS heap is only reported by Chromium browsers.</p>
      )}
    </section>
  )
}

function ReviewAids({ domain, demo }) {
  const container = `${domain.service}-app`
  const pgContainer = `${domain.service}-postgres`
  const dbName = domain.service.replace('-', '_')
  const table = demo.sql.match(/FROM\s+(\S+)/i)?.[1] ?? ''
  const logs = `docker logs ${container} 2>&1 | grep "data-query query" | tail -3`
  const activity = `docker exec ${pgContainer} psql -U postgres -d ${dbName} -c "select state, wait_event, left(query, 60) as query from pg_stat_activity where query ilike '%${table}%' and pid <> pg_backend_pid()"`
  return (
    <section className="rounded-lg bg-slate-50 p-5 ring-1 ring-slate-200" aria-label="How to check it">
      <h2 className="text-sm font-semibold text-slate-900">Check it from outside</h2>
      <p className="mt-1 text-xs text-slate-600">
        If a run stops early, the service log says why: the last line shows <code className="font-mono">outcome</code> and, for a cut-off, <code className="font-mono">reason</code>.
      </p>
      <div className="mt-3 space-y-2">
        <Command label="How did the last stream end" command={logs} />
        <Command label="Database connection" command={activity} />
      </div>
    </section>
  )
}

function Command({ label, command }) {
  return (
    <div>
      <div className="mb-1 flex items-center justify-between">
        <span className="text-xs font-medium text-slate-700">{label}</span>
        <CopyButton text={command} />
      </div>
      <pre className="overflow-x-auto rounded bg-white p-2 font-mono text-xs text-slate-800 ring-1 ring-slate-200">{command}</pre>
    </div>
  )
}

function Stat({ label, value }) {
  return (
    <div>
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="mt-0.5 font-semibold tabular-nums text-slate-900">{value}</dd>
    </div>
  )
}

function Select({ label, value, options, onChange }) {
  const id = `demo-${label.replace(/\W+/g, '-').toLowerCase()}`
  return (
    <div>
      <label htmlFor={id} className="block text-xs font-medium text-slate-700">
        {label}
      </label>
      <select
        id={id}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="mt-1 rounded-md border-0 py-1.5 pl-3 pr-8 text-sm text-slate-900 ring-1 ring-inset ring-slate-300 focus:ring-2 focus:ring-inset focus:ring-indigo-600"
      >
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </div>
  )
}

/** True while `active`, and for `ms` after it turns false. */
function useCooldown(active, ms) {
  const [on, setOn] = useState(false)
  useEffect(() => {
    if (active) {
      setOn(true)
      return undefined
    }
    const t = setTimeout(() => setOn(false), ms)
    return () => clearTimeout(t)
  }, [active, ms])
  return on
}

/** performance.now() refreshed a few times a second while `running`, so the elapsed time ticks between paints. */
function useTicker(running) {
  const [now, setNow] = useState(() => performance.now())
  useEffect(() => {
    if (!running) return undefined
    const id = setInterval(() => setNow(performance.now()), 250)
    return () => clearInterval(id)
  }, [running])
  return now
}

function formatDuration(ms) {
  const s = Math.floor(ms / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}
