import { describe, expect, it } from 'vitest'
import { DEFAULT_SETTINGS, syncSettings } from './usePlainBatchDemo.js'

describe('usePlainBatchDemo syncSettings', () => {
  it('synchronizes fetch size to limit size and vice versa', () => {
    let settings = { ...DEFAULT_SETTINGS }
    expect(settings.fetchSize).toBe(100)
    expect(settings.limit).toBe(100)

    // Updating fetchSize updates limit
    settings = syncSettings(settings, { fetchSize: 500 })
    expect(settings.fetchSize).toBe(500)
    expect(settings.limit).toBe(500)

    // Updating limit updates fetchSize
    settings = syncSettings(settings, { limit: 250 })
    expect(settings.limit).toBe(250)
    expect(settings.fetchSize).toBe(250)

    // Updating delayMs preserves limit and fetchSize
    settings = syncSettings(settings, { delayMs: 50 })
    expect(settings.delayMs).toBe(50)
    expect(settings.limit).toBe(250)
    expect(settings.fetchSize).toBe(250)
  })
})
