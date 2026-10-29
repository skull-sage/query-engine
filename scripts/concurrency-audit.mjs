#!/usr/bin/env node

/**
 * Concurrency & Performance Audit Tool
 *
 * Compares Plain Batch (/data-query) vs Reactive Streaming (/data-query-streaming)
 * under varying concurrency levels (e.g. 40, 50, 100) and batch sizes (50, 100, 500, 1000).
 *
 * Usage:
 *   node scripts/concurrency-audit.mjs --concurrency 40 --batch-size 100 --rows 10000
 *   node scripts/concurrency-audit.mjs --matrix
 */

import { performance } from 'node:perf_hooks'

const DEFAULT_BASE_URL = process.env.ORDER_QUERY_URL || 'http://localhost:8081'
const DEFAULT_ROLES = 'data-platform'
const QUERY_STREAM = 'orders.historyByDate'
const QUERY_BATCH = 'orders.historyByBatch'

function parseArgs() {
  const args = process.argv.slice(2)
  const options = {
    url: DEFAULT_BASE_URL,
    concurrency: 40,
    batchSize: 100,
    rows: 5000,
    mode: 'both', // 'batch' | 'stream' | 'both'
    matrix: false,
  }

  for (let i = 0; i < args.length; i++) {
    switch (args[i]) {
      case '--url':
        options.url = args[++i]
        break
      case '--concurrency':
      case '-c':
        options.concurrency = parseInt(args[++i], 10)
        break
      case '--batch-size':
      case '-b':
        options.batchSize = parseInt(args[++i], 10)
        break
      case '--rows':
      case '-r':
        options.rows = parseInt(args[++i], 10)
        break
      case '--mode':
      case '-m':
        options.mode = args[++i]
        break
      case '--matrix':
        options.matrix = true
        break
    }
  }
  return options
}

/** Measures single stream execution for one client */
async function runStreamWorker({ url, batchSize, rows, signal }) {
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
      signal,
    })

    status = res.status
    if (!res.ok) {
      const text = await res.text()
      return { status, rows: 0, durationMs: performance.now() - start, error: text }
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (rowsReceived < rows) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() // keep incomplete trailing line
      rowsReceived += lines.filter((l) => l.trim().length > 0).length
    }
    reader.cancel()
  } catch (err) {
    error = err.message
  }

  return {
    status: status || 500,
    rows: rowsReceived,
    durationMs: performance.now() - start,
    error,
  }
}

/** Measures plain batch pagination for one client */
async function runBatchWorker({ url, batchSize, rows, signal }) {
  const start = performance.now()
  let rowsReceived = 0
  let offset = 0
  let batchCount = 0
  const latencies = []
  let error = null
  let lastStatus = 200

  while (rowsReceived < rows) {
    const currentLimit = Math.min(batchSize, rows - rowsReceived)
    const t0 = performance.now()

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
        signal,
      })

      lastStatus = res.status
      latencies.push(performance.now() - t0)

      if (!res.ok) {
        error = await res.text()
        break
      }

      const data = await res.json()
      if (!Array.isArray(data) || data.length === 0) break

      rowsReceived += data.length
      offset += data.length
      batchCount++

      if (data.length < currentLimit) break
    } catch (err) {
      error = err.message
      break
    }
  }

  return {
    status: lastStatus,
    rows: rowsReceived,
    batches: batchCount,
    durationMs: performance.now() - start,
    avgBatchLatencyMs: latencies.length ? latencies.reduce((a, b) => a + b, 0) / latencies.length : 0,
    error,
  }
}

/** Runs concurrent test */
async function runAudit({ mode, concurrency, batchSize, rows, url }) {
  console.log(`\n===============================================================`)
  console.log(` AUDIT RUN: [${mode.toUpperCase()}] | Concurrency: ${concurrency} | Batch/FetchSize: ${batchSize} | Target Rows/Client: ${rows}`)
  console.log(` Endpoint: ${url} | Total Target Rows: ${(concurrency * rows).toLocaleString()}`)
  console.log(`===============================================================`)

  const t0 = performance.now()
  const workers = Array.from({ length: concurrency }, (_, i) => {
    return mode === 'stream'
      ? runStreamWorker({ url, batchSize, rows })
      : runBatchWorker({ url, batchSize, rows })
  })

  const results = await Promise.all(workers)
  const totalMs = performance.now() - t0

  const successful = results.filter((r) => r.status === 200 && !r.error)
  const busy503 = results.filter((r) => r.status === 503)
  const otherFailed = results.filter((r) => r.status !== 200 && r.status !== 503)
  const totalRows = results.reduce((acc, r) => acc + r.rows, 0)
  const durations = results.map((r) => r.durationMs).sort((a, b) => a - b)

  const p50 = durations[Math.floor(durations.length * 0.5)] || 0
  const p95 = durations[Math.floor(durations.length * 0.95)] || 0
  const p99 = durations[Math.floor(durations.length * 0.99)] || 0
  const throughput = totalMs > 0 ? Math.round((totalRows / totalMs) * 1000) : 0

  return {
    mode,
    concurrency,
    batchSize,
    totalDurationSec: (totalMs / 1000).toFixed(2),
    successRate: `${((successful.length / concurrency) * 100).toFixed(1)}%`,
    busy503Count: busy503.length,
    failedCount: otherFailed.length,
    totalRowsLoaded: totalRows,
    throughputRowsSec: throughput,
    p50Ms: Math.round(p50),
    p95Ms: Math.round(p95),
    p99Ms: Math.round(p99),
  }
}

async function main() {
  const opts = parseArgs()

  if (opts.matrix) {
    const concurrencyLevels = [10, 40, 50, 100]
    const batchSizes = [50, 100, 500, 1000]
    const summary = []

    for (const c of concurrencyLevels) {
      for (const b of batchSizes) {
        // Run both
        const resBatch = await runAudit({ mode: 'batch', concurrency: c, batchSize: b, rows: opts.rows, url: opts.url })
        const resStream = await runAudit({ mode: 'stream', concurrency: c, batchSize: b, rows: opts.rows, url: opts.url })
        summary.push(resBatch, resStream)
      }
    }

    console.log('\n\n========================= SUMMARY MATRIX =========================')
    console.table(summary)
    return
  }

  const reports = []
  if (opts.mode === 'stream' || opts.mode === 'both') {
    reports.push(await runAudit({ mode: 'stream', ...opts }))
  }
  if (opts.mode === 'batch' || opts.mode === 'both') {
    reports.push(await runAudit({ mode: 'batch', ...opts }))
  }

  console.log('\n\n========================= AUDIT SUMMARY =========================')
  console.table(reports)
}

main().catch(console.error)

