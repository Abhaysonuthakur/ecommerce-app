import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { placeOrder } from '../api/cart'
import { useAuth } from '../context/AuthContext'
import { useCart } from '../context/CartContext'
import { useToast } from '../context/ToastContext'
import { Alert, Badge, EmptyState, Field, Skeleton } from '../components/ui'
import { formatMoney } from '../lib/format'

/**
 * The cart, and checkout.
 *
 * <h2>The one thing this page must get right</h2>
 *
 * Checking out is a real inventory operation. The backend re-reads every product under a row
 * lock, re-verifies stock, decrements it, snapshots prices, clears the cart and commits - or
 * rolls the whole thing back. So `INSUFFICIENT_STOCK` is a normal, expected response here,
 * not an exceptional one: someone else can buy the last unit while this page is open, and
 * that is precisely the race the backend's locking is there to settle correctly.
 *
 * The UI therefore treats that failure as a state to recover from - it reloads the cart so
 * the user sees the truth - rather than as an error to apologise for.
 */
export function CartPage() {
  const { isAuthenticated, user } = useAuth()
  const { cart, loading, error, pendingItemId, updateItem, removeItem, clear, loadCart, resetAfterCheckout } =
    useCart()
  const toast = useToast()
  const navigate = useNavigate()

  const [shippingAddress, setShippingAddress] = useState(user?.address ?? '')
  const [addressError, setAddressError] = useState(null)
  const [placing, setPlacing] = useState(false)
  const [checkoutError, setCheckoutError] = useState(null)

  if (!isAuthenticated) {
    return (
      <div className="page">
        <div className="container">
          <EmptyState
            title="Sign in to see your cart"
            message="Your cart is stored against your account, so it follows you between devices."
            action={
              <Link className="btn btn--primary" to="/login" state={{ from: { pathname: '/cart' } }}>
                Sign in
              </Link>
            }
          />
        </div>
      </div>
    )
  }

  if (loading && !cart) {
    return (
      <div className="page">
        <div className="container">
          <h1 className="page__title">Your cart</h1>
          <div className="cart-layout">
            <div className="cart-lines">
              {[0, 1].map((index) => (
                <div key={index} className="cart-line">
                  <Skeleton width="88px" height="88px" radius="10px" />
                  <div className="cart-line__body">
                    <Skeleton width="55%" />
                    <Skeleton width="25%" />
                  </div>
                </div>
              ))}
            </div>
            <Skeleton height="260px" radius="14px" />
          </div>
        </div>
      </div>
    )
  }

  if (error && !cart) {
    return (
      <div className="page">
        <div className="container">
          <Alert tone="danger" title="Could not load your cart">
            {error.message}
          </Alert>
          <p className="u-center">
            <button type="button" className="btn btn--secondary" onClick={() => loadCart()}>
              Try again
            </button>
          </p>
        </div>
      </div>
    )
  }

  if (!cart || cart.empty) {
    return (
      <div className="page">
        <div className="container">
          <h1 className="page__title">Your cart</h1>
          <EmptyState
            title="Your cart is empty"
            message="Browse the catalogue and add something to get started."
            action={
              <Link className="btn btn--primary" to="/products">
                Browse products
              </Link>
            }
          />
        </div>
      </div>
    )
  }

  /**
   * Places the order.
   *
   * Note what this does NOT do: it does not send prices, and it does not send a total. The
   * server derives both from the cart's own lines and the products' current rows, which is
   * the only way the charge can be trusted - a client that sent its own total could send any
   * total.
   */
  const handleCheckout = async (event) => {
    event.preventDefault()

    const address = shippingAddress.trim()
    if (!address) {
      setAddressError('Please enter a shipping address.')
      return
    }
    if (address.length > 255) {
      setAddressError('Shipping address must not exceed 255 characters.')
      return
    }

    setAddressError(null)
    setCheckoutError(null)
    setPlacing(true)

    try {
      const order = await placeOrder({ shippingAddress: address })
      /*
       * The server cleared the cart as part of the same transaction, so the local copy is
       * already stale. resetAfterCheckout drops it rather than issuing a DELETE, which would
       * be a second request racing the first for no benefit.
       */
      resetAfterCheckout()
      toast.success(`Order ${order.orderNumber} placed`, 'Thank you')
      navigate(`/orders/${order.id}`, { replace: true })
    } catch (cause) {
      /*
       * INSUFFICIENT_STOCK deserves its own handling. The cart on screen is now wrong - it
       * was rendered before someone else bought the stock - so the useful action is to
       * refresh it and show what actually changed, not to report a failure and leave the
       * user looking at stale numbers.
       */
      if (cause.code === 'INSUFFICIENT_STOCK') {
        setCheckoutError({
          tone: 'warning',
          title: 'Some items are no longer available',
          message: cause.message,
        })
        await loadCart()
      } else if (cause.code === 'EMPTY_CART') {
        setCheckoutError({
          tone: 'warning',
          title: 'Your cart is empty',
          message: 'It may have been cleared in another tab.',
        })
        await loadCart()
      } else if (cause.code === 'PRODUCT_UNAVAILABLE') {
        setCheckoutError({
          tone: 'warning',
          title: 'A product was withdrawn',
          message: cause.message,
        })
        await loadCart()
      } else {
        setCheckoutError({ tone: 'danger', title: 'Could not place the order', message: cause.message })
      }
    } finally {
      setPlacing(false)
    }
  }

  const blockedLines = cart.items.filter((line) => !line.available || !line.hasEnoughStock)

  return (
    <div className="page">
      <div className="container">
        <header className="page__head">
          <h1 className="page__title">Your cart</h1>
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={async () => {
              const result = await clear()
              if (result.ok) toast.info('Cart cleared')
            }}
            disabled={pendingItemId === 'cart'}
          >
            {pendingItemId === 'cart' ? 'Clearing…' : 'Clear cart'}
          </button>
        </header>

        <div className="cart-layout">
          <section className="cart-lines" aria-label="Cart items">
            {cart.items.map((line) => (
              <CartLine
                key={line.id}
                line={line}
                pending={pendingItemId === line.id}
                onUpdate={(quantity) => updateItem(line.id, quantity)}
                onRemove={() => removeItem(line.id)}
              />
            ))}
          </section>

          <aside className="cart-summary" aria-label="Order summary">
            <h2 className="cart-summary__title">Summary</h2>

            <dl className="cart-summary__rows">
              <div>
                <dt>Items</dt>
                <dd>
                  {cart.totalItems} {cart.totalItems === 1 ? 'unit' : 'units'}
                </dd>
              </div>
              <div>
                <dt>Lines</dt>
                <dd>{cart.itemCount}</dd>
              </div>
              <div className="cart-summary__total">
                <dt>Subtotal</dt>
                {/*
                 * The server's subtotal, printed as sent. It is not recomputed from the lines
                 * - that would be a second pricing implementation, and it would disagree with
                 * the first over rounding.
                 */}
                <dd>{formatMoney(cart.subtotal)}</dd>
              </div>
            </dl>

            {blockedLines.length > 0 && (
              <Alert tone="warning" title="Some items need attention">
                <ul className="alert__list">
                  {blockedLines.map((line) => (
                    <li key={line.id}>
                      <strong>{line.productName}</strong>
                      {!line.available
                        ? ' is no longer available.'
                        : ` — only ${line.availableStock} in stock.`}
                    </li>
                  ))}
                </ul>
                <p>Adjust or remove them, then place the order.</p>
              </Alert>
            )}

            {checkoutError && (
              <Alert tone={checkoutError.tone} title={checkoutError.title}>
                {checkoutError.message}
              </Alert>
            )}

            <form onSubmit={handleCheckout} noValidate>
              <Field
                id="shippingAddress"
                label="Shipping address"
                as="textarea"
                rows={3}
                required
                value={shippingAddress}
                onChange={(event) => {
                  setShippingAddress(event.target.value)
                  setAddressError(null)
                }}
                error={addressError}
                placeholder="123 Example Street, City, State, PIN"
                hint="Up to 255 characters."
              />

              <button
                type="submit"
                className="btn btn--primary btn--block"
                /*
                 * Disabled while any line is short. The server would reject it anyway - this
                 * is a courtesy that saves a round trip, not a correctness check. The real
                 * verification happens inside the checkout transaction, because stock can
                 * change between this render and that call.
                 */
                disabled={placing || !cart.checkoutReady || blockedLines.length > 0}
              >
                {placing ? 'Placing order…' : `Place order · ${formatMoney(cart.subtotal)}`}
              </button>
            </form>

            <p className="cart-summary__note">
              Totals are calculated by the server when the order is placed.
            </p>
          </aside>
        </div>
      </div>
    </div>
  )
}

