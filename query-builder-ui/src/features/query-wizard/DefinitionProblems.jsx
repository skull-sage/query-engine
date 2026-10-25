import Alert from '../../components/Alert.jsx'

const WHERE = { yaml: 'YAML', queryName: 'queryName', sql: 'sql', params: 'params', fetchSize: 'fetchSize' }

/** "1 problem" / "3 problems". */
export function problemCount(n) {
  return `${n} problem${n === 1 ? '' : 's'}`
}

/**
 * The list of problems with the query definition, announced to screen readers as an error. Used twice: live
 * while the author types (title says it is invalid), and after a save that the server stored as DRAFT (the
 * server's own list, which has the final say).
 */
export function ProblemList({ problems, title, children }) {
  return (
    <Alert variant="error" title={title}>
      <ul className="mt-1 list-disc space-y-1 pl-5">
        {problems.map((p, i) => (
          <li key={`${p.code}-${p.param ?? ''}-${i}`}>
            <span className="font-mono text-xs font-semibold">{p.code}</span>
            {p.param && <span className="font-mono text-xs"> ({p.param})</span>}
            {p.field && WHERE[p.field] && <span className="text-xs"> in {WHERE[p.field]}</span>}: {p.message}
          </li>
        ))}
      </ul>
      {children && <p className="mt-2 text-xs">{children}</p>}
    </Alert>
  )
}

/**
 * Live feedback under the YAML editor on whether the definition is valid. It is the browser's copy of the
 * server's rules, so it never blocks saving; the server checks again and has the final say.
 *
 * `checks` is the result of useDefinitionChecks: null while there is nothing to check yet.
 */
export default function DefinitionProblems({ checks }) {
  if (!checks) return null

  if (checks.problems.length === 0) {
    return <p className="text-xs text-emerald-700">The definition looks valid: one read-only SELECT, params declared and used.</p>
  }

  return (
    <ProblemList problems={checks.problems} title={`Invalid query definition: ${problemCount(checks.problems.length)}`}>
      You can still save it, but the draft stays invalid and can&apos;t be simulated or published until these are fixed.
    </ProblemList>
  )
}
