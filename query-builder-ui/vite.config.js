import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import dockerStats from './vite-plugins/dockerStats.js'

// By default the UI calls each domain service directly from the browser (see src/domains.js), so every
// request shows up in DevTools against the service's own origin, and there is no proxy here.
//
// A domain can be switched to proxy mode instead, for a service that has CORS off
// (query-engine.cors.enabled: false): start the dev server with VITE_<DOMAIN>_QUERY_MODE=proxy and this
// file forwards that domain's prefix to its service, so the browser only ever talks to the dev server.
//   VITE_ORDER_QUERY_MODE=proxy ./dry-run.sh        (or: VITE_ORDER_QUERY_MODE=proxy npm run dev)
// The target is VITE_<DOMAIN>_QUERY_URL, or the default address below. Keep prefixes and defaults in sync
// with src/domains.js.
const DOMAINS = [
  { prefix: '/api/order', mode: 'VITE_ORDER_QUERY_MODE', url: 'VITE_ORDER_QUERY_URL', fallback: 'http://localhost:8081' },
  { prefix: '/api/inventory', mode: 'VITE_INVENTORY_QUERY_MODE', url: 'VITE_INVENTORY_QUERY_URL', fallback: 'http://localhost:8082' },
]

/** One proxy entry per domain in proxy mode: /api/order/engine/list -> http://localhost:8081/engine/list. */
function proxiesFor(env) {
  const proxy = {}
  for (const d of DOMAINS) {
    if (env[d.mode] !== 'proxy') continue
    proxy[d.prefix] = {
      target: (env[d.url] || d.fallback).replace(/\/+$/, ''),
      changeOrigin: true,
      rewrite: (path) => path.slice(d.prefix.length) || '/',
      // When the service aborts a response half way (a stream cut off by an error), pass the abort on to
      // the browser. Without this the proxy leaves the browser's connection open and the page waits forever.
      configure: (p) => {
        p.on('proxyRes', (proxyRes, req, res) => {
          proxyRes.on('aborted', () => res.destroy())
        })
      },
    }
  }
  return proxy
}

export default defineConfig(({ mode }) => ({
  // dockerStats serves /__dev/stats (dev server only) for the Downstream demo's resource monitor.
  plugins: [react(), tailwindcss(), dockerStats()],
  server: {
    port: 5173,
    // Fail instead of silently moving to 5174 when a stale dev server still holds 5173.
    strictPort: true,
    proxy: proxiesFor(loadEnv(mode, process.cwd(), 'VITE_')),
  },
}))
