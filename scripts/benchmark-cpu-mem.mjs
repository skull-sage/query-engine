#!/usr/bin/env node

/**
 * Isolated Concurrency, CPU & Memory Benchmark Audit Tool
 *
 * Compares /data-query (Plain Batch) vs /data-query-streaming (Reactive Streaming)
 * under varying concurrent loads with an identical batch size (e.g. 100).
 *
 * Key guarantees:
 *  1. Non-invasive out-of-band Docker metrics collection (zero agent overhead inside containers).
 *  2. Full isolation: strictly sequential execution with forced cooldown periods between runs so
 *     one workload's GC, buffer cache, or connection pool usage never skews the other.
 *  3. Measures baseline, peak, and average CPU & Memory for both the Spring Boot app and PostgreSQL.
 */

import { spawn } from 'node:child_process'
import { performance } from 'node:perf_hooks'

const DEFAULT_BASE_URL = process.env.ORDER_QUERY_URL || 'http://localhost:8081'
const DEFAULT_ROLES = 'data-platform'
const QUERY_STREAM = 'orders.historyByDate'
const QUERY_BATCH = 'orders.historyByBatch'

const APP_CONTAINER = 'order-query-app'
const PG_CONTAINER = 'order-query-postgres'
const ANSI = /\x1b\[[0-9;?]*[A-Za-z]/g

function parseArgs() {
  const args = process.argv.slice(2)
  const options = {
    url: DEFAULT_BASE_URL,
    concurrencyList: [10, 40, 50, 100],
    batchSize: 100, // same batch size for both!
    rowsPerClient: 5000, // rows each concurrent client fetches
    cooldownSec: 4,
    quick: false,
  }

  for (let i = 0; i < args.length; i++) {
    switch (args[i]) {
      case '--url':
        options.url = args[++i]
        break
      case '--concurrency':
      case '-c':
        options.concurrencyList = args[++i].split(',').map((n) => parseInt(n.trim(), 10))
        break
      case '--batch-size':
      case '-b':
        options.batchSize = parseInt(args[++i], 10)
        break
      case '--rows':
      case '-r':
        options.rowsPerClient = parseInt(args[++i], 10)
        break
      case '--cooldown':
        options.cooldownSec = parseInt(args[++i], 10)
        break
      case '--quick':
        options.quick = true
        options.concurrencyList = [10, 40]
        options.rowsPerClient = 2500
        break
    }
  }
  return options
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function parseMemMiB(text) {
  if (!text) return 0
  const m = /^\s*([\d.]+)\s*([A-Za-z]+)\s*$/.exec(text)
  if (!m) return 0
  const val = parseFloat(m[1])
  const unit = m[2].toLowerCase()
  if (unit === 'gib' || unit === 'gb') return val * 1024
  if (unit === 'kib' || unit === 'kb') return val / 1024
  if (unit === 'b') return val / (1024 * 1024)
  return val
}

/** Non-invasive persistent Docker stats stream daemon */
class DockerStatsMonitor {
  constructor() {
    this.child = null
    this.latest = {}
    this.activeSamples = []
    this.recording = false
  }

  start() {
    this.child = spawn('docker', ['stats', '--format', '{{json .}}'], { stdio: ['ignore', 'pipe', 'ignore'] })
    let carry = ''

    this.child.stdout.on('data', (chunk) => {
      carry += chunk.toString('utf8').replace(ANSI, '\n')
      const lines = carry.split('\n')
      carry = lines.pop()

      for (const line of lines) {
        if (!line.trim().startsWith('{')) continue
        try {
          const row = JSON.parse(line.trim())
          const [used] = String(row.MemUsage || '').split('/')
          const sample = {
            cpu: parseFloat(row.CPUPerc) || 0,
            memMiB: parseMemMiB(used),
            updatedAt: performance.now(),
          }
          this.latest[row.Name] = sample

          if (this.recording) {
            this.activeSamples.push({
              time: performance.now(),
              appCpu: this.latest[APP_CONTAINER]?.cpu || 0,
              appMem: this.latest[APP_CONTAINER]?.memMiB || 0,
              pgCpu: this.latest[PG_CONTAINER]?.cpu || 0,
              pgMem: this.latest[PG_CONTAINER]?.memMiB || 0,
            })
          }
        } catch {
          // ignore parsing error
        }
      }
    })
  }

  getSnapshot() {
    return {
      appMem: this.latest[APP_CONTAINER]?.memMiB || 0,
      appCpu: this.latest[APP_CONTAINER]?.cpu || 0,
      pgMem: this.latest[PG_CONTAINER]?.memMiB || 0,
      pgCpu: this.latest[PG_CONTAINER]?.cpu || 0,
    }
  }

  beginRecording() {
    this.activeSamples = []
    this.recording = true
    // Seed initial sample
    this.activeSamples.push({
      time: performance.now(),
      appCpu: this.latest[APP_CONTAINER]?.cpu || 0,
      appMem: this.latest[APP_CONTAINER]?.memMiB || 0,
      pgCpu: this.latest[PG_CONTAINER]?.cpu || 0,
      pgMem: this.latest[PG_CONTAINER]?.memMiB || 0,
    })
  }

  endRecording() {
    this.recording = false
    const samples = this.activeSamples
    if (!samples.length) {
      const snap = this.getSnapshot()
      return {
        appPeakCpu: snap.appCpu,
        appAvgCpu: snap.appCpu,
        appPeakMem: Math.round(snap.appMem),
        pgPeakCpu: snap.pgCpu,
        pgAvgCpu: snap.pgCpu,
        pgPeakMem: Math.round(snap.pgMem),
      }
    }

    const appCpus = samples.map((s) => s.appCpu)
    const appMems = samples.map((s) => s.appMem)
    const pgCpus = samples.map((s) => s.pgCpu)
    const pgMems = samples.map((s) => s.pgMem)

    return {
      appPeakCpu: Math.max(...appCpus),
      appAvgCpu: Math.round(appCpus.reduce((a, b) => a + b, 0) / appCpus.length),
      appPeakMem: Math.round(Math.max(...appMems)),
      pgPeakCpu: Math.max(...pgCpus),
      pgAvgCpu: Math.round(pgCpus.reduce((a, b) => a + b, 0) / pgCpus.length),
      pgPeakMem: Math.round(Math.max(...pgMems)),
    }
  }

  stop() {
    if (this.child) {
      this.child.kill()
      this.child = null
    }
  }
}

/** Single streaming worker */
async function streamWorker(url, rows) {
  const start = performance.now()
  let rowsReceived = 0
  let status = 0
  let error = null

  try {
    const res = await fetch(`${url}/data-query-streaming`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/x-ndjson',
        'X-Roles': DEFAULT_ROLES,
      },
      body: JSON.stringify({
        name: QUERY_STREAM,
        params: { since: '2026-01-01' },
      }),
    })

    status = res.status
    if (!res.ok) {
      return { status, rows: 0, durationMs: performance.now() - start, error: await res.text() }
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (rowsReceived < rows) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop()
      rowsReceived += lines.filter((l) => l.trim().length > 0).length
    }
    reader.cancel()
  } catch (err) {
    error = err.message
  }

  return { status: status || 500, rows: rowsReceived, durationMs: performance.now() - start, error }
}

