import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { Alert, Field } from '../components/ui'

/**
 * Sign-in page.
 *
 * <h2>On the error shown when credentials are wrong</h2>
 *
 * The backend answers 401 INVALID_CREDENTIALS and deliberately does not say whether it was
 * the email or the password that was wrong. That is not an oversight to be worked around:
 * distinguishing them turns the endpoint into an account-enumeration oracle, letting anyone
 * discover which email addresses are registered. The UI relays the single message as given.
 *
 * `user_not_found` is not surfaced as a field error for the same reason.
 */
export function LoginPage() {
  const { signIn } = useAuth()
  const toast = useToast()
  const navigate = useNavigate()
  const location = useLocation()

  const [form, setForm] = useState({ email: '', password: '' })
  const [errors, setErrors] = useState({})
  const [submitting, setSubmitting] = useState(false)

  /*
   * Where to go after signing in.
   *
   * A guard redirect carries the attempted location in router state, so someone who was
   * interrupted on /admin/orders lands there rather than on the home page. `replace` on the
   * navigate below (and on the guard's redirect) keeps the failed attempt out of history -
   * otherwise the back button returns to a page that immediately bounces forward again.
   */
  const from = location.state?.from?.pathname ?? '/'

  const update = (field) => (event) => {
    setForm((current) => ({ ...current, [field]: event.target.value }))
    // Clear the field's error as soon as the user edits it. Leaving a stale message under a
    // field the user has already fixed reads as though the correction was rejected.
    setErrors((current) => ({ ...current, [field]: null, form: null }))
  }

  const handleSubmit = async (event) => {
    event.preventDefault()
    setSubmitting(true)
    setErrors({})

    try {
      const user = await signIn(form)
      toast.success(`Welcome back, ${user.name}`, 'Signed in')
      navigate(from, { replace: true })
    } catch (cause) {
      if (cause.isValidation) {
        // A 400 has field-level detail; map it onto the form.
        setErrors({
          email: cause.for('email'),
          password: cause.for('password'),
          form: cause.for('email') || cause.for('password') ? null : cause.message,
        })
      } else {
        /*
         * INVALID_CREDENTIALS, ACCOUNT_DISABLED and anything else land here as a form-level
         * message. They are the user's problem to act on, so they belong on the form, not in
         * a toast that disappears.
         */
        setErrors({ form: cause.message })
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <header className="auth-card__head">
          <h1 className="auth-card__title">Welcome back</h1>
          <p className="auth-card__subtitle">Sign in to continue shopping.</p>
        </header>

        {errors.form && <Alert tone="error">{errors.form}</Alert>}

        <form onSubmit={handleSubmit} noValidate>
          <Field
            id="email"
            label="Email"
            type="email"
            autoComplete="email"
            required
            value={form.email}
            onChange={update('email')}
            error={errors.email}
            placeholder="you@example.com"
          />

          <Field
            id="password"
            label="Password"
            type="password"
            /* Tells a password manager which credential this is, so it can fill it. */
            autoComplete="current-password"
            required
            value={form.password}
            onChange={update('password')}
            error={errors.password}
            placeholder="••••••••"
          />

          <button type="submit" className="btn btn--primary btn--block" disabled={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p className="auth-card__foot">
          No account? <Link to="/register">Create one</Link>
        </p>
      </div>

      <aside className="auth-demo">
        <h2 className="auth-demo__title">Demo accounts</h2>
        <p className="auth-demo__note">
          Seeded by the backend. Role rules are enforced server-side - a customer cannot
          reach admin endpoints by editing the UI.
        </p>
        <dl className="auth-demo__list">
          <div>
            <dt>Customer</dt>
            <dd>customer@shop.com / Customer@123</dd>
          </div>
          <div>
            <dt>Administrator</dt>
            <dd>admin@shop.com / Admin@123</dd>
          </div>
        </dl>
      </aside>
    </div>
  )
}

/**
 * Registration page.
 *
 * <h2>What is deliberately absent</h2>
 *
 * There is no role selector, here or anywhere in the public UI. The backend's
 * RegisterRequest has no `role` field at all, so a request carrying one has it ignored -
 * which is the correct design, because accepting an authority level from a registration
 * form would let any visitor create an administrator. Administrators are created by an
 * existing administrator promoting a user from the admin console, or by seeding.
 */
export function RegisterPage() {
  const { signUp } = useAuth()
  const toast = useToast()
  const navigate = useNavigate()

  const [form, setForm] = useState({ name: '', email: '', password: '', confirm: '', phone: '' })
  const [errors, setErrors] = useState({})
  const [submitting, setSubmitting] = useState(false)

  const update = (field) => (event) => {
    setForm((current) => ({ ...current, [field]: event.target.value }))
    setErrors((current) => ({ ...current, [field]: null, form: null }))
  }

  const handleSubmit = async (event) => {
    event.preventDefault()
    setErrors({})

    /*
     * Confirm-password is checked client-side only, because the backend does not receive it
     * and therefore cannot validate it. It is a typing-mistake guard, not a security
     * control - the password itself is validated by @Size and @ByteLength on the server.
     */
    if (form.password !== form.confirm) {
      setErrors({ confirm: 'Passwords do not match.' })
      return
    }

    setSubmitting(true)
    try {
      const user = await signUp({
        name: form.name,
        email: form.email,
        password: form.password,
        phone: form.phone || undefined,
      })
      toast.success(`Welcome, ${user.name}`, 'Account created')
      navigate('/', { replace: true })
    } catch (cause) {
      if (cause.isValidation) {
        setErrors({
          name: cause.for('name'),
          email: cause.for('email'),
          password: cause.for('password'),
          phone: cause.for('phone'),
          form:
            cause.for('name') || cause.for('email') || cause.for('password') || cause.for('phone')
              ? null
              : cause.message,
        })
      } else {
        // EMAIL_ALREADY_EXISTS lands here, and belongs against the email field.
        setErrors(
          cause.code === 'EMAIL_ALREADY_EXISTS'
            ? { email: cause.message }
            : { form: cause.message },
        )
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-page auth-page--single">
      <div className="auth-card">
        <header className="auth-card__head">
          <h1 className="auth-card__title">Create your account</h1>
          <p className="auth-card__subtitle">It takes a moment.</p>
        </header>

        {errors.form && <Alert tone="error">{errors.form}</Alert>}

        <form onSubmit={handleSubmit} noValidate>
          <Field
            id="name"
            label="Full name"
            required
            autoComplete="name"
            value={form.name}
            onChange={update('name')}
            error={errors.name}
            hint="Between 2 and 60 characters."
            placeholder="Ada Lovelace"
          />

          <Field
            id="email"
            label="Email"
            type="email"
            required
            autoComplete="email"
            value={form.email}
            onChange={update('email')}
            error={errors.email}
            placeholder="you@example.com"
          />

          <Field
            id="phone"
            label="Phone"
            autoComplete="tel"
            value={form.phone}
            onChange={update('phone')}
            error={errors.phone}
            hint="Optional. Digits, spaces and + ( ) - only."
            placeholder="+91 98765 43210"
          />

          <Field
            id="password"
            label="Password"
            type="password"
            required
            autoComplete="new-password"
            value={form.password}
            onChange={update('password')}
            error={errors.password}
            /*
             * The 72-byte cap is stated rather than hidden. It is a real limit of bcrypt,
             * which ignores input past 72 bytes - so without this note a user can set a
             * long passphrase, have the tail silently discarded, and never know.
             */
            hint="At least 8 characters, at most 72 bytes."
            placeholder="••••••••"
          />

          <Field
            id="confirm"
            label="Confirm password"
            type="password"
            required
            autoComplete="new-password"
            value={form.confirm}
            onChange={update('confirm')}
            error={errors.confirm}
            placeholder="••••••••"
          />

          <button type="submit" className="btn btn--primary btn--block" disabled={submitting}>
            {submitting ? 'Creating account…' : 'Create account'}
          </button>
        </form>

        <p className="auth-card__foot">
          Already registered? <Link to="/login">Sign in</Link>
        </p>
      </div>
    </div>
  )
}
