import { useEffect, useState } from 'react'

const POLL_MS = 2000
const SLOW_MS = 1000

/**
 * Probes the service with a cheap authoring-side call (GET /query-roles, which uses the service's
 * main pool, not the streaming pool) every couple of seconds. It shows whether the service stays
 * responsive while a stream is running, and when it stops answering.
 */
export function useServiceAvailability(client) {
  const [state, setState] = useState({ status: 'checking', latencyMs: null, lastOkAt: null, failures: 0, error: null, history: [] })

  useEffect(() => {
    let cancelled = false
    let timer = null
    const controller = new AbortController()

    async function probe() {
      const started = performance.now()
      let result
      try {
        await client.getRoles({ signal: controller.signal })
        result = { ok: true, latencyMs: performance.now() - started }
      } catch (err) {
        if (cancelled) return
        result = { ok: false, error: err }
      }
      if (cancelled) return
      setState((prev) => {
        const history = [...prev.history, result.ok ? Math.round(result.latencyMs) : null].slice(-30)
        if (result.ok) {
          const slow = result.latencyMs > SLOW_MS
          return { status: slow ? 'slow' : 'up', latencyMs: result.latencyMs, lastOkAt: Date.now(), failures: 0, error: null, history }
        }
        return { ...prev, status: 'down', failures: prev.failures + 1, error: result.error, history }
      })
      timer = setTimeout(probe, POLL_MS)
    }
    probe()
    return () => {
      cancelled = true
      controller.abort()
      clearTimeout(timer)
    }
  }, [client])

  return state
}
