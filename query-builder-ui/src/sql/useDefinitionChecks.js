import { useEffect, useState } from 'react'
import { checkDefinition } from './checkDefinition.js'

const DEBOUNCE_MS = 300

/**
 * The checks for the editor text, recomputed a moment after typing stops: null while there is nothing to check,
 * otherwise { problems } (see checkDefinition).
 */
export function useDefinitionChecks(yamlText) {
  const [result, setResult] = useState(() => checkDefinition(yamlText))
  useEffect(() => {
    const timer = setTimeout(() => setResult(checkDefinition(yamlText)), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [yamlText])
  return result
}
