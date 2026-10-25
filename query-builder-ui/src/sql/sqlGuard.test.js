import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { analyzeSql } from './sqlGuard.js'

// The backend runs this same file (SqlGuardTest.java), so the two lexers cannot drift apart.
const CASES_PATH = new URL('../../../query-engine/src/test/resources/sql-guard-cases.json', import.meta.url)
const cases = JSON.parse(readFileSync(CASES_PATH, 'utf8'))

describe('shared SQL guard cases', () => {
  it('loads the shared case list', () => {
    expect(cases.length).toBeGreaterThan(50)
  })

  for (const c of cases) {
    it(c.name, () => {
      const result = analyzeSql(c.sql)
      const codes = [...new Set(result.errors.map((e) => e.code))].sort()
      expect(codes, `error codes for: ${c.sql}`).toEqual([...c.errors].sort())
      if (c.params) expect(result.params, `params for: ${c.sql}`).toEqual(c.params)
    })
  }
})

describe('analyzeSql', () => {
  it('says where the problem is', () => {
    const { errors } = analyzeSql('SELECT 1\nFROM t\nWHERE pg_sleep(1)')
    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('pg_sleep')
    expect(errors[0].message).toContain('line 3, column 7')
  })

  it('treats blank SQL as nothing to check', () => {
    expect(analyzeSql('  ').errors).toEqual([])
    expect(analyzeSql(null).errors).toEqual([])
  })

  it('uses a replacement denylist when given one', () => {
    expect(analyzeSql('SELECT my_func(1)', ['my_func']).errors).toHaveLength(1)
    expect(analyzeSql('SELECT pg_sleep(1)', ['my_func']).errors).toEqual([])
  })
})
