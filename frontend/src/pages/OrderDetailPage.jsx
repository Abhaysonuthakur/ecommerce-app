import { Link, useParams } from 'react-router-dom'
import { getMyOrder, orderStatusLabel, orderStatusTone } from '../api/orders'
import { Badge, ErrorState, Skeleton } from '../components/ui'
import { useApiQuery } from '../hooks/useApi'
import { formatDateTime, formatMoney } from '../lib/format'

/**
 * One order, in full.
 *
 * Reached from the order list and immediately after checkout, so it must work for both a
 * fresh order (navigated to with replace) and a deep link from an email.
 *
 * <h3>Why the line prices shown here may differ from the product page</h3>
 *
 * Each `OrderItemResponse` carries `unitPrice` as it was *at the time of the order*, captured
 * from the product row during checkout. That is the price the customer was charged, and it
 * is deliberately not re-read from the product - a price rise tomorrow must not silently
 * rewrite yesterday's invoice. The note at the bottom of the page says so, because a
 * customer comparing the two would otherwise reasonably think one of them was wrong.
 */
export function OrderDetailPage() {
  const { orderId } = useParams()

  const order = useApiQuery((signal) => getMyOrder(orderId, { signal }), [orderId])
  const data = order.data

  if (order.error) {
    const notFound = order.error.code === 'ORDER_NOT_FOUND'

    return (
      <div className="page">
        <div className="container">
          <ErrorState
            error={order.error}
            title={notFound ? 'Order not found' : 'Could not load this order'}
            /*
             * No retry for a 404. "Not found" is a definitive answer, and offering to retry
             * implies the server might change its mind. This also covers the security case:
             * another customer's order returns 404 rather than 403 by design, so the API does
             * not confirm that the id exists.
             */
            onRetry={notFound ? undefined : () => order.refetch()}
          />
          <p className="u-center">
            <Link className="btn btn--secondary" to="/orders">
              Back to my orders
            </Link>
          </p>
        </div>
      </div>
    )
  }

  if (order.loading || !data) {
    return (
      <div className="page">
        <div className="container">
          <Skeleton width="200px" height="2rem" />
          <Skeleton height="140px" radius="12px" />
          <Skeleton height="240px" radius="12px" />
        </div>
      </div>
    )
  }

  return (
    <div className="page">
      <div className="container container--narrow">
        <nav className="breadcrumbs" aria-label="Breadcrumb">
          <Link to="/orders">My orders</Link>
          <span aria-hidden="true">/</span>
          <span aria-current="page">{data.orderNumber}</span>
        </nav>

        <header className="order-head">
          <div>
            <h1 className="page__title">{data.orderNumber}</h1>
            <p className="page__subtitle">Placed {formatDateTime(data.createdAt)}</p>
          </div>
          <Badge tone={orderStatusTone(data.status)}>{orderStatusLabel(data.status)}</Badge>
        </header>

        <section className="card">
          <h2 className="card__title">Items</h2>

          <div className="order-lines">
            {data.items?.map((item) => (
              <div key={item.id} className="order-line">
                <div className="order-line__media">
                  {item.imageUrl ? (
                    <img src={item.imageUrl} alt={item.productName} loading="lazy" />
                  ) : (
                    <div className="cart-line__placeholder" aria-hidden="true">
                      <span>{item.productName?.charAt(0) ?? '?'}</span>
                    </div>
                  )}
                </div>

                <div className="order-line__body">
                  {/*
                   * Linked back to the product so the customer can reorder - but only as a
                   * link, because the product may since have been withdrawn. The order keeps
                   * its own snapshot of the name and price regardless.
                   */}
                  <Link to={`/products/${item.productId}`} className="order-line__name">
                    {item.productName}
                  </Link>
                  <p className="order-line__unit">
                    {item.quantity} × {formatMoney(item.unitPrice)}
                  </p>
                </div>

                <p className="order-line__subtotal">{formatMoney(item.subtotal)}</p>
              </div>
            ))}
          </div>

          <div className="order-total">
            <span>Total</span>
            <strong>{formatMoney(data.totalAmount)}</strong>
          </div>
        </section>

        <section className="card">
          <h2 className="card__title">Delivery</h2>
          <dl className="detail__facts">
            <div>
              <dt>Shipping address</dt>
              <dd>{data.shippingAddress ?? 'Not recorded'}</dd>
            </div>
            <div>
              <dt>Customer</dt>
              <dd>{data.customerName}</dd>
            </div>
            <div>
              <dt>Contact</dt>
              <dd>{data.customerEmail}</dd>
            </div>
            <div>
              <dt>Last updated</dt>
              <dd>{formatDateTime(data.updatedAt)}</dd>
            </div>
          </dl>
        </section>

        {/*
         * The next legal transitions, straight from the server.
         *
         * This is informational for a customer - they cannot change their own order status.
         * It is shown because "what happens next" is the question someone looks at this page
         * to answer, and `allowedNextStatuses` is the backend's own answer, so the page
         * cannot drift from the state machine.
         */}
        <p className="order-note">
          {data.allowedNextStatuses?.length
            ? `This order can next move to: ${data.allowedNextStatuses.map(orderStatusLabel).join(', ')}.`
            : 'This order is in a final state.'}
        </p>

        <p className="order-note order-note--muted">
          Prices shown are those charged at the time of the order and do not change if the
          product price changes later.
        </p>
      </div>
    </div>
  )
}
