// Checks that a query's SQL is a single read-only SELECT, and finds its :param placeholders. This is the
// browser's copy of the backend's SqlGuard (query-engine/.../validation/SqlGuard.java): it gives the
// author feedback while typing. The server stays the authority and re-checks on draft, simulate, publish
// and every call, so nothing here blocks a save.
//
// Both copies run query-engine/src/test/resources/sql-guard-cases.json; change the two together.
//
// Rules (error codes): SQL_UNTERMINATED, SQL_NOT_SELECT, SQL_MULTIPLE_STATEMENTS, SQL_DATA_MODIFYING,
// SQL_SELECT_INTO, SQL_LOCKING_CLAUSE, SQL_FORBIDDEN_TABLE, SQL_FORBIDDEN_FUNCTION, SQL_UNSUPPORTED_SYNTAX.

import { Kind, lex, location } from './sqlLexer.js'

/** The engine's own table (stored queries with their SQL and allowed roles); author SQL may not name it. */
export const ENGINE_TABLE = 'qe_stored_query'
const MAX_ERRORS = 20

/** Same list as the backend's default. If a deployment changes it, the server's answer wins. */
export const DEFAULT_DENIED_FUNCTIONS = [
  // sessions and settings
  'set_config', 'pg_terminate_backend', 'pg_cancel_backend', 'pg_reload_conf',
  // waiting and locks
  'pg_sleep', 'pg_sleep_for', 'pg_sleep_until', 'pg_advisory_*', 'pg_try_advisory_*',
  // side effects
  'nextval', 'setval', 'pg_notify', 'lo_*', 'dblink*',
  // server files
  'pg_read_file', 'pg_read_binary_file', 'pg_ls_dir', 'pg_stat_file',
  // run other SQL text, or read a table by its name given as a string
  'query_to_xml', 'query_to_xml_and_xmlschema', 'query_to_xmlschema',
  'cursor_to_xml', 'cursor_to_xmlschema',
  'table_to_xml', 'table_to_xml_and_xmlschema', 'table_to_xmlschema',
  'schema_to_xml', 'schema_to_xml_and_xmlschema', 'schema_to_xmlschema',
  'database_to_xml', 'database_to_xml_and_xmlschema', 'database_to_xmlschema',
]

const isWord = (token, word) => token?.kind === Kind.WORD && token.text === word
const isSymbol = (token, symbol) => token?.kind === Kind.SYMBOL && token.text === symbol
const isName = (token) => token?.kind === Kind.WORD || token?.kind === Kind.QUOTED_IDENT

function matcherFor(denied) {
  const exact = new Set()
  const prefixes = []
  for (const entry of denied) {
    const name = entry.trim().toLowerCase()
    if (name.endsWith('*')) prefixes.push(name.slice(0, -1))
    else if (name) exact.add(name)
  }
  return (name) => exact.has(name) || prefixes.some((p) => name.startsWith(p))
}

/** `FOR UPDATE` and `FOR NO KEY UPDATE`: the UPDATE belongs to a locking clause, reported at FOR. */
function isLockingUpdate(tokens, index) {
  if (index === 0) return false
  const prev = tokens[index - 1]
  if (isWord(prev, 'for')) return true
  return isWord(prev, 'key') && index >= 2 && isWord(tokens[index - 2], 'no')
}

function display(sql, token) {
  if (token.kind === Kind.WORD) return token.text.toUpperCase()
  if (token.kind === Kind.QUOTED_IDENT) return `"${token.text}"`
  return sql[token.offset]
}

/**
 * Returns { errors: [{ code, param: null, message }], params: [names in order of first use], lexFailed }.
 * Blank SQL has no errors (the draft's own "sql is required" covers it).
 */
export function analyzeSql(sql, deniedFunctions = DEFAULT_DENIED_FUNCTIONS) {
  if (sql == null || sql.trim() === '') return { errors: [], params: [], lexFailed: false }
  const isDenied = matcherFor(deniedFunctions)
  const error = (code, message) => ({ code, param: null, message })

  const lexed = lex(sql)
  const tokens = lexed.tokens
  const params = []
  for (const token of tokens) {
    if (token.kind === Kind.PARAM && !params.includes(token.text)) params.push(token.text)
  }

  const errors = []
  if (lexed.unterminated) {
    const open = lexed.unterminated
    errors.push(error('SQL_UNTERMINATED', `Unterminated ${open.what} starting at ${location(sql, open.offset)}; it is never closed`))
    return { errors, params, lexFailed: true }
  }

  // Statement shape: one SELECT or WITH.
  const n = tokens.length
  let head = 0
  while (head < n && isSymbol(tokens[head], '(')) head++
  const selectLike = head < n && (isWord(tokens[head], 'select') || isWord(tokens[head], 'with'))
  if (!selectLike) {
    const found = head < n ? `'${display(sql, tokens[head])}' at ${location(sql, tokens[head].offset)}` : 'nothing'
    errors.push(error('SQL_NOT_SELECT', `Only a SELECT query is allowed: the statement must start with SELECT or WITH (found ${found})`))
  }
  for (let i = 0; i < n - 1; i++) {
    if (isSymbol(tokens[i], ';')) {
      errors.push(error('SQL_MULTIPLE_STATEMENTS', `Only one statement is allowed, but there is more text after the ';' at ${location(sql, tokens[i].offset)}`))
      break
    }
  }
  if (!selectLike) return { errors, params, lexFailed: false }

  for (let i = 0; i < n && errors.length < MAX_ERRORS; i++) {
    const token = tokens[i]
    const prev = i > 0 ? tokens[i - 1] : null
    const next = i + 1 < n ? tokens[i + 1] : null
    const afterDot = isSymbol(prev, '.')
    const where = ` (${location(sql, token.offset)})`

    if (token.kind === Kind.UNICODE_IDENT) {
      errors.push(error('SQL_UNSUPPORTED_SYNTAX', `Names written as U&"..." are not supported; write the name directly${where}`))
      continue
    }
    if (token.kind === Kind.WORD && !afterDot) {
      switch (token.text) {
        case 'insert':
        case 'delete':
        case 'merge':
          errors.push(error('SQL_DATA_MODIFYING', `${token.text.toUpperCase()} is not allowed, not even inside WITH: queries may only read${where}`))
          break
        case 'update':
          if (!isLockingUpdate(tokens, i)) {
            errors.push(error('SQL_DATA_MODIFYING', `UPDATE is not allowed, not even inside WITH: queries may only read${where}`))
          }
          break
        case 'into':
          if (!(prev && (isWord(prev, 'insert') || isWord(prev, 'merge')))) {
            errors.push(error('SQL_SELECT_INTO', `SELECT ... INTO creates a table and is not allowed${where}`))
          }
          break
        case 'for':
          if (next && (isWord(next, 'update') || isWord(next, 'share') || isWord(next, 'no') || isWord(next, 'key'))) {
            errors.push(error('SQL_LOCKING_CLAUSE', `Row locking (FOR UPDATE / FOR SHARE) is not allowed: queries may only read${where}`))
          }
          break
        default:
      }
    }
    if (isName(token) && token.text === ENGINE_TABLE) {
      errors.push(error('SQL_FORBIDDEN_TABLE', `The '${ENGINE_TABLE}' table belongs to the query engine and can't be queried${where}`))
    }
    if (isName(token) && next && isSymbol(next, '(') && isDenied(token.text)) {
      errors.push(error('SQL_FORBIDDEN_FUNCTION', `Function '${token.text}' is not allowed${where}`))
    }
  }
  return { errors, params, lexFailed: false }
}
