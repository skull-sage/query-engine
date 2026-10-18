import yaml from 'js-yaml'

// The editor works on a small YAML document: { queryName, sql, params, fetchSize? }. allowed_roles are not
// part of it; they're chosen at publish time.

/** Parses editor YAML into the POST /engine/draft body. Throws an Error with a readable message. */
export function yamlToDraftEntry(yamlText) {
  let parsed
  try {
    parsed = yaml.load(yamlText)
  } catch (err) {
    throw new Error(`Invalid YAML: ${err.reason || err.message}${err.mark ? ` (line ${err.mark.line + 1})` : ''}`)
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('The YAML must be a mapping with queryName, sql and params.')
  }
  const { queryName, sql, params, fetchSize } = parsed
  if (typeof queryName !== 'string' || !queryName.trim()) throw new Error('queryName is required.')
  if (typeof sql !== 'string' || !sql.trim()) throw new Error('sql is required.')
  if (params != null && (typeof params !== 'object' || Array.isArray(params))) {
    throw new Error('params must be a mapping of param name to type, e.g. "status: String".')
  }
  const normalizedParams = {}
  for (const [name, type] of Object.entries(params || {})) {
    if (typeof type !== 'string') throw new Error(`params.${name} must be a type name such as String or int.`)
    normalizedParams[name] = type
  }
  const entry = { queryName: queryName.trim(), sql, params: normalizedParams }
  // Optional; the engine validates the range and reports INVALID_FETCH_SIZE on the draft.
  if (fetchSize != null) {
    if (typeof fetchSize !== 'number' || !Number.isInteger(fetchSize)) throw new Error('fetchSize must be a whole number, e.g. "fetchSize: 500".')
    entry.fetchSize = fetchSize
  }
  return entry
}

/** Best-effort read of queryName from editor YAML; null while the YAML is incomplete. */
export function peekQueryName(yamlText) {
  try {
    const parsed = yaml.load(yamlText)
    return typeof parsed?.queryName === 'string' ? parsed.queryName.trim() : null
  } catch {
    return null
  }
}

/** Builds editor YAML from a draft entry or published query: { sql, params: { name: type } }. */
export function entryToYaml(queryName, { sql, params, fetchSize }) {
  const lines = [`queryName: ${scalar(queryName)}`, 'sql: |-']
  for (const line of String(sql || '').trim().split('\n')) {
    lines.push(`  ${line.trimEnd()}`)
  }
  const entries = Object.entries(params || {})
  if (entries.length === 0) {
    lines.push('params: {}')
  } else {
    lines.push('params:')
    for (const [name, type] of entries) lines.push(`  ${scalar(name)}: ${scalar(type)}`)
  }
  if (fetchSize != null) lines.push(`fetchSize: ${fetchSize}`)
  return `${lines.join('\n')}\n`
}

const DISPLAY_TYPE = {
  STRING: 'String',
  INT: 'int',
  LONG: 'long',
  DECIMAL: 'decimal',
  BOOLEAN: 'boolean',
  DATE: 'date',
  DATETIME: 'datetime',
}

/** Converts a validated param list ([{ name, type: 'STRING' }]) back to the declared map form. */
export function paramListToMap(paramList) {
  const map = {}
  for (const p of paramList || []) map[p.name] = DISPLAY_TYPE[p.type] || p.type
  return map
}

function scalar(value) {
  const s = String(value)
  return /^[A-Za-z_][\w.-]*$/.test(s) ? s : JSON.stringify(s)
}
