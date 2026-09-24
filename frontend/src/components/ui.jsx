import { Link, Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

/**
 * Small presentational components shared across pages.
 *
 * Kept in one file because each is a handful of lines and they are always used together -
 * splitting them into eight files would add eight imports per page for no benefit. The
 * moment one grows real logic it should move out.
 */

/* ------------------------------------------------------------------ *
 *  Loading and empty states
 * ------------------------------------------------------------------ */

/**
 * A skeleton, not a spinner, for anything whose size is known in advance.
 *
 * A spinner tells the user to wait; a skeleton tells them what is coming and reserves the
 * space so the layout does not jump when the data arrives. Layout shift is the more
 * annoying of the two failures, and it is the one a spinner cannot avoid.
 */
export function Skeleton({ width = '100%', height = '1rem', radius = '4px', className = '' }) {
  return (
    <span
      className={`skeleton ${className}`}
      style={{ width, height, borderRadius: radius }}
      aria-hidden="true"
    />
  )
}

export function SkeletonCard() {
  return (
    <div className="skeleton-card" aria-hidden="true">
      <Skeleton height="180px" radius="10px" />
      <Skeleton width="70%" height="1rem" />
      <Skeleton width="40%" height="0.85rem" />
    </div>
  )
}

export function Spinner({ size = 18, label }) {
  return (
    <span className="spinner-wrap">
      <span className="spinner" style={{ width: size, height: size }} aria-hidden="true" />
      {label && <span className="spinner-label">{label}</span>}
    </span>
  )
}

/** Shown when a list is legitimately empty - distinct from "still loading". */
export function EmptyState({ title, message, action }) {
  return (
    <div className="empty-state">
      <h3 className="empty-state__title">{title}</h3>
      {message && <p className="empty-state__message">{message}</p>}
      {action}
    </div>
  )
}

/**
 * Shown when a request failed.
 *
 * Separate from EmptyState because they mean different things and need different actions:
 * an empty cart needs "browse products", a failed request needs "try again". Conflating
 * them is how an outage comes to look like an empty catalogue.
 */
export function ErrorState({ error, onRetry, title = 'Could not load this' }) {
  return (
    <div className="error-state" role="alert">
      <span className="error-state__icon" aria-hidden="true">
        !
      </span>
      <h3 className="error-state__title">{title}</h3>
      <p className="error-state__message">
        {error?.message ?? 'An unexpected error occurred.'}
      </p>
      {onRetry && (
        <button type="button" className="btn btn--secondary" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ *
 *  Form field
 * ------------------------------------------------------------------ */

/**
 * A labelled input that owns its own error and hint.
 *
 * The accessibility wiring is the reason this is a component rather than repeated markup:
 * `id`/`htmlFor` must match, and the error must be linked with `aria-describedby` so a
 * screen reader announces it. Done by hand, one of those four attributes is eventually
 * missed - usually `aria-invalid`, which is exactly the one that matters for a failed submit.
 */
export function Field({
  id,
  label,
  error,
  hint,
  required,
  type = 'text',
  as = 'input',
  children,
  ...rest
}) {
  const describedBy = [error ? `${id}-error` : null, hint ? `${id}-hint` : null]
    .filter(Boolean)
    .join(' ')

  const shared = {
    id,
    required,
    'aria-invalid': error ? 'true' : undefined,
    'aria-describedby': describedBy || undefined,
    className: `field__control ${error ? 'field__control--invalid' : ''}`,
    ...rest,
  }

  return (
    <div className={`field ${error ? 'field--invalid' : ''}`}>
      {label && (
        <label className="field__label" htmlFor={id}>
          {label}
          {required && <span className="field__required" aria-hidden="true"> *</span>}
        </label>
      )}

      {as === 'textarea' ? (
        <textarea {...shared} />
      ) : as === 'select' ? (
        <select {...shared}>{children}</select>
      ) : (
        <input type={type} {...shared} />
      )}

      {hint && !error && (
        <p className="field__hint" id={`${id}-hint`}>
          {hint}
        </p>
      )}

      {/*
       * `role="alert"` makes the message announce the moment it appears. Without it a
       * sighted user sees the red border and a screen-reader user gets nothing.
       */}
      {error && (
        <p className="field__error" id={`${id}-error`} role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ *
 *  Badges and buttons
 * ------------------------------------------------------------------ */

export function Badge({ tone = 'neutral', children }) {
  return <span className={`badge badge--${tone}`}>{children}</span>
}

/** Availability, which appears on both the product card and the cart line. */
export function StockBadge({ stock }) {
  if (stock === null || stock === undefined) return null

  if (stock <= 0) return <Badge tone="danger">Out of stock</Badge>
  if (stock <= 5) return <Badge tone="warning">Only {stock} left</Badge>

  return <Badge tone="success">In stock</Badge>
}

export function Alert({ tone = 'info', title, children }) {
  return (
    <div className={`alert alert--${tone}`} role={tone === 'danger' || tone === 'error' ? 'alert' : 'status'}>
      {title && <p className="alert__title">{title}</p>}
      {children && <div className="alert__body">{children}</div>}
    </div>
  )
}

/* ------------------------------------------------------------------ *
 *  Pagination
 * ------------------------------------------------------------------ */

/**
 * Pagination driven by the backend's own PageResponse.
 *
 * `page`, `totalPages`, `first` and `last` all come from the server, so this component does
 * no arithmetic on them. The alternative - deriving the page count from `content.length` -
 * is wrong whenever the last page is partial, which is most of the time.
 */
export function Pagination({ page, totalPages, first, last, onChange, totalElements, label = 'items' }) {
  if (!totalPages || totalPages <= 1) {
    return totalElements ? (
      <p className="pagination__summary">
        {totalElements} {label}
      </p>
    ) : null
  }

  return (
    <nav className="pagination" aria-label="Pagination">
      <button
        type="button"
        className="btn btn--ghost"
        onClick={() => onChange(page - 1)}
        disabled={first}
        aria-label="Previous page"
      >
        ← Previous
      </button>

      <span className="pagination__status">
        Page <strong>{page + 1}</strong> of <strong>{totalPages}</strong>
        {totalElements ? <span className="pagination__total"> · {totalElements} {label}</span> : null}
      </span>

      <button
        type="button"
        className="btn btn--ghost"
        onClick={() => onChange(page + 1)}
        disabled={last}
        aria-label="Next page"
      >
        Next →
      </button>
    </nav>
  )
}

/* ------------------------------------------------------------------ *
 *  Route guards
 * ------------------------------------------------------------------ */

/**
 * Requires a signed-in user.
 *
 * Renders nothing while the session is being restored. See AuthProvider for why that is not
 * a cosmetic detail: redirecting during restoration would sign out a user whose token is
 * perfectly valid.
 *
 * The attempted location is carried in router state so the login page can send them back
 * where they were going, rather than dumping them on the home page.
 */
export function RequireAuth({ children, redirectTo = '/login' }) {
  const { isAuthenticated, initialising } = useAuth()

  if (initialising) return <PageLoader />

  if (!isAuthenticated) return <NavigateToLogin redirectTo={redirectTo} />

  return children
}

/** Requires an ADMIN. Checked on the server too - this only avoids rendering a page that would 403. */
export function RequireAdmin({ children }) {
  const { isAuthenticated, isAdmin, initialising } = useAuth()

  if (initialising) return <PageLoader />

  if (!isAuthenticated) return <NavigateToLogin redirectTo="/admin" />

  if (!isAdmin) {
    return (
      <EmptyState
        title="Not available"
        message="This area is for administrators only."
        action={<Link className="btn btn--secondary" to="/">Back to store</Link>}
      />
    )
  }

  return children
}

function NavigateToLogin({ redirectTo }) {
  const location = useLocation()
  return <Navigate to={redirectTo} state={{ from: location }} replace />
}

export function PageLoader({ label = 'Loading…' }) {
  return (
    <div className="page-loader">
      <Spinner size={22} label={label} />
    </div>
  )
}
