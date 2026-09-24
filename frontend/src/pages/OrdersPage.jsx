import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getMyOrder, listMyOrders, ORDER_SORT_OPTIONS, ORDER_STATUSES, orderStatusLabel, orderStatusTone } from '../api/orders'
import { Badge, EmptyState, ErrorState, Pagination, Skeleton } from '../components/ui'
import { useApiQuery } from '../hooks/useApi'
import { formatDateTime, formatMoney, formatRelative } from '../lib/format'

/**
 * The customer's order history.
 *
 * Paginated and server-filtered by status, for the same reason the catalogue is: an account
 * with three years of orders should not receive all of them in one response to render a
 * table that shows twenty.
 */
export function OrdersPage() {
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState('')

  const orders = useApiQuery(
    (signal) => listMyOrders({ page, size: 10, status: status || undefined, sort: 'createdAt,desc' }, { signal }),
    [page, status],
  )

  const result = orders.data

  return (
    <div className="page">
      <div className="container">
        <header className="page__head">
          <div>
            <h1 className="page__title">My orders</h1>
            <p className="page__subtitle">
              {result ? `${result.totalElements} order${result.totalElements === 1 ? '' : 's'}` : 'Loading your history'}
            </p>
          </div>

          <div className="filters__group">
            <label className="filters__label" htmlFor="order-status">
              Status
            </label>
            <select
              id="order-status"
              className="filters__select"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value)
                // Reset to the first page: staying on page 3 of a narrower filter usually
                // lands past the end of the results.
                setPage(0)
              }}
            >
              <option value="">All statuses</option>
              {ORDER_STATUSES.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
        </header>

        {orders.error ? (
          <ErrorState error={orders.error} title="Could not load your orders" onRetry={() => orders.refetch()} />
        ) : orders.loading && !result ? (
          <div className="order-list">
            {[0, 1, 2].map((index) => (
              <Skeleton key={index} height="96px" radius="12px" />
            ))}
          </div>
        ) : result?.content?.length ? (
          <>
            <div className="order-list">
              {result.content.map((order) => (
                <OrderRow key={order.id} order={order} />
              ))}
            </div>

            <Pagination
              page={result.page}
              totalPages={result.totalPages}
              first={result.first}
              last={result.last}
              totalElements={result.totalElements}
              onChange={setPage}
              label="orders"
            />
          </>
        ) : (
          <EmptyState
            title={status ? `No ${orderStatusLabel(status).toLowerCase()} orders` : 'No orders yet'}
            message={status ? 'Try a different status filter.' : 'Anything you buy will appear here.'}
            action={
              <Link className="btn btn--primary" to="/products">
                Browse products
              </Link>
            }
          />
        )}
      </div>
    </div>
  )
}

/**
 * A row in the order list.
 *
 * Shows the order number, not the raw id. `ORD-000004` is the same value the backend
 * generates for display, and it is what a customer would quote to support - so it is
 * generated here by the same rule rather than the id being shown with a prefix bolted on.
 */
function OrderRow({ order }) {
  return (
    <Link to={`/orders/${order.id}`} className="order-row">
      <div className="order-row__main">
        <p className="order-row__number">{order.orderNumber}</p>
        <p className="order-row__meta">
          {formatDateTime(order.createdAt)} · {order.totalItems}{' '}
          {order.totalItems === 1 ? 'item' : 'items'}
        </p>
        {order.items?.length > 0 && (
          <p className="order-row__preview">
            {order.items
              .slice(0, 2)
              .map((item) => `${item.quantity} × ${item.productName}`)
              .join(', ')}
            {order.items.length > 2 ? ` +${order.items.length - 2} more` : ''}
          </p>
        )}
      </div>

      <div className="order-row__side">
        <Badge tone={orderStatusTone(order.status)}>{orderStatusLabel(order.status)}</Badge>
        <p className="order-row__total">{formatMoney(order.totalAmount)}</p>
      </div>
    </Link>
  )
}
