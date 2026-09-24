import { Link, Route, Routes, useLocation } from 'react-router-dom'
import { Footer, Header } from './components/Layout'
import { RequireAdmin, RequireAuth } from './components/ui'
import { CategoriesPage, ProfilePage } from './pages/ProfilePage'
import { CartPage } from './pages/CartPage'
import { LoginPage, RegisterPage } from './pages/AuthPages'
import { OrderDetailPage } from './pages/OrderDetailPage'
import { OrdersPage } from './pages/OrdersPage'
import { ProductDetailPage } from './pages/ProductDetailPage'
import { ProductListPage } from './pages/ProductListPage'
import { AdminLayout } from './pages/admin/AdminLayout'
import { config } from './config'

/**
 * The application shell and its route table.
 *
 * <h2>Why the 404 is a catch-all route rather than a redirect</h2>
 *
 * Redirecting unknown paths to the home page is common and harmful: the URL is silently
 * rewritten, the user is told nothing, and a typo'd link looks like it worked. An explicit
 * "not found" preserves the URL, says what happened, and keeps the back button honest.
 *
 * <h2>Where the auth guards sit</h2>
 *
 * On the route elements, not inside the pages. A page should not have to remember to check
 * whether it is allowed to render - that is a property of the route, and putting it there
 * means one place to audit. The guards are a UX affordance only; every protected endpoint
 * checks the token itself, which is what actually enforces anything.
 */
export function App() {
  return (
    <div className="app">
      <Header />

      <main className="main">
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/products" element={<ProductListPage />} />
          <Route path="/products/:id" element={<ProductDetailPage />} />
          <Route path="/categories" element={<CategoriesPage />} />

          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />

          {/*
           * Protected routes. Each is wrapped individually rather than nesting a layout
           * route, so the guard's redirect carries this specific location - which is what
           * lets the login page send the user back to where they were interrupted.
           */}
          <Route
            path="/cart"
            element={
              <RequireAuth>
                <CartPage />
              </RequireAuth>
            }
          />
          <Route
            path="/orders"
            element={
              <RequireAuth>
                <OrdersPage />
              </RequireAuth>
            }
          />
          <Route
            path="/orders/:orderId"
            element={
              <RequireAuth>
                <OrderDetailPage />
              </RequireAuth>
            }
          />
          <Route
            path="/profile"
            element={
              <RequireAuth>
                <ProfilePage />
              </RequireAuth>
            }
          />

          {/*
           * The admin console is a nested route with its own tab bar, hence the `/*` - the
           * section components (products, orders, users) are matched by the child Routes
           * inside AdminLayout.
           */}
          <Route
            path="/admin/*"
            element={
              <RequireAdmin>
                <AdminLayout />
              </RequireAdmin>
            }
          />

          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </main>

      <Footer />
    </div>
  )
}

/**
 * The landing page.
 *
 * Has no data dependency of its own - the panels link into the catalogue, which fetches what
 * it needs. A hero that loads its own product feed would make the first paint depend on a
 * request, for content that is decoration.
 */
function HomePage() {
  return (
    <>
      <section className="hero">
        <div className="container hero__inner">
          <p className="hero__eyebrow">{config.appName} · Store</p>
          <h1 className="hero__title">
            Everyday objects,
            <br />
            chosen carefully.
          </h1>
          <p className="hero__lede">
            A storefront built on a Spring Boot REST API - real authentication, real inventory,
            and a checkout that is safe when two people buy the last one at the same time.
          </p>

          <div className="hero__actions">
            <Link className="btn btn--primary btn--lg" to="/products">
              Browse the catalogue
            </Link>
            <Link className="btn btn--ghost btn--lg" to="/categories">
              Shop by category
            </Link>
          </div>
        </div>

        {/*
         * Decorative only, and hidden from assistive technology. These are not links to
         * anything, and a screen reader announcing three abstract shapes would be noise.
         */}
        <div className="hero__orb hero__orb--one" aria-hidden="true" />
        <div className="hero__orb hero__orb--two" aria-hidden="true" />
      </section>

      <section className="container features">
        <article className="feature">
          <h2 className="feature__title">Server-side search</h2>
          <p className="feature__body">
            Filtering, sorting and pagination all run in SQL. The page count you see is the
            real one, not the count of a page that was fetched and filtered in the browser.
          </p>
        </article>

        <article className="feature">
          <h2 className="feature__title">Safe checkout</h2>
          <p className="feature__body">
            Stock is verified and decremented inside a single transaction with row locks, so
            two simultaneous orders for the last unit cannot both succeed.
          </p>
        </article>

        <article className="feature">
          <h2 className="feature__title">3D where it helps</h2>
          <p className="feature__body">
            The product viewer is the only place three.js is used. The rest of the store is
            plain HTML, because that is what makes it fast.
          </p>
        </article>
      </section>
    </>
  )
}

function NotFoundPage() {
  const location = useLocation()

  return (
    <div className="page">
      <div className="container container--narrow">
        <div className="not-found">
          <p className="not-found__code">404</p>
          <h1 className="not-found__title">Page not found</h1>
          <p className="not-found__message">
            Nothing is served at <code className="code">{location.pathname}</code>.
          </p>
          <div className="not-found__actions">
            <Link className="btn btn--primary" to="/">
              Go home
            </Link>
            <Link className="btn btn--secondary" to="/products">
              Browse products
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}
