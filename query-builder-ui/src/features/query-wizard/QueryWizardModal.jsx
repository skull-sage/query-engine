import { useEffect, useReducer, useState } from 'react'
import Modal from '../../components/Modal.jsx'
import Button from '../../components/Button.jsx'
import Alert, { ErrorAlert } from '../../components/Alert.jsx'
import ConfirmDialog from '../../components/ConfirmDialog.jsx'
import Spinner from '../../components/Spinner.jsx'
import Stepper from '../../components/Stepper.jsx'
import { useDomain } from '../../context/DomainContext.jsx'
import { useLeaveGuard } from '../../context/LeaveGuard.jsx'
import { useAbortableRequests } from '../../hooks/useAbortableRequests.js'
import { entryToYaml, paramListToMap, peekQueryName, yamlToDraftEntry } from '../../api/yaml.js'
import { AUTHOR } from '../../config.js'
import { useDefinitionChecks } from '../../sql/useDefinitionChecks.js'
import { problemCount } from './DefinitionProblems.jsx'
import { STEPS, deriveWizard, initialWizardState, resolveResumeStep, wizardReducer } from './wizardState.js'
import DraftStep from './DraftStep.jsx'
import SimulateStep from './SimulateStep.jsx'
import PublishStep from './PublishStep.jsx'

const STEP_DESCRIPTIONS = {
  draft: 'Define the query and save it as a draft.',
  simulate: 'Run the draft against real data before anyone can call it.',
  publish: 'Choose who may call it, then make it live.',
}

const UNSAVED_MESSAGE = "Your edits to this query haven't been saved as a draft. Leave and lose them?"

/**
 * The draft -> simulate -> publish flow in one dialog. Opened for a new query, or for an existing
 * one at a given step (the list's Edit / Simulate / Publish actions all open this same dialog).
 *
 * intent:
 *   { kind: 'new' }
 *   { kind: 'edit', queryName, step: 'draft' | 'simulate' | 'publish', fallbackEntry? }
 *     fallbackEntry ({ sql, params }) seeds the editor when the query has no open draft, e.g. to
 *     start a new draft from a published query.
 *
 * Every request is cancelled when the dialog unmounts. Answers that mean the server's draft has
 * moved on (gone, re-drafted, no longer simulated) send the user to the step that can fix it
 * instead of failing in place.
 */
