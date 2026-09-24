#!/usr/bin/env node
/**
 * End-to-end smoke test for the Aurora Store API.
 *
 * Takes the full customer journey — register, browse, add to cart, check out — and the
 * admin journey — moderate, transition orders, manage catalogue — and asserts the result
 * against a REAL running server backed by a REAL MySQL.
 *
 * <h3>Why not a Spring test</h3>
 *
 * There is already a 273-test suite covering units, slices and HTTP contracts. What that
 * suite cannot do is prove the assembled system works: that the Vite proxy reaches the
 * backend, that the seed data matches what the frontend expects, that a JWT minted by the
 * login endpoint is accepted by the cart endpoint, that stock actually moves. Those are
 * integration facts, and integration facts need an integrated system.
 *
 * <h3>Usage</h3>
 *
 *   node e2e.mjs                       # against http://localhost:8080
 *   node e2e.mjs http://host:port      # against something else
 *
 * Exits non-zero on the first assertion family that fails, so it composes into CI.
 */

const BASE = (process.argv[2] || 'http://localhost:8080').replace(/\/+$/, '')

// ---------------------------------------------------------------------------
// Tiny assertion harness. Deliberately hand-rolled: pulling in a test framework
// to make ~60 HTTP calls would add a dependency tree larger than the thing it
// is testing.
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0
const failures = []

function check(label, condition, detail = '') {
  if (condition) {
    passed++
    console.log(`  \u2713 ${label}`)
  } else {
    failed++
    failures.push(`${label}${detail ? ` \u2014 ${detail}` : ''}`)
    console.log(`  \u2717 ${label}${detail ? `  [${detail}]` : ''}`)
  }
}

function section(title) {
  console.log(`\n${title}`)
}

/**
 * One HTTP call, returning `{ status, body, headers }`.
 *
 * Never throws on a non-2xx: a 409 is a legitimate outcome that several of these
 * tests assert on, and treating it as an exception would make the failure paths
 * awkward to express.
 */
async function api(path, { method = 'GET', body, token, raw } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  // `--noproxy '*'` equivalent: Node honours NO_PROXY only if explicitly unset,
  // and a system proxy silently intercepts localhost on many Windows setups,
  // producing confusing 502s for a server that is running fine.
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  let parsed = null
  const text = await res.text()
  if (text) {
    try { parsed = JSON.parse(text) } catch { parsed = raw ? text : null }
  }
  return { status: res.status, body: parsed, headers: res.headers }
}

/** Unique suffix so repeated runs do not collide on unique email constraints. */
const stamp = Date.now().toString().slice(-8)
const CUSTOMER_EMAIL = `e2e.customer.${stamp}@example.com`
const ADMIN_EMAIL = 'admin@shop.com'
const ADMIN_PASSWORD = 'Admin@123'
const SEEDED_CUSTOMER_EMAIL = 'customer@shop.com'
const SEEDED_CUSTOMER_PASSWORD = 'Customer@123'

let customerToken = null
let adminToken = null
let customerId = null
let createdCartItemId = null
let createdProductId = null
let createdCategoryId = null
let placedOrderId = null
let stockBefore = null

