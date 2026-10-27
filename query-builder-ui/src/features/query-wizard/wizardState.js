import { pickParamValues } from '../../components/ParamsForm.jsx'

export const STEPS = [
  { key: 'draft', label: 'Draft' },
  { key: 'simulate', label: 'Simulate' },
  { key: 'publish', label: 'Publish' },
]

/**
 * State of one wizard session. The server's draft is the source of truth: `draft` is the last
 * DraftResponse, and `savedYaml` is the editor text that produced it, so any edit since then
 * (yamlText !== savedYaml) makes the draft stale for simulate/publish until it's saved again.
 */
export function initialWizardState({ yamlText, resuming }) {
  return {
    phase: resuming ? 'loading' : 'ready', // loading | ready | load-error
    loadError: null,
    notice: null, // info shown on the draft step (e.g. "started from the published version")
    step: 'draft',
    initialYaml: yamlText,
    yamlText,
    savedYaml: null,
    draft: null,
    revision: 0, // bumps on every successful save; simulate results belong to one revision
    draftBusy: false,
    draftError: null,
    stale: null, // message when the server draft disappeared under us
    paramValues: {},
    simulation: null, // { revision, result }
    simulateBusy: false,
    simulateError: null,
    stepNotice: null, // { step, message } shown once when the wizard moved the user to another step
    selectedRoles: [],
    publishBusy: false,
    publishError: null,
    published: null,
    discardBusy: false,
    discardError: null,
  }
}

export function wizardReducer(state, action) {
  switch (action.type) {
    case 'RESUME_START':
      return { ...state, phase: 'loading', loadError: null }

    case 'RESUME_SUCCESS':
      return {
        ...state,
        phase: 'ready',
        draft: action.draft,
        initialYaml: action.yaml,
        yamlText: action.yaml,
        savedYaml: action.yaml,
        revision: state.revision + 1,
        step: action.step,
        stepNotice: action.stepNotice || null,
        // A query that was published before starts the publish step from the roles it had.
        selectedRoles: state.selectedRoles.length ? state.selectedRoles : action.draft.allowedRoles || [],
      }

    case 'RESUME_EMPTY':
      return {
        ...state,
        phase: 'ready',
        draft: null,
        initialYaml: action.yaml,
        yamlText: action.yaml,
        savedYaml: null,
        step: 'draft',
        notice: action.notice,
      }

    case 'RESUME_FAILURE':
      return { ...state, phase: 'load-error', loadError: action.error }

    case 'EDIT_YAML':
      return { ...state, yamlText: action.yamlText, draftError: null }

    case 'GOTO':
      return { ...state, step: action.step, stepNotice: null }

    case 'DRAFT_START':
      return { ...state, draftBusy: true, draftError: null }

    case 'DRAFT_SUCCESS': {
      const params = action.draft.validatedQuery?.params
      return {
        ...state,
        draftBusy: false,
        draft: action.draft,
        savedYaml: action.yaml,
        revision: state.revision + 1,
        stale: null,
        notice: null,
        // A re-draft resets the server's simulated flag, so the old result no longer counts.
        simulation: null,
        simulateError: null,
        publishError: null,
        paramValues: pickParamValues(params, state.paramValues),
        selectedRoles: state.selectedRoles.length ? state.selectedRoles : action.draft.allowedRoles || [],
      }
    }

    case 'DRAFT_FAILURE':
      return { ...state, draftBusy: false, draftError: action.error }

    case 'PARAMS_CHANGE':
      return { ...state, paramValues: action.values, simulateError: null }

    case 'SIMULATE_START':
      return { ...state, simulateBusy: true, simulateError: null }

    case 'SIMULATE_SUCCESS':
      return {
        ...state,
        simulateBusy: false,
        simulation: { revision: state.revision, result: action.result },
        draft: state.draft ? { ...state.draft, simulated: true } : state.draft,
      }

    case 'SIMULATE_FAILURE':
      return { ...state, simulateBusy: false, simulateError: action.error }

    // The draft no longer exists on the server (discarded or published elsewhere).
    // Keep the editor text, require a save to re-create it, and send the user back to Draft.
    case 'DRAFT_GONE':
      return {
        ...state,
        draftBusy: false,
        simulateBusy: false,
        publishBusy: false,
        draft: null,
        savedYaml: null,
        simulation: null,
        stale: action.message,
        step: 'draft',
        stepNotice: null,
      }

    // The server's draft changed under us (e.g. a DRAFT_INVALID or NOT_SIMULATED answer): adopt
    // the server's copy, including its text, since it's the one simulate/publish act on.
    case 'DRAFT_RESYNC':
      return {
        ...state,
        simulateBusy: false,
        publishBusy: false,
        draft: action.draft,
        yamlText: action.yaml,
        savedYaml: action.yaml,
        revision: state.revision + 1,
        simulation: null,
        paramValues: pickParamValues(action.draft.validatedQuery?.params, state.paramValues),
        step: action.step,
        stepNotice: { step: action.step, message: action.message },
      }

    case 'ROLES_CHANGE':
      return { ...state, selectedRoles: action.roles, publishError: null }

    case 'PUBLISH_START':
      return { ...state, publishBusy: true, publishError: null }

    case 'PUBLISH_SUCCESS':
      return { ...state, publishBusy: false, published: action.result, step: 'publish' }

    case 'PUBLISH_FAILURE':
      return { ...state, publishBusy: false, publishError: action.error }

    case 'DISCARD_START':
      return { ...state, discardBusy: true, discardError: null }

    case 'DISCARD_FAILURE':
      return { ...state, discardBusy: false, discardError: action.error }

    default:
      return state
  }
}

/** Derived flags shared by the modal, its steps and its footer. */
export function deriveWizard(state) {
  const saved = state.savedYaml !== null
  const dirty = !saved || state.yamlText !== state.savedYaml
  const unsavedChanges = saved ? state.yamlText !== state.savedYaml : state.yamlText !== state.initialYaml
  const validated = state.draft?.status === 'VALIDATED'
  const canSimulate = !!state.draft && validated && !dirty && !state.published
  const canPublish = canSimulate && !!state.draft?.simulated
  const busy = state.draftBusy || state.simulateBusy || state.publishBusy || state.discardBusy
  return {
    dirty,
    unsavedChanges: unsavedChanges && !state.published,
    validated,
    canSimulate,
    canPublish,
    busy,
    reachable: {
      draft: !state.published,
      simulate: canSimulate,
      publish: canPublish,
    },
    completed: {
      draft: validated && !dirty,
      simulate: !!state.draft?.simulated && !dirty,
      publish: !!state.published,
    },
  }
}

/**
 * Picks the step to open when resuming a draft from the list. Falls back to the furthest step the
 * draft actually allows, and says why.
 */
export function resolveResumeStep(requested, draft) {
  if (requested === 'publish' || requested === 'simulate') {
    if (draft.status !== 'VALIDATED') {
      return { step: 'draft', stepNotice: { step: 'draft', message: 'This draft has validation errors. Fix and save it before simulating.' } }
    }
    if (requested === 'publish' && !draft.simulated) {
      return { step: 'simulate', stepNotice: { step: 'simulate', message: 'This draft changed since it was last simulated. Run a simulation before publishing.' } }
    }
  }
  return { step: requested || 'draft' }
}
