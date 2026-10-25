import CodeMirror from '@uiw/react-codemirror'
import { yaml } from '@codemirror/lang-yaml'
import { githubLight } from '@uiw/codemirror-theme-github'

const EXTENSIONS = [yaml()]

/**
 * Syntax-highlighted YAML editor for a query entry (queryName, sql, params). `invalid` outlines it in red, so
 * a definition with problems is visible at a glance even when the list of problems has scrolled out of view.
 */
export default function YamlEditor({ value, onChange, readOnly = false, invalid = false, height = '300px', label = 'Query definition (YAML)' }) {
  const ring = readOnly
    ? 'ring-1 ring-slate-200 opacity-80'
    : invalid
      ? 'ring-2 ring-rose-500 focus-within:ring-rose-600'
      : 'ring-1 ring-slate-300 focus-within:ring-2 focus-within:ring-indigo-600'
  return (
    <div className={`overflow-hidden rounded-md ${ring}`} data-invalid={invalid || undefined}>
      <CodeMirror
        value={value}
        height={height}
        extensions={EXTENSIONS}
        theme={githubLight}
        editable={!readOnly}
        readOnly={readOnly}
        onChange={onChange}
        aria-label={label}
        aria-invalid={invalid || undefined}
        basicSetup={{ lineNumbers: true, foldGutter: true, tabSize: 2 }}
      />
    </div>
  )
}
