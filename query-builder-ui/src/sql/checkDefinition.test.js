import { describe, expect, it } from 'vitest'
import { DOMAINS } from '../domains.js'
import { checkDefinition } from './checkDefinition.js'

const codes = (yamlText) => checkDefinition(yamlText).problems.map((p) => p.code)
const definition = (body) => `queryName: orders.byStatus\n${body}\n`

describe('checkDefinition', () => {
  it('passes every sample draft and the streaming demo query the UI ships', () => {
    for (const domain of DOMAINS) {
      expect(checkDefinition(domain.sampleDraft)?.problems, `${domain.key} sampleDraft`).toEqual([])
      if (domain.streamDemo) {
        const s = domain.streamDemo
        const params = Object.entries(s.params).map(([k, v]) => `  ${k}: ${v}`).join('\n')
        const yaml = `queryName: ${s.queryName}\nsql: |-\n${s.sql.split('\n').map((l) => `  ${l}`).join('\n')}\nparams:\n${params}\nfetchSize: ${s.fetchSize}\n`
        expect(checkDefinition(yaml)?.problems, `${domain.key} streamDemo`).toEqual([])
      }
    }
  })

  it('has nothing to say about an empty editor', () => {
    expect(checkDefinition('')).toBeNull()
    expect(checkDefinition('  \n ')).toBeNull()
    expect(checkDefinition(null)).toBeNull()
  })

  it('flags YAML that does not parse, with the line', () => {
    const [p] = checkDefinition('queryName: orders.x\nsql: [\n').problems
    expect(p.code).toBe('YAML_INVALID')
    expect(p.message).toMatch(/line \d+/)
  })

  it('flags a definition that is not a mapping', () => {
    expect(codes('- a\n- b\n')).toEqual(['DEFINITION_INVALID'])
    expect(codes('just text')).toEqual(['DEFINITION_INVALID'])
  })

  it('flags a missing or malformed queryName', () => {
    expect(codes('sql: SELECT 1\nparams: {}\n')).toEqual(['INVALID_QUERY_NAME'])
    expect(codes('queryName: orders\nsql: SELECT 1\nparams: {}\n')).toEqual(['INVALID_QUERY_NAME'])
    expect(codes('queryName: "a.b.c"\nsql: SELECT 1\nparams: {}\n')).toEqual(['INVALID_QUERY_NAME'])
  })

  it('flags a missing sql', () => {
    expect(codes(definition('params: {}'))).toEqual(['MISSING_SQL'])
    expect(codes(definition('sql: "  "\nparams: {}'))).toEqual(['MISSING_SQL'])
  })

  it('reports the SQL rules with the SQL_ codes', () => {
    const p = checkDefinition(definition('sql: DELETE FROM order_projection\nparams: {}')).problems
    expect(p.map((x) => x.code)).toEqual(['SQL_NOT_SELECT'])
    expect(p[0].field).toBe('sql')
    expect(codes(definition('sql: SELECT pg_sleep(30)\nparams: {}'))).toEqual(['SQL_FORBIDDEN_FUNCTION'])
    expect(codes(definition('sql: "SELECT 1; DELETE FROM t"\nparams: {}'))).toEqual(['SQL_MULTIPLE_STATEMENTS', 'SQL_DATA_MODIFYING'])
  })

  it('flags params used but not declared, declared but not used, and of an unknown type', () => {
    expect(codes(definition('sql: SELECT * FROM t WHERE a = :a AND b = :b\nparams:\n  a: String'))).toEqual(['UNDECLARED_PARAM'])
    expect(codes(definition('sql: SELECT 1\nparams:\n  a: String'))).toEqual(['UNUSED_PARAM'])
    expect(codes(definition('sql: SELECT * FROM t WHERE a = :a\nparams:\n  a: varchar'))).toEqual(['UNKNOWN_PARAM_TYPE'])
    expect(codes(definition('sql: SELECT * FROM t WHERE a = :a\nparams:\n  a: INT'))).toEqual([])
  })

  it('names the param on a param problem', () => {
    const [p] = checkDefinition(definition('sql: SELECT * FROM t WHERE a = :a\nparams: {}')).problems
    expect(p).toMatchObject({ code: 'UNDECLARED_PARAM', param: 'a', field: 'params' })
  })

  it('does not bury an unterminated string under param complaints', () => {
    expect(codes(definition("sql: \"SELECT ':a\"\nparams:\n  x: String"))).toEqual(['SQL_UNTERMINATED'])
  })

  it('does not count a cast or a colon inside a string as a param', () => {
    expect(codes(definition("sql: \"SELECT status::text, ':nope' FROM t\"\nparams: {}"))).toEqual([])
  })

  it('flags params that is not a mapping', () => {
    expect(codes(definition('sql: SELECT 1\nparams: [a, b]'))).toEqual(['DEFINITION_INVALID'])
  })

  it('flags a bad fetchSize', () => {
    expect(codes(definition('sql: SELECT 1\nparams: {}\nfetchSize: 0'))).toEqual(['INVALID_FETCH_SIZE'])
    expect(codes(definition('sql: SELECT 1\nparams: {}\nfetchSize: 20000'))).toEqual(['INVALID_FETCH_SIZE'])
    expect(codes(definition('sql: SELECT 1\nparams: {}\nfetchSize: 2.5'))).toEqual(['INVALID_FETCH_SIZE'])
    expect(codes(definition('sql: SELECT 1\nparams: {}\nfetchSize: 500'))).toEqual([])
  })

  it('collects several problems at once, in field order', () => {
    const yaml = 'queryName: nope\nsql: "SELECT :a FROM t FOR UPDATE"\nparams:\n  b: wat\nfetchSize: 0\n'
    expect(codes(yaml)).toEqual(['INVALID_QUERY_NAME', 'SQL_LOCKING_CLAUSE', 'UNDECLARED_PARAM', 'UNUSED_PARAM', 'UNKNOWN_PARAM_TYPE', 'INVALID_FETCH_SIZE'])
  })
})
