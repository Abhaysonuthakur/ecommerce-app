import { http } from './client'

/**
 * Product and category reads for the storefront, plus the admin writes.
 *
 * Mirrors ProductController and CategoryController.
 */

/** Fields the backend's SortValidator accepts, in the order the UI offers them. */
export const PRODUCT_SORT_OPTIONS = [
  { value: '', label: 'Relevance' },
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'price,asc', label: 'Price: low to high' },
  { value: 'price,desc', label: 'Price: high to low' },
  { value: 'name,asc', label: 'Name: A to Z' },
  { value: 'name,desc', label: 'Name: Z to A' },
  { value: 'stock,desc', label: 'Stock: high to low' },
]

/**
 * Lists products with search, filtering, sorting and pagination - all server-side.
 *
 * <h3>Why every one of these is a server parameter</h3>
 *
 * The tempting shortcut is to fetch one large page and filter in the browser. That is wrong
 * in a way that only shows up with real data: the total count is wrong, pagination cannot
 * work (the client has some rows, not all), and the request still transfers the whole table.
 * The backend already implements specification-based filtering and returns a
 * `PageResponse` with `totalElements` and `totalPages`, so the correct thing costs nothing.
 *
 * `active` is intentionally tri-state and passed through as-is:
 *   undefined -> omitted -> storefront default (active only)
 *   true      -> explicit, active only
 *   false     -> admin console, withdrawn stock included
 * Coercing it to a boolean here would destroy the distinction the backend relies on.
 */
export const listProducts = ({ page, size, keyword, categoryId, minPrice, maxPrice, active, sort }, options) =>
  http.get(
    '/api/products',
    { page, size, keyword, categoryId, minPrice, maxPrice, active, sort },
    options,
  )

/** One product by id. Throws ApiError with code PRODUCT_NOT_FOUND if it is gone. */
export const getProduct = (id, options) => http.get(`/api/products/${id}`, undefined, options)

/** Active categories, for the storefront nav and filters. */
export const listCategories = (options) => http.get('/api/categories', undefined, options)

export const getCategory = (id, options) => http.get(`/api/categories/${id}`, undefined, options)

/** All categories including deactivated ones. Admin only. */
export const listAllCategories = (options) => http.get('/api/admin/categories', undefined, options)

export const createCategory = (body) => http.post('/api/admin/categories', body)

export const updateCategory = (id, body) => http.put(`/api/admin/categories/${id}`, body)

/**
 * Deactivates rather than deletes.
 *
 * A category with products cannot be deleted - the foreign key forbids it - and the backend
 * answers with PRODUCT_IN_USE. Deactivation is what the UI offers, which is also the honest
 * model: historical orders reference the category, so removing the row would corrupt them.
 */
export const deactivateCategory = (id) => http.patch(`/api/admin/categories/${id}/deactivate`)

export const createProduct = (body) => http.post('/api/admin/products', body)

export const updateProduct = (id, body) => http.put(`/api/admin/products/${id}`, body)

/** Soft-hides a product from the storefront while leaving existing orders intact. */
export const deactivateProduct = (id) => http.patch(`/api/admin/products/${id}/deactivate`)

/** Hard delete. Fails with PRODUCT_IN_USE when order history references the product. */
export const deleteProduct = (id) => http.delete(`/api/admin/products/${id}`)
