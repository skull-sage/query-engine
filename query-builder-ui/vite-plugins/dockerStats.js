import { spawn } from 'node:child_process'

// Dev-server-only plugin: serves container CPU and memory at GET /__dev/stats so the Downstream
// demo page can chart the service and database while a stream runs. A browser cannot read
// another process's CPU or memory, so the dev server (which runs on the developer's machine, next
// to Docker) does it with `docker stats`.
//
// It only exists under `vite` / `npm run dev` (configureServer), never in a production build.
// It runs one long-lived `docker stats` child, started on the first request and stopped after
// IDLE_STOP_MS without requests, so it costs nothing when the page is not open.
//
// Response: { available, reason?, now, containers: { [name]: { cpu, memBytes, memLimitBytes,
// memPct, updatedAt } } }  (cpu is a percentage of one core, as docker reports it)

const IDLE_STOP_MS = 30_000
const RESTART_DELAY_MS = 5_000
const ANSI = /\x1b\[[0-9;?]*[A-Za-z]/g // docker stats clears the screen between refreshes

const UNITS = { b: 1, kb: 1e3, mb: 1e6, gb: 1e9, tb: 1e12, kib: 1024, mib: 1024 ** 2, gib: 1024 ** 3, tib: 1024 ** 4 }

/** "265.1MiB" -> bytes. Returns null when it cannot be read. */
export function parseSize(text) {
  const m = /^\s*([\d.]+)\s*([A-Za-z]+)\s*$/.exec(text ?? '')
  const unit = m && UNITS[m[2].toLowerCase()]
  return unit ? Number(m[1]) * unit : null
}

/** One `docker stats --format '{{json .}}'` line -> a sample, or null if it is not a container line. */
export function parseStatsLine(line, now = Date.now()) {
  if (!line.startsWith('{')) return null
  let row
  try {
    row = JSON.parse(line)
  } catch {
    return null
  }
  if (!row.Name) return null
  const [used, limit] = String(row.MemUsage ?? '').split('/')
  return {
    name: row.Name,
    cpu: parseFloat(row.CPUPerc) || 0,
    memBytes: parseSize(used),
    memLimitBytes: parseSize(limit),
    memPct: parseFloat(row.MemPerc) || 0,
    updatedAt: now,
  }
}

export default function dockerStatsPlugin() {
  return {
    name: 'dev-docker-stats',
    apply: 'serve',
    configureServer(server) {
      const containers = new Map()
      let child = null
      let reason = null
      let idleTimer = null
      let restartTimer = null
      let stopped = false

      function start() {
        if (child || stopped) return
        let stderr = ''
        let carry = ''
        reason = null
        try {
          child = spawn('docker', ['stats', '--format', '{{json .}}'], { stdio: ['ignore', 'pipe', 'pipe'] })
        } catch (err) {
          reason = `could not start docker: ${err.message}`
          return
        }
        const mine = child
        mine.stdout.on('data', (chunk) => {
          carry += chunk.toString('utf8').replace(ANSI, '\n')
          const lines = carry.split('\n')
          carry = lines.pop()
          for (const line of lines) {
            const sample = parseStatsLine(line.trim())
            if (sample) {
              containers.set(sample.name, sample)
            }
          }
        })
        mine.stderr.on('data', (chunk) => {
          stderr = (stderr + chunk.toString('utf8')).slice(-400)
        })
        mine.on('error', (err) => {
          reason = err.code === 'ENOENT' ? 'the docker CLI was not found on PATH' : `docker failed: ${err.message}`
        })
        mine.on('close', () => {
          if (child === mine) child = null
          containers.clear()
          if (!reason) reason = stderr.trim() || 'docker stats exited'
          if (!stopped && idleTimer) restartTimer = setTimeout(start, RESTART_DELAY_MS)
        })
      }

      function stop() {
        clearTimeout(restartTimer)
        restartTimer = null
        clearTimeout(idleTimer)
        idleTimer = null
        if (child) child.kill('SIGTERM')
        child = null
        containers.clear()
      }

      function touch() {
        if (!idleTimer) start()
        clearTimeout(idleTimer)
        idleTimer = setTimeout(stop, IDLE_STOP_MS)
      }

      server.middlewares.use('/__dev/stats', (req, res) => {
        touch()
        const now = Date.now()
        const body = {
          available: child !== null,
          reason: child ? null : reason,
          now,
          containers: Object.fromEntries(containers),
        }
        res.setHeader('Content-Type', 'application/json')
        res.setHeader('Cache-Control', 'no-store')
        res.end(JSON.stringify(body))
      })

      server.httpServer?.once('close', () => {
        stopped = true
        stop()
      })
    },
  }
}
