/**
 * Front-end configuration, read once from Vite's environment.
 *
 * <h3>Why a module instead of `import.meta.env.X` at each call site</h3>
 *
 * Three reasons, in order of how much they matter:
 *
 * 1. **The `.env.example` file documents these keys.** If nothing reads them, the file is a
 *    lie - and a lie of the worst kind, because a reader who sets `VITE_APP_NAME` sees the app
 *    keep calling itself "Aurora" and concludes the whole config file is decorative. A
 *    documented setting must have exactly one reader, and it must be here.
 *
 * 2. **They need sensible fallbacks in one place.** `import.meta.env.X` is `undefined` in a
 *    production build if the variable was not set, and `undefined` reaching the UI renders as
 *    the literal string "undefined". Defaults belong at the boundary.
 *
 * 3. **An empty string is not the same as unset.** Vite substitutes a *declared but empty*
 *    variable as `""`, which is falsy but NOT nullish - so `?? 'default'` does not catch it.
 *    Anyone who copies `.env.example` gets exactly that, which is why every value below is
 *    parsed through a helper rather than read directly. This is not hypothetical: it is the
 *    shape of the `VITE_API_BASE_URL` landmine documented in `src/api/client.js`.
 *
 * Only `VITE_`-prefixed variables reach the bundle. That is a safety mechanism, not a naming
 * convention: anything else in `.env` stays on the build machine. It is also why no secret can
 * live here - the bundle is public.
 */

/**
 * Reads a string setting, treating empty and whitespace-only as absent.
 *
 * The `trim()` matters because `VITE_APP_NAME=` in a copied `.env` yields `""`, and a name of
 * `""` renders as a blank header rather than falling back.
 */
function readString(raw, fallback) {
  const value = typeof raw === 'string' ? raw.trim() : ''
  return value === '' ? fallback : value
}

/**
 * Reads a positive integer setting.
 *
 * Clamped rather than rejected. A bad `VITE_PRODUCTS_PAGE_SIZE` is a typo, not a security
 * boundary - the backend independently caps `size` at 100 and answers 400 beyond it, so the
 * worst case here is an ugly grid, and failing to boot over it would be a worse trade.
 */
function readInt(raw, fallback, { min = 1, max = 100 } = {}) {
  const parsed = Number.parseInt(readString(raw, ''), 10)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(Math.max(parsed, min), max)
}

const env = import.meta.env

export const config = {
  /**
   * Display name, used in the header, footer, home hero and the document title.
   *
   * Kept in configuration rather than hardcoded so white-labelling does not require a code
   * change. It appears in several components, which was previously six copies of the same
   * literal - the kind of duplication that produces a half-renamed product.
   */
  appName: readString(env.VITE_APP_NAME, 'Aurora'),

  /**
   * Rows requested per product page.
   *
   * 12 divides evenly into the 2/3/4-column grid at the layout's breakpoints, so the last row
   * is never a lonely orphan on common screen sizes. The bound mirrors the server's own cap.
   */
  productsPageSize: readInt(env.VITE_PRODUCTS_PAGE_SIZE, 12),

  /**
   * Origin the demo images are served from.
   *
   * Only used to recognise a placeholder URL; the seed data carries absolute URLs, so this is
   * a hint rather than a prefix to build paths from. Empty means "do not special-case
   * anything", which is the right behaviour once real assets replace the placeholders.
   */
  imageHost: readString(env.VITE_IMAGE_HOST, ''),

  /**
   * Development-only flag from Vite itself, not from `.env`.
   *
   * Used to decide whether to render the demo-credential hint on the login page. Reading it
   * from `import.meta.env.DEV` rather than a custom variable means it cannot be accidentally
   * turned on in a production bundle.
   */
  isDev: Boolean(env.DEV),
}

export default config
