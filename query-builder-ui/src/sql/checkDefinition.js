import yaml from 'js-yaml'
import { analyzeSql } from './sqlGuard.js'

// Checks the whole query definition in the editor (queryName, sql, params, fetchSize) the way the server
// does when the draft is saved, so an invalid definition is flagged while it is typed instead of only after
// a round trip. The server stays the authority and checks again; nothing here blocks a save.
//
// Problem codes are the server's: the SQL_* rules from sqlGuard.js, plus UNDECLARED_PARAM, UNUSED_PARAM,
// UNKNOWN_PARAM_TYPE, MISSING_SQL, INVALID_QUERY_NAME and INVALID_FETCH_SIZE. YAML_INVALID and
// DEFINITION_INVALID are only found here: the server never sees text that is not a definition.

/** The declared types the engine accepts (case-insensitive), as listed in DraftStep. */
export const PARAM_TYPES = ['String', 'int', 'long', 'decimal', 'boolean', 'date', 'datetime']
/** The service default for `query-engine.stream.max-fetch-size`; the server has the final say. */
export const MAX_FETCH_SIZE = 10000

const QUERY_NAME = /^[a-zA-Z0-9_]+\.[a-zA-Z0-9_]+$/
const KNOWN_TYPES = new Set(PARAM_TYPES.map((t) => t.toLowerCase()))

const problem = (code, message, field, param = null) => ({ code, message, field, param })

/**
 * Returns null while there is nothing to check (empty editor), otherwise { problems }, where each problem is
 * { code, message, field: 'yaml' | 'queryName' | 'sql' | 'params' | 'fetchSize', param }. An empty list
 * means the definition looks valid.
 */
export function checkDefinition(yamlText) {
  if (yamlText == null || yamlText.trim() === '') return null

  let parsed
  try {
    parsed = yaml.load(yamlText)
  } catch (err) {
    const where = err.mark ? ` (line ${err.mark.line + 1})` : ''
    return { problems: [problem('YAML_INVALID', `Invalid YAML: ${err.reason || err.message}${where}`, 'yaml')] }
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { problems: [problem('DEFINITION_INVALID', 'The definition must be a YAML mapping with queryName, sql and params.', 'yaml')] }
  }

  const problems = []
  const { queryName, sql, params, fetchSize } = parsed

  if (typeof queryName !== 'string' || !QUERY_NAME.test(queryName.trim())) {
    problems.push(
      problem(
        'INVALID_QUERY_NAME',
        queryName == null || queryName === ''
          ? 'queryName is missing. Use the form module.name, for example orders.byStatus.'
          : `queryName '${queryName}' must be in the form module.name (letters, digits and underscores).`,
        'queryName',
      ),
    )
  }

  let used = []
  let sqlUsable = false
  if (typeof sql !== 'string' || sql.trim() === '') {
    problems.push(problem('MISSING_SQL', 'sql is required: write the SELECT the query runs.', 'sql'))
  } else {
    const analysis = analyzeSql(sql)
    for (const e of analysis.errors) problems.push(problem(e.code, e.message, 'sql'))
    used = analysis.params
    // When the text could not even be tokenized the placeholders are unreliable, and complaining about them
    // would only bury the real problem.
    sqlUsable = !analysis.lexFailed
  }

  if (params != null && (typeof params !== 'object' || Array.isArray(params))) {
    problems.push(problem('DEFINITION_INVALID', 'params must be a mapping of param name to type, e.g. "status: String".', 'params'))
  } else {
    const declared = params || {}
    if (sqlUsable) {
      for (const name of used) {
        if (!(name in declared)) {
          problems.push(problem('UNDECLARED_PARAM', `:${name} is used in sql but missing from params`, 'params', name))
        }
      }
      for (const name of Object.keys(declared)) {
        if (!used.includes(name)) {
          problems.push(problem('UNUSED_PARAM', `${name} is declared in params but never used in sql`, 'params', name))
        }
      }
    }
    for (const [name, type] of Object.entries(declared)) {
      if (typeof type !== 'string' || !KNOWN_TYPES.has(type.toLowerCase())) {
        problems.push(
          problem('UNKNOWN_PARAM_TYPE', `params declares an unknown type '${type}' for ${name}. Use one of ${PARAM_TYPES.join(', ')}.`, 'params', name),
        )
      }
    }
  }

  if (fetchSize != null) {
    if (typeof fetchSize !== 'number' || !Number.isInteger(fetchSize) || fetchSize < 1 || fetchSize > MAX_FETCH_SIZE) {
      problems.push(problem('INVALID_FETCH_SIZE', `fetchSize must be a whole number between 1 and ${MAX_FETCH_SIZE} (got ${fetchSize})`, 'fetchSize'))
    }
  }

  return { problems }
}
