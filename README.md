# Aurora Store

A full-stack e-commerce application: **Spring Boot 3.5 REST API + React 18 storefront**, with
real authentication, real inventory, and a checkout that stays correct when two customers buy
the last unit at the same instant.

This is a learning project built to be *explained*, not just run. Every layer is visible, every
non-obvious decision is commented where it lives, and the hard parts have the measurements
behind them recorded rather than the conclusions alone.

```
                              ┌─────────────────────────┐
                              │  Browser                │
                              │  React 18 + Vite        │
                              │  :5173                  │
                              └───────────┬─────────────┘
                                          │  JSON over HTTP
                                          │  Authorization: Bearer <jwt>
                                          ▼
                              ┌─────────────────────────┐
                              │  Spring Boot 3.5        │
                              │  embedded Tomcat :8080  │
                              │                         │
                              │  filter  -> validation  │
                              │  controller -> service  │
                              │     -> repository       │
                              └───────────┬─────────────┘
                                          │  JDBC
                                          ▼
                              ┌─────────────────────────┐
                              │  MySQL 8                │
                              │  InnoDB, REPEATABLE READ│
                              └─────────────────────────┘
```

---

## Table of contents

1. [The one thing worth reading first](#1-the-one-thing-worth-reading-first)
2. [Quick start](#2-quick-start)
3. [Demo accounts](#3-demo-accounts)
4. [Feature list](#4-feature-list)
5. [Architecture](#5-architecture)
6. [The checkout race, in full](#6-the-checkout-race-in-full)
6a. [Two more bugs, found by the E2E suite](#6a-two-more-bugs-found-by-writing-the-end-to-end-suite)
7. [Security model](#7-security-model)
8. [Testing](#8-testing)
9. [API reference](#9-api-reference)
10. [Configuration](#10-configuration)
11. [Project layout](#11-project-layout)
12. [Deliverables](#12-deliverables)
13. [Known limitations](#13-known-limitations)

---

## 1. The one thing worth reading first

Two customers, one unit of stock. Both press **Place order** at the same moment. Both get a
`201 Created`. Stock goes to `-1`. One of them paid for something that does not exist, and you
find out when they complain.

The fix is not "add a lock". Adding a lock is the easy half, and in this codebase **adding a
lock was not enough** — the lock was held, and the oversell still happened. The reason is a
MySQL `REPEATABLE READ` rule that is easy to be wrong about, and the only way to find it was to
stop reading the code and start reading the database's own log.

The answer, the measurement, and the four-part fix are in
[§6](#6-the-checkout-race-in-full). It is the centrepiece of this project.

---

## 2. Quick start

### Prerequisites

| Tool | Version used | Notes |
|---|---|---|
| JDK | **17** | `JAVA_HOME` must point at it |
| Maven | 3.9.x | wrapper is not committed; use your own |
| MySQL | **8.0** | InnoDB; `utf8mb4` |
| Node | **20+** | 22 used here |

### 2a. Database

```bash
# Creates the schema and, if run fresh, an empty database.
mysql -u root -p --default-character-set=utf8mb4 < backend/db/schema.sql
```

> `schema.sql` begins with `DROP DATABASE IF EXISTS ecommerce_db`. That makes it a safe,
> repeatable reset — and a destructive one. Never point it at a database you want to keep.

### 2b. Backend

```bash
cd backend

# The one variable with no default. The app refuses to start without it.
export JWT_SECRET="$(openssl rand -base64 48)"

export DB_USERNAME=root
export DB_PASSWORD=your-password

mvn clean spring-boot:run
```

The application starts on **:8080** and, because the database is empty, seeds demo data
automatically: **2 users, 6 categories, 19 products.**

Verify:

```bash
curl --noproxy '*' http://localhost:8080/api/health
# {"status":"UP","googleEnabled":false}
```

**Verified end to end** on this machine: `openssl rand -base64 48` produces a 64-character
secret, `spring-boot:run` starts offline, and `/api/health` returns `UP`. On a second run the
seeder logs `Database already seeded (N users) - skipping demo data` rather than duplicating it.

#### If it fails to start

**`mvn: command not found`** — Maven is not on `PATH` here. Either add it, or call it by its full
path (`C:\Users\lenovo\.m2\wrapper\dists\apache-maven-3.9.11\bin\mvn.cmd` on this machine).

**`UnsatisfiedDependencyException ... jwtService ... Constructor threw exception`** — this is
almost always a missing or too-short `JWT_SECRET`, not a wiring bug. The real message is in the
**last** `Caused by` line of the stack trace; read that rather than the Tomcat error at the top.
Confirm the value is set and at least 32 bytes:

```bash
echo -n "$JWT_SECRET" | wc -c     # must be >= 32
```

The application refuses to start without it on purpose. A fallback default would mean every
deployment that forgot the variable would sign tokens with the same publicly-known key — so the
loud failure is the correct behaviour, not an obstacle to work around.

> **`--noproxy '*'` matters on Windows.** A system proxy commonly intercepts `localhost` and
> you get a misleading `502` for a server that is running perfectly.

### 2c. Frontend

```bash
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**. The Vite dev server proxies `/api` to `:8080`, so the browser
sees same-origin requests — no CORS configuration needed in development.

### 2d. API docs

Swagger UI: **http://localhost:8080/swagger-ui.html**

Sign in via `POST /api/auth/login`, copy the `token`, click **Authorize**, paste it. All
endpoints are annotated; the bearer scheme is declared once in the OpenAPI config.

---

## 3. Demo accounts

Seeded only when the `users` table is empty.

| Role | Email | Password | Can do |
|---|---|---|---|
| **ADMIN** | `admin@shop.com` | `Admin@123` | Everything, plus `/api/admin/**` |
| **CUSTOMER** | `customer@shop.com` | `Customer@123` | Own cart, own orders, own profile |

> These are **development fixtures**, not a backdoor. The seeder refuses to run against a
> non-empty database, so it cannot resurrect a deleted admin in production. Role is enforced
> server-side on every request; the UI hiding a button is a convenience, never a control.
>
> To promote a user in a real deployment, change the row directly — there is deliberately no
> "become admin" endpoint.

---

## 4. Feature list

**Accounts**
- Register / sign in with email + password (BCrypt, cost 10)
- JWT bearer tokens, HS256, 1-hour expiry
- Google OAuth2 / OIDC sign-in *(optional; see [§10](#10-configuration))*
- Profile read and update

**Catalogue**
- 6 categories, 19 products
- Search by keyword, filter by category and price range
- Sort by any of 7 whitelisted fields, both directions
- Pagination with a stable tie-breaker on `id`

**Cart**
- Server-side cart — the client never invents a price
- Add, change quantity, remove, clear
- Per-line availability checks surfaced *before* checkout

**Checkout**
- Atomic order placement: stock decremented, order written, cart emptied — all or nothing
- Pessimistic locking so two concurrent buyers cannot both win the last unit
- Stock restored on cancellation
- Order status machine with validated transitions

**Admin**
- Dashboard: 8 counters plus an orders-by-status breakdown
- Category CRUD, product CRUD, soft-delete for products referenced by orders
- Order status transitions, with the legal next states supplied per order
- User list and role management, protecting the last remaining admin

**Frontend**
- React 18 + React Router 6
- URL-driven filters (shareable, bookmarkable, back-button correct)
- Interactive **3D product viewer** — Three.js, lazy-loaded, optional
- Accessible: keyboard navigation, focus management, live regions, reduced-motion support

---

## 5. Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  FILTER LAYER                                                   │
│                                                                 │
│   JwtAuthenticationFilter                                       │
│     reads Authorization: Bearer ...                             │
│     validates signature + expiry, loads the user, sets the      │
│     SecurityContext. It does NOT decide who may call what.      │
│                                                                 │
│   OAuth2SuccessHandler                                          │
│     turns a Google login into the SAME JWT the password login   │
│     issues, so downstream code has one identity format.         │
└────────────────────────────────┬────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  SECURITY LAYER   — who may call what                           │
│                                                                 │
│   SecurityFilterChain:                                          │
│     PUBLIC   → /api/auth/**, /api/products, /api/categories,    │
│                /api/health, swagger, oauth2                     │
│     ADMIN    → /api/admin/**            (hasRole('ADMIN'))      │
│     THE REST → .authenticated()                                 │
└────────────────────────────────┬────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  CONTROLLER      HTTP in, HTTP out. No business logic.          │
│                  @Valid on request bodies. Returns DTOs.        │
│                  Never returns an entity.                       │
└────────────────────────────────┬────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  SERVICE         Business rules and transaction boundaries.     │
│                  @Transactional lives here, not on controllers. │
└────────────────────────────────┬────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  REPOSITORY      Spring Data JPA. Locking queries, fetch joins. │
└────────────────────────────────┬────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  ENTITY          Hibernate mappings. Never leaves this layer.   │
└─────────────────────────────────────────────────────────────────┘
```

### Why DTOs, always

An entity is a persistence object; its shape is dictated by the database and its associations
are lazy proxies that can explode outside a transaction. Returning one from a controller
publishes your schema as a public API, leaks fields you never meant to expose (a password
hash), and makes every refactor a breaking change. So:

```
Entity  ──(mapper)──▶  Response DTO  ──▶  JSON
Request DTO  ──(mapper)──▶  Entity
```

Mapping is hand-written and explicit. A mapping library would hide exactly the decisions
worth seeing here — which fields are exposed, and which are computed.

### The three-bean transaction pattern

Checkout involves three things that cannot be one transaction:

```
TransactionService          NOT @Transactional — an orchestrator
    │
    ├─▶ LedgerService            @Transactional   — moves the money/stock
    │
    └─▶ FailedTransactionRecorder REQUIRES_NEW    — writes the FAILED audit row
```

A `FAILED` audit row cannot be written from inside a transaction that is about to roll back —
it would vanish with it. It needs its own transaction. But a `REQUIRES_NEW` method called on
`this` bypasses the proxy and silently runs in the caller's transaction, so the recorder must
be a **separate bean**. And the orchestrator must *not* be transactional, because otherwise
the `REQUIRES_NEW` insert blocks on the shared foreign-key lock held by the outer transaction,
and the audit write deadlocks the checkout it exists to document.

Remove any one of the three and the audit trail breaks — quietly. This is the kind of design
that only makes sense once you have watched it fail.

---

## 6. The checkout race, in full

### The symptom

`OrderConcurrencyTest` fires N concurrent orders at a product with **one** unit of stock and
asserts exactly one succeeds:

```
expected: 1
 but was: 2
```

### The obvious fix, which was not enough

The code already did what every article recommends:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
@Query("select p from Product p where p.id in :ids order by p.id")
List<Product> findAllByIdWithLock(@Param("ids") Collection<Long> ids);
```

Taking the locks **in ascending id order** so that A→B and B→A cannot deadlock. Correct, and
still oversold.

Worse, the locking read **appeared to work**. Instrumenting
`performance_schema.data_locks` from a third connection showed exactly what a working lock
looks like:

```
session 1373  ... LOCK_STATUS = GRANTED
session 1374  ... LOCK_STATUS = WAITING   (polled 8 times, ~1070 ms)
```

One granted, one waiting. The lock was real, held, and blocking. And both transactions still
read the same stale stock and both committed.

**Holding a lock and reading a current value are independent properties.** That sentence is
the whole bug.

### The actual cause

MySQL's default isolation is `REPEATABLE READ`, and the read view is pinned by the
transaction's **first plain read** — not by `START TRANSACTION`.

A locking read bypasses the snapshot when it is the first read. Once a plain read has already
pinned a view, a later `SELECT ... FOR UPDATE` still takes its locks and still blocks for the
full duration — and then **returns the snapshot value anyway.**

Measured, both sessions waiting ~1070 ms:

| First statement in the transaction | Value the locking read returned | Correct? |
|---|---|---|
| `SELECT ... FOR UPDATE` | `999` (the committed value) | ✅ |
| plain `SELECT` first, then `FOR UPDATE` | `7` (the stale snapshot) | ❌ |

`EntityManager.refresh()` is a plain read, so it cannot repair a pinned view either.

### Why the first fix attempt failed

The checkout query was supposed to read **only** order lines, then lock products separately.
But `CartItem.product` was mapped `@ManyToOne(fetch = EAGER)` — and **Hibernate adds its own
join to satisfy an eager association on every query that returns the owning entity**, whether
or not the query mentions it.

MySQL's general log showed the "lines-only" query emitting:

```sql
select ... from cart_items i1_0 left join products p1_0 on p1_0.id = i1_0.product_id ...
```

The join hydrated the product and pinned the read view before the lock was taken. The lock
then honoured the snapshot.

> Anyone reading the HQL would conclude no product was read. The database log is the only
> place the truth was visible. Hibernate's own SQL log interleaves threads and hid it.

### The fix — all four parts

**1. `CartItem.product` becomes `LAZY`.**

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "product_id", nullable = false)
private Product product;
```

Now the checkout query touches only `cart_items`, and no read view is pinned before the lock.

**2. A read-only FK column**, so code can get the product *id* without hydrating the product:

```java
@Column(name = "product_id", insertable = false, updatable = false)
private Long productId;
```

**3. Two purpose-built cart queries**, because one query cannot serve both callers:

```java
// The cart page renders each line's product, so it needs the fetch join.
@Query("select distinct c from Cart c join fetch c.items i join fetch i.product where c.user.id = :userId")
Optional<Cart> findByUserIdWithItems(@Param("userId") Long userId);

// Checkout must NOT read products before locking them. FK column only.
@Query("select distinct c from Cart c join fetch c.items i where c.user.id = :userId")
Optional<Cart> findByUserIdWithItemsOnly(@Param("userId") Long userId);
```

**4. Lock the ids in ascending order**, then read stock from the locked rows.

### Verification

The race now has to be proven, not assumed. Four independent checks:

| Check | Scope | Result |
|---|---|---|
| `OrderConcurrencyTest` × 5 consecutive runs | MySQL, real concurrency | **5/5 green** |
| `mvn clean test` | 289 tests, H2 | **0 failures, 0 errors** |
| Cancel path over real HTTP | stock restored | `3` and `1` units returned |
| Ledger reconciliation SQL | 0 mismatches | ✔ |

A single green run of a concurrency test proves nothing — it can pass because the race did not
happen to occur. Hence **five** consecutive runs.

### The regression guard

`RepositoryPersistenceTest` originally asserted `CartItem.product` was `EAGER`, with a
reasonable-sounding comment defending it. **The test was protecting the bug.** It now asserts
`LAZY`, carries the mechanism in the comment, and a companion test pins that the two cart
queries differ *only* by the product join:

```java
assertThat(fetchJoinDeclaredIn(CartRepository.class, "findByUserIdWithItemsOnly", "i.product"))
        .as("the checkout path must NOT read the products before it locks them - "
                + "this join is what allowed the oversell")
        .isFalse();
```

### The lesson worth keeping

> When a primitive you believe is correct misbehaves **in situ**, bisect the call path before
> re-examining the primitive.

`SELECT ... FOR UPDATE` was never broken. The query feeding it was silently different from
what its source code said. An entire day went into probing locking, isolation, connection
pooling and autocommit — all of which were fine — while the answer was one line of SQL in the
general log.

The defensive version of that habit: **on day one, turn on MySQL's general log and print the
SQL by default.** It costs one config change and saves the whole investigation.

---

## 6a. Two more bugs, found by writing the end-to-end suite

The 289-test suite runs against H2 and slices. It cannot prove the assembled system works, so
there is also `backend/db/e2e.mjs` — 115 assertions against a real server and a real MySQL.
Writing it surfaced two defects the unit suite structurally could not see.

Both are the same shape: **a guard that reads as protection but is not wired up.** Neither
would have been caught by adding more tests to the existing suite, because the flaw was in the
absence of behaviour.

### Bug 1 — query-parameter constraints were never evaluated

`ProductFilter` declares its bounds properly:

```java
@Min(value = 1, message = "Page size must be at least 1")
@Max(value = 100, message = "Page size must not exceed 100")
Integer size,
```

with a comment explaining that *"without an upper bound, ?size=1000000 is a..."* — and the
endpoint's own `@ApiResponse` advertises a `400` for invalid input.

But the list endpoint declares its parameters individually (so Swagger renders each one) and
then builds the record by hand:

```java
ProductFilter filter = new ProductFilter(page, size, keyword, categoryId, ...);
```

**Bean Validation does not run on a plain constructor call.** Constraints are evaluated when
Spring binds an object, when `@Valid` is applied to a request body, or when a `Validator` is
invoked. A hand-built record is none of those, so every constraint was inert.

Measured: `?size=1000000` returned **200** and asked MySQL for a million rows.

A second instance of the same bug sat on **both** order-list endpoints.

**The fix** is to validate explicitly, since the parameters must stay individual for the
documentation:

```java
ProductFilter filter = new ProductFilter(page, size, keyword, categoryId, minPrice, maxPrice, active, sort);
validateFilter(filter);   // <- a plain constructor call validates nothing
```

The resulting `ConstraintViolationException` reaches the existing handler, so the error shape
is unchanged — and now names the offending field:

```json
{"error": "VALIDATION_FAILED", "path": "/api/products",
 "fieldErrors": [{"field": "size", "message": "Page size must not exceed 100"}]}
```

**What proved the fix.** A regression test was added, then the fix was temporarily **reverted**
to confirm the test fails: 5 failures in the product-list group, build red. A regression test
that passes against broken code is worthless, so it was checked in both directions.

> **The lesson:** declaring a constraint is not enforcing one. And the existing
> `DtoValidationTest` passed the whole time, because it validated the records with a `Validator`
> directly — supplying exactly the invocation the controller was missing. A test can prove that
> a rule is *correct* while never proving it is *applied*.

### Bug 2 — the admin's "Show inactive" toggle fetched the storefront list

The admin console has a checkbox to include withdrawn products. It sent:

```js
active: showInactive ? undefined : true
```

with a comment asserting that undefined *"omits the parameter entirely, which the server reads
as 'no filter'"*.

The server does not read it that way. `ProductFilter`'s compact constructor sets
`active = true` when the parameter is absent — deliberately, so a storefront request that
forgets it cannot publish withdrawn stock. So omitting it returned the **storefront list**, and
the checkbox quietly hid the very products it existed to reveal.

Measured, with one inactive product in the database:

| Request | Result |
|---|---|
| `?active=false` | `total: 1` — the withdrawn product ✅ |
| `?active` omitted | `total: 19` — the storefront list ❌ |

**The fix** — the parameter is genuinely tri-state, and there is no single value meaning
"both", so the client asks for both:

```js
showInactive
  ? Promise.all([listProducts({ active: true }), listProducts({ active: false })])
      .then(merge)
  : listProducts({ active: true })
```

Two requests is the honest cost of a server default that protects the storefront. Weakening
that default to make the client simpler would have been the wrong trade.

> **The lesson:** a confidently-worded comment is not evidence. That comment was wrong, and it
> was wrong in the specific direction that makes a bug invisible — it described what the author
> *intended* the server to do, and nobody re-read the constructor.

---

## 7. Security model

```
                    ┌──────────────────────────────┐
   POST /api/auth/login │ verify password (BCrypt)  │
                    │ issue JWT                    │
                    │ { sub, role, iat, exp, iss } │
                    └───────────────┬──────────────┘
                                    ▼
                    ┌──────────────────────────────┐
   every request    │ JwtAuthenticationFilter      │
                    │  - signature (HS256)         │
                    │  - expiry                    │
                    │  - issuer                    │
                    │  - load user, set context    │
                    └───────────────┬──────────────┘
                                    ▼
                    ┌──────────────────────────────┐
                    │ SecurityFilterChain          │
                    │  /api/admin/** → hasRole     │
                    │  public list   → permitAll   │
                    │  everything    → authenticated│
                    └──────────────────────────────┘
```

Decisions worth stating:

- **The JWT filter is not a `@Component`.** If it were, Spring Boot would also register it as a
  servlet filter for *every* request, so it would run twice. It is constructed explicitly and
  wired with `.addFilterBefore(...)`.
- **`role` is never accepted from a registration request.** Not validated — *absent*. Published
  input is an attack surface, and the safest validation rule is the field not existing.
- **Google users are always `CUSTOMER`.** An identity provider authenticates; it does not
  authorise. Deriving a role from a provider's claims would hand application privileges to
  whoever controls that provider's configuration.
- **Passwords are capped at 72 bytes.** BCrypt truncates silently past that, so two different
  long passwords would authenticate each other. Better to reject than to accept the wrong one.
- **Login failures are indistinguishable.** Wrong email and wrong password both return
  `INVALID_CREDENTIALS`, so the endpoint cannot be used to enumerate accounts.
- **An unauthenticated 401 is not a bug.** An unmapped path returns `401` for anonymous
  callers (the catch-all `.authenticated()` rule applies before routing) and `404` once
  authenticated. Returning `404` to anonymous callers would turn the API into a path scanner.
  Both behaviours are asserted in tests.
- **`NoResourceFoundException` is handled explicitly**, otherwise unmapped paths return `500`
  instead of `404`.

### Authorisation is enforced at three levels

1. **Route** — `SecurityFilterChain` requires `ADMIN` for `/api/admin/**`.
2. **Method** — services re-check ownership or role. A customer fetching another customer's
   order gets `404`, deliberately not `403`: `403` would confirm the order exists.
3. **Query** — repository methods are scoped by the current user (`findByIdAndUserId`), so a
   missing check cannot return another user's row.

Level 3 is the one that matters. Levels 1 and 2 are conveniences by comparison.

---

## 8. Testing

Four layers, because each answers a question the others cannot.

```
1. Unit + slice          mvn test                          289 tests   (H2)
2. Concurrency           mvn test -Dtest=OrderConcurrencyTest  MySQL only
3. End-to-end API        node db/e2e.mjs                   115 checks  (real HTTP)
4. Rendered frontend     node scripts/*-check.cjs           headless Chrome
```

```
$ mvn clean test
289 tests, 0 failures, 0 errors, 1 skipped

$ node db/e2e.mjs
115 passed, 0 failed
```

| Suite | Tests | What it actually proves |
|---|---:|---|
| `RepositoryPersistenceTest` | 37 | Fetch types, locking queries, fetch joins |
| `DtoValidationTest` | 31 | Bean Validation rules, both directions |
| `EntityInvariantsTest` | 24 | Domain rules that must hold regardless of caller |
| `AuthenticationHttpTest` | 24 | Login, register, token handling over HTTP |
| `MapperOutputTest` | 23 | Entity→DTO mapping, field by field |
| `SortValidatorTest` | 23 | Rejects unknown sort fields (injection surface) |
| `JwtServiceTest` | 23 | Signing, expiry, tampering, issuer |
| `OrderStatusTest` | 19 | The status machine and its illegal transitions |
| `ErrorContractHttpTest` | 17 | Every documented error code, with real HTTP |
| `CartHttpTest` | 14 | Cart operations end to end |
| `DomainBoundaryTest` | 13 | Multi-tenant boundaries: A cannot read B's data |
| `ByteLengthValidatorTest` | 10 | The 72-byte BCrypt boundary |
| `CategorySlugTest` | 8 | Slug derivation and collisions |
| `QueryCountTest` | 3 | Guards against N+1 |
| `QueryParameterValidationHttpTest` | 16 | Query-parameter constraints actually REACH the record |
| `OrderConcurrencyTest` | 1 *(gated)* | **The oversell** — MySQL only |

### The end-to-end suite

```bash
cd backend
node db/e2e.mjs                        # against http://localhost:8080
node db/e2e.mjs http://host:port       # against something else
```

115 assertions against a running server and a real MySQL, walking the whole customer and admin
journeys:

```
health • catalogue • auth boundary • login (both roles) • registration + validation
identity • role enforcement • cart operations • checkout • order lifecycle
admin CRUD • self-protection guards • pagination/search/sort • error contract
configuration is wired, not decorative
```

It exists because the Spring suite cannot prove the assembled system works. It cannot check
that a token minted by `/auth/login` is accepted by `/cart`, that stock actually moves, that
the seeded data matches what the frontend expects, or that an authorisation boundary returns
403 rather than 500. Those are integration facts, and they need an integrated system.

**It paid for itself immediately** — it found the two bugs in [§6a](#6a-two-more-bugs-found-by-writing-the-end-to-end-suite),
both of which the 289-test suite had passed over for the entire project.

### Rendered frontend checks

`npm run build` proves the code **compiles**. It says nothing about whether the app **mounts** —
a hook-order violation or a bad import in a lazily-loaded chunk builds perfectly and then leaves
a blank page with a red console.

```bash
cd frontend
node scripts/render-check.cjs "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
node scripts/admin-inactive-check.cjs "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
node scripts/config-check.cjs "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe" http://localhost:5173
```

These drive real Chrome over the DevTools Protocol — no Puppeteer dependency, just Node's
built-in WebSocket — and report console errors, uncaught exceptions, failed requests, and
whether React actually painted into `#root`.

```
8/8 routes rendered clean

PASS  /                              1024 chars
PASS  /products                      2170 chars   (19 products, 6 categories)
PASS  /products/19                   1037 chars   (3D viewer chunk loaded)
PASS  /login                          596 chars
PASS  /register                       540 chars
PASS  /cart                           -> redirected to /login (RequireAuth)
PASS  /orders                         -> redirected to /login (RequireAuth)
PASS  /this-route-does-not-exist      376 chars   (404 page, URL preserved)
```

`config-check.cjs` covers a third failure mode, and one that survives review because the file
*looks* correct: **a documented setting that nothing reads.** Point it at a dev server and tell it
what the rendered page should say:

```bash
# with VITE_APP_NAME=TestMart and VITE_PRODUCTS_PAGE_SIZE=4 in frontend/.env
node scripts/config-check.cjs "<chrome-path>" http://localhost:5173 TestMart 4

# expected app name  : TestMart   observed brand : TestMart   PASS
# expected page size : 4          observed cards : 4          PASS
```

The expected values are passed on the command line rather than read from `.env`, so the check
cannot pass by reading the same file the app read. Both paths are verified: with a `.env` the
custom values appear, and with no `.env` at all the fallbacks do.

> This check found a genuine instance of the bug. `VITE_PRODUCTS_PAGE_SIZE` was documented, read
> into `config.js`, and then **ignored by the catalogue page**, which still used a local
> `const PAGE_SIZE = 12`. The variable was wired everywhere except the one page a reader would
> test it on — and `VITE_IMAGE_HOST` had no reader at all.

`admin-inactive-check.cjs` covers what the first cannot: it logs in as an admin through the real
form, navigates to the console, flips the "Include deactivated" toggle, and asserts the count
moves. That is the browser-level check for Bug 2 — which was invisible from the outside, since
the checkbox looked right and the request succeeded.

> **The first version of this check could not fail on its own bug.** It asserted
> `after >= before`, which is *trivially true when the toggle does nothing at all* — and doing
> nothing is exactly the bug. Worse, with no withdrawn product in the database both readings were
> `19`, so it passed on an empty fixture for months. A check that cannot fail on the defect it
> was written for is not a check.
>
> It now **provisions its own fixture**: it creates a product through the public admin API,
> deactivates it, then asserts the count moves by **exactly +1** (not `>=`), that the withdrawn
> product's name is *absent* while the toggle is off and *present* once it is on, and finally
> deletes the probe in a `finally` so a failure never leaves litter behind. Proved by
> re-injecting the original bug into `AdminLayout.jsx`: the check went red on exactly the two
> assertions that matter (`19 -> 19, expected 20`) where the old version would have printed
> `PASS: 19 >= 19`.

> **`net::ERR_ABORTED` entries are expected and correct.** They are the app's own
> `AbortController` cancelling superseded requests — React 18 StrictMode deliberately runs
> effects twice in development. Confirmed in the backend log: two product-list calls 15 ms
> apart, the first aborted, both served successfully. Aborting is the fix for a stale-response
> race, not a failure.

### Why one test is skipped by default

`OrderConcurrencyTest` needs **real** MySQL. H2 in `MODE=MySQL` parses `FOR UPDATE` and
silently does not block, so on H2 the test is meaningless — it would pass while proving
nothing. It is therefore gated and run explicitly:

```bash
mvn test -Dtest=OrderConcurrencyTest -Dspring.profiles.active=mysql-it
```

> A test that passes for the wrong reason is worse than no test. This one refuses to run rather
> than report a false green.

### Deliberate gaps

- `H2` does not enforce MySQL `CHECK` constraints. H2 tests prove Java logic; only the
  real-MySQL constraint suite proves the schema. Both are run.
- `MockMvc` is single-threaded, so it cannot express a race. That is precisely why
  `OrderConcurrencyTest` uses real HTTP against a real server.

---

## 9. API reference

Base path `/api`. All responses are JSON. Errors share one contract:

```json
{
  "timestamp": "2026-09-24T07:12:33Z",
  "status": 400,
  "error": "VALIDATION_FAILED",
  "message": "Validation failed",
  "path": "/api/products",
  "fieldErrors": [{ "field": "price", "message": "must be greater than 0" }]
}
```

Branch on **`error`** — a stable, machine-readable code. Never on `message`, which is written
for a human and may be reworded at any time.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/auth/register` | — | Create an account |
| POST | `/auth/login` | — | Obtain a JWT |
| POST | `/auth/me` | any | Who am I? |
| GET | `/users/me` | any | Profile |
| PUT | `/users/me` | any | Update profile |
| GET | `/products` | — | Search, filter, sort, page |
| GET | `/products/{id}` | — | One product |
| GET | `/categories` | — | Category tree |
| GET | `/cart` | any | My cart |
| POST | `/cart/items` | any | Add |
| PUT | `/cart/items/{id}` | any | Change quantity |
| DELETE | `/cart/items/{id}` | any | Remove |
| DELETE | `/cart` | any | Empty |
| POST | `/orders` | any | **Checkout** |
| GET | `/orders` | any | My orders |
| GET | `/orders/{id}` | owner | One order |
| PATCH | `/admin/orders/{id}/status` | admin | Transition |
| GET | `/admin/dashboard/stats` | admin | Counters |
| GET/POST/PUT/PATCH/DELETE | `/admin/products`, `/admin/categories` | admin | CRUD |
| GET/PATCH | `/admin/users`, `/admin/users/{id}/role` | admin | Users |
| GET | `/health` | — | Liveness |

### Notable error codes

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Field errors attached |
| `INVALID_CREDENTIALS` | 401 | Wrong email *or* password (same code) |
| `TOKEN_EXPIRED` | 401 | Valid signature, past `exp` |
| `INSUFFICIENT_STOCK` | 409 | Not enough stock; current stock included |
| `EMPTY_CART` | 409 | Checkout with nothing in the cart |
| `INVALID_ORDER_STATUS_TRANSITION` | 409 | Illegal state move; allowed next states included |
| `PRODUCT_IN_USE` | 409 | Hard delete would orphan an order line |
| `ACCESS_DENIED` | 403 | Authenticated but not permitted — includes self-demotion and last-admin |

The `409`s carry actionable detail. `INVALID_ORDER_STATUS_TRANSITION` returns the legal next
states, so a client can render correct buttons without duplicating the state machine.

---

## 10. Configuration

Two `.env.example` files document every variable: `backend/.env.example` and
`frontend/.env.example`. Copy to `.env` and fill in. **The real `.env` is git-ignored.**

### Backend

| Variable | Required | Notes |
|---|---|---|
| `JWT_SECRET` | **yes** | ≥32 bytes. No default — the app will not start without it |
| `DB_URL` | no | Defaults to local `ecommerce_db` |
| `DB_USERNAME` / `DB_PASSWORD` | no | Defaults to `root` / `root` |
| `JWT_EXPIRATION_MS` | no | Default `3600000` (1 h) |
| `JWT_ISSUER` | no | Default `aurora-store` |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | no | Blank disables Google login |
| `OAUTH2_REDIRECT_URI` | no | Where the OAuth flow returns |
| `FRONTEND_URL` | no | CORS origin |
| `SERVER_PORT` | no | Default `8080` |

### Frontend

| Variable | Notes |
|---|---|
| `VITE_API_BASE_URL` | **Empty by default — leave it empty.** An origin, not a path. See the warning below |
| `VITE_APP_NAME` | Display name |
| `VITE_PRODUCTS_PAGE_SIZE` | Default `12` |

> **`VITE_API_BASE_URL` is a prefix, not a replacement for `/api`.** Every call site already
> writes the full path (`/api/products`). Unset → `/api/products`. Set to
> `http://api.example.com` → `http://api.example.com/api/products`. Setting it to `/api` would
> produce `/api/api/products` and every request would 404.

### Running without Google

Leave `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` blank. The OAuth2 client is not
registered, the frontend hides the Google button, and `/api/health` reports
`"googleEnabled": false`. This is a supported configuration — it is what lets the project run
without a Google account.

---

## 11. Project layout

```
ecommerce-app/
├── backend/
│   ├── db/
│   │   ├── schema.sql              DDL; begins with DROP DATABASE
│   │   └── constraint-test.sql     MySQL CHECK/index assertions
│   ├── probe-src/probe/            Investigation harnesses for the lock work
│   ├── src/main/java/com/shop/ecommerce/
│   │   ├── config/                 Security, OpenAPI, CORS, seeding
│   │   ├── controller/             8 controllers, thin
│   │   ├── service/                Business rules, @Transactional
│   │   ├── repository/             Spring Data JPA, locking queries
│   │   ├── entity/                 7 entities
│   │   ├── dto/                    27 request/response DTOs
│   │   ├── mapper/                 Hand-written entity↔DTO
│   │   ├── security/               JwtService, JwtAuthenticationFilter, OAuth2
│   │   └── exception/              @RestControllerAdvice, ErrorCode
│   ├── src/test/java/…             16 test classes, 289 tests
│   ├── db/e2e.mjs                  110-assertion end-to-end suite (real HTTP)
│   └── .env.example
│
├── frontend/
│   ├── src/
│   │   ├── api/                    One module per backend controller
│   │   ├── context/                Auth, Cart, Toast
│   │   ├── components/             Layout, cards, filters, ui primitives
│   │   │   └── three/              3D viewer (lazy-loaded)
│   │   ├── hooks/                  useApiQuery, useDebounced, useMediaQuery
│   │   ├── config.js               Reads VITE_* once, with fallbacks
│   │   ├── lib/format.js           Money and date formatting
│   │   ├── pages/                  8 pages + admin console
│   │   └── styles/index.css        Design tokens
│   ├── scripts/
│   │   ├── render-check.cjs        Headless render verification, all routes
│   │   └── admin-inactive-check.cjs  Tri-state filter check (self-provisioning fixture)
│   └── .env.example
│
├── docs/
│   ├── 01-REQUIREMENTS.md
│   ├── 02-ARCHITECTURE.md
│   └── …                           See §12
└── README.md
```

### The 3D viewer is optional and lazy

`ProductViewer3D` is loaded with `React.lazy` only when a visitor asks for it. Three.js is
~900 kB; a static import puts that in the initial bundle for every visitor including those who
never open a product page. Measured effect:

| | Initial JS (gzipped) | Three.js |
|---|---:|---|
| Static import | ~322 kB | in main bundle |
| `lazy()` + `Suspense` | **~74 kB** | separate chunk, on demand |

The viewer degrades to a static image when WebGL is unavailable, the screen is small, or the
user prefers reduced motion.

---

## 12. Deliverables

| # | Deliverable | Where |
|---|---|---|
| A | Complete source code | `backend/`, `frontend/` |
| B | Database schema | `backend/db/schema.sql` |
| C | API documentation | Swagger UI + [§9](#9-api-reference) |
| D | Architecture documentation | `docs/02-ARCHITECTURE.md` |
| E | Requirements | `docs/01-REQUIREMENTS.md` |
| F | Security documentation | `docs/03-SECURITY.md` → [§7](#7-security-model) |
| G | Test suite | 289 unit/slice + 115 E2E + 3 browser checks, [§8](#8-testing) |
| H | Configuration | `backend/.env.example`, `frontend/.env.example` |
| I | Setup guide | [§2](#2-quick-start) |
| J | Interview guide | `docs/04-INTERVIEW-QA.md` |

---

## 13. Known limitations

Stated rather than hidden. Each is a deliberate scope decision.

1. **Single-row ledger, not double-entry.** Each transaction writes one row. Double-entry
   (a debit and a credit per movement) is the accounting-correct model and is not implemented.
   Reconciliation works here because there is one account per transfer; a real ledger would not.
2. **No refresh tokens.** A JWT lasts one hour and then the user signs in again. Refresh
   tokens need rotation and a revocation store, which is a larger surface than this project
   needs.
3. **Tokens in `localStorage`** are XSS-readable in a way an `httpOnly` cookie is not. The
   honest mitigation used here — short expiry, no user-supplied HTML, React's default escaping
   — is real but partial. A system handling real money should use `httpOnly` cookies plus CSRF
   tokens.
4. **No order-level idempotency key.** A double-submitted checkout creates two orders. The UI
   disables the button, but that is a courtesy, not a guarantee.
5. **`ddl-auto: validate` is a good check, not a complete one.** It catches a missing table or
   column; it does **not** catch a varchar length mismatch or a missing index. Measured, not
   assumed.
6. **Prices are `BigDecimal` but there is no multi-currency support.** One currency, stored
   with scale 2.
7. **`auto-commit: false` is kept for reasoning, not because it fixed anything.** It was added
   during the lock investigation and wrongly credited as the fix. It is retained because
   opening the transaction before the first statement is the behaviour the locking design
   assumes — and the comment saying so is in `application.yml`.

---

## Licence

Educational project. Use it however is useful.

**Stack:** Java 17 · Spring Boot 3.5.16 · Spring Data JPA · Hibernate 6.6 · MySQL 8.0.46 ·
Spring Security · JJWT 0.12 · springdoc 2.8 · React 18.3 · React Router 6.27 · Vite 5.4 ·
Three.js 0.169 · React Three Fiber 8.17 · GSAP 3.12
