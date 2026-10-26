import Alert, { ErrorAlert } from '../../components/Alert.jsx'
import Button from '../../components/Button.jsx'
import MetricsStrip from '../../components/MetricsStrip.jsx'
import ParamsForm from '../../components/ParamsForm.jsx'
import ResultsTable from '../../components/ResultsTable.jsx'
import Spinner from '../../components/Spinner.jsx'

/**
 * Step 2: run the saved draft against the domain's real projection store with sample params.
 * A successful run is what unlocks publishing.
 */
export default function SimulateStep({ state, onParamsChange, onSimulate }) {
  const { draft, simulation, simulateBusy, simulateError, paramValues, revision } = state
  const params = draft?.validatedQuery?.params || []
  const result = simulation?.revision === revision ? simulation.result : null

  return (
    <div className="space-y-5">
      <SqlPreview sql={draft?.validatedQuery?.sql} />

      <section aria-labelledby="simulate-params-heading" className="space-y-3">
        <h3 id="simulate-params-heading" className="text-sm font-semibold text-slate-900">
          Sample params
        </h3>
        <ParamsForm params={params} values={paramValues} onChange={onParamsChange} disabled={simulateBusy} />
        <div className="flex items-center gap-3">
          <Button variant="primary" onClick={onSimulate} loading={simulateBusy}>
            {result ? 'Run again' : 'Run simulation'}
          </Button>
          {draft?.simulated && !result && !simulateBusy && (
            <span className="text-xs text-slate-500">Already simulated earlier, so you can publish. Run it again to see the rows.</span>
          )}
        </div>
      </section>

      <ErrorAlert error={simulateError} title={simulateError?.code === 'INVALID_ARGUMENT' ? 'Check the params' : undefined} />
      {simulateError?.code === 'SQL_INVALID' && (
        <p className="text-xs text-slate-600">The database could not run this SQL, so it cannot be published. Go back to the Draft step, fix it and save again.</p>
      )}

      {result && (
        <section aria-labelledby="simulate-result-heading" className={`space-y-3 transition-opacity ${simulateBusy ? 'opacity-50' : ''}`}>
          <div className="flex items-center gap-2">
            <h3 id="simulate-result-heading" className="text-sm font-semibold text-slate-900">
              Result
            </h3>
            {simulateBusy && <Spinner className="h-3.5 w-3.5 text-slate-400" />}
          </div>
          <MetricsStrip metrics={result.metrics} rowCount={result.rowCount} truncated={result.truncated} />
          {result.truncated && <Alert variant="warning">Only the first {result.rowCount} rows are shown (row cap).</Alert>}
          <ResultsTable rows={result.rows} emptyText="The query ran, but no rows matched these params." />
        </section>
      )}
    </div>
  )
}

export function SqlPreview({ sql }) {
  if (!sql) return null
  return (
    <details className="group rounded-md bg-slate-50 ring-1 ring-slate-200" open>
      <summary className="cursor-pointer select-none px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-500">SQL template</summary>
      <pre className="overflow-x-auto px-3 pb-3 font-mono text-xs leading-relaxed whitespace-pre-wrap text-slate-800">{sql}</pre>
    </details>
  )
}
