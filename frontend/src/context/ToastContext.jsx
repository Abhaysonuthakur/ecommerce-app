import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react'
import { ApiError } from '../api/client'

/**
 * Transient messages, so a failed action is never silent.
 *
 * <h2>Why this exists rather than per-component error state</h2>
 *
 * Some failures belong next to the control that caused them - a validation message under the
 * offending field. Others do not: "could not add to cart", "your session expired", "the
 * server is unreachable" are answers to an action, not properties of a form. Putting those
 * in the component that fired them means every caller writes the same banner, and the ones
 * that forget produce a button that appears to do nothing.
 */
const ToastContext = createContext(null)

const DEFAULTS = { duration: 5000 }

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])
  const nextId = useRef(1)

  const dismiss = useCallback((id) => {
    setToasts((current) => current.filter((toast) => toast.id !== id))
  }, [])

  const push = useCallback(
    (toast) => {
      const id = nextId.current++
      const withId = { id, tone: 'info', ...toast }
      setToasts((current) => [...current, withId])

      // Auto-dismiss. Errors are given longer, because they carry a sentence that needs
      // reading rather than glancing at.
      const duration = withId.duration ?? (withId.tone === 'error' ? 8000 : DEFAULTS.duration)
      if (duration > 0) {
        setTimeout(() => dismiss(id), duration)
      }

      return id
    },
    [dismiss],
  )

  const success = useCallback((message, title) => push({ tone: 'success', message, title }), [push])
  const info = useCallback((message, title) => push({ tone: 'info', message, title }), [push])
  const error = useCallback((message, title) => push({ tone: 'error', message, title }), [push])

  /**
   * Turns an error into a message a person can act on.
   *
   * The backend's `message` is already written for humans, so it is used directly when
   * present. The codes handled specially are the ones where the raw message is technically
   * accurate but unhelpful at the point it appears - a session expiring mid-click is not
   * usefully described as "Invalid or expired token".
   */
  const fromError = useCallback(
    (cause, fallbackTitle = 'Something went wrong') => {
      if (cause instanceof ApiError) {
        if (cause.code === 'NETWORK_ERROR') {
          return push({ tone: 'error', title: 'Cannot reach the server', message: cause.message })
        }
        if (cause.isAuthFailure) {
          return push({ tone: 'error', title: 'Session expired', message: 'Please sign in again.' })
        }
        return push({ tone: 'error', title: fallbackTitle, message: cause.message })
      }

      return push({
        tone: 'error',
        title: fallbackTitle,
        message: cause?.message ?? 'An unexpected error occurred.',
      })
    },
    [push],
  )

  const value = useMemo(
    () => ({ toasts, push, dismiss, success, info, error, fromError }),
    [toasts, push, dismiss, success, info, error, fromError],
  )

  return (
    <ToastContext.Provider value={value}>
      {children}
      <ToastViewport toasts={toasts} onDismiss={dismiss} />
    </ToastContext.Provider>
  )
}

const ICONS = { success: '✓', error: '!', info: 'i' }

function ToastViewport({ toasts, onDismiss }) {
  if (toasts.length === 0) return null

  return (
    /*
     * aria-live="polite" announces each message to a screen reader without interrupting.
     * "assertive" would be wrong for a confirmation toast and is reserved for real
     * interruptions; a failed add-to-cart is important but not urgent enough to cut across
     * whatever the user is doing.
     */
    <div className="toast-viewport" aria-live="polite" aria-atomic="false">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast toast--${toast.tone}`} role="status">
          <span className="toast__icon" aria-hidden="true">
            {ICONS[toast.tone] ?? ICONS.info}
          </span>
          <div className="toast__body">
            {toast.title && <p className="toast__title">{toast.title}</p>}
            <p className="toast__message">{toast.message}</p>
          </div>
          <button
            type="button"
            className="toast__close"
            onClick={() => onDismiss(toast.id)}
            aria-label="Dismiss notification"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  )
}

export function useToast() {
  const context = useContext(ToastContext)
  if (!context) {
    throw new Error('useToast must be used inside a <ToastProvider>')
  }
  return context
}
