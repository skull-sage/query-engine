// A small tokenizer for PostgreSQL text: not a parser. It splits SQL into pieces so sqlGuard.js can look
// at the words that really are code, ignoring the ones inside strings, quoted names and comments.
//
// This is a port of the backend's SqlLexer (query-engine/.../validation/SqlLexer.java). Both run the
// cases in query-engine/src/test/resources/sql-guard-cases.json, so change the two together.
//
// Token kinds: WORD (unquoted, lower-cased the way Postgres folds names, ASCII only), QUOTED_IDENT,
// UNICODE_IDENT (U&"..."), STRING (any literal, content dropped), PARAM (:name), CAST (::), NUMBER,
// SYMBOL (one punctuation character). Assumes standard_conforming_strings = on, the Postgres default.

export const Kind = Object.freeze({
  WORD: 'WORD',
  QUOTED_IDENT: 'QUOTED_IDENT',
  UNICODE_IDENT: 'UNICODE_IDENT',
  STRING: 'STRING',
  PARAM: 'PARAM',
  CAST: 'CAST',
  NUMBER: 'NUMBER',
  SYMBOL: 'SYMBOL',
})

const isSpace = (c) => c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '\f' || c === '\u000B'
const isDigit = (c) => c >= '0' && c <= '9'
const isAsciiLetter = (c) => (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
const isDollarTagStart = (c) => c === '_' || isAsciiLetter(c) || c >= '\u0080'
const isIdentStart = isDollarTagStart
const isIdentPart = (c) => isIdentStart(c) || isDigit(c) || c === '$'
const isParamStart = (c) => c === '_' || isAsciiLetter(c)
const isParamPart = (c) => isParamStart(c) || isDigit(c)

/** Lower-cases ASCII letters only, which is how Postgres folds unquoted names. */
export function asciiLower(text) {
  return text.replace(/[A-Z]/g, (c) => String.fromCharCode(c.charCodeAt(0) + 32))
}

/** Index just past the closing quote of the literal opened at `open`, or -1 if it never closes. */
function endOfQuoted(sql, open, quote, backslashEscapes) {
  const n = sql.length
  let j = open + 1
  while (j < n) {
    const d = sql[j]
    if (backslashEscapes && d === '\\') {
      j += 2
    } else if (d === quote) {
      if (j + 1 < n && sql[j + 1] === quote) j += 2
      else return j + 1
    } else {
      j++
    }
  }
  return -1
}

/** Index of the closing `$` of a dollar-quote opening tag (`$$` or `$name$`) at `at`, or -1. */
function dollarTagEnd(sql, at) {
  const n = sql.length
  let j = at + 1
  if (j < n && sql[j] === '$') return j
  if (j < n && isDollarTagStart(sql[j])) {
    j++
    while (j < n && (isDollarTagStart(sql[j]) || isDigit(sql[j]))) j++
    if (j < n && sql[j] === '$') return j
  }
  return -1
}

/** Returns { tokens: [{ kind, text, offset }], unterminated: null | { what, offset } }. */
export function lex(sql) {
  const tokens = []
  const n = sql.length
  let i = 0
  const unterminated = (what, offset) => ({ tokens, unterminated: { what, offset } })

  while (i < n) {
    const c = sql[i]
    const next = i + 1 < n ? sql[i + 1] : '\0'

    if (isSpace(c)) {
      i++
    } else if (c === '-' && next === '-') {
      i += 2
      while (i < n && sql[i] !== '\n' && sql[i] !== '\r') i++
    } else if (c === '/' && next === '*') {
      const start = i
      let depth = 1
      i += 2
      while (i < n && depth > 0) {
        if (sql.startsWith('/*', i)) {
          depth++
          i += 2
        } else if (sql.startsWith('*/', i)) {
          depth--
          i += 2
        } else {
          i++
        }
      }
      if (depth > 0) return unterminated('comment', start)
    } else if (c === "'") {
      const end = endOfQuoted(sql, i, "'", false)
      if (end < 0) return unterminated('string', i)
      tokens.push({ kind: Kind.STRING, text: '', offset: i })
      i = end
    } else if (c === '"') {
      const end = endOfQuoted(sql, i, '"', false)
      if (end < 0) return unterminated('quoted name', i)
      tokens.push({ kind: Kind.QUOTED_IDENT, text: sql.substring(i + 1, end - 1).replaceAll('""', '"'), offset: i })
      i = end
    } else if (c === '$') {
      const tagEnd = dollarTagEnd(sql, i)
      if (tagEnd < 0) {
        tokens.push({ kind: Kind.SYMBOL, text: '$', offset: i })
        i++
      } else {
        const tag = sql.substring(i, tagEnd + 1)
        const close = sql.indexOf(tag, tagEnd + 1)
        if (close < 0) return unterminated('dollar-quoted string', i)
        tokens.push({ kind: Kind.STRING, text: '', offset: i })
        i = close + tag.length
      }
    } else if (c === ':') {
      if (next === ':') {
        tokens.push({ kind: Kind.CAST, text: '::', offset: i })
        i += 2
      } else if (isParamStart(next)) {
        let j = i + 2
        while (j < n && isParamPart(sql[j])) j++
        tokens.push({ kind: Kind.PARAM, text: sql.substring(i + 1, j), offset: i })
        i = j
      } else {
        tokens.push({ kind: Kind.SYMBOL, text: ':', offset: i })
        i++
      }
    } else if (isDigit(c) || (c === '.' && isDigit(next))) {
      let j = i
      while (j < n && isDigit(sql[j])) j++
      if (j < n && sql[j] === '.' && !(j + 1 < n && sql[j + 1] === '.')) {
        j++
        while (j < n && isDigit(sql[j])) j++
      }
      if (j < n && (sql[j] === 'e' || sql[j] === 'E')) {
        let k = j + 1
        if (k < n && (sql[k] === '+' || sql[k] === '-')) k++
        if (k < n && isDigit(sql[k])) {
          while (k < n && isDigit(sql[k])) k++
          j = k
        }
      }
      tokens.push({ kind: Kind.NUMBER, text: sql.substring(i, j), offset: i })
      i = j
    } else if (isIdentStart(c)) {
      let j = i + 1
      while (j < n && isIdentPart(sql[j])) j++
      const lower = asciiLower(sql.substring(i, j))
      const after = j < n ? sql[j] : '\0'
      const afterNext = j + 1 < n ? sql[j + 1] : '\0'

      if (after === "'" && (lower === 'e' || lower === 'b' || lower === 'x' || lower === 'n')) {
        // E'..' lets a backslash escape the quote; B'..', X'..', N'..' are plain strings.
        const end = endOfQuoted(sql, j, "'", lower === 'e')
        if (end < 0) return unterminated('string', i)
        tokens.push({ kind: Kind.STRING, text: '', offset: i })
        i = end
      } else if (lower === 'u' && after === '&' && afterNext === "'") {
        const end = endOfQuoted(sql, j + 1, "'", false)
        if (end < 0) return unterminated('string', i)
        tokens.push({ kind: Kind.STRING, text: '', offset: i })
        i = end
      } else if (lower === 'u' && after === '&' && afterNext === '"') {
        const end = endOfQuoted(sql, j + 1, '"', false)
        if (end < 0) return unterminated('quoted name', i)
        tokens.push({ kind: Kind.UNICODE_IDENT, text: sql.substring(j + 2, end - 1), offset: i })
        i = end
      } else {
        tokens.push({ kind: Kind.WORD, text: lower, offset: i })
        i = j
      }
    } else {
      tokens.push({ kind: Kind.SYMBOL, text: c, offset: i })
      i++
    }
  }
  return { tokens, unterminated: null }
}

/** "line 2, column 7" for a character offset in `sql`. */
export function location(sql, offset) {
  let line = 1
  let column = 1
  for (let k = 0; k < offset && k < sql.length; k++) {
    const ch = sql[k]
    if (ch === '\n') {
      line++
      column = 1
    } else if (ch !== '\r') {
      column++
    }
  }
  return `line ${line}, column ${column}`
}
