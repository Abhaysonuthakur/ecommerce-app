import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import * as cartApi from '../api/cart'
import { useAuth } from './AuthContext'

/**
 * The shopping cart, shared by every component that shows a count or a line.
 *
 * <h2>Why the cart is global state rather than per-page</h2>
 *
 * The header shows an item count on every screen. If the cart lived inside the cart page,
 * the header would need its own copy - and the two would disagree the moment a product was
 * added from the catalogue, because that action happens on a different page from the one
 * that renders the count. One owner, one source of truth.
 *
 * <h2>Why the server is the source of truth</h2>
 *
 * Every mutation returns the complete recalculated cart, so the state here is always a value
 * the server produced. The tempting optimisation - increment a local counter on add, and
 * recompute the subtotal in the browser - creates a second implementation of the pricing
 * rule that will drift from the first, most likely over rounding. The backend adds money
 * with BigDecimal; JavaScript adds it with binary floating point.
 */
const CartContext = createContext(null)

export function CartProvider({ children }) {
  const { isAuthenticated } = useAuth()

  const [cart, setCart] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  /** The id currently being mutated, so a single row can show a spinner without freezing the page. */
  const [pendingItemId, setPendingItemId] = useState(null)

  const applyCart = useCallback((next) => {
    setCart(next)
    setError(null)
    return next
  }, [])

  /**
   * Loads the cart.
   *
   * Guarded on `isAuthenticated` because the endpoint requires a token: calling it as an
   * anonymous visitor would produce a 401 on every page load, which is noise, not a bug
   * worth surfacing. A signed-out cart is simply null.
   */
  const loadCart = useCallback(async () => {
    if (!isAuthenticated) {
      setCart(null)
      return null
    }

    setLoading(true)
    try {
      return applyCart(await cartApi.getCart())
    } catch (cause) {
      setError(cause)
      return null
    } finally {
      setLoading(false)
    }
  }, [isAuthenticated, applyCart])

  useEffect(() => {
    loadCart()
  }, [loadCart])

  /**
   * Wraps a mutation so every one shares the same loading, error and state-application
   * behaviour. Returns the ApiError rather than throwing, because the callers are event
   * handlers that need to render the failure next to the button that caused it.
   */
  const mutate = useCallback(
    async (itemId, operation) => {
      setPendingItemId(itemId ?? 'cart')
      setError(null)
      try {
        return { ok: true, cart: applyCart(await operation()) }
      } catch (cause) {
        setError(cause)
        return { ok: false, error: cause }
      } finally {
        setPendingItemId(null)
      }
    },
    [applyCart],
  )

  const addItem = useCallback(
    (productId, quantity = 1) => mutate(productId, () => cartApi.addToCart({ productId, quantity })),
    [mutate],
  )

  const updateItem = useCallback(
    (itemId, quantity) => mutate(itemId, () => cartApi.updateCartItem(itemId, quantity)),
    [mutate],
  )

  const removeItem = useCallback((itemId) => mutate(itemId, () => cartApi.removeCartItem(itemId)), [mutate])

  const clear = useCallback(() => mutate(null, () => cartApi.clearCart()), [mutate])

  /**
   * Empties local state without calling the server.
   *
   * Used after a successful checkout. The server has already cleared the cart as part of
   * placing the order - calling the clear endpoint afterwards would be a second request
   * that races the first and can only ever fail or be redundant.
   */
  const resetAfterCheckout = useCallback(() => setCart(null), [])

  const value = useMemo(
    () => ({
      cart,
      loading,
      error,
      pendingItemId,
      itemCount: cart?.totalItems ?? 0,
      isEmpty: cart ? cart.empty : true,
      /** True when every line is in stock in the quantity requested. The server computes it. */
      checkoutReady: cart?.checkoutReady ?? false,
      addItem,
      updateItem,
      removeItem,
      clear,
      loadCart,
      resetAfterCheckout,
    }),
    [cart, loading, error, pendingItemId, addItem, updateItem, removeItem, clear, loadCart, resetAfterCheckout],
  )

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}

export function useCart() {
  const context = useContext(CartContext)
  if (!context) {
    throw new Error('useCart must be used inside a <CartProvider>')
  }
  return context
}
