import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Fetches from the API with loading/error state, and cancels superseded requests.
 *
 * <h2>The bug this exists to prevent</h2>
 *
 * A search box where every keystroke fires a request has an inherent race: the response to
 * "sh" can arrive after the response to "shirt", and whichever lands last wins. So the user
 * types "shirt" and sees results for "sh".
 *
 * Aborting the previous request fixes it at the source. `AbortController` is threaded
 * through `request()` for exactly this, and fetch rejects with an `AbortError` which is
 * swallowed here rather than surfacing as a failure the user did not cause.
 *
 * A sequence number guards the same race for the cases abort cannot cover - two requests
 * fired within the same tick, or a response already in flight and past the point of being
 * cancelled.
 *
 * @param {Function} fetcher - receives an AbortSignal, returns a promise.
 * @param {Array} deps - refetch when these change.
 */
export function useApiQuery(fetcher, deps = [], { skip = false } = {}) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(!skip)

  // Incremented on every run; a response whose sequence is stale is discarded.
  const sequence = useRef(0)
  const controllerRef = useRef(null)

  /**
   * `fetcher` is usually an inline arrow function, so it is a new reference on every render.
   * Holding it in a ref keeps it out of the dependency array - otherwise every render would
   * trigger a refetch, which is an infinite loop. The caller controls refetching through
   * `deps`, explicitly.
   */
  const fetcherRef = useRef(fetcher)
  useEffect(() => {
    fetcherRef.current = fetcher
  })

  const run = useCallback(async () => {
    controllerRef.current?.abort()

    const controller = new AbortController()
    controllerRef.current = controller

    const current = ++sequence.current
    setLoading(true)
    setError(null)

    try {
      const result = await fetcherRef.current(controller.signal)
      if (current === sequence.current) setData(result)
    } catch (cause) {
      if (cause.name === 'AbortError') return
      if (current === sequence.current) setError(cause)
    } finally {
      if (current === sequence.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (skip) {
      setLoading(false)
      return undefined
    }

    run()

    // Abandoning the request on unmount prevents a state update on a gone component.
    return () => controllerRef.current?.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, skip])

  return { data, error, loading, refetch: run, setData }
}

/**
 * Fetches a single value through an arbitrary async function.
 *
 * For mutations and one-off loads where the input is not a URL. Keeps the same
 * loading/error handling so a caller does not have to write it again.
 */
export function useAsyncAction() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const execute = useCallback(async (operation) => {
    setLoading(true)
    setError(null)
    try {
      const result = await operation()
      return { ok: true, data: result }
    } catch (cause) {
      setError(cause)
      return { ok: false, error: cause }
    } finally {
      setLoading(false)
    }
  }, [])

  return { execute, loading, error, setError }
}

/**
 * Debounces a rapidly-changing value.
 *
 * Used by the search input. Debouncing the *value* rather than the fetch means the network
 * layer stays simple - it sees one clean change - and the input itself remains fully
 * responsive, because only the derived value is delayed.
 */
export function useDebounced(value, delay = 350) {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])

  return debounced
}

/**
 * Tracks a media query.
 *
 * The product viewer uses this to decide whether to render the 3D canvas at all. A phone
 * should not download three.js and start a WebGL context to show a product it cannot rotate
 * comfortably - and the preference is also how a user asks for less motion.
 */
export function useMediaQuery(query) {
  const [matches, setMatches] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(query).matches,
  )

  useEffect(() => {
    const list = window.matchMedia(query)
    const handler = (event) => setMatches(event.matches)

    setMatches(list.matches)
    list.addEventListener('change', handler)
    return () => list.removeEventListener('change', handler)
  }, [query])

  return matches
}

/** True when the user has asked the OS to reduce motion. */
export function usePrefersReducedMotion() {
  return useMediaQuery('(prefers-reduced-motion: reduce)')
}