/**
 * One cart line.
 *
 * The quantity control clamps against `availableStock`, which arrives on the line itself -
 * so this component never needs to fetch or know about the product. That is the value of the
 * backend's CartItemResponse carrying `availableStock`, `available` and `hasEnoughStock`: the
 * cart page can render everything without a second request per line.
 */
function CartLine({ line, pending, onUpdate, onRemove }) {
  const maxQuantity = Math.max(1, line.availableStock ?? 1)

  return (
    <article className={`cart-line ${!line.available || !line.hasEnoughStock ? 'cart-line--blocked' : ''}`}>
      <div className="cart-line__media">
        {line.imageUrl ? (
          <img src={line.imageUrl} alt={line.productName} loading="lazy" />
        ) : (
          <div className="cart-line__placeholder" aria-hidden="true">
            <span>{line.productName?.charAt(0) ?? '?'}</span>
          </div>
        )}
      </div>

      <div className="cart-line__body">
        <div className="cart-line__head">
          <Link to={`/products/${line.productId}`} className="cart-line__name">
            {line.productName}
          </Link>
          <p className="cart-line__unit">{formatMoney(line.unitPrice)} each</p>
        </div>

        <div className="cart-line__badges">
          {!line.available ? (
            <Badge tone="danger">No longer available</Badge>
          ) : !line.hasEnoughStock ? (
            <Badge tone="warning">Only {line.availableStock} in stock</Badge>
          ) : (
            <Badge tone="success">In stock</Badge>
          )}
        </div>
      </div>

      <div className="cart-line__side">
        <div className="qty qty--compact">
          <label className="sr-only" htmlFor={`qty-${line.id}`}>
            Quantity for {line.productName}
          </label>
          <div className="qty__control">
            <button
              type="button"
              className="qty__button"
              onClick={() => onUpdate(line.quantity - 1)}
              /*
               * At 1 the next step would be 0, and the backend rejects quantity 0 with
               * INVALID_OPERATION - removing a line is a DELETE, not a quantity of zero. So
               * the control stops at 1 and deletion is the explicit button below.
               */
              disabled={pending || line.quantity <= 1}
              aria-label="Decrease quantity"
            >
              −
            </button>
            <input
              id={`qty-${line.id}`}
              type="number"
              className="qty__input"
              min="1"
              max={maxQuantity}
              value={line.quantity}
              onChange={(event) => {
                const next = Number(event.target.value)
                if (Number.isNaN(next)) return
                onUpdate(Math.min(Math.max(1, next), maxQuantity))
              }}
              disabled={pending}
            />
            <button
              type="button"
              className="qty__button"
              onClick={() => onUpdate(line.quantity + 1)}
              disabled={pending || line.quantity >= maxQuantity}
              aria-label="Increase quantity"
            >
              +
            </button>
          </div>
        </div>

        <p className="cart-line__subtotal">{formatMoney(line.subtotal)}</p>

        <button
          type="button"
          className="cart-line__remove"
          onClick={onRemove}
          disabled={pending}
          aria-label={`Remove ${line.productName} from cart`}
        >
          {pending ? 'Removing…' : 'Remove'}
        </button>
      </div>
    </article>
  )
}
