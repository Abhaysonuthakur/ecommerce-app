import { http } from './client'

/**
 * Authentication and the current user's profile.
 *
 * Mirrors AuthController and UserController.
 */

/**
 * Registers a new customer.
 *
 * The backend accepts `name`, `email`, `password`, `phone` - and deliberately nothing else.
 * In particular there is no `role` field: sending one is ignored, because accepting a role
 * from a registration request would let anyone create an administrator. If you find yourself
 * adding `role` here to make an admin from the UI, that is the vulnerability, not a missing
 * feature.
 */
export const register = ({ name, email, password, phone }) =>
  http.post('/api/auth/register', { name, email, password, phone })

/** Exchanges credentials for a JWT. Returns AuthResponse: { token, tokenType, expiresIn, user }. */
export const login = ({ email, password }) => http.post('/api/auth/login', { email, password })

/**
 * Returns the authenticated user.
 *
 * A POST rather than a GET, which is unusual and deliberate on the backend's side: the
 * operation is not idempotent in the way a read is expected to be - it revalidates the token
 * with the user's current database row, so a disabled account is rejected here even while
 * holding an unexpired token. Treating it as a POST keeps intermediaries from caching a
 * response that is really an authorisation check.
 */
export const me = () => http.post('/api/auth/me')

/** Reads the current profile. Separate from `me()`: this one is a plain authenticated read. */
export const getProfile = () => http.get('/api/users/me')

/** Updates name, phone and/or address. Fields left undefined are unchanged. */
export const updateProfile = ({ name, phone, address }) =>
  http.put('/api/users/me', { name, phone, address })
