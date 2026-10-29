import { useCallback, useRef, useState } from 'react'
import { isAbortError } from '../../api/engineClient.js'
import { ensureDemoQuery } from '../streaming-demo/ensureDemoQuery.js'

export const LOG_BATCHES = 8 // how many recent batches the page keeps and shows
export const BATCH_SIZES = [10, 50, 100, 500, 1000]
export const DELAYS_MS = [0, 50, 100, 250, 500]

export const DEFAULT_SETTINGS = {
  fetchSize: 100, // corresponds to LIMIT size
  limit: 100,     // corresponds to fetch size
  delayMs: 100,
}

const INITIAL = {
  phase: 'idle', // idle | preparing | loading | done | failed | stopped
  prepared: null, // 'existing' | 'created'
  rows: 0,
  batches: 0,
  outOfOrder: 0,
  avgLatencyMs: 0,
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

/** Synchronizes fetch size to limit size and vice-versa. */
export function syncSettings(current, patch) {
  let next = { ...current, ...patch }
  if ('fetchSize' in patch && !('limit' in patch)) {
    next.limit = patch.fetchSize
  } else if ('limit' in patch && !('fetchSize' in patch)) {
    next.fetchSize = patch.limit
  }
  return next
}

/**
 * Loads data in discrete batches from the frontend using the reactive POST /data-query endpoint
 * (non-streaming, without database cursors). The fetch size input corresponds to the SQL LIMIT
 * size and vice versa.
 */
export function usePlainBatchDemo({ client, demo }) {
  const [settings, setSettings] = useState(DEFAULT_SETTINGS)
  const settingsRef = useRef(settings)
  const [run, setRun] = useState(INITIAL)
  const controllerRef = useRef(null)
  const rowsRef = useRef(0) // rows received so far, read by the resource monitor
  const [runKey, setRunKey] = useState(0) // changes for every run

  const updateSettings = useCallback((patch) => {
    // Synchronize fetchSize and limit: fetch size input corresponds to LIMIT size and vice versa.
    const next = syncSettings(settingsRef.current, patch)
    settingsRef.current = next
    setSettings(next)
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

    try {
      const prepared = await ensureDemoQuery(client, demo, signal)
      const startedAt = performance.now()
      live({ phase: 'loading', prepared, startedAt })

      let offset = 0
      let rowsLoaded = 0
      let batchIndex = 0
      let outOfOrder = 0
      let totalLatency = 0
      let lastIdNum = 0
      let log = []

      while (!signal.aborted && rowsLoaded < demo.expectedRows) {
        const currentLimit = settingsRef.current.limit || settingsRef.current.fetchSize
        const t0 = performance.now()

        // Calls POST /data-query directly via client.invoke without cursor or streaming
        const batchRows = await client.invoke(
          demo.queryName,
          { ...demo.args, limit: currentLimit, offset },
          demo.role,
          { signal }
        )
        const latencyMs = Math.max(1, Math.round(performance.now() - t0))
        totalLatency += latencyMs

        if (!batchRows || !Array.isArray(batchRows) || batchRows.length === 0) {
          break
        }

        // Verify ordering
        for (const row of batchRows) {
          const id = row[demo.idField]
          if (id && id.startsWith(demo.idPrefix)) {
            const num = parseInt(id.slice(demo.idPrefix.length), 10)
            if (lastIdNum > 0 && num !== lastIdNum + 1) {
              outOfOrder++
            }
            lastIdNum = num
          }
        }

        batchIndex++
        rowsLoaded += batchRows.length
        offset += batchRows.length
        rowsRef.current = rowsLoaded

        const entry = {
          batch: batchIndex,
          rows: batchRows.length,
          latencyMs,
          offset: offset - batchRows.length,
          limit: currentLimit,
          firstId: batchRows[0]?.[demo.idField],
          lastId: batchRows[batchRows.length - 1]?.[demo.idField],
        }

        log = [entry, ...log.slice(0, LOG_BATCHES - 1)]

        live({
          rows: rowsLoaded,
          batches: batchIndex,
          outOfOrder,
          avgLatencyMs: Math.round(totalLatency / batchIndex),
          log,
        })

        if (batchRows.length < currentLimit) {
          break
        }

        const { delayMs } = settingsRef.current
        if (delayMs > 0) {
          await sleep(delayMs, signal)
        }
      }

      finish('done')
    } catch (err) {
      if (isAbortError(err)) {
        finish('stopped')
      } else {
        finish('failed', { error: err })
      }
    }
  }, [client, demo])

  return {
    run,
    runKey,
    rowsRef,
    settings,
    updateSettings,
    start,
    stop,
  }
}
