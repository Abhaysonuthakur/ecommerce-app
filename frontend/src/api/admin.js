import { http } from './client'

/**
 * Admin-only endpoints: the dashboard, and user management.
 *
 * Mirrors AdminController and the admin half of UserController. Every one of these requires
 * an ADMIN role; the backend answers 403 ACCESS_DENIED otherwise, and 401 when anonymous.
 */

/**
 * Aggregate counts and revenue for the admin dashboard.
 *
 * Returns: totalProducts, activeProducts, totalCategories, totalOrders, totalCustomers,
 * totalRevenue, ordersByStatus (a map), lowStockProducts.
 *
 * Computed in the database - counts and sums are aggregate queries, not loaded collections
 * counted in Java. That matters at scale, and it is also why this is one request rather than
 * six: six round trips to render a dashboard is a self-inflicted slow page.
 */
export const getDashboardStats = (options) => http.get('/api/admin/dashboard/stats', undefined, options)

/** Paged list of users, optionally filtered by role. */
export const listUsers = ({ page = 0, size = 20, role } = {}, options) =>
  http.get('/api/admin/users', { page, size, role }, options)

export const getUser = (userId, options) => http.get(`/api/admin/users/${userId}`, undefined, options)

/**
 * Promotes or demotes a user.
 *
 * Worth knowing when wiring the UI: the backend refuses to remove the last remaining
 * administrator. That check is deliberately not reproduced here - a client-side guard would
 * be a second implementation of a rule the server already enforces, and the server has to
 * enforce it regardless because the client can be bypassed.
 */
export const updateUserRole = (userId, role) =>
  http.patch(`/api/admin/users/${userId}/role`, { role })
