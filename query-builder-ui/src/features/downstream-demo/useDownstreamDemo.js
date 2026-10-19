import { useCallback, useEffect, useRef, useState } from 'react'
import { isAbortError } from '../../api/engineClient.js'
import { ensureDemoQuery } from './ensureDemoQuery.js'
import { splitNdjson } from './ndjson.js'

const PAINT_INTERVAL_MS = 50
export const LOG_BATCHES = 8 // how many recent batches the page keeps and shows

export const DEFAULT_SETTINGS = { batchSize: 100, delayMs: 100 }

const INITIAL = {
  phase: 'idle', // idle | preparing | streaming | done | failed | stopped
  prepared: null, // 'existing' | 'created'
  rows: 0,
  batches: 0,
  outOfOrder: 0,
  unparsable: 0,
  startedAt: null,
  endedAt: null,
  error: null,
  log: [],
}

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new DOMException('Aborted', 'AbortError'))
    const t = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    function onAbort() {
      clearTimeout(t)
      reject(new DOMException('Aborted', 'AbortError'))
    }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

/**
 * Plays a slow downstream consumer. It calls POST /data-query with Accept: application/x-ndjson,
 * then takes `batchSize` rows, "processes" them for `delayMs`, and only then reads more. The
 * service is never told to slow down explicitly: the browser stops reading, TCP's window fills,
 * and the service's database cursor stops being fetched. Only counters are kept (no rows), so a
 * 50K-row result costs the page nothing.
 *
 * Nothing runs until start() is called (the page's "Run query" button), which first makes sure the
 * demo query is published, then streams it. Leaving the page aborts the request, which also
 * releases the service's database connection.
 */
export function useDownstreamDemo({ client, demo }) {
  const [settings, setSettings] = useState(DEFAULT_SETTINGS)
  const settingsRef = useRef(settings)
  const [run, setRun] = useState(INITIAL)
  const controllerRef = useRef(null)
  const rowsRef = useRef(0) // rows received so far, read by the resource monitor
  const [runKey, setRunKey] = useState(0) // changes for every run

  const updateSettings = useCallback((patch) => {
    settingsRef.current = { ...settingsRef.current, ...patch }
    setSettings(settingsRef.current)
  }, [])

  const stop = useCallback(() => controllerRef.current?.abort(), [])

  const start = useCallback(async () => {
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    const { signal } = controller
    const live = (patch) => {
      if (!signal.aborted) setRun((r) => ({ ...r, ...(typeof patch === 'function' ? patch(r) : patch) }))
    }
    const finish = (phase, extra = {}) => setRun((r) => ({ ...r, phase, endedAt: performance.now(), ...extra }))

    rowsRef.current = 0
    setRunKey((k) => k + 1)
    setRun({ ...INITIAL, phase: 'preparing' })
    let reader = null
    let rows = 0
    try {
      const prepared = await ensureDemoQuery(client, demo, signal)
      const res = await client.openStream(demo.queryName, demo.args, demo.role, { signal })
      reader = res.body.getReader()
      const decoder = new TextDecoder()
      const startedAt = performance.now()
      live({ phase: 'streaming', prepared, startedAt })

      let carry = ''
      let pending = []
      let streamDone = false
      let batches = 0
      let outOfOrder = 0
      let unparsable = 0
      let lastPaint = 0
      let log = []

      for (;;) {
        const { batchSize, delayMs } = settingsRef.current
        // Read only while we have less than a batch in hand: this is what applies backpressure.
        while (!streamDone && pending.length < batchSize) {
          const { done, value } = await reader.read()
          if (done) {
            carry += decoder.decode()
            if (carry.trim()) pending.push(carry)
            carry = ''
            streamDone = true
          } else {
            const split = splitNdjson(carry, decoder.decode(value, { stream: true }))
            for (const line of split.lines) pending.push(line)
            carry = split.rest
          }
        }
        if (pending.length === 0) break

        const batch = pending.splice(0, batchSize)
        const ids = []
        for (const line of batch) {
          let row
          try {
            row = JSON.parse(line)
          } catch {
            unparsable++
            continue
          }
          rows++
          ids.push(row[demo.idField])
          if (row[demo.idField] !== `${demo.idPrefix}${String(rows).padStart(6, '0')}`) outOfOrder++
        }
        batches++
        rowsRef.current = rows

        const now = performance.now()
        // One entry per batch: a heading with the first and last id, then every id in the batch.
        if (ids.length > 0) log = [...log, `Records from ${ids[0]} to ${ids[ids.length - 1]}\n${ids.join(', ')}`].slice(-LOG_BATCHES)
        if (now - lastPaint >= PAINT_INTERVAL_MS) {
          lastPaint = now
          live({ rows, batches, outOfOrder, unparsable, log })
        }
        if (!(streamDone && pending.length === 0)) await sleep(delayMs, signal)
      }
      if (signal.aborted) return
      finish('done', { rows, batches, outOfOrder, unparsable, log })
    } catch (err) {
      if (signal.aborted || isAbortError(err)) {
        setRun((r) => (controllerRef.current === controller ? { ...r, rows: Math.max(r.rows, rows), phase: 'stopped', endedAt: performance.now() } : r))
        return
      }
      const interrupted = reader !== null
      finish('failed', {
        error: {
          code: err.code || (interrupted ? 'STREAM_INTERRUPTED' : 'ERROR'),
          message: interrupted
            ? `The stream was cut off after ${rows.toLocaleString()} rows. The service aborts the transfer when it fails mid-stream. (${err.message})`
            : err.message,
        },
      })
    }
  }, [client, demo])

  // Leaving the page cancels an in-flight run.
  useEffect(() => () => controllerRef.current?.abort(), [])

  return { run, runKey, rowsRef, settings, updateSettings, start, stop }
}
