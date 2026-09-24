import { http } from './client'

/**
 * The cart, and checkout.
 *
 * Mirrors CartController and the customer half of OrderController.
 *
 * <h2>A note on what these calls return</h2>
 *
 * Every cart mutation returns the WHOLE updated cart, not just the changed line. That is a
 * deliberate backend decision and the frontend should lean on it: after an add, an update or
 * a remove, there is no need to re-fetch, and no need to patch a local copy and hope the
 * arithmetic matches the server's. The response is the new truth for subtotal, item count,
 * availability and `checkoutReady` alike.
 *
 * Recomputing the subtotal in the browser instead would be a second implementation of the
 * same rule, and the two would eventually disagree - most likely over rounding, since the
 * backend uses BigDecimal and JavaScript uses binary floating point.
 */

/** The current user's cart, creating an empty one on first access if needed. */
export const getCart = (options) => http.get('/api/cart', undefined, options)

/**
 * Adds a product, or increases its quantity if the line already exists.
 *
 * Throws ApiError with code INSUFFICIENT_STOCK when the requested quantity exceeds what is
 * available - including the case where the line already holds some of it. The `message` is
 * human-readable; code INSUFFICIENT_STOCK is what to branch on.
 */
export const addToCart = ({ productId, quantity }) =>
  http.post('/api/cart/items', { productId, quantity })

/** Sets a line to an absolute quantity. Quantity 0 is rejected; remove the line instead. */
export const updateCartItem = (itemId, quantity) =>
  http.put(`/api/cart/items/${itemId}`, { quantity })

export const removeCartItem = (itemId) => http.delete(`/api/cart/items/${itemId}`)

export const clearCart = () => http.delete('/api/cart')

/**
 * Places an order from the current cart.
 *
 * <h3>What this endpoint does, and why the UI must not duplicate it</h3>
 *
 * The backend re-reads every product under a row lock, re-checks stock against current
 * values, decrements it, snapshots the unit price into each order line, clears the cart and
 * commits - all in one transaction. If any line is short, nothing happens at all.
 *
 * So the client's stock check before calling this is a courtesy, not a guarantee: stock can
 * change between rendering the cart and pressing the button. The failure that matters is
 * INSUFFICIENT_STOCK arriving *from this call*, and the UI has to handle it as a normal
 * outcome rather than an error state. Prices are snapshotted for the same reason - showing a
 * cart total and charging it are different acts, and the order records what was actually
 * charged.
 */
export const placeOrder = ({ shippingAddress }) =>
  http.post('/api/orders', { shippingAddress })
