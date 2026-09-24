import { Fragment, useMemo, useState } from 'react'
import { Link, NavLink, Route, Routes } from 'react-router-dom'
import { getDashboardStats, listUsers, updateUserRole } from '../../api/admin'
import { config } from '../../config'
import { listAllCategories, listProducts, createCategory, updateCategory, deactivateCategory, createProduct, updateProduct, deactivateProduct, deleteProduct } from '../../api/products'
import { listAllOrders, updateOrderStatus, orderStatusLabel, orderStatusTone, ORDER_STATUSES } from '../../api/orders'
import { useToast } from '../../context/ToastContext'
import { Alert, Badge, EmptyState, ErrorState, Field, Pagination, Skeleton } from '../../components/ui'
import { useApiQuery } from '../../hooks/useApi'
import { formatDateTime, formatMoney, formatMoneyCompact } from '../../lib/format'

/**
 * The admin console.
 *
 * <h2>Why everything here is also enforced on the server</h2>
 *
 * `RequireAdmin` in the router stops a customer from *rendering* these pages, and that is all
 * it does. It is not access control: a user could edit the token's claims, or simply call the
 * API directly. Every endpoint under `/api/admin/**` independently checks the role, answers
 * 401 when anonymous and 403 when not permitted, and that is the boundary that actually
 * holds. The guard exists to avoid showing a page that would only produce errors.
 */