/** Single plain batch worker with LIMIT and OFFSET */
async function batchWorker(url, batchSize, rows) {
  const start = performance.now()
  let rowsReceived = 0
  let offset = 0
  let error = null
  let lastStatus = 200

  while (rowsReceived < rows) {
    const currentLimit = Math.min(batchSize, rows - rowsReceived)

    try {
      const res = await fetch(`${url}/data-query`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          'X-Roles': DEFAULT_ROLES,
        },
        body: JSON.stringify({
          name: QUERY_BATCH,
          params: { since: '2026-01-01', limit: currentLimit, offset },
        }),
      })

      lastStatus = res.status
      if (!res.ok) {
        error = await res.text()
        break
      }

      const data = await res.json()
      if (!Array.isArray(data) || data.length === 0) break

      rowsReceived += data.length
      offset += data.length
      if (data.length < currentLimit) break
    } catch (err) {
      error = err.message
      break
    }
  }

  return { status: lastStatus, rows: rowsReceived, durationMs: performance.now() - start, error }
}

/** Runs one benchmark isolated from other workloads */
async function runIsolatedBenchmark(monitor, { mode, concurrency, batchSize, rows, url, cooldownSec }) {
  console.log(`\n---------------------------------------------------------------------------------`)
  console.log(`[ISOLATION] Cooling down for ${cooldownSec}s to settle memory, connections & GC...`)
  await sleep(cooldownSec * 1000)

  const baseline = monitor.getSnapshot()
  console.log(`[BASELINE] App Mem: ${baseline.appMem.toFixed(1)} MiB | PG Mem: ${baseline.pgMem.toFixed(1)} MiB`)
  console.log(`[EXECUTION] Launching ${concurrency} concurrent ${mode.toUpperCase()} workers (batch/fetchSize: ${batchSize}, rows/client: ${rows})...`)

  monitor.beginRecording()

  const t0 = performance.now()
  const workers = Array.from({ length: concurrency }, () =>
    mode === 'streaming' ? streamWorker(url, rows) : batchWorker(url, batchSize, rows)
  )

  const results = await Promise.all(workers)
  const totalMs = performance.now() - t0
  const metrics = monitor.endRecording()

  const successful = results.filter((r) => r.status === 200 && !r.error)
  const busy503 = results.filter((r) => r.status === 503)
  const totalRows = results.reduce((acc, r) => acc + r.rows, 0)
  const throughput = totalMs > 0 ? Math.round((totalRows / totalMs) * 1000) : 0

  return {
    mode: mode === 'streaming' ? 'Reactive Streaming' : 'Plain Batch',
    concurrency,
    batchSize,
    successRate: `${((successful.length / concurrency) * 100).toFixed(0)}%`,
    busy503: busy503.length,
    throughputRowsSec: throughput,
    durationSec: (totalMs / 1000).toFixed(1),
    appPeakCpu: `${metrics.appPeakCpu}%`,
    appPeakMemMiB: `${metrics.appPeakMem} MiB`,
    pgPeakCpu: `${metrics.pgPeakCpu}%`,
    pgPeakMemMiB: `${metrics.pgPeakMem} MiB`,
  }
}

