# Project 5 — Professional E-Commerce Application

## 1. What we are building

A full-stack e-commerce store. Two kinds of people use it:

| Actor | Wants to |
|---|---|
| **Visitor** (not signed in) | Browse products, search, filter, read a product page in 3D |
| **Customer** (`ROLE_CUSTOMER`) | Everything a visitor does, plus: own cart, place orders, see order history, edit profile |
| **Admin** (`ROLE_ADMIN`) | Manage the catalogue (products, categories), see every order, move orders through their lifecycle, see store statistics |

## 2. Functional requirements

### 2.1 Catalogue (public read, admin write)

- List products with **pagination** (`page`, `size`).
- **Search** by keyword across name and description.
- **Filter** by category, minimum price, maximum price, active flag. Filters combine.
- **Sort** by any whitelisted field, ascending or descending.
- Get one product by id.
- Admin: create, update, deactivate (soft delete), hard delete only when never ordered.
- Categories: list (public), admin create/update/deactivate.

### 2.2 Accounts

- Register with email + password. **The role is always CUSTOMER** — a registration
  request that contains a `role` field is rejected, not silently ignored.
- Log in with email + password. Password verified against a BCrypt hash.
- Log in with Google (OAuth 2.0 + OpenID Connect). A brand-new Google user is
  **always CUSTOMER**.
- Read own profile, update own profile (name, phone, address).
- Never return a password field in any response, ever.

### 2.3 Cart (one per customer)

- Get my cart (created on first use).
- Add a product, or increase the quantity if it is already there.
- Update a line's quantity.
- Remove a line.
- Clear the cart.
- Server computes the subtotal. The client never sends prices.
- Reject: negative or zero quantity, unknown product, inactive product,
  quantity above available stock.

### 2.4 Orders

- Place an order from the cart — **atomically**:
  validate → snapshot prices → create order + items → decrement stock → clear cart,
  all in one transaction. Any failure rolls the whole thing back.
- Order lines keep the **price at purchase time**, so a later price change does not
  rewrite history.
- Customer: list own orders, get one of own orders.
- Admin: list all orders, filter by status, change status.
- Status lifecycle: `PENDING → CONFIRMED → PROCESSING → SHIPPED → DELIVERED`,
  plus `CANCELLED` from a non-terminal state (which restores stock).

### 2.5 Statistics (admin)

- Total products, total categories, total orders, total customers, revenue,
  order count by status.

## 3. Non-functional requirements

| Concern | Requirement |
|---|---|
| Security | BCrypt password hashing; JWT bearer auth; backend is the only authority on roles; no secrets in source; CORS restricted to a configured origin |
| Integrity | Money as `BigDecimal`/`DECIMAL(19,2)`; stock decrement cannot go negative; order placement is transactional |
| Performance | No N+1 on list endpoints; pagination capped at 100 per page; indexes on every searched/filtered column |
| Errors | One consistent JSON error body with a stable machine-readable `code` |
| Docs | OpenAPI 3 served at `/v3/api-docs`, Swagger UI with working bearer auth |
| Testability | Real integration tests over a real database, not `assertTrue(true)` |

## 4. Explicit non-goals

Naming these because a reviewer will ask, and "we did not build it" is a better answer
than silence:

- **No payment gateway.** Orders are placed, not paid. A real integration is a
  provider SDK plus webhook handling plus idempotency keys — a project of its own.
- **No file upload.** `imageUrl` is a URL string. Uploading needs object storage,
  content-type sniffing, and size limits.
- **No email.** Order confirmation is a database row, not a message.
- **No refresh tokens.** The access token is short-lived and the client re-authenticates.
  The trade-off is written down in the README.
- **No multi-currency, no tax, no shipping calculation.** Single currency (INR).
- **No microservices.** This is deliberately a modular monolith.

## 5. Definition of done

Backend is done when: it starts against MySQL, migrates its own schema, every endpoint
in the API table returns what the table says, the Swagger UI can authorise and call a
protected endpoint, and `mvn test` is green with tests that would fail if the security
rules inverted.

Frontend is done when: a visitor can browse and search, a customer can register, log in,
add to cart and place an order, an admin can manage the catalogue and advance an order,
the 3D viewer works on desktop and degrades to an image on a phone, and the whole flow
works with the backend restarted in between.