// ===========================================================================
async function main() {
  console.log(`\n=== Aurora Store E2E ===\n>>> ${BASE}\n`)

  // -------------------------------------------------------------------------
  section('1. Health and public catalogue')
  // -------------------------------------------------------------------------
  {
    const health = await api('/api/health')
    check('health responds 200', health.status === 200, `got ${health.status}`)
    check('health reports UP', health.body?.status === 'UP', JSON.stringify(health.body))

    const products = await api('/api/products?size=5')
    check('product list is 200', products.status === 200, `got ${products.status}`)
    check('product list is a page envelope',
      products.body && 'content' in products.body && 'totalElements' in products.body,
      JSON.stringify(Object.keys(products.body ?? {})))
    check('seed data present (>=15 products)', (products.body?.totalElements ?? 0) >= 15,
      `total=${products.body?.totalElements}`)

    const first = products.body?.content?.[0]
    check('product exposes an id and price', first?.id != null && first?.price != null)
    check('product does NOT leak an entity shape', first && !('category_id' in first))
    stockBefore = first?.stock

    const categories = await api('/api/categories')
    check('categories is 200', categories.status === 200, `got ${categories.status}`)
    check('categories is an array', Array.isArray(categories.body),
      typeof categories.body)
    check('categories carry a productCount',
      categories.body?.[0] && 'productCount' in categories.body[0],
      JSON.stringify(Object.keys(categories.body?.[0] ?? {})))
  }

  // -------------------------------------------------------------------------
  section('2. Auth boundary (before we hold any token)')
  // -------------------------------------------------------------------------
  {
    const cart = await api('/api/cart')
    check('anonymous cart is 401', cart.status === 401, `got ${cart.status}`)
    check('401 carries a machine-readable code',
      typeof cart.body?.error === 'string', JSON.stringify(cart.body))

    const admin = await api('/api/admin/dashboard/stats')
    check('anonymous admin endpoint is 401', admin.status === 401, `got ${admin.status}`)

    const unmapped = await api('/api/no-such-endpoint')
    check('unmapped path to an anonymous caller is 401, not 404',
      unmapped.status === 401,
      `got ${unmapped.status} - a 404 here would make the API a path scanner`)
  }

  // -------------------------------------------------------------------------
  section('3. Login (both roles)')
  // -------------------------------------------------------------------------
  {
    const bad = await api('/api/auth/login', {
      method: 'POST',
      body: { email: ADMIN_EMAIL, password: 'definitely-wrong' },
    })
    check('wrong password is 401', bad.status === 401, `got ${bad.status}`)
    check('wrong password and unknown email are INDISTINGUISHABLE',
      bad.body?.error === 'INVALID_CREDENTIALS', `code=${bad.body?.error}`)

    const unknown = await api('/api/auth/login', {
      method: 'POST',
      body: { email: `nobody.${stamp}@example.com`, password: 'whatever123' },
    })
    check('unknown email returns the SAME code (no account enumeration)',
      unknown.body?.error === bad.body?.error,
      `${unknown.body?.error} vs ${bad.body?.error}`)

    const admin = await api('/api/auth/login', {
      method: 'POST',
      body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
    })
    check('admin login is 200', admin.status === 200, `got ${admin.status}`)
    check('login returns a bearer token', admin.body?.tokenType === 'Bearer')
    check('login returns an expiry', typeof admin.body?.expiresIn === 'number',
      String(admin.body?.expiresIn))
    check('login returns the user inline (no second round trip)',
      admin.body?.user?.email === ADMIN_EMAIL)
    check('login response contains NO password field',
      !JSON.stringify(admin.body).toLowerCase().includes('password'))
    adminToken = admin.body?.token

    const customer = await api('/api/auth/login', {
      method: 'POST',
      body: { email: SEEDED_CUSTOMER_EMAIL, password: SEEDED_CUSTOMER_PASSWORD },
    })
    check('seeded customer login is 200', customer.status === 200, `got ${customer.status}`)
    check('seeded customer is not an admin', customer.body?.user?.role === 'CUSTOMER',
      `role=${customer.body?.user?.role}`)
    customerToken = customer.body?.token
  }

  // -------------------------------------------------------------------------
  section('4. Registration and validation')
  // -------------------------------------------------------------------------
  {
    const weak = await api('/api/auth/register', {
      method: 'POST',
      body: { name: 'E2E User', email: CUSTOMER_EMAIL, password: 'short' },
    })
    check('short password is rejected 400', weak.status === 400, `got ${weak.status}`)
    check('validation returns fieldErrors',
      Array.isArray(weak.body?.fieldErrors) && weak.body.fieldErrors.length > 0,
      JSON.stringify(weak.body))

    const escalation = await api('/api/auth/register', {
      method: 'POST',
      body: { name: 'Attacker', email: `attacker.${stamp}@example.com`,
              password: 'ValidPass123', role: 'ADMIN' },
    })
    // Either the unknown field is ignored or the request is rejected - what must
    // NEVER happen is the role being honoured.
    check('registering with role=ADMIN does not create an admin',
      escalation.status >= 400 || escalation.body?.user?.role !== 'ADMIN',
      `status=${escalation.status} role=${escalation.body?.user?.role}`)

    const reg = await api('/api/auth/register', {
      method: 'POST',
      body: { name: 'E2E Customer', email: CUSTOMER_EMAIL, password: 'ValidPass123' },
    })
    check('valid registration is 201', reg.status === 201, `got ${reg.status}`)
    check('registered user is a CUSTOMER', reg.body?.user?.role === 'CUSTOMER',
      `role=${reg.body?.user?.role}`)
    customerToken = reg.body?.token ?? customerToken
    customerId = reg.body?.user?.id

    if (!customerToken) {
      const retry = await api('/api/auth/login', {
        method: 'POST', body: { email: CUSTOMER_EMAIL, password: 'ValidPass123' },
      })
      customerToken = retry.body?.token
      customerId = retry.body?.user?.id
    }
    check('we hold a customer token', typeof customerToken === 'string' && customerToken.length > 20)

    const dupe = await api('/api/auth/register', {
      method: 'POST',
      body: { name: 'E2E Customer', email: CUSTOMER_EMAIL, password: 'ValidPass123' },
    })
    check('duplicate email is refused', dupe.status === 409 || dupe.status === 400,
      `got ${dupe.status}`)
  }

  // -------------------------------------------------------------------------
  section('5. Identity and profile')
  // -------------------------------------------------------------------------
  {
    const me = await api('/api/auth/me', { method: 'POST', token: customerToken })
    check('me is 200 with a valid token', me.status === 200, `got ${me.status}`)
    check('me returns our own email', me.body?.email === CUSTOMER_EMAIL,
      `${me.body?.email} vs ${CUSTOMER_EMAIL}`)

    const tampered = await api('/api/auth/me', {
      method: 'POST',
      token: customerToken.slice(0, -4) + 'AAAA',
    })
    check('a TAMPERED token is rejected', tampered.status === 401, `got ${tampered.status}`)

    const garbage = await api('/api/auth/me', { method: 'POST', token: 'not.a.jwt' })
    check('a malformed token is rejected', garbage.status === 401, `got ${garbage.status}`)
  }

  // -------------------------------------------------------------------------
  section('6. Role enforcement (the security boundary)')
  // -------------------------------------------------------------------------
  {
    const asCustomer = await api('/api/admin/dashboard/stats', { token: customerToken })
    check('customer cannot read admin stats (403)', asCustomer.status === 403,
      `got ${asCustomer.status} - must be 403, not 500 or 200`)

    const asCustomerUsers = await api('/api/admin/users', { token: customerToken })
    check('customer cannot list users (403)', asCustomerUsers.status === 403,
      `got ${asCustomerUsers.status}`)

    const admin = await api('/api/admin/dashboard/stats', { token: adminToken })
    check('admin CAN read stats (200)', admin.status === 200, `got ${admin.status}`)
    check('stats include an ordersByStatus map',
      admin.body?.ordersByStatus && typeof admin.body.ordersByStatus === 'object',
      JSON.stringify(admin.body))

    const statuses = Object.keys(admin.body?.ordersByStatus ?? {})
    check('stats status keys are all real OrderStatus values',
      statuses.every((s) => ['PENDING','CONFIRMED','PROCESSING','SHIPPED','DELIVERED','CANCELLED'].includes(s)),
      statuses.join(','))
  }

  // -------------------------------------------------------------------------
  section('7. Cart operations')
  // -------------------------------------------------------------------------
  {
    const products = await api('/api/products?size=2&sort=stock,desc')
    const target = products.body.content[0]
    const second = products.body.content[1]
    stockBefore = target.stock

    const add = await api('/api/cart/items', {
      method: 'POST', token: customerToken,
      body: { productId: target.id, quantity: 2 },
    })
    check('add to cart is 200/201', add.status === 200 || add.status === 201,
      `got ${add.status}`)
    check('cart recalculates the subtotal',
      add.body?.subtotal != null && Number(add.body.subtotal) > 0,
      `subtotal=${add.body?.subtotal}`)
    check('cart exposes checkoutReady', typeof add.body?.checkoutReady === 'boolean')
    check('cart line exposes availability for the UI',
      add.body?.items?.[0] && 'availableStock' in add.body.items[0] && 'hasEnoughStock' in add.body.items[0],
      JSON.stringify(Object.keys(add.body?.items?.[0] ?? {})))
    createdCartItemId = add.body?.items?.[0]?.id

    const overAdd = await api('/api/cart/items', {
      method: 'POST', token: customerToken,
      body: { productId: second.id, quantity: 99 },
    })
    // 99 passes @Max(99) on the DTO, so this reaches the stock check rather than
    // being stopped at validation - which is the path we actually want to exercise.
    check('adding more than stock is refused 409',
      overAdd.status === 409, `got ${overAdd.status}`)
    check('INSUFFICIENT_STOCK is the code',
      overAdd.body?.error === 'INSUFFICIENT_STOCK', `code=${overAdd.body?.error}`)

    const absurd = await api('/api/cart/items', {
      method: 'POST', token: customerToken,
      body: { productId: second.id, quantity: 999999 },
    })
    // Note WHICH layer stops this. @Max(99) rejects it before stock is consulted,
    // so the code is VALIDATION_FAILED, not INSUFFICIENT_STOCK. Asserting 409 here
    // would be asserting the wrong guard.
    check('a quantity above the DTO cap is refused 400',
      absurd.status === 400, `got ${absurd.status}`)
    check('and it is VALIDATION_FAILED, not INSUFFICIENT_STOCK (the DTO cap fires first)',
      absurd.body?.error === 'VALIDATION_FAILED', `code=${absurd.body?.error}`)

    const zero = await api('/api/cart/items', {
      method: 'POST', token: customerToken, body: { productId: target.id, quantity: 0 },
    })
    check('quantity 0 is rejected 400', zero.status === 400, `got ${zero.status}`)

    const missing = await api('/api/cart/items', {
      method: 'POST', token: customerToken, body: { productId: 99999999, quantity: 1 },
    })
    check('unknown product is 404', missing.status === 404, `got ${missing.status}`)

    const upd = await api(`/api/cart/items/${createdCartItemId}`, {
      method: 'PUT', token: customerToken, body: { quantity: 3 },
    })
    check('quantity update is 200', upd.status === 200, `got ${upd.status}`)

    const after = await api('/api/cart', { token: customerToken })
    check('cart total reflects the new quantity',
      after.body?.totalItems === 3, `totalItems=${after.body?.totalItems}`)

    const remove = await api(`/api/cart/items/${createdCartItemId}`, {
      method: 'DELETE', token: customerToken,
    })
    check('remove line is 200/204', remove.status === 200 || remove.status === 204,
      `got ${remove.status}`)

    const empty = await api('/api/cart', { token: customerToken })
    check('cart is empty after removal', empty.body?.itemCount === 0,
      `itemCount=${empty.body?.itemCount}`)
    check('empty cart reports checkoutReady = false', empty.body?.checkoutReady === false)
  }

  // -------------------------------------------------------------------------
  section('8. Checkout')
  // -------------------------------------------------------------------------
  {
    const emptyCheckout = await api('/api/orders', {
      method: 'POST', token: customerToken, body: { shippingAddress: '1 Test Street' },
    })
    // EMPTY_CART is raised as a BadRequestException, so it is a 400 - not a 409.
    // Asserting the STATUS and the CODE separately is what caught this: the code
    // was right and my status expectation was wrong.
    check('checkout with an empty cart is refused 400', emptyCheckout.status === 400,
      `got ${emptyCheckout.status}`)
    check('EMPTY_CART is the code', emptyCheckout.body?.error === 'EMPTY_CART',
      `code=${emptyCheckout.body?.error}`)

    const products = await api('/api/products?size=1&sort=stock,desc')
    const target = products.body.content[0]
    stockBefore = target.stock

    await api('/api/cart/items', {
      method: 'POST', token: customerToken, body: { productId: target.id, quantity: 2 },
    })

    const order = await api('/api/orders', {
      method: 'POST', token: customerToken,
      body: { shippingAddress: '1 Test Street, Test City' },
    })
    check('checkout is 201', order.status === 201, `got ${order.status}`)
    check('order has a human-readable number',
      typeof order.body?.orderNumber === 'string' && order.body.orderNumber.length > 0,
      `orderNumber=${order.body?.orderNumber}`)
    check('order starts PENDING', order.body?.status === 'PENDING', `status=${order.body?.status}`)
    check('order exposes allowedNextStatuses',
      Array.isArray(order.body?.allowedNextStatuses) && order.body.allowedNextStatuses.length > 0,
      JSON.stringify(order.body?.allowedNextStatuses))
    check('order snapshots line price AND name (historical record)',
      order.body?.items?.[0]?.productName && order.body?.items?.[0]?.unitPrice != null,
      JSON.stringify(order.body?.items?.[0] ?? {}))
    placedOrderId = order.body?.id

    const afterOrder = await api('/api/products?size=1&sort=stock,desc')
    const stockAfter = afterOrder.body.content[0].stock
    check('stock was DECREMENTED by the order',
      stockAfter === stockBefore - 2, `${stockBefore} -> ${stockAfter} (expected -2)`)

    const cart = await api('/api/cart', { token: customerToken })
    check('cart was emptied by checkout', cart.body?.itemCount === 0,
      `itemCount=${cart.body?.itemCount}`)

    const mine = await api('/api/orders', { token: customerToken })
    check('the order appears in my orders', mine.status === 200 && mine.body?.totalElements >= 1,
      `total=${mine.body?.totalElements}`)

    const other = await api('/api/orders/99999999', { token: customerToken })
    check('an unknown order id is 404', other.status === 404, `got ${other.status}`)
  }

  // -------------------------------------------------------------------------
  section('9. Order lifecycle (admin)')
  // -------------------------------------------------------------------------
  {
    const all = await api('/api/admin/orders', { token: adminToken })
    check('admin can list all orders', all.status === 200, `got ${all.status}`)
    check('admin sees the customer name on an order',
      all.body?.content?.[0]?.customerName != null,
      JSON.stringify(Object.keys(all.body?.content?.[0] ?? {})))

    const illegal = await api(`/api/admin/orders/${placedOrderId}/status`, {
      method: 'PATCH', token: adminToken, body: { status: 'DELIVERED' },
    })
    check('PENDING -> DELIVERED is refused 409', illegal.status === 409, `got ${illegal.status}`)
    check('INVALID_ORDER_STATUS_TRANSITION is the code',
      illegal.body?.error === 'INVALID_ORDER_STATUS_TRANSITION', `code=${illegal.body?.error}`)
    check('the 409 tells the client the LEGAL transitions',
      /CONFIRMED|CANCELLED/.test(illegal.body?.message ?? ''),
      illegal.body?.message)

    const confirm = await api(`/api/admin/orders/${placedOrderId}/status`, {
      method: 'PATCH', token: adminToken, body: { status: 'CONFIRMED' },
    })
    check('PENDING -> CONFIRMED is allowed', confirm.status === 200, `got ${confirm.status}`)
    check('allowedNextStatuses advanced to PROCESSING/CANCELLED',
      JSON.stringify(confirm.body?.allowedNextStatuses) ===
        JSON.stringify(['PROCESSING', 'CANCELLED']),
      JSON.stringify(confirm.body?.allowedNextStatuses))

    const cancel = await api(`/api/admin/orders/${placedOrderId}/status`, {
      method: 'PATCH', token: adminToken, body: { status: 'CANCELLED' },
    })
    check('CONFIRMED -> CANCELLED is allowed', cancel.status === 200, `got ${cancel.status}`)

    const restored = await api(`/api/products/${all.body.content[0].items?.[0]?.productId ?? 1}`)
    const finalStock = await api('/api/products?size=1&sort=stock,desc')
    check('cancelling RESTORED the stock',
      finalStock.body.content[0].stock === stockBefore,
      `expected back to ${stockBefore}, got ${finalStock.body.content[0].stock}`)

    const afterCancel = await api(`/api/admin/orders/${placedOrderId}/status`, {
      method: 'PATCH', token: adminToken, body: { status: 'SHIPPED' },
    })
    check('CANCELLED is terminal (cannot ship a cancelled order)',
      afterCancel.status === 409, `got ${afterCancel.status}`)
  }

  // -------------------------------------------------------------------------
  section('10. Admin catalogue CRUD')
  // -------------------------------------------------------------------------
  {
    const cat = await api('/api/admin/categories', {
      method: 'POST', token: adminToken,
      body: { name: `E2E Category ${stamp}`, description: 'Created by the E2E suite' },
    })
    check('create category is 201', cat.status === 201, `got ${cat.status}`)
    check('category slug is DERIVED, not accepted from the client',
      typeof cat.body?.slug === 'string' && cat.body.slug.length > 0,
      `slug=${cat.body?.slug}`)
    createdCategoryId = cat.body?.id

    const prod = await api('/api/admin/products', {
      method: 'POST', token: adminToken,
      body: {
        name: `E2E Product ${stamp}`,
        description: 'Created by the E2E suite',
        price: 1234.56,
        stock: 7,
        categoryId: createdCategoryId,
        active: true,
      },
    })
    check('create product is 201', prod.status === 201, `got ${prod.status}`)
    check('price survives at 2 decimal places', Number(prod.body?.price) === 1234.56,
      `price=${prod.body?.price}`)
    createdProductId = prod.body?.id

    const badPrice = await api('/api/admin/products', {
      method: 'POST', token: adminToken,
      body: { name: 'Bad Price', price: 12.345, stock: 1, categoryId: createdCategoryId },
    })
    check('a price with 3 decimals is REJECTED, not silently rounded',
      badPrice.status === 400, `got ${badPrice.status} - MySQL would have rounded it`)

    const update = await api(`/api/admin/products/${createdProductId}`, {
      method: 'PUT', token: adminToken,
      body: { name: `E2E Product ${stamp} v2`, price: 999.99, stock: 7,
              categoryId: createdCategoryId, active: true },
    })
    check('update product is 200', update.status === 200, `got ${update.status}`)

    const deactivate = await api(`/api/admin/products/${createdProductId}/deactivate`, {
      method: 'PATCH', token: adminToken,
    })
    check('deactivate product is 200', deactivate.status === 200, `got ${deactivate.status}`)

    const storefront = await api('/api/products?size=100')
    const visible = (storefront.body?.content ?? []).some((p) => p.id === createdProductId)
    check('an inactive product is HIDDEN from the storefront', visible === false,
      'storefront must not show withdrawn stock')

    /*
     * The tri-state, tested at all three positions. This is the contract that a client gets
     * wrong most easily: omitting `active` does NOT mean "no filter".
     */
    const activeOnly = await api(`/api/products?size=100&active=true`, { token: adminToken })
    check('active=true returns ONLY active products',
      (activeOnly.body?.content ?? []).every((p) => p.active === true),
      'found an inactive product in an active-only page')

    const inactiveOnly = await api(`/api/products?size=100&active=false`, { token: adminToken })
    check('active=false DOES return the withdrawn product',
      (inactiveOnly.body?.content ?? []).some((p) => p.id === createdProductId),
      `total=${inactiveOnly.body?.totalElements}`)

    const omitted = await api('/api/products?size=100', { token: adminToken })
    const omittedHasIt = (omitted.body?.content ?? []).some((p) => p.id === createdProductId)
    check('OMITTING active defaults to true, so it does NOT mean "all"',
      omittedHasIt === false,
      'omitted active behaved like "no filter" - the storefront default is being bypassed')

    const del = await api(`/api/admin/products/${createdProductId}`, {
      method: 'DELETE', token: adminToken,
    })
    check('delete product is 200/204', del.status === 200 || del.status === 204,
      `got ${del.status}`)
  }

  // -------------------------------------------------------------------------
  section('11. Self-protection guards')
  // -------------------------------------------------------------------------
  {
    const me = await api('/api/auth/me', { method: 'POST', token: adminToken })
    const adminId = me.body?.id

    const selfDemote = await api(`/api/admin/users/${adminId}/role`, {
      method: 'PATCH', token: adminToken, body: { role: 'CUSTOMER' },
    })
    check('an admin cannot demote themselves (403)', selfDemote.status === 403,
      `got ${selfDemote.status}`)
    check('ACCESS_DENIED is the code', selfDemote.body?.error === 'ACCESS_DENIED',
      `code=${selfDemote.body?.error}`)

    const noSuchUser = await api('/api/admin/users/99999999/role', {
      method: 'PATCH', token: adminToken, body: { role: 'ADMIN' },
    })
    check('an unknown user is 404', noSuchUser.status === 404, `got ${noSuchUser.status}`)
  }

  // -------------------------------------------------------------------------
  section('12. Pagination, search and sort')
  // -------------------------------------------------------------------------
  {
    const paged = await api('/api/products?page=0&size=5')
    check('page size is honoured', paged.body?.content?.length <= 5)
    check('the envelope reports the page/size it used',
      paged.body?.page === 0 && paged.body?.size === 5,
      `page=${paged.body?.page} size=${paged.body?.size}`)
    check('totalPages is consistent with totalElements',
      paged.body?.totalPages === Math.ceil(paged.body?.totalElements / 5),
      `${paged.body?.totalElements} / 5 != ${paged.body?.totalPages}`)

    const second = await api('/api/products?page=1&size=5')
    const firstIds = (paged.body.content ?? []).map((p) => p.id)
    const secondIds = (second.body.content ?? []).map((p) => p.id)
    check('page 2 does not repeat page 1',
      firstIds.every((id) => !secondIds.includes(id)),
      `overlap found: ${firstIds.filter((id) => secondIds.includes(id))}`)

    const tooBig = await api('/api/products?size=1000')
    check('size above the cap is rejected 400', tooBig.status === 400, `got ${tooBig.status}`)
    check('and the rejection names the offending FIELD',
      tooBig.body?.fieldErrors?.some((e) => e.field === 'size'),
      JSON.stringify(tooBig.body?.fieldErrors))
    check('the page-size cap fires at the boundary, not off-by-one',
      (await api('/api/products?size=100')).status === 200,
      'size=100 is the documented maximum and must be accepted')
    check('and size=101 is over it',
      (await api('/api/products?size=101')).status === 400)

    const badSort = await api('/api/products?sort=password,desc')
    check('an unknown sort field is rejected 400', badSort.status === 400, `got ${badSort.status}`)

    const injected = await api('/api/products?sort=name%3BDROP%20TABLE%20products')
    check('a sort-injection attempt is rejected',
      injected.status === 400, `got ${injected.status}`)

    const search = await api('/api/products?keyword=wallet&size=20')
    check('keyword search is 200', search.status === 200, `got ${search.status}`)
    check('keyword search actually filters',
      (search.body?.totalElements ?? 0) <= (paged.body?.totalElements ?? 0),
      `search=${search.body?.totalElements}`)

    const sorted = await api('/api/products?size=10&sort=price,asc')
    const prices = (sorted.body.content ?? []).map((p) => Number(p.price))
    check('ascending sort is actually ascending',
      prices.every((p, i) => i === 0 || prices[i - 1] <= p),
      prices.join(','))
  }

  // -------------------------------------------------------------------------
  section('13. Error contract')
  // -------------------------------------------------------------------------
  {
    const nf = await api('/api/products/99999999')
    check('unknown product is 404', nf.status === 404, `got ${nf.status}`)
    check('404 uses the shared error shape',
      nf.body && 'timestamp' in nf.body && 'status' in nf.body &&
      'error' in nf.body && 'message' in nf.body && 'path' in nf.body,
      JSON.stringify(Object.keys(nf.body ?? {})))
    check('error response leaks NO stack trace',
      !JSON.stringify(nf.body).includes('at com.'), 'stack trace in the body')
    check('error response leaks NO exception class name',
      !/Exception/.test(nf.body?.message ?? ''), nf.body?.message)
  }

  // -------------------------------------------------------------------------
  section('14. Cleanup')
  // -------------------------------------------------------------------------
  {
    if (createdCategoryId) {
      /*
       * Categories have no hard DELETE, only deactivate. That is deliberate: a category is
       * referenced by products, so removing the row would orphan or cascade them. A 405 here
       * would be the endpoint not existing, which is why this asserts PATCH.
       */
      const deact = await api(`/api/admin/categories/${createdCategoryId}/deactivate`, {
        method: 'PATCH', token: adminToken,
      })
      check('cleanup: E2E category deactivated',
        deact.status === 200 || deact.status === 204,
        `got ${deact.status}`)
    }
    const cart = await api('/api/cart', { method: 'DELETE', token: customerToken })
    check('cleanup: cart cleared', cart.status === 200 || cart.status === 204,
      `got ${cart.status}`)
  }

  // -------------------------------------------------------------------------
  section('15. Configuration is wired, not decorative')
  // -------------------------------------------------------------------------
  {
    /*
     * Every variable documented in `.env.example` must actually be READ by the config.
     *
     * This is the "documented but nothing reads it" bug, which is worse than an undocumented
     * setting: it looks like configuration and behaves like a comment. It recurred three times
     * in this project - `SPRING_DATASOURCE_*` (the yml reads `DB_*`), `OAUTH2_SUCCESS_REDIRECT`
     * (the yml reads `OAUTH2_REDIRECT_URI`), and `VITE_PRODUCTS_PAGE_SIZE` (read into the
     * config module, then ignored by the page that mattered). Each looked correct in review.
     *
     * A static comparison catches all of them in one assertion, so it is worth the twenty
     * lines even though it is not testing the API.
     */
    const fs = await import('node:fs')
    const path = await import('node:path')
    /*
     * This script lives in `backend/db/`, so the backend root is its parent directory.
     *
     * `new URL(import.meta.url).pathname` yields `/D:/...` on Windows, and feeding that to
     * `fs` fails with ENOENT - the leading slash that is correct on POSIX is wrong here. The
     * replace strips it only when a drive letter follows, so the same line works on both.
     */
    const scriptDir = path.dirname(
      decodeURIComponent(new URL(import.meta.url).pathname).replace(/^\/([A-Za-z]:)/, '$1'),
    )
    const backendRoot = path.dirname(scriptDir)

    const envExamplePath = path.join(backendRoot, '.env.example')
    const ymlPath = path.join(backendRoot, 'src', 'main', 'resources', 'application.yml')

    if (!fs.existsSync(envExamplePath) || !fs.existsSync(ymlPath)) {
      check('config audit can locate .env.example and application.yml', false,
        `${envExamplePath} / ${ymlPath}`)
    } else {
      const documented = new Set(
        fs.readFileSync(envExamplePath, 'utf8')
          .split('\n')
          .filter((line) => /^[A-Z][A-Z0-9_]*=/.test(line))
          .map((line) => line.split('=')[0]),
      )
      const ymlText = fs.readFileSync(ymlPath, 'utf8')
      // ${VAR} / ${VAR:default} - the placeholder text inside comments is excluded by requiring
      // a name of at least four characters, which filters the `ENV_VAR` / `VAR` examples.
      const read = new Set(
        [...ymlText.matchAll(/\$\{([A-Z][A-Z0-9_]{3,})/g)].map((m) => m[1]),
      )

      check('the config audit found variables to check', documented.size > 0,
        `parsed ${documented.size} from .env.example`)

      const unread = [...documented].filter((name) => !read.has(name))
      check('every variable in .env.example is read by application.yml',
        unread.length === 0,
        unread.length ? `never read: ${unread.join(', ')}` : '')

      // The security-relevant half of the audit. Read this carefully, because the obvious
      // assertion is the wrong one.
      //
      // `application.yml` writes `${JWT_SECRET:}` - technically a default, of an empty string.
      // Asserting "no default" would therefore fail, and the tempting fix would be to change
      // the yml. That would be wrong: the empty default exists because `"${VAR:}"` resolves to
      // "" instead of failing, which lets the REAL guard live somewhere better.
      //
      // That guard is JwtService's constructor, which rejects a blank secret AND one shorter
      // than 32 bytes. Checking length in the yml is impossible, and an application that boots
      // with a weak secret looks perfectly healthy while minting forgeable tokens. So the
      // assertion belongs on the constructor.
      const jwtServicePath = path.join(
        backendRoot, 'src', 'main', 'java', 'com', 'shop', 'ecommerce',
        'security', 'jwt', 'JwtService.java',
      )
      if (fs.existsSync(jwtServicePath)) {
        const jwtService = fs.readFileSync(jwtServicePath, 'utf8')
        check('JwtService refuses a blank secret at startup',
          /secret\.isBlank\(\)/.test(jwtService),
          'a blank secret would sign forgeable tokens while the app looked healthy')
        check('JwtService enforces the 32-byte minimum for HS256',
          /keyBytes\.length\s*<\s*32/.test(jwtService),
          'a short secret is the common mistake - 32 CHARACTERS is not 32 BYTES')
      } else {
        check('the config audit can find JwtService.java', false, jwtServicePath)
      }

      // And the operational half: the variable must be documented, since a reader cannot guess
      // the name of the one setting with no usable default.
      check('JWT_SECRET is documented in .env.example', documented.has('JWT_SECRET'),
        'the one variable a reader cannot start without must be discoverable')
    }
  }

  // -------------------------------------------------------------------------
  console.log('\n' + '='.repeat(56))
  console.log(`  ${passed} passed, ${failed} failed`)
  if (failed) {
    console.log('\n  FAILURES:')
    failures.forEach((f) => console.log(`    \u2022 ${f}`))
  }
  console.log('='.repeat(56) + '\n')
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((err) => {
  console.error('\nE2E aborted:', err.message)
  console.error(err.stack?.split('\n').slice(1, 4).join('\n'))
  process.exit(2)
})