export function AdminLayout() {
  return (
    <div className="page">
      <div className="container">
        <header className="page__head">
          <div>
            <h1 className="page__title">Admin console</h1>
            <p className="page__subtitle">Manage the catalogue, orders and users.</p>
          </div>
        </header>

        <nav className="tabs" aria-label="Admin sections">
          <NavLink to="/admin" end className="tabs__tab">Dashboard</NavLink>
          <NavLink to="/admin/products" className="tabs__tab">Products</NavLink>
          <NavLink to="/admin/categories" className="tabs__tab">Categories</NavLink>
          <NavLink to="/admin/orders" className="tabs__tab">Orders</NavLink>
          <NavLink to="/admin/users" className="tabs__tab">Users</NavLink>
        </nav>

        <Routes>
          <Route index element={<AdminDashboard />} />
          <Route path="products" element={<AdminProducts />} />
          <Route path="categories" element={<AdminCategories />} />
          <Route path="orders" element={<AdminOrders />} />
          <Route path="users" element={<AdminUsers />} />
        </Routes>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ *
 *  Dashboard
 * ------------------------------------------------------------------ */

function AdminDashboard() {
  const stats = useApiQuery((signal) => getDashboardStats({ signal }), [])
  const data = stats.data

  if (stats.error) {
    return <ErrorState error={stats.error} title="Could not load dashboard" onRetry={() => stats.refetch()} />
  }

  if (stats.loading && !data) {
    return (
      <div className="stat-grid">
        {[0, 1, 2, 3].map((index) => (
          <Skeleton key={index} height="110px" radius="12px" />
        ))}
      </div>
    )
  }

  return (
    <>
      <div className="stat-grid">
        <StatTile label="Revenue" value={formatMoneyCompact(data.totalRevenue)} tone="primary" />
        <StatTile label="Orders" value={data.totalOrders} hint="All time" />
        <StatTile label="Customers" value={data.totalCustomers} hint="Registered accounts" />
        <StatTile
          label="Products"
          value={data.totalProducts}
          hint={`${data.activeProducts} active · ${data.lowStockProducts} low stock`}
          tone={data.lowStockProducts > 0 ? 'warning' : undefined}
        />
      </div>

      <section className="card">
        <h2 className="card__title">Orders by status</h2>

        {data.ordersByStatus && Object.keys(data.ordersByStatus).length > 0 ? (
          <div className="status-grid">
            {ORDER_STATUSES.map((status) => {
              const count = data.ordersByStatus[status.value] ?? 0

              return (
                <Link key={status.value} to={`/admin/orders?status=${status.value}`} className="status-tile">
                  <Badge tone={status.tone}>{status.label}</Badge>
                  <span className="status-tile__count">{count}</span>
                </Link>
              )
            })}
          </div>
        ) : (
          <p className="u-muted">No orders yet.</p>
        )}

        {/*
         * Stated because it is the honest reading of the number above: this map is keyed by
         * the statuses that exist, and a status with no orders is absent rather than zero.
         * Without the note, "one status is missing" looks like a bug.
         */}
        <p className="order-note order-note--muted">
          Statuses with no orders are shown as zero. Revenue counts every order placed,
          including those later cancelled.
        </p>
      </section>
    </>
  )
}

function StatTile({ label, value, hint, tone }) {
  return (
    <div className={`stat-tile ${tone ? `stat-tile--${tone}` : ''}`}>
      <p className="stat-tile__label">{label}</p>
      <p className="stat-tile__value">{value}</p>
      {hint && <p className="stat-tile__hint">{hint}</p>}
    </div>
  )
}

/* ------------------------------------------------------------------ *
 *  Products
 * ------------------------------------------------------------------ */

const EMPTY_PRODUCT = { name: '', description: '', price: '', stock: '', imageUrl: '', categoryId: '', active: true }

function AdminProducts() {
  const toast = useToast()
  const [page, setPage] = useState(0)
  const [showInactive, setShowInactive] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form, setForm] = useState(EMPTY_PRODUCT)
  const [errors, setErrors] = useState({})
  const [saving, setSaving] = useState(false)

  /*
   * `active` is tri-state, and the three states are genuinely different:
   *
   *   active=true            -> active products only          (the storefront list)
   *   active=false           -> INACTIVE products only         (the withdrawn pile)
   *   (parameter omitted)    -> active products only, because the server defaults it to true
   *
   * That third line is the one that catches people out. Omitting the parameter does NOT mean
   * "no filter" - ProductFilter's compact constructor sets `active = true` when it is absent,
   * precisely so a storefront request that forgets the parameter cannot publish withdrawn
   * stock. An earlier version of this component assumed omit meant "all" and sent undefined
   * for the toggle; the result was that "Show inactive" returned the storefront list and
   * silently hid the very products it was meant to reveal.
   *
   * So there is no single parameter value meaning "either" - to show active AND inactive the
   * client has to ask for both. Two requests is the honest cost of the server default, and
   * it is cheaper than weakening a default that protects the storefront.
   */
  const products = useApiQuery(
    (signal) =>
      showInactive
        ? Promise.all([
            listProducts({ page, size: config.productsPageSize, active: true, sort: 'createdAt,desc' }, { signal }),
            listProducts({ page, size: config.productsPageSize, active: false, sort: 'createdAt,desc' }, { signal }),
          ]).then(([activePage, inactivePage]) => ({
            ...activePage,
            content: [...activePage.content, ...inactivePage.content],
            totalElements: activePage.totalElements + inactivePage.totalElements,
          }))
        : listProducts(
            { page, size: config.productsPageSize, active: true, sort: 'createdAt,desc' },
            { signal },
          ),
    [page, showInactive],
  )

  const categories = useApiQuery((signal) => listAllCategories({ signal }), [])

  const openCreate = () => {
    setEditing('new')
    setForm(EMPTY_PRODUCT)
    setErrors({})
  }

  const openEdit = (product) => {
    setEditing(product.id)
    setForm({
      name: product.name ?? '',
      description: product.description ?? '',
      price: String(product.price ?? ''),
      stock: String(product.stock ?? ''),
      imageUrl: product.imageUrl ?? '',
      categoryId: String(product.category?.id ?? ''),
      active: product.active,
    })
    setErrors({})
  }

  const closeForm = () => {
    setEditing(null)
    setErrors({})
  }

  const handleSubmit = async (event) => {
    event.preventDefault()
    setSaving(true)
    setErrors({})

    /*
     * Numeric coercion happens here rather than in the input, so the form can hold whatever
     * the user typed (including an empty string) and only convert on submit. Converting on
     * change would make the field impossible to clear - an empty string parses to NaN.
     */
    const payload = {
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      price: Number(form.price),
      stock: Number(form.stock),
      imageUrl: form.imageUrl.trim() || undefined,
      categoryId: form.categoryId ? Number(form.categoryId) : undefined,
      active: form.active,
    }

    try {
      if (editing === 'new') {
        await createProduct(payload)
        toast.success(`${payload.name} created`)
      } else {
        await updateProduct(editing, payload)
        toast.success(`${payload.name} updated`)
      }
      closeForm()
      products.refetch()
    } catch (cause) {
      if (cause.isValidation) {
        setErrors({
          name: cause.for('name'),
          description: cause.for('description'),
          price: cause.for('price'),
          stock: cause.for('stock'),
          imageUrl: cause.for('imageUrl') || cause.for('image_url'),
          categoryId: cause.for('categoryId') || cause.for('category_id'),
          form: cause.message,
        })
      } else {
        setErrors({ form: cause.message })
      }
    } finally {
      setSaving(false)
    }
  }

  const handleDeactivate = async (product) => {
    try {
      await deactivateProduct(product.id)
      toast.info(`${product.name} is now hidden from the storefront`)
      products.refetch()
    } catch (cause) {
      toast.fromError(cause, 'Could not deactivate')
    }
  }

  const handleDelete = async (product) => {
    /*
     * A hard delete, and it is genuinely destructive - hence the confirmation. It also fails
     * with PRODUCT_IN_USE when order history references the product, which is the backend
     * protecting referential integrity: an order line keeps a snapshot of the name and price,
     * but the foreign key to the product still has to resolve. Deactivating is the correct
     * action for anything that has ever been sold, and the failure message says so.
     */
    if (!window.confirm(`Permanently delete "${product.name}"? This cannot be undone.`)) return

    try {
      await deleteProduct(product.id)
      toast.success(`${product.name} deleted`)
      products.refetch()
    } catch (cause) {
      if (cause.code === 'PRODUCT_IN_USE') {
        toast.info('This product appears in existing orders, so it can be deactivated but not deleted.', 'Cannot delete')
      } else {
        toast.fromError(cause, 'Could not delete')
      }
    }
  }

  const result = products.data

  return (
    <>
      <div className="toolbar">
        <label className="toolbar__toggle">
          <input
            type="checkbox"
            checked={showInactive}
            onChange={(event) => {
              setShowInactive(event.target.checked)
              setPage(0)
            }}
          />
          Include deactivated
        </label>

        <button type="button" className="btn btn--primary btn--sm" onClick={openCreate}>
          New product
        </button>
      </div>

      {editing !== null && (
        <section className="card">
          <h2 className="card__title">{editing === 'new' ? 'New product' : 'Edit product'}</h2>

          {errors.form && <Alert tone="error">{errors.form}</Alert>}

          <form onSubmit={handleSubmit} noValidate>
            <div className="form-grid">
              <Field
                id="p-name"
                label="Name"
                required
                value={form.name}
                onChange={(event) => setForm((c) => ({ ...c, name: event.target.value }))}
                error={errors.name}
              />
              <Field
                id="p-price"
                label="Price"
                type="number"
                step="0.01"
                min="0"
                required
                value={form.price}
                onChange={(event) => setForm((c) => ({ ...c, price: event.target.value }))}
                error={errors.price}
                hint="Up to 17 digits with 2 decimals."
              />
              <Field
                id="p-stock"
                label="Stock"
                type="number"
                min="0"
                required
                value={form.stock}
                onChange={(event) => setForm((c) => ({ ...c, stock: event.target.value }))}
                error={errors.stock}
              />
              <Field
                id="p-category"
                label="Category"
                as="select"
                required
                value={form.categoryId}
                onChange={(event) => setForm((c) => ({ ...c, categoryId: event.target.value }))}
                error={errors.categoryId}
              >
                <option value="">Select a category…</option>
                {(categories.data ?? []).map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.name}
                    {!category.active ? ' (inactive)' : ''}
                  </option>
                ))}
              </Field>
            </div>

            <Field
              id="p-image"
              label="Image URL"
              value={form.imageUrl}
              onChange={(event) => setForm((c) => ({ ...c, imageUrl: event.target.value }))}
              error={errors.imageUrl}
              hint="Optional. Leave blank to use the generated placeholder."
            />

            <Field
              id="p-description"
              label="Description"
              as="textarea"
              rows={3}
              value={form.description}
              onChange={(event) => setForm((c) => ({ ...c, description: event.target.value }))}
              error={errors.description}
            />

            <label className="checkbox">
              <input
                type="checkbox"
                checked={form.active}
                onChange={(event) => setForm((c) => ({ ...c, active: event.target.checked }))}
              />
              Visible in the storefront
            </label>

            <div className="form-actions">
              <button type="submit" className="btn btn--primary" disabled={saving}>
                {saving ? 'Saving…' : editing === 'new' ? 'Create product' : 'Save changes'}
              </button>
              <button type="button" className="btn btn--ghost" onClick={closeForm} disabled={saving}>
                Cancel
              </button>
            </div>
          </form>
        </section>
      )}

      {products.error ? (
        <ErrorState error={products.error} onRetry={() => products.refetch()} />
      ) : products.loading && !result ? (
        <Skeleton height="320px" radius="12px" />
      ) : result?.content?.length ? (
        <>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">Product</th>
                  <th scope="col">Category</th>
                  <th scope="col" className="u-right">Price</th>
                  <th scope="col" className="u-right">Stock</th>
                  <th scope="col">Status</th>
                  <th scope="col" className="u-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {result.content.map((product) => (
                  <tr key={product.id}>
                    <td>
                      <Link to={`/products/${product.id}`} className="table__link">
                        {product.name}
                      </Link>
                      <span className="table__id">#{product.id}</span>
                    </td>
                    <td>{product.category?.name ?? '—'}</td>
                    <td className="u-right">{formatMoney(product.price)}</td>
                    <td className="u-right">
                      <span className={product.stock === 0 ? 'u-danger' : product.stock <= 5 ? 'u-warning' : ''}>
                        {product.stock}
                      </span>
                    </td>
                    <td>
                      {product.active ? (
                        <Badge tone="success">Active</Badge>
                      ) : (
                        <Badge tone="neutral">Inactive</Badge>
                      )}
                    </td>
                    <td className="u-right table__actions">
                      <button type="button" className="btn btn--ghost btn--sm" onClick={() => openEdit(product)}>
                        Edit
                      </button>
                      {product.active && (
                        <button type="button" className="btn btn--ghost btn--sm" onClick={() => handleDeactivate(product)}>
                          Deactivate
                        </button>
                      )}
                      <button type="button" className="btn btn--ghost btn--sm u-danger" onClick={() => handleDelete(product)}>
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <Pagination
            page={result.page}
            totalPages={result.totalPages}
            first={result.first}
            last={result.last}
            totalElements={result.totalElements}
            onChange={setPage}
            label="products"
          />
        </>
      ) : (
        <EmptyState title="No products" message="Create one to get started." />
      )}
    </>
  )
}

