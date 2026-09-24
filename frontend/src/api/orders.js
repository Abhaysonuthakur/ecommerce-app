import { http } from './client'

/**
 * Orders: the customer's own, and the admin console's view of all of them.
 *
 * Mirrors OrderController.
 */

/** Fields the backend accepts for order sorting. */
export const ORDER_SORT_OPTIONS = [
  { value: '', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'totalAmount,desc', label: 'Highest value' },
  { value: 'totalAmount,asc', label: 'Lowest value' },
]

/**
 * The status machine, mirroring the backend's OrderStatus enum.
 *
 * <h3>This list must contain every status the backend can return</h3>
 *
 * A status missing from here does not error - `orderStatusLabel` falls back to the raw value
 * and `orderStatusTone` to neutral, so the UI degrades quietly. That is the dangerous kind of
 * bug, because nothing looks broken: an order in the missing state renders with an unstyled
 * badge and a raw uppercase enum name, and only someone who knew what it should say would
 * notice the difference.
 *
 * This file originally omitted PROCESSING, which is why the comment exists.
 *
 * <h3>The transitions below are documentation only</h3>
 *
 * The authority is each order's own `allowedNextStatuses`, computed server-side from the
 * current state (see OrderStatus.allowedNext in the backend). The admin UI builds its buttons
 * from that field, not from this table, so offering an illegal transition is impossible
 * rather than merely discouraged. This map records the machine for the reader.
 */
export const ORDER_STATUSES = [
  { value: 'PENDING', label: 'Pending', tone: 'warning' },
  { value: 'CONFIRMED', label: 'Confirmed', tone: 'info' },
  { value: 'PROCESSING', label: 'Processing', tone: 'info' },
  { value: 'SHIPPED', label: 'Shipped', tone: 'info' },
  { value: 'DELIVERED', label: 'Delivered', tone: 'success' },
  { value: 'CANCELLED', label: 'Cancelled', tone: 'danger' },
]

/** Mirrors OrderStatus.allowedNext(). Kept in step with the backend by the note above. */
export const ORDER_TRANSITIONS = {
  PENDING: ['CONFIRMED', 'CANCELLED'],
  CONFIRMED: ['PROCESSING', 'CANCELLED'],
  PROCESSING: ['SHIPPED', 'CANCELLED'],
  SHIPPED: ['DELIVERED', 'CANCELLED'],
  DELIVERED: [],
  CANCELLED: [],
}

export function orderStatusLabel(status) {
  return ORDER_STATUSES.find((s) => s.value === status)?.label ?? status
}

export function orderStatusTone(status) {
  return ORDER_STATUSES.find((s) => s.value === status)?.tone ?? 'neutral'
}

/** The signed-in customer's orders, newest first by default. Returns a PageResponse. */
export const listMyOrders = ({ page, size, status, sort } = {}, options) =>
  http.get('/api/orders', { page, size, status, sort }, options)

export const getMyOrder = (orderId, options) => http.get(`/api/orders/${orderId}`, undefined, options)

/** Every order in the system. Admin only. */
export const listAllOrders = ({ page, size, status, sort } = {}, options) =>
  http.get('/api/admin/orders', { page, size, status, sort }, options)

export const getOrder = (orderId, options) => http.get(`/api/admin/orders/${orderId}`, undefined, options)

/**
 * Moves an order to a new status. Admin only.
 *
 * Only the transitions listed in the order's own `allowedNextStatuses` will succeed;
 * anything else fails with INVALID_ORDER_STATUS_TRANSITION. Cancelling is special and the
 * reason the backend keeps this endpoint transactional: CANCELLED restores each line's
 * quantity to its product's stock, so it is a real inventory operation, not just a label
 * change.
 */
export const updateOrderStatus = (orderId, status) =>
  http.patch(`/api/admin/orders/${orderId}/status`, { status })
