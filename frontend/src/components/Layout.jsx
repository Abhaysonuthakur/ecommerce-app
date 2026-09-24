import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { useCart } from '../context/CartContext'
import { config } from '../config'

/**
 * The site header: navigation, cart count and account menu.
 *
 * <h2>Where the cart count comes from</h2>
 *
 * From CartContext, which holds the server's own `totalItems`. It is not a counter the
 * header maintains - so adding a product from the catalogue page, the product page, or a
 * "add to cart" on a search result all update this one number, because all of them go
 * through the same context. A local counter here would drift the first time a mutation
 * happened outside the header's knowledge.
 */
export function Header() {
  const { user, isAuthenticated, isAdmin, signOut } = useAuth()
  const { itemCount } = useCart()
  const navigate = useNavigate()

  const [menuOpen, setMenuOpen] = useState(false)
  const [mobileOpen, setMobileOpen] = useState(false)
  const menuRef = useRef(null)

  /**
   * Closes the account menu on an outside click or Escape.
   *
   * Both are needed. Without the outside click the menu stays open while the user works
   * elsewhere, which looks broken; without Escape it cannot be dismissed from the keyboard,
   * which makes it unusable without a mouse.
   *
   * The listener is attached to `mousedown` rather than `click`, so the menu closes before
   * the click lands on whatever is underneath.
   */
  useEffect(() => {
    if (!menuOpen) return undefined

    const onClick = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) setMenuOpen(false)
    }
    const onKey = (event) => {
      if (event.key === 'Escape') setMenuOpen(false)
    }

    document.addEventListener('mousedown', onClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [menuOpen])

  // A route change should always close the mobile drawer, or it stays over the new page.
  useEffect(() => {
    setMobileOpen(false)
    setMenuOpen(false)
  }, [navigate])

  const handleSignOut = () => {
    signOut()
    setMenuOpen(false)
    navigate('/')
  }

  return (
    <header className="header">
      <div className="container header__inner">
        <Link to="/" className="brand" aria-label={`${config.appName} home`}>
          <span className="brand__mark" aria-hidden="true" />
          <span className="brand__name">{config.appName}</span>
        </Link>

        <nav className="header__nav" aria-label="Main">
          <NavLink to="/products" className="header__link">
            Shop
          </NavLink>
          <NavLink to="/categories" className="header__link">
            Categories
          </NavLink>
          {isAuthenticated && (
            <NavLink to="/orders" className="header__link">
              Orders
            </NavLink>
          )}
          {isAdmin && (
            <NavLink to="/admin" className="header__link header__link--admin">
              Admin
            </NavLink>
          )}
        </nav>

        <div className="header__actions">
          <Link to="/cart" className="header__cart" aria-label={`Cart, ${itemCount} items`}>
            <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="none"
                 stroke="currentColor" strokeWidth="1.8">
              <path d="M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" />
              <path d="M3 6h18" />
              <path d="M16 10a4 4 0 0 1-8 0" />
            </svg>
            {/*
             * Only rendered when non-zero. A permanent "0" badge is noise, and the
             * aria-label above already states the count for anyone not seeing the badge.
             */}
            {itemCount > 0 && <span className="header__cart-count">{itemCount > 99 ? '99+' : itemCount}</span>}
          </Link>

          {isAuthenticated ? (
            <div className="header__menu" ref={menuRef}>
              <button
                type="button"
                className="header__avatar"
                onClick={() => setMenuOpen((open) => !open)}
                aria-haspopup="menu"
                aria-expanded={menuOpen}
                aria-label="Account menu"
              >
                {initials(user?.name)}
              </button>

              {menuOpen && (
                <div className="dropdown" role="menu">
                  <div className="dropdown__head">
                    <p className="dropdown__name">{user?.name}</p>
                    <p className="dropdown__email">{user?.email}</p>
                    {isAdmin && <span className="badge badge--info">Administrator</span>}
                  </div>

                  <Link to="/profile" className="dropdown__item" role="menuitem">
                    Profile
                  </Link>
                  <Link to="/orders" className="dropdown__item" role="menuitem">
                    My orders
                  </Link>
                  {isAdmin && (
                    <Link to="/admin" className="dropdown__item" role="menuitem">
                      Admin console
                    </Link>
                  )}

                  <button type="button" className="dropdown__item dropdown__item--danger"
                          onClick={handleSignOut} role="menuitem">
                    Sign out
                  </button>
                </div>
              )}
            </div>
          ) : (
            <div className="header__auth">
              <Link to="/login" className="btn btn--ghost btn--sm">
                Sign in
              </Link>
              <Link to="/register" className="btn btn--primary btn--sm">
                Create account
              </Link>
            </div>
          )}

          <button
            type="button"
            className="header__burger"
            onClick={() => setMobileOpen((open) => !open)}
            aria-expanded={mobileOpen}
            aria-label="Toggle navigation"
          >
            <span />
            <span />
            <span />
          </button>
        </div>
      </div>

      {mobileOpen && (
        <div className="header__mobile">
          <Link to="/products" className="header__mobile-link">Shop</Link>
          <Link to="/categories" className="header__mobile-link">Categories</Link>
          {isAuthenticated && <Link to="/orders" className="header__mobile-link">Orders</Link>}
          {isAuthenticated && <Link to="/profile" className="header__mobile-link">Profile</Link>}
          {isAdmin && <Link to="/admin" className="header__mobile-link">Admin</Link>}
          {!isAuthenticated && <Link to="/login" className="header__mobile-link">Sign in</Link>}
        </div>
      )}
    </header>
  )
}

/**
 * Builds initials for the avatar.
 *
 * Falls back to '?' rather than rendering an empty circle, and takes at most two words - a
 * name with four parts would otherwise produce a four-letter monogram that no circle fits.
 */
function initials(name) {
  if (!name) return '?'

  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}

export function Footer() {
  return (
    <footer className="footer">
      <div className="container footer__inner">
        <div className="footer__col">
          <p className="brand__name">{config.appName}</p>
          <p className="footer__note">
            A storefront built on a Spring Boot REST API - Spring Data JPA, Spring Security and JWT.
          </p>
        </div>

        <div className="footer__col">
          <h4 className="footer__heading">Shop</h4>
          <Link to="/products" className="footer__link">All products</Link>
          <Link to="/categories" className="footer__link">Categories</Link>
          <Link to="/cart" className="footer__link">Cart</Link>
        </div>

        <div className="footer__col">
          <h4 className="footer__heading">Account</h4>
          <Link to="/profile" className="footer__link">Profile</Link>
          <Link to="/orders" className="footer__link">Orders</Link>
          <Link to="/login" className="footer__link">Sign in</Link>
        </div>

        <div className="footer__col">
          <h4 className="footer__heading">API</h4>
          {/*
           * A plain anchor, not a router Link - this leaves the SPA for a page served by
           * the backend, and a client-side navigation would try to render it as a route.
           */}
          <a className="footer__link" href="/swagger-ui.html" target="_blank" rel="noreferrer">
            API documentation
          </a>
          <a className="footer__link" href="/api/health" target="_blank" rel="noreferrer">
            Health check
          </a>
        </div>
      </div>

      <div className="container footer__base">
        <p>© {new Date().getFullYear()} {config.appName}. A learning project.</p>
      </div>
    </footer>
  )
}