async function main() {
  const opts = parseArgs()

  console.log(`=================================================================================`)
  console.log(`   CONCURRENCY AUDIT: PLAIN BATCH vs REACTIVE STREAMING (CPU & MEMORY)`)
  console.log(`   Fixed Batch Size: ${opts.batchSize} rows | Target Rows/Client: ${opts.rowsPerClient}`)
  console.log(`   Concurrency Load Levels: [${opts.concurrencyList.join(', ')}]`)
  console.log(`   Isolation: Strict sequential execution with ${opts.cooldownSec}s cooldown between runs`)
  console.log(`=================================================================================`)

  const monitor = new DockerStatsMonitor()
  monitor.start()
  // Wait 1.5s for initial docker stats stream to connect and populate
  await sleep(1500)

  const powerTable = []

  try {
    for (const c of opts.concurrencyList) {
      // 1. Run Plain Batch
      const batchResult = await runIsolatedBenchmark(monitor, {
        mode: 'batch',
        concurrency: c,
        batchSize: opts.batchSize,
        rows: opts.rowsPerClient,
        url: opts.url,
        cooldownSec: opts.cooldownSec,
      })
      powerTable.push(batchResult)

      // 2. Run Reactive Streaming
      const streamResult = await runIsolatedBenchmark(monitor, {
        mode: 'streaming',
        concurrency: c,
        batchSize: opts.batchSize,
        rows: opts.rowsPerClient,
        url: opts.url,
        cooldownSec: opts.cooldownSec,
      })
      powerTable.push(streamResult)
    }
  } finally {
    monitor.stop()
  }

  console.log('\n\n=================================================================================')
  console.log('                          FINAL AUDIT POWER TABLE')
  console.log('=================================================================================')
  console.table(powerTable)
}

main().catch(console.error)

