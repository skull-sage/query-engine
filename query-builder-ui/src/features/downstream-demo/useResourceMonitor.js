import { useCallback, useEffect, useRef, useState } from 'react'

const SAMPLE_MS = 1000
const DRIFT_MS = 100
const MAX_SAMPLES = 900 // 15 minutes at one sample per second
const STATS_URL = '/__dev/stats'

/**
 * Samples, once a second while a run is active:
 *  - the service and database containers' CPU and memory, from the dev server's /__dev/stats
 *    (which wraps `docker stats`; see vite-plugins/dockerStats.js),
 *  - this page's own pressure: event-loop lag, long tasks, JS heap (Chrome only), tab visibility,
 *  - rows received, so every spike can be read against progress.
 *
 * Sampling is separate from the stream, so it does not affect how fast rows are read. The
 * samples live in a bounded list; a new run (runKey) starts a fresh timeline.
 *
 * @param active   true while the run is preparing or streaming
 * @param runKey   changes for every new run
 * @param rowsRef  ref holding the rows received so far
 */
export function useResourceMonitor({ active, runKey, rowsRef }) {
  const [samples, setSamples] = useState([])
  const [events, setEvents] = useState([])
  const [docker, setDocker] = useState({ state: 'unknown', reason: null })

  const t0 = useRef(performance.now())
  const maxDrift = useRef(0)
  const longTasks = useRef({ count: 0, ms: 0 })
  const lastRows = useRef({ rows: 0, at: 0 })
  const inFlight = useRef(false)
  const runKeyRef = useRef(runKey)

  const mark = useCallback((label) => {
    setEvents((list) => [...list, { t: performance.now() - t0.current, label }].slice(-60))
  }, [])

  // A new run clears the timeline.
  useEffect(() => {
    runKeyRef.current = runKey
    t0.current = performance.now()
    lastRows.current = { rows: 0, at: 0 }
    maxDrift.current = 0
    longTasks.current = { count: 0, ms: 0 }
    setSamples([])
    setEvents([])
  }, [runKey])

  // Browser-side signals that need to run continuously while active.
  useEffect(() => {
    if (!active) return undefined
    let expected = performance.now() + DRIFT_MS
    const drift = setInterval(() => {
      const now = performance.now()
      maxDrift.current = Math.max(maxDrift.current, now - expected)
      expected = now + DRIFT_MS
    }, DRIFT_MS)

    let observer = null
    try {
      observer = new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) {
          longTasks.current.count += 1
          longTasks.current.ms += entry.duration
        }
      })
      observer.observe({ type: 'longtask', buffered: false })
    } catch {
      observer = null // not supported in this browser
    }

    const onVisibility = () => mark(document.hidden ? 'tab hidden' : 'tab visible')
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      clearInterval(drift)
      observer?.disconnect()
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [active, mark])

  // The once-a-second sampler.
  useEffect(() => {
    if (!active) return undefined
    const keyAtStart = runKeyRef.current
    async function sample() {
      if (inFlight.current) return
      inFlight.current = true
      const controller = new AbortController()
      const timeout = setTimeout(() => controller.abort(), SAMPLE_MS * 0.9)
      let stats = null
      let dockerState = { state: 'ok', reason: null }
      try {
        const res = await fetch(STATS_URL, { signal: controller.signal, cache: 'no-store' })
        const body = res.ok ? await res.json() : null
        if (!body || typeof body !== 'object') throw new Error(`HTTP ${res.status}`)
        if (!body.available) dockerState = { state: 'unavailable', reason: body.reason || 'docker stats is not running' }
        else if (Object.keys(body.containers).length === 0) dockerState = { state: 'empty', reason: 'no running containers reported yet' }
        else stats = body
      } catch (err) {
        dockerState = { state: 'unreachable', reason: err.name === 'AbortError' ? 'the stats endpoint did not answer in time' : 'the stats endpoint is not available (only the dev server provides it)' }
      } finally {
        clearTimeout(timeout)
        inFlight.current = false
      }
      if (runKeyRef.current !== keyAtStart) return // a newer run started while we waited

      const now = performance.now()
      const rows = rowsRef.current
      const last = lastRows.current
      const rowsPerSec = last.at ? Math.max(0, ((rows - last.rows) / (now - last.at)) * 1000) : 0
      lastRows.current = { rows, at: now }

      const point = {
        t: now - t0.current,
        rows,
        rowsPerSec,
        lagMs: maxDrift.current,
        longTasks: longTasks.current.count,
        longTaskMs: longTasks.current.ms,
        heapMB: performance.memory ? performance.memory.usedJSHeapSize / 1048576 : null,
        hidden: document.hidden,
        containers: {},
      }
      maxDrift.current = 0
      longTasks.current = { count: 0, ms: 0 }
      if (stats) {
        for (const [name, c] of Object.entries(stats.containers)) {
          point.containers[name] = {
            cpu: c.cpu,
            memMiB: c.memBytes == null ? null : c.memBytes / 1048576,
            memLimitMiB: c.memLimitBytes == null ? null : c.memLimitBytes / 1048576,
            ageMs: stats.now - c.updatedAt,
          }
        }
      }
      setDocker(dockerState)
      setSamples((list) => [...list, point].slice(-MAX_SAMPLES))
    }
    sample()
    const id = setInterval(sample, SAMPLE_MS)
    return () => clearInterval(id)
  }, [active, rowsRef])

  return { samples, events, docker, mark }
}

/** The samples as CSV, one row per second, for pasting into a ticket or a spreadsheet. */
export function samplesToCsv(samples, containerNames) {
  const head = ['seconds', 'rows', 'rows_per_sec', 'browser_lag_ms', 'long_tasks', 'long_task_ms', 'browser_heap_mb', 'tab_hidden']
  for (const n of containerNames) head.push(`${n}_cpu_pct`, `${n}_mem_mib`)
  const lines = [head.join(',')]
  for (const s of samples) {
    const row = [
      (s.t / 1000).toFixed(1),
      s.rows,
      s.rowsPerSec.toFixed(0),
      s.lagMs.toFixed(0),
      s.longTasks,
      s.longTaskMs.toFixed(0),
      s.heapMB == null ? '' : s.heapMB.toFixed(1),
      s.hidden ? 1 : 0,
    ]
    for (const n of containerNames) {
      const c = s.containers[n]
      row.push(c ? c.cpu.toFixed(1) : '', c?.memMiB == null ? '' : c.memMiB.toFixed(1))
    }
    lines.push(row.join(','))
  }
  return lines.join('\n')
}
