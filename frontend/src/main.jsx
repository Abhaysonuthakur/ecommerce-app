import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import { ErrorBoundary } from './components/ErrorBoundary'
import { AuthProvider } from './context/AuthContext'
import { CartProvider } from './context/CartContext'
import { ToastProvider } from './context/ToastContext'
import { config } from './config'
import './styles/index.css'

/**
 * The entry point, and the provider order.
 *
 * <h2>Why the nesting order is this and not something else</h2>
 *
 * The providers have real dependencies on one another, so the order is forced rather than
 * chosen:
 *
 *   BrowserRouter
 *     └─ ToastProvider        - depends on nothing; needed by the providers below it
 *          └─ AuthProvider    - depends on nothing; only the API client
 *               └─ CartProvider  - READS AuthContext to decide whether to fetch the cart
 *                    └─ App
 *
 * `CartProvider` is inside `AuthProvider` because it calls `useAuth()` to know whether there
 * is a session at all - fetching a cart anonymously would 401 on every page load. Reversing
 * them throws immediately, because a hook cannot read a context that has not been provided
 * yet.
 *
 * `ToastProvider` is outside both so the auth and cart layers can surface failures through
 * it. If it were innermost, a failed sign-in would have no way to report itself.
 */
/*
 * Apply the configured name to the document title.
 *
 * `index.html` carries a hardcoded title so the tab is not blank before React runs, but that
 * literal would then win permanently - `VITE_APP_NAME` would be documented, read into config,
 * and still not reach the most visible piece of text in the browser. Setting it here means the
 * static title is a placeholder for the instant before hydration, and the configured name
 * takes over from then on.
 */
document.title = `${config.appName} - Store`

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <ErrorBoundary>
      <BrowserRouter>
        <ToastProvider>
          <AuthProvider>
            <CartProvider>
              <App />
            </CartProvider>
          </AuthProvider>
        </ToastProvider>
      </BrowserRouter>
    </ErrorBoundary>
  </StrictMode>,
)
