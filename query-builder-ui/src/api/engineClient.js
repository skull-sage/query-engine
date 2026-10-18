// Client for one domain query service's query-engine endpoints. Request/response shapes mirror
// query-engine's dto package (see each service's http/*.http files for the same payloads).
//
// Every call accepts an optional { signal } so callers can cancel in-flight requests when a
// modal closes or the domain changes; a cancelled call rejects with an AbortError, which
// callers should ignore (see isAbortError).

/** Normalized error for every failed call. `code` is the engine's error code when there is one. */
export class EngineError extends Error {
  constructor({ status, code, message, queryName = null, body = null }) {
    super(message)
    this.name = 'EngineError'
    this.status = status
    this.code = code
    this.queryName = queryName
    this.body = body
  }
}

/** Error codes the client produces itself, when the engine never answered with its own error body. */
export const CLIENT_ERRORS = {
  SERVICE_UNREACHABLE: 'SERVICE_UNREACHABLE',
  SERVICE_ERROR: 'SERVICE_ERROR',
  BAD_RESPONSE: 'BAD_RESPONSE',
}

export function isAbortError(err) {
  return err?.name === 'AbortError'
}

/** The engine's ErrorResponse is { error, message, queryName }; Spring's default error body has a timestamp. */
function isEngineErrorBody(body) {
  return body && typeof body === 'object' && typeof body.error === 'string' && 'message' in body && !('timestamp' in body)
}

/** JSON.parse that returns null for an empty body and undefined when the body isn't JSON. */
function parseJson(text) {
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

export function createEngineClient({ baseUrl, serviceLabel }) {
  /** Builds the EngineError for a non-2xx response from its parsed body. */
  function errorFromResponse(status, parsed) {
    if (isEngineErrorBody(parsed)) {
      return new EngineError({
        status,
        code: parsed.error,
        message: parsed.message || `${serviceLabel} rejected the request (HTTP ${status}).`,
        queryName: parsed.queryName ?? null,
        body: parsed,
      })
    }
    // No engine error body. An empty 5xx is the dev-server proxy saying it couldn't connect
    // (502); anything else came from the framework rather than the engine's error handling.
    const proxyFailure = status >= 500 && (parsed === null || parsed === undefined)
    const detail = parsed && typeof parsed === 'object' ? parsed.message || parsed.error : null
    let message
    if (proxyFailure) message = `Can't reach ${serviceLabel}. Check that the service is running (./dry-run.sh).`
    else if (status < 500) message = `${serviceLabel} rejected the request (HTTP ${status})${detail ? `: ${detail}` : '.'}`
    else message = `${serviceLabel} failed to handle the request (HTTP ${status}). Check the service logs.`
    return new EngineError({
      status,
      code: proxyFailure ? CLIENT_ERRORS.SERVICE_UNREACHABLE : CLIENT_ERRORS.SERVICE_ERROR,
      message,
      body: parsed ?? null,
    })
  }

  function unreachable() {
    return new EngineError({
      status: 0,
      code: CLIENT_ERRORS.SERVICE_UNREACHABLE,
      message: `Can't reach ${serviceLabel}. Check that the service is running (./dry-run.sh).`,
    })
  }

  async function request(path, { method = 'POST', body, headers = {}, signal } = {}) {
    let res
    try {
      res = await fetch(`${baseUrl}${path}`, {
        method,
        headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal,
      })
    } catch (err) {
      if (isAbortError(err)) throw err
      throw unreachable()
    }

    const text = await res.text()
    const parsed = parseJson(text)
    if (!res.ok) throw errorFromResponse(res.status, parsed)
    if (parsed === undefined) {
      throw new EngineError({
        status: res.status,
        code: CLIENT_ERRORS.BAD_RESPONSE,
        message: `${serviceLabel} returned a response that isn't JSON.`,
      })
    }
    return parsed
  }

  return {
    /** POST /engine/draft -- { queryName, sql, params } -> DraftResponse */
    draft: (entry, author, opts) =>
      request('/engine/draft', { body: entry, headers: { 'X-Author': author }, ...opts }),

    /** POST /engine/drafted -- { queryName } -> DraftResponse (404 DRAFT_NOT_FOUND when there's no open draft) */
    getDrafted: (queryName, opts) => request('/engine/drafted', { body: { queryName }, ...opts }),

    /** POST /engine/simulate -- { queryName, params } -> SimulateResponse */
    simulate: (queryName, params, opts) => request('/engine/simulate', { body: { queryName, params }, ...opts }),

    /** POST /engine/publish -- { queryName, allowed_roles } -> PublishResponse */
    publish: (queryName, author, allowedRoles, opts) =>
      request('/engine/publish', {
        body: { queryName, allowed_roles: allowedRoles },
        headers: { 'X-Author': author },
        ...opts,
      }),

    /** POST /engine/discard -- { queryName } -> DiscardResponse */
    discard: (queryName, opts) => request('/engine/discard', { body: { queryName }, ...opts }),

    /** POST /engine/list -- { filter, page } -> ListResponse */
    list: ({ filter, page = { size: 200 } } = {}, opts) => request('/engine/list', { body: { filter, page }, ...opts }),

    /** POST /data-query -- { name, params } with the calling identity in X-Roles -> row array */
    invoke: (name, params, roles, opts) =>
      request('/data-query', { body: { name, params }, headers: { 'X-Roles': roles }, ...opts }),

    /**
     * POST /data-query with Accept: application/x-ndjson. Resolves with the Response once the
     * status is known (non-2xx is thrown as an EngineError before any body is read); the caller
     * reads response.body at its own pace, and the server only sends as fast as it is read.
     */
    openStream: async (name, params, roles, { signal } = {}) => {
      let res
      try {
        res = await fetch(`${baseUrl}/data-query`, {
          method: 'POST',
          headers: { Accept: 'application/x-ndjson', 'Content-Type': 'application/json', 'X-Roles': roles },
          body: JSON.stringify({ name, params }),
          signal,
        })
      } catch (err) {
        if (isAbortError(err)) throw err
        throw unreachable()
      }
      if (!res.ok) throw errorFromResponse(res.status, parseJson(await res.text()))
      if (!res.body) throw new EngineError({ status: res.status, code: CLIENT_ERRORS.BAD_RESPONSE, message: 'This browser cannot stream response bodies.' })
      return res
    },
    /** GET /query-roles -> { realmRoles: [{ name, description }], groups: [{ name, path, description }] } */
    getRoles: (opts) => request('/query-roles', { method: 'GET', ...opts }),
  }
}
