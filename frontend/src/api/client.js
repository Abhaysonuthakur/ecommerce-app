/**
 * The HTTP client. Every call to the backend goes through `request`.
 *
 * <h2>Why this file exists at all</h2>
 *
 * The obvious alternative is `fetch` at each call site. That works until the first time the
 * backend's error shape matters, and then every component grows its own parsing, its own
 * idea of what a failed request looks like, and its own way of deciding whether to show a
 * toast - none of them agreeing. Centralising it means the backend's `ApiErrorResponse`
 * contract is understood in exactly one place.
 *
 * <h2>What the backend actually returns on failure</h2>
 *
 * Every failing endpoint, without exception, returns this shape with a matching HTTP status:
 *
 *   {
 *     "timestamp": "2026-09-24T07:12:33Z",
 *     "status": 400,
 *     "error": "VALIDATION_FAILED",     <- stable, machine-readable
 *     "message": "Validation failed",
 *     "path": "/api/products",
 *     "fieldErrors": [{ "field": "quantity", "message": "must be greater than 0" }]
 *   }
 *
 * The `error` code is what code should branch on. `message` is written for a human and
 * could be reworded at any time, so matching on it would be a latent bug.
 */

/**
 * Where the API lives.
 *
 * Empty by default, which means every path stays relative (`/api/products`) and the request
 * goes to the origin that served the page. That is what makes the Vite dev proxy work with
 * no CORS handshake, and it is also what a production reverse proxy expects.
 *
 * <h3>Why this is an origin, not a path prefix</h3>
 *
 * Every call site already writes a full API path - `http.get('/api/products')` - so this
 * value is joined as a *prefix*, not substituted for `/api`. That gives one rule:
 *
 *     VITE_API_BASE_URL is unset            ->  /api/products
 *     VITE_API_BASE_URL=http://api.example  ->  http://api.example/api/products
 *
 * Setting it to `/api` would produce `/api/api/products`, so the example file documents it
 * as an origin and the normaliser below strips a trailing slash to stop `//products`
 * appearing. A double slash is survivable but it shows up in server logs and defeats
 * nothing, so it is worth removing at the source rather than diagnosing later.
 */
const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '')

/**
 * Key under which the JWT is kept in localStorage.
 *
 * <h3>The trade-off, stated rather than hidden</h3>
 *
 * localStorage is readable by any script on the page, so it is vulnerable to XSS in a way
 * an httpOnly cookie is not. The alternative - a cookie - requires CSRF protection, which
 * needs backend changes that this API does not have.
 *
 * For this project localStorage is the honest choice: the backend issues a bearer token and
 * nothing else, so there is no cookie to use. What makes it acceptable is that the token is
 * short-lived (1 hour) and the app renders no user-supplied HTML - React escapes by default.
 * A production system handling real money should move to httpOnly cookies plus CSRF tokens.
 */
const TOKEN_KEY = 'ecommerce.token'

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (token) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

/**
 * An error carrying the backend's own error code and field errors.
 *
 * Extending `Error` rather than returning `{ ok, data, error }` means a failed call throws,
 * so it propagates through `try/catch` and cannot be ignored by accident. The failure mode
 * this avoids is real: a caller that forgets to check an `ok` flag treats an error body as
 * data and renders `undefined` all over the page.
 */
export class ApiError extends Error {
  constructor({ status, code, message, fieldErrors, path }) {
    super(message || 'Request failed')
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors ?? null
    this.path = path ?? null
  }

  /** A field error lookup for form components: `errors.for('quantity')`. */
  for(field) {
    return this.fieldErrors?.find((e) => e.field === field)?.message ?? null
  }

  get isValidation() {
    return this.code === 'VALIDATION_FAILED'
  }

  /**
   * True when the credential is missing, expired, or malformed.
   *
   * The three codes are grouped because the correct reaction is identical in all three
   * cases - clear the token and send the user to log in - and the backend distinguishes
   * them only to make debugging easier.
   */
  get isAuthFailure() {
    return ['UNAUTHORIZED', 'TOKEN_EXPIRED', 'INVALID_TOKEN'].includes(this.code)
  }
}

