import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import dockerStats from './vite-plugins/dockerStats.js'

// Each domain query service gets its own path prefix on the dev server, proxied to that service
// with the prefix stripped: /api/order/engine/list -> http://localhost:8081/engine/list. The UI
// never talks to a service cross-origin, so the services need no CORS setup. Must stay in sync
// with the apiBase values in src/domains.js.
const DOMAIN_SERVICES = {
  '/api/order': process.env.ORDER_QUERY_URL || 'http://localhost:8081',
  '/api/inventory': process.env.INVENTORY_QUERY_URL || 'http://localhost:8082',
}

const proxy = Object.fromEntries(
  Object.entries(DOMAIN_SERVICES).map(([prefix, target]) => [
    prefix,
    {
      target,
      changeOrigin: true,
      rewrite: (path) => path.slice(prefix.length) || '/',
    },
  ]),
)

export default defineConfig({
  // dockerStats serves /__dev/stats (dev server only) for the Downstream demo's resource monitor.
  plugins: [react(), tailwindcss(), dockerStats()],
  server: {
    port: 5173,
    // Fail instead of silently moving to 5174 when a stale dev server still holds 5173.
    strictPort: true,
    proxy,
  },
})
