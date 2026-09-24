import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import * as authApi from '../api/auth'
import { tokenStore } from '../api/client'

/**
 * Authentication state for the whole app.
 *
 * <h2>The four states, and why "loading" is not optional</h2>
 *
 * On a page refresh the token is in localStorage but the user object is not - it lived only
 * in memory. So the app must call the backend to find out who the token belongs to, and
 * during that call the answer to "is the user signed in?" is genuinely unknown.
 *
 * Collapsing that into a boolean produces a real bug, not a cosmetic one: a protected route
 * renders, sees `user === null`, and redirects to the login page - a fraction of a second
 * before the profile arrives. The user is signed in, the token is valid, and they were
 * kicked out. Hence `initialising`: while true, route guards render nothing and redirect
 * nowhere.
 */
const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [initialising, setInitialising] = useState(true)

  /**
   * Restores the session on mount.
   *
   * `me()` rather than trusting the stored token's contents: the token is a bearer
   * credential, not a profile. It could be expired, and - more importantly - the account
   * could have been disabled or its role changed since the token was issued. Only the
   * server can answer that, so the server is asked.
   *
   * A failure is not an error worth showing. An expired or rejected token is the normal
   * outcome of coming back the next day, so it is handled by clearing the token and
   * continuing as an anonymous visitor.
   */
  useEffect(() => {
    let cancelled = false

    async function restore() {
      if (!tokenStore.get()) {
        setInitialising(false)
        return
      }

      try {
        const profile = await authApi.me()
        if (!cancelled) setUser(profile)
      } catch {
        // Expired, revoked, or the account was disabled. Either way the token is useless.
        tokenStore.clear()
        if (!cancelled) setUser(null)
      } finally {
        if (!cancelled) setInitialising(false)
      }
    }

    restore()
    return () => {
      cancelled = true
    }
  }, [])

  const signIn = useCallback(async (credentials) => {
    const auth = await authApi.login(credentials)
    // Persist the token BEFORE setting the user, so any request triggered by the resulting
    // re-render already carries the Authorization header.
    tokenStore.set(auth.token)
    setUser(auth.user)
    return auth.user
  }, [])

  const signUp = useCallback(async (details) => {
    const auth = await authApi.register(details)
    tokenStore.set(auth.token)
    setUser(auth.user)
    return auth.user
  }, [])

  /**
   * Clears local state only.
   *
   * There is no logout endpoint, and that is not an omission: a JWT is validated by
   * signature, so the server holds no session to invalidate. The token remains technically
   * valid until it expires - which is the honest limitation of stateless auth, and the
   * reason the lifetime is one hour rather than one month. A production system that needs
   * immediate revocation needs a denylist or short-lived tokens with refresh, neither of
   * which this API has.
   */
  const signOut = useCallback(() => {
    tokenStore.clear()
    setUser(null)
  }, [])

  const refreshUser = useCallback(async () => {
    const profile = await authApi.getProfile()
    setUser(profile)
    return profile
  }, [])

  const value = useMemo(
    () => ({
      user,
      initialising,
      isAuthenticated: Boolean(user),
      isAdmin: user?.role === 'ADMIN',
      signIn,
      signUp,
      signOut,
      refreshUser,
    }),
    [user, initialising, signIn, signUp, signOut, refreshUser],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside an <AuthProvider>')
  }
  return context
}