export default function QueryWizardModal({ intent, knownQueries, onClose, onRun }) {
  const { domain, client } = useDomain()
  const run = useAbortableRequests()
  const resuming = intent.kind === 'edit'
  const [state, dispatch] = useReducer(wizardReducer, null, () =>
    initialWizardState({ yamlText: resuming ? '' : domain.sampleDraft, resuming }),
  )
  const derived = deriveWizard(state)
  const definition = useDefinitionChecks(state.yamlText) // live check of the editor text; the server re-checks on save
  const [confirm, setConfirm] = useState(null) // 'close' | 'discard' | null
  const [resumeAttempt, setResumeAttempt] = useState(0)

  const lockedName = state.draft?.queryName ?? (resuming ? intent.queryName : null)
  const queryName = state.draft?.queryName ?? lockedName

  useLeaveGuard(derived.unsavedChanges, UNSAVED_MESSAGE)

  // ---- resume an existing draft -------------------------------------------------------------
  useEffect(() => {
    if (!resuming) return
    dispatch({ type: 'RESUME_START' })
    run((signal) => client.getDrafted(intent.queryName, { signal }))
      .then((draft) => {
        const { step, stepNotice } = resolveResumeStep(intent.step, draft)
        dispatch({ type: 'RESUME_SUCCESS', draft, yaml: draftToYaml(draft), step, stepNotice })
      })
      .catch((error) => {
        if (error.code !== 'DRAFT_NOT_FOUND') {
          dispatch({ type: 'RESUME_FAILURE', error })
          return
        }
        const fallback = intent.fallbackEntry
        dispatch({
          type: 'RESUME_EMPTY',
          yaml: entryToYaml(intent.queryName, fallback || { sql: '', params: {} }),
          notice: fallback
            ? `${intent.queryName} has no open draft, so this starts a new one from the published version. The published query keeps serving callers until you publish again.`
            : `The draft for ${intent.queryName} no longer exists: it was discarded or published from elsewhere. Write it again and save to start over.`,
        })
      })
    // Re-run only on explicit retry; intent is fixed for the dialog's lifetime.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resumeAttempt])

  // ---- server answers that mean our view of the draft is out of date -------------------------
  function recoverFromLifecycleError(error) {
    const name = state.draft?.queryName
    if (error.code === 'DRAFT_NOT_FOUND') {
      dispatch({
        type: 'DRAFT_GONE',
        message: `${name} no longer has an open draft on ${domain.label}: it was discarded or published from elsewhere. Save the draft again to re-create it.`,
      })
      return true
    }
    if (error.code === 'DRAFT_INVALID' || error.code === 'NOT_SIMULATED') {
      const toDraft = error.code === 'DRAFT_INVALID'
      run((signal) => client.getDrafted(name, { signal }))
        .then((draft) =>
          dispatch({
            type: 'DRAFT_RESYNC',
            draft,
            yaml: draftToYaml(draft),
            step: toDraft || draft.status !== 'VALIDATED' ? 'draft' : 'simulate',
            message: toDraft
              ? 'The saved draft has validation errors, so it was reloaded. Fix and save it to continue.'
              : 'The draft changed since it was simulated (it may have been re-saved elsewhere), so it was reloaded. Run the simulation again before publishing.',
          }),
        )
        .catch((resyncError) => {
          if (resyncError.code === 'DRAFT_NOT_FOUND') recoverFromLifecycleError(resyncError)
          else dispatch({ type: toDraft ? 'SIMULATE_FAILURE' : 'PUBLISH_FAILURE', error: resyncError })
        })
      return true
    }
    return false
  }

  // ---- actions --------------------------------------------------------------------------------
  function saveDraft() {
    let entry
    try {
      entry = yamlToDraftEntry(state.yamlText)
    } catch (e) {
      dispatch({ type: 'DRAFT_FAILURE', error: { code: 'INVALID_YAML', message: e.message } })
      return
    }
    if (lockedName && entry.queryName !== lockedName) {
      dispatch({
        type: 'DRAFT_FAILURE',
        error: {
          code: 'QUERY_NAME_CHANGED',
          message: `This dialog edits ${lockedName}, so queryName must stay ${lockedName}. To create a differently named query, close this and use "New query".`,
        },
      })
      return
    }
    const yaml = state.yamlText
    dispatch({ type: 'DRAFT_START' })
    run((signal) => client.draft(entry, AUTHOR, { signal }))
      .then((draft) => dispatch({ type: 'DRAFT_SUCCESS', draft, yaml }))
      .catch((error) => dispatch({ type: 'DRAFT_FAILURE', error }))
  }

  function simulate() {
    const name = state.draft.queryName
    dispatch({ type: 'SIMULATE_START' })
    run((signal) => client.simulate(name, state.paramValues, { signal }))
      .then((result) => dispatch({ type: 'SIMULATE_SUCCESS', result }))
      .catch((error) => {
        if (!recoverFromLifecycleError(error)) dispatch({ type: 'SIMULATE_FAILURE', error })
      })
  }

  function publish() {
    const name = state.draft.queryName
    dispatch({ type: 'PUBLISH_START' })
    run((signal) => client.publish(name, AUTHOR, state.selectedRoles, { signal }))
      .then((result) => dispatch({ type: 'PUBLISH_SUCCESS', result }))
      .catch((error) => {
        if (!recoverFromLifecycleError(error)) dispatch({ type: 'PUBLISH_FAILURE', error })
      })
  }

  function discard() {
    const name = state.draft.queryName
    dispatch({ type: 'DISCARD_START' })
    run((signal) => client.discard(name, { signal }))
      .then(() => onClose())
      .catch((error) => {
        // Already gone is what we wanted.
        if (error.code === 'DRAFT_NOT_FOUND') onClose()
        else {
          setConfirm(null)
          dispatch({ type: 'DISCARD_FAILURE', error })
        }
      })
  }

  function requestClose() {
    if (derived.unsavedChanges) setConfirm('close')
    else onClose()
  }

  // ---- render ---------------------------------------------------------------------------------
  const nameWarning = intent.kind === 'new' && !state.draft ? existingNameWarning(peekQueryName(state.yamlText), knownQueries) : null
  const stepNotice = state.stepNotice?.step === state.step ? state.stepNotice.message : null
  const ready = state.phase === 'ready'

  return (
    <>
      <Modal
        size="xl"
        title={queryName || 'New query'}
        description={`${domain.label} · ${STEP_DESCRIPTIONS[state.step]}`}
        headerExtra={<span className="hidden rounded-full bg-slate-100 px-2.5 py-1 font-mono text-xs text-slate-600 sm:inline">{domain.service}</span>}
        onRequestClose={requestClose}
        footerStart={
          ready && state.draft && !state.published ? (
            <Button variant="danger" onClick={() => setConfirm('discard')} disabled={derived.busy}>
              Discard draft
            </Button>
          ) : null
        }
        footer={ready ? <Footer state={state} derived={derived} definition={definition} actions={{ saveDraft, publish, requestClose, onClose, onRun, goto: (step) => dispatch({ type: 'GOTO', step }) }} /> : (
          <Button variant="secondary" onClick={onClose}>Close</Button>
        )}
      >
        {state.phase === 'loading' && (
          <div className="flex items-center justify-center gap-2 py-16 text-sm text-slate-500" role="status">
            <Spinner /> Loading the draft for {intent.queryName}…
          </div>
        )}

        {state.phase === 'load-error' && (
          <ErrorAlert
            error={state.loadError}
            title="Couldn't load the draft"
            actions={<Button size="sm" onClick={() => setResumeAttempt((n) => n + 1)}>Try again</Button>}
          />
        )}

        {ready && (
          <div className="space-y-5">
            <Stepper
              steps={STEPS}
              current={state.step}
              reachable={derived.reachable}
              completed={derived.completed}
              disabled={derived.busy}
              onSelect={(step) => dispatch({ type: 'GOTO', step })}
            />
            <ErrorAlert error={state.discardError} title="Couldn't discard the draft" />
            {stepNotice && <Alert variant="info">{stepNotice}</Alert>}

            <div key={state.step} className="motion-safe:animate-step-in">
              {state.step === 'draft' && (
                <DraftStep
                  state={state}
                  derived={derived}
                  definition={definition}
                  nameWarning={nameWarning}
                  onYamlChange={(yamlText) => dispatch({ type: 'EDIT_YAML', yamlText })}
                />
              )}
              {state.step === 'simulate' && (
                <SimulateStep state={state} onParamsChange={(values) => dispatch({ type: 'PARAMS_CHANGE', values })} onSimulate={simulate} />
              )}
              {state.step === 'publish' && (
                <PublishStep state={state} domain={domain} onRolesChange={(roles) => dispatch({ type: 'ROLES_CHANGE', roles })} />
              )}
            </div>
          </div>
        )}
      </Modal>

      {confirm === 'close' && (
        <ConfirmDialog title="Discard unsaved edits?" confirmLabel="Close without saving" onCancel={() => setConfirm(null)} onConfirm={onClose}>
          {state.draft
            ? 'Your latest edits haven\u2019t been saved. The draft keeps its last saved version.'
            : 'This query hasn\u2019t been saved as a draft yet, so closing loses it.'}
        </ConfirmDialog>
      )}
      {confirm === 'discard' && (
        <ConfirmDialog
          title={`Discard the draft for ${state.draft?.queryName}?`}
          confirmLabel="Discard draft"
          busy={state.discardBusy}
          onCancel={() => setConfirm(null)}
          onConfirm={discard}
        >
          The draft is deleted from {domain.label}. A published version of this query, if there is one, keeps serving callers.
        </ConfirmDialog>
      )}
    </>
  )
}

function Footer({ state, derived, definition, actions }) {
  const { step, draft, published } = state

  if (published) {
    return (
      <>
        <Button variant="secondary" onClick={() => actions.onRun(published.queryName)}>
          Test run
        </Button>
        <Button variant="primary" onClick={actions.onClose} data-autofocus>
          Done
        </Button>
      </>
    )
  }

  if (step === 'draft') {
    const nothingToSave = !!draft && !derived.dirty
    // Say plainly why the flow can't go on, so a disabled button is never the only signal.
    const savedInvalid = !!draft && !derived.dirty && draft.status === 'DRAFT'
    const liveProblems = !savedInvalid && derived.dirty ? definition?.problems.length ?? 0 : 0
    const invalidNote = savedInvalid
      ? 'The saved draft is invalid. Fix the problems above and save again to continue.'
      : liveProblems > 0
        ? `${problemCount(liveProblems)} in the definition. You can save it, but it stays an invalid draft.`
        : null
    return (
      <>
        {invalidNote && (
          <span role="status" className="mr-auto text-xs font-medium text-rose-700">
            {invalidNote}
          </span>
        )}
        <Button variant="secondary" onClick={actions.requestClose}>
          Cancel
        </Button>
        {derived.canSimulate ? (
          <Button variant="primary" onClick={() => actions.goto('simulate')}>
            Continue to simulate
          </Button>
        ) : (
          <Button variant="primary" onClick={actions.saveDraft} loading={state.draftBusy} disabled={nothingToSave}>
            {draft ? 'Save changes' : 'Save draft'}
          </Button>
        )}
      </>
    )
  }

  if (step === 'simulate') {
    return (
      <>
        {!derived.canPublish && <span className="text-xs text-slate-500">Run a successful simulation to continue.</span>}
        <Button variant="secondary" onClick={() => actions.goto('draft')} disabled={derived.busy}>
          Back
        </Button>
        <Button variant="primary" onClick={() => actions.goto('publish')} disabled={!derived.canPublish || derived.busy}>
          Continue to publish
        </Button>
      </>
    )
  }

  const count = state.selectedRoles.length
  return (
    <>
      {count === 0 && <span className="text-xs text-slate-500">Select at least one role or group.</span>}
      <Button variant="secondary" onClick={() => actions.goto('simulate')} disabled={derived.busy}>
        Back
      </Button>
      <Button variant="success" onClick={actions.publish} loading={state.publishBusy} disabled={count === 0}>
        {count ? `Publish to ${count} role${count === 1 ? '' : 's'}` : 'Publish'}
      </Button>
    </>
  )
}

function draftToYaml(draft) {
  const entry = draft.entry || { sql: draft.validatedQuery?.sql, params: paramListToMap(draft.validatedQuery?.params), fetchSize: draft.validatedQuery?.fetchSize }
  return entryToYaml(draft.queryName, entry)
}

function existingNameWarning(name, knownQueries) {
  const existing = name ? knownQueries?.get(name) : null
  if (!existing) return null
  if (existing.draft) {
    const holder = existing.draft.author !== AUTHOR ? ` held by ${existing.draft.author}` : ''
    return `${name} already has an open draft${holder}. Saving overwrites it${holder ? ' (or fails if it\u2019s locked)' : ''}. Use its Edit action instead to keep its current content.`
  }
  return `${name} is already published. Saving opens a draft that replaces the published version once you publish it.`
}
