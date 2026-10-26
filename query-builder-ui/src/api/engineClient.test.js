import { afterEach, describe, expect, it, vi } from 'vitest'
import { CLIENT_ERRORS, createEngineClient } from './engineClient.js'

const direct = () => createEngineClient({ baseUrl: 'http://localhost:8081', serviceLabel: 'OrderQuery', modeVar: 'VITE_ORDER_QUERY_MODE' })
const proxied = () => createEngineClient({ baseUrl: '/api/order', serviceLabel: 'OrderQuery', serviceUrl: 'http://localhost:8081' })

/** A fetch that fails the real call the way a browser does (TypeError), then answers the no-cors probe. */
function stubFetch({ probeReachable }) {
  const calls = []
  vi.stubGlobal('fetch', (url, init = {}) => {
    calls.push({ url, mode: init.mode })
    if (init.mode === 'no-cors') return probeReachable ? Promise.resolve({ type: 'opaque' }) : Promise.reject(new TypeError('Failed to fetch'))
    return Promise.reject(new TypeError('Failed to fetch'))
  })
  return calls
}

afterEach(() => vi.unstubAllGlobals())

describe('a failed call to a service that is called directly', () => {
  it('is reported as CORS when the service answers a no-cors request', async () => {
    const calls = stubFetch({ probeReachable: true })
    const err = await direct().getRoles().catch((e) => e)
    expect(err.code).toBe(CLIENT_ERRORS.CORS_BLOCKED)
    expect(err.message).toContain('is running at http://localhost:8081')
    expect(err.message).toContain('query-engine.cors.enabled')
    expect(err.message).toContain('VITE_ORDER_QUERY_MODE=proxy')
    expect(calls.map((c) => c.mode)).toEqual([undefined, 'no-cors'])
  })

  it('is reported as unreachable when nothing answers', async () => {
    stubFetch({ probeReachable: false })
    const err = await direct().getRoles().catch((e) => e)
    expect(err.code).toBe(CLIENT_ERRORS.SERVICE_UNREACHABLE)
    expect(err.message).toContain("Can't reach OrderQuery at http://localhost:8081")
  })

  it('applies to streams too', async () => {
    stubFetch({ probeReachable: true })
    const err = await direct().openStream('orders.q', {}, 'role').catch((e) => e)
    expect(err.code).toBe(CLIENT_ERRORS.CORS_BLOCKED)
  })

  it('does not hide an abort', async () => {
    vi.stubGlobal('fetch', () => Promise.reject(new DOMException('Aborted', 'AbortError')))
    const err = await direct().getRoles().catch((e) => e)
    expect(err.name).toBe('AbortError')
  })
})

describe('proxy mode', () => {
  it('never probes cross-origin and names the dev server', async () => {
    const calls = stubFetch({ probeReachable: true })
    const err = await proxied().getRoles().catch((e) => e)
    expect(err.code).toBe(CLIENT_ERRORS.SERVICE_UNREACHABLE)
    expect(err.message).toContain('through the dev server (/api/order -> http://localhost:8081)')
    expect(calls).toHaveLength(1)
  })

  it('treats an empty 5xx from the proxy as the service being down', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve(new Response('', { status: 502 })))
    const err = await proxied().getRoles().catch((e) => e)
    expect(err.code).toBe(CLIENT_ERRORS.SERVICE_UNREACHABLE)
    expect(err.message).toContain('through the dev server')
  })

  it('still reports the engine error when the service answered', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve(new Response(JSON.stringify({ error: 'FORBIDDEN', message: 'no', queryName: 'q' }), { status: 403 })))
    const err = await proxied().getRoles().catch((e) => e)
    expect(err.code).toBe('FORBIDDEN')
  })
})
