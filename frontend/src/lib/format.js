/**
 * Formatting helpers.
 *
 * <h2>Why money is formatted from a string, not a number</h2>
 *
 * The backend sends prices as JSON numbers, but the underlying Java type is BigDecimal with
 * two decimal places, and JSON does not preserve that. `1499.00` arrives as the JavaScript
 * number `1499`, and `Number.prototype.toFixed(2)` then reconstructs "1499.00" by rounding a
 * binary float - which is correct for the values in this catalogue and quietly wrong for
 * others. `0.1 + 0.2` is the canonical example.
 *
 * This is the reasoning behind the backend's own "never use double for money" rule, and it
 * does not stop being true on the client. So values are treated as decimal strings
 * throughout, and formatting never does arithmetic on them.
 *
 * For the totals the backend has already computed, the value is used exactly as sent - the
 * client does not add the lines up, because the server already did, with BigDecimal, and two
 * implementations of a pricing rule eventually disagree.
 */

const inr = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

/**
 * Formats a price.
 *
 * Accepts what the API actually sends: a number, or a numeric string. Returns an em dash for
 * null/undefined rather than "NaN" or a crash, because a missing price in a product grid
 * should look like missing data, not like a broken page.
 */
export function formatMoney(value) {
  if (value === null || value === undefined || value === '') return '—'

  const numeric = typeof value === 'string' ? Number(value) : value
  if (!Number.isFinite(numeric)) return '—'

  return inr.format(numeric)
}

/** A compact form for dashboard tiles, where the exact paise are noise. */
export function formatMoneyCompact(value) {
  if (value === null || value === undefined || value === '') return '—'

  const numeric = typeof value === 'string' ? Number(value) : value
  if (!Number.isFinite(numeric)) return '—'

  if (numeric >= 10000000) return `₹${(numeric / 10000000).toFixed(2)}Cr`
  if (numeric >= 100000) return `₹${(numeric / 100000).toFixed(2)}L`
  if (numeric >= 1000) return `₹${(numeric / 1000).toFixed(1)}K`

  return inr.format(numeric)
}

/**
 * Formats an instant as a readable date.
 *
 * The backend sends ISO-8601 with a zone (e.g. `2026-09-24T07:12:33Z`), which `Date` parses
 * correctly and renders in the viewer's local zone. Formatting a server timestamp as though
 * it were local time is a classic bug: an order placed at 22:00 in Delhi would appear as the
 * previous day to a reader in London, or vice versa.
 */
export function formatDateTime(value) {
  if (!value) return '—'

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'

  return date.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function formatDate(value) {
  if (!value) return '—'

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'

  return date.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
}

/**
 * Describes how long ago something happened.
 *
 * Deliberately coarse. Precision here would be false: the timestamps come from the database
 * and a "3 seconds ago" label is stale the moment it renders, so anything under a minute is
 * just "just now".
 */
export function formatRelative(value) {
  if (!value) return '—'

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'

  const seconds = Math.floor((Date.now() - date.getTime()) / 1000)

  if (seconds < 60) return 'just now'
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`
  if (seconds < 604800) return `${Math.floor(seconds / 86400)}d ago`

  return formatDate(value)
}

/** Truncates for a card, without splitting a word in half. */
export function truncate(text, max = 120) {
  if (!text) return ''
  if (text.length <= max) return text

  const clipped = text.slice(0, max)
  const lastSpace = clipped.lastIndexOf(' ')
  return `${lastSpace > max * 0.6 ? clipped.slice(0, lastSpace) : clipped}…`
}

/** Turns a category slug into a label: "home-decor" -> "Home Decor". */
export function humanise(slug) {
  if (!slug) return ''
  return slug
    .split(/[-_]/)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}