/* ------------------------------------------------------------------ *
 *  Categories
 * ------------------------------------------------------------------ */

const EMPTY_CATEGORY = { name: '', description: '', imageUrl: '', active: true }

function AdminCategories() {
  const toast = useToast()
  const [editing, setEditing] = useState(null)
  const [form, setForm] = useState(EMPTY_CATEGORY)
  const [errors, setErrors] = useState({})
  const [saving, setSaving] = useState(false)

  const categories = useApiQuery((signal) => listAllCategories({ signal }), [])

  const openCreate = () => {
    setEditing('new')
    setForm(EMPTY_CATEGORY)
    setErrors({})
  }

  const openEdit = (category) => {
    setEditing(category.id)
    setForm({
      name: category.name ?? '',
      description: category.description ?? '',
      imageUrl: category.imageUrl ?? '',
      active: category.active,
    })
    setErrors({})
  }

  const handleSubmit = async (event) => {
    event.preventDefault()
    setSaving(true)
    setErrors({})

    /*
     * Note there is no `slug` field. The backend derives it from the name -
     * CategoryService.slugify - so this form cannot produce a name and a slug that disagree,
     * and cannot submit a duplicate slug by hand. That is a validation rule that belongs on
     * the server because it is derivable there.
     */
    const payload = {
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      imageUrl: form.imageUrl.trim() || undefined,
      active: form.active,
    }

    try {
      if (editing === 'new') {
        await createCategory(payload)
        toast.success(`${payload.name} created`)
      } else {
        await updateCategory(editing, payload)
        toast.success(`${payload.name} updated`)
      }
      setEditing(null)
      categories.refetch()
    } catch (cause) {
      if (cause.isValidation) {
        setErrors({
          name: cause.for('name'),
          description: cause.for('description'),
          imageUrl: cause.for('imageUrl') || cause.for('image_url'),
          form: cause.message,
        })
      } else if (cause.code === 'CATEGORY_ALREADY_EXISTS') {
        setErrors({ name: cause.message })
      } else {
        setErrors({ form: cause.message })
      }
    } finally {
      setSaving(false)
    }
  }

  const handleDeactivate = async (category) => {
    try {
      await deactivateCategory(category.id)
      toast.info(`${category.name} is now hidden from the storefront`)
      categories.refetch()
    } catch (cause) {
      toast.fromError(cause, 'Could not deactivate')
    }
  }

  const result = categories.data

  return (
    <>
      <div className="toolbar">
        <span className="u-muted">
          {result ? `${result.length} ${result.length === 1 ? 'category' : 'categories'}` : 'Loading…'}
        </span>
        <button type="button" className="btn btn--primary btn--sm" onClick={openCreate}>
          New category
        </button>
      </div>

      {editing !== null && (
        <section className="card">
          <h2 className="card__title">{editing === 'new' ? 'New category' : 'Edit category'}</h2>

          {errors.form && <Alert tone="error">{errors.form}</Alert>}

          <form onSubmit={handleSubmit} noValidate>
            <Field
              id="c-name"
              label="Name"
              required
              value={form.name}
              onChange={(event) => setForm((c) => ({ ...c, name: event.target.value }))}
              error={errors.name}
              hint="The URL slug is generated from this."
            />

            <Field
              id="c-image"
              label="Image URL"
              value={form.imageUrl}
              onChange={(event) => setForm((c) => ({ ...c, imageUrl: event.target.value }))}
              error={errors.imageUrl}
            />

            <Field
              id="c-description"
              label="Description"
              as="textarea"
              rows={3}
              value={form.description}
              onChange={(event) => setForm((c) => ({ ...c, description: event.target.value }))}
              error={errors.description}
            />

            <label className="checkbox">
              <input
                type="checkbox"
                checked={form.active}
                onChange={(event) => setForm((c) => ({ ...c, active: event.target.checked }))}
              />
              Visible in the storefront
            </label>

            <div className="form-actions">
              <button type="submit" className="btn btn--primary" disabled={saving}>
                {saving ? 'Saving…' : editing === 'new' ? 'Create category' : 'Save changes'}
              </button>
              <button type="button" className="btn btn--ghost" onClick={() => setEditing(null)} disabled={saving}>
                Cancel
              </button>
            </div>
          </form>
        </section>
      )}

      {categories.error ? (
        <ErrorState error={categories.error} onRetry={() => categories.refetch()} />
      ) : categories.loading && !result ? (
        <Skeleton height="280px" radius="12px" />
      ) : result?.length ? (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Category</th>
                <th scope="col">Slug</th>
                <th scope="col" className="u-right">Products</th>
                <th scope="col">Status</th>
                <th scope="col" className="u-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {result.map((category) => (
                <tr key={category.id}>
                  <td>
                    <span className="table__strong">{category.name}</span>
                    <span className="table__id">#{category.id}</span>
                  </td>
                  <td>
                    {/* A monospace slug, because its exact form is what appears in URLs. */}
                    <code className="code">{category.slug}</code>
                  </td>
                  <td className="u-right">{category.productCount}</td>
                  <td>
                    {category.active ? (
                      <Badge tone="success">Active</Badge>
                    ) : (
                      <Badge tone="neutral">Inactive</Badge>
                    )}
                  </td>
                  <td className="u-right table__actions">
                    <button type="button" className="btn btn--ghost btn--sm" onClick={() => openEdit(category)}>
                      Edit
                    </button>
                    {category.active && (
                      <button type="button" className="btn btn--ghost btn--sm" onClick={() => handleDeactivate(category)}>
                        Deactivate
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <EmptyState title="No categories" message="Create one to get started." />
      )}
    </>
  )
}

/* ------------------------------------------------------------------ *
 *  Orders
 * ------------------------------------------------------------------ */

function AdminOrders() {
  const toast = useToast()
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState('')
  const [updatingId, setUpdatingId] = useState(null)
  const [expanded, setExpanded] = useState(null)

  const orders = useApiQuery(
    (signal) => listAllOrders({ page, size: 15, status: status || undefined, sort: 'createdAt,desc' }, { signal }),
    [page, status],
  )

  /**
   * Moves an order to a new status.
   *
   * The buttons offered are built from each order's own `allowedNextStatuses`, which is the
   * backend's state machine answered per row. That is deliberately not a table duplicated in
   * this file: the server decides what is legal, and rendering a button for a transition it
   * would reject produces an error the user cannot avoid.
   *
   * Cancelling is worth knowing about - it restores every line's quantity to its product's
   * stock inside the same transaction - so the confirmation says so rather than treating it
   * as a label change.
   */
  const changeStatus = async (order, nextStatus) => {
    if (nextStatus === 'CANCELLED') {
      const confirmed = window.confirm(
        `Cancel ${order.orderNumber}? Every item's quantity will be returned to stock. This cannot be undone.`,
      )
      if (!confirmed) return
    }

    setUpdatingId(order.id)
    try {
      await updateOrderStatus(order.id, nextStatus)
      toast.success(`${order.orderNumber} is now ${orderStatusLabel(nextStatus).toLowerCase()}`)
      orders.refetch()
    } catch (cause) {
      /*
       * INVALID_ORDER_STATUS_TRANSITION means the order moved under us - another admin
       * changed it between this page rendering and the click. Refreshing shows the truth
       * rather than leaving stale buttons on screen.
       */
      if (cause.code === 'INVALID_ORDER_STATUS_TRANSITION') {
        toast.info(cause.message, 'Status changed elsewhere')
        orders.refetch()
      } else {
        toast.fromError(cause, 'Could not update the order')
      }
    } finally {
      setUpdatingId(null)
    }
  }

  const result = orders.data

  return (
    <>
      <div className="toolbar">
        <div className="filters__group">
          <label className="filters__label" htmlFor="admin-order-status">
            Status
          </label>
          <select
            id="admin-order-status"
            className="filters__select"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value)
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

        <span className="u-muted">
          {result ? `${result.totalElements} orders` : 'Loading…'}
        </span>
      </div>

      {orders.error ? (
        <ErrorState error={orders.error} onRetry={() => orders.refetch()} />
      ) : orders.loading && !result ? (
        <Skeleton height="360px" radius="12px" />
      ) : result?.content?.length ? (
        <>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">Order</th>
                  <th scope="col">Customer</th>
                  <th scope="col">Placed</th>
                  <th scope="col" className="u-right">Total</th>
                  <th scope="col">Status</th>
                  <th scope="col" className="u-right">Move to</th>
                </tr>
              </thead>
              <tbody>
                {result.content.map((order) => (
                  <Fragment key={order.id}>
                    <tr>
                      <td>
                        <button
                          type="button"
                          className="table__link table__link--button"
                          onClick={() => setExpanded(expanded === order.id ? null : order.id)}
                          aria-expanded={expanded === order.id}
                        >
                          {order.orderNumber}
                        </button>
                        <span className="table__id">{order.totalItems} items</span>
                      </td>
                      <td>
                        <span className="table__strong">{order.customerName}</span>
                        <span className="table__id">{order.customerEmail}</span>
                      </td>
                      <td>{formatDateTime(order.createdAt)}</td>
                      <td className="u-right">{formatMoney(order.totalAmount)}</td>
                      <td>
                        <Badge tone={orderStatusTone(order.status)}>{orderStatusLabel(order.status)}</Badge>
                      </td>
                      <td className="u-right table__actions">
                        {order.allowedNextStatuses?.length ? (
                          order.allowedNextStatuses.map((next) => (
                            <button
                              key={next}
                              type="button"
                              className={`btn btn--sm ${next === 'CANCELLED' ? 'btn--ghost u-danger' : 'btn--secondary'}`}
                              onClick={() => changeStatus(order, next)}
                              disabled={updatingId === order.id}
                              /*
                               * The spinner is per-row rather than global, so updating one
                               * order does not blank the whole table.
                               */
                              aria-label={`Move ${order.orderNumber} to ${orderStatusLabel(next)}`}
                            >
                              {updatingId === order.id ? '…' : orderStatusLabel(next)}
                            </button>
                          ))
                        ) : (
                          <span className="u-muted">Final state</span>
                        )}
                      </td>
                    </tr>

                    {/* The line items, on demand rather than always - a table of tables is unreadable. */}
                    {expanded === order.id && (
                      <tr className="table__detail-row">
                        <td colSpan={6}>
                          <div className="order-lines order-lines--compact">
                            {order.items?.map((item) => (
                              <div key={item.id} className="order-line order-line--compact">
                                <div className="order-line__body">
                                  <span className="order-line__name">{item.productName}</span>
                                  <span className="order-line__unit">
                                    {item.quantity} × {formatMoney(item.unitPrice)}
                                  </span>
                                </div>
                                <span className="order-line__subtotal">{formatMoney(item.subtotal)}</span>
                              </div>
                            ))}
                          </div>
                          <p className="order-note order-note--muted">
                            Shipping to: {order.shippingAddress ?? 'not recorded'}
                          </p>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
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
          message={status ? 'Try a different status.' : 'Orders will appear here as customers check out.'}
        />
      )}
    </>
  )
}

/* ------------------------------------------------------------------ *
 *  Users
 * ------------------------------------------------------------------ */

function AdminUsers() {
  const toast = useToast()
  const [page, setPage] = useState(0)
  const [role, setRole] = useState('')
  const [updatingId, setUpdatingId] = useState(null)

  const users = useApiQuery(
    (signal) => listUsers({ page, size: 20, role: role || undefined }, { signal }),
    [page, role],
  )

  const changeRole = async (user, nextRole) => {
    setUpdatingId(user.id)
    try {
      await updateUserRole(user.id, nextRole)
      toast.success(`${user.name} is now ${nextRole === 'ADMIN' ? 'an administrator' : 'a customer'}`)
      users.refetch()
    } catch (cause) {
      /*
       * The backend refuses to demote the last administrator. That is a real safety rule -
       * without it, one careless click locks everyone out of the admin console permanently,
       * with no way back in through the application. It is enforced on the server, where it
       * has to be, and simply relayed here.
       */
      if (cause.code === 'INVALID_OPERATION') {
        toast.info(cause.message, 'Not allowed')
      } else {
        toast.fromError(cause, 'Could not change the role')
      }
    } finally {
      setUpdatingId(null)
    }
  }

  const result = users.data

  return (
    <>
      <div className="toolbar">
        <div className="filters__group">
          <label className="filters__label" htmlFor="admin-user-role">
            Role
          </label>
          <select
            id="admin-user-role"
            className="filters__select"
            value={role}
            onChange={(event) => {
              setRole(event.target.value)
              setPage(0)
            }}
          >
            <option value="">All roles</option>
            <option value="CUSTOMER">Customers</option>
            <option value="ADMIN">Administrators</option>
          </select>
        </div>

        <span className="u-muted">{result ? `${result.totalElements} users` : 'Loading…'}</span>
      </div>

      {users.error ? (
        <ErrorState error={users.error} onRetry={() => users.refetch()} />
      ) : users.loading && !result ? (
        <Skeleton height="340px" radius="12px" />
      ) : result?.content?.length ? (
        <>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">User</th>
                  <th scope="col">Phone</th>
                  <th scope="col">Joined</th>
                  <th scope="col">Provider</th>
                  <th scope="col">Role</th>
                  <th scope="col" className="u-right">Change role</th>
                </tr>
              </thead>
              <tbody>
                {result.content.map((user) => (
                  <tr key={user.id}>
                    <td>
                      <span className="table__strong">{user.name}</span>
                      <span className="table__id">{user.email}</span>
                    </td>
                    <td>{user.phone || '—'}</td>
                    <td>{formatDateTime(user.createdAt)}</td>
                    <td>
                      {/*
                       * Google accounts are marked, because they have no password. A
                       * promoted Google user still cannot sign in with credentials - the
                       * provider is part of how the account authenticates, and knowing that
                       * matters when granting access.
                       */}
                      <Badge tone="neutral">{user.provider === 'GOOGLE' ? 'Google' : 'Local'}</Badge>
                    </td>
                    <td>
                      <Badge tone={user.role === 'ADMIN' ? 'info' : 'neutral'}>
                        {user.role === 'ADMIN' ? 'Admin' : 'Customer'}
                      </Badge>
                    </td>
                    <td className="u-right table__actions">
                      <button
                        type="button"
                        className="btn btn--sm btn--secondary"
                        onClick={() => changeRole(user, user.role === 'ADMIN' ? 'CUSTOMER' : 'ADMIN')}
                        disabled={updatingId === user.id}
                      >
                        {updatingId === user.id
                          ? '…'
                          : user.role === 'ADMIN'
                            ? 'Make customer'
                            : 'Make admin'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <Pagination
            page={result.page}
            totalPages={result.totalPages}
            first={result.first}
            last={result.last}
            totalElements={result.totalElements}
            onChange={setPage}
            label="users"
          />
        </>
      ) : (
        <EmptyState title="No users" message="No accounts match this filter." />
      )}
    </>
  )
}
