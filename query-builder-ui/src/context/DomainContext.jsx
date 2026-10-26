import { createContext, useContext, useMemo } from 'react'
import { createEngineClient } from '../api/engineClient.js'

const DomainContext = createContext(null)

/**
 * Scopes everything below it to one domain query service: the domain's config and an engine
 * client bound to that service. Mount it with key={domain.key} so switching domains unmounts the
 * whole workspace (cancelling its requests and closing its modals) and mounts a fresh one.
 */
export function DomainProvider({ domain, children }) {
  const client = useMemo(
    () =>
      createEngineClient({
        baseUrl: domain.apiBase,
        serviceLabel: `${domain.label} (${domain.service})`,
        serviceUrl: domain.serviceUrl,
        modeVar: domain.modeVar,
      }),
    [domain],
  )
  const value = useMemo(() => ({ domain, client }), [domain, client])
  return <DomainContext.Provider value={value}>{children}</DomainContext.Provider>
}

export function useDomain() {
  const ctx = useContext(DomainContext)
  if (!ctx) throw new Error('useDomain must be used inside <DomainProvider>')
  return ctx
}