/**
 * Builds the query string, dropping empty values.
 *
 * Omitted parameters matter more than they look. The backend distinguishes "`active` not
 * supplied" (storefront default: active only) from "`active=false`" (admin console: show
 * withdrawn stock), and it does so with a boxed Boolean. Sending `active=` for an unset
 * filter would arrive as an empty string, fail to bind, and either error or silently
 * change which products are listed. So absent means absent: not sent at all.
 *
 * `false` and `0` must survive, which is why the check is against null/undefined/'' rather
 * than truthiness - `if (value)` would drop the very values that carry meaning.
 */
function buildQuery(params = {}) {
  const search = new URLSearchParams()

  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    if (Array.isArray(value)) {
      value.forEach((v) => search.append(key, v))
    } else {
      search.append(key, value)
    }
  }

  const query = search.toString()
  return query ? `?${query}` : ''
}

/**
 * Parses a response body, tolerating the two cases where there is no JSON.
 *
 * A 204 has no body, and a misconfigured or crashed upstream can return HTML. `response.json()`
 * throws on both, and that throw would surface as "Unexpected token < in JSON" - a message
 * that sends the reader looking for a bug in the parsing code instead of at the server.
 */
async function parseBody(response) {
  if (response.status === 204) return null

  const text = await response.text()
  if (!text) return null

  try {
    return JSON.parse(text)
  } catch {
    return { error: 'INTERNAL_ERROR', message: text.slice(0, 200) }
  }
}

/**
 * Performs a request and returns the parsed body, throwing {@link ApiError} on failure.
 *
 * @param {string} path - API path beginning with `/api`, e.g. `/api/products`.
 * @param {object} [options]
 * @param {string} [options.method='GET']
 * @param {object} [options.body] - serialised as JSON. Omitted entirely when absent, so a
 *   bodyless POST sends no `Content-Type` and does not trip a 415.
 * @param {object} [options.query] - query parameters; empty values are dropped.
 * @param {AbortSignal} [options.signal] - for cancellation, which the search page uses to
 *   discard superseded requests rather than letting a slow early keystroke overwrite a fast
 *   later one.
 */
export async function request(path, { method = 'GET', body, query, signal } = {}) {
  const headers = {}

  const token = tokenStore.get()
  if (token) headers.Authorization = `Bearer ${token}`

  // Only declare a JSON body when there is one. A GET with Content-Type: application/json
  // and no body is legal but invites servers to try to parse it.
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let response
  try {
    response = await fetch(`${BASE_URL}${path}${buildQuery(query)}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    })
  } catch (cause) {
    /*
     * fetch rejects only for network-level failures - the server is down, DNS failed, or
     * the request was aborted. These are NOT HTTP errors and have no status, so they get a
     * synthetic one. Distinguishing them matters: "can't reach the server" needs a retry,
     * while "the server said no" usually does not.
     */
    if (cause.name === 'AbortError') throw cause

    throw new ApiError({
      status: 0,
      code: 'NETWORK_ERROR',
      message: 'Could not reach the server. Check that the backend is running.',
      path,
    })
  }

  const payload = await parseBody(response)

  if (!response.ok) {
    throw new ApiError({
      status: response.status,
      code: payload?.error ?? 'INTERNAL_ERROR',
      message: payload?.message ?? `Request failed with status ${response.status}`,
      fieldErrors: payload?.fieldErrors ?? null,
      path: payload?.path ?? path,
    })
  }

  return payload
}

/** Convenience wrappers. Thin on purpose - they exist for call-site readability, not logic. */
export const http = {
  get: (path, query, options) => request(path, { ...options, query }),
  post: (path, body, options) => request(path, { ...options, method: 'POST', body }),
  put: (path, body, options) => request(path, { ...options, method: 'PUT', body }),
  patch: (path, body, options) => request(path, { ...options, method: 'PATCH', body }),
  delete: (path, options) => request(path, { ...options, method: 'DELETE' }),
}
