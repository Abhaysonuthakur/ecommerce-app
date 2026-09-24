# Architecture

## 1. The shape of the system

```
┌──────────────────────────────────────────────┐
│  Browser — React 18 + Vite                   │
│                                              │
│  pages/ ─── layouts/ ─── components/         │
│     │                                        │
│     ├─ context/AuthContext  (who am I?)      │
│     ├─ services/api.js      (ONE axios)      │
│     └─ 3d/  (lazy-loaded, optional)          │
└────────────────────┬─────────────────────────┘
                     │  JSON over HTTP
                     │  Authorization: Bearer <jwt>
                     ▼
┌──────────────────────────────────────────────┐
│  Spring Boot 3.5 (embedded Tomcat :8080)     │
│                                              │
│  security/  ── JwtAuthFilter                 │
│      │         OAuth2SuccessHandler          │
│      ▼                                       │
│  controller/  thin — HTTP in, HTTP out       │
│      ▼                                       │
│  service/     business rules, @Transactional │
│      ▼                                       │
│  repository/  Spring Data JPA                │
│      ▼                                       │
│  entity/      Hibernate mappings             │
└────────────────────┬─────────────────────────┘
                     │  JDBC
                     ▼
             ┌───────────────┐
             │    MySQL 8    │
             └───────────────┘
```

## 2. Why this layer order, and what each layer is forbidden to do

The rule that keeps a layered app honest is that **each layer may only talk to the one
below it, and dependencies point one way.**

| Layer | Knows about | Must NOT |
|---|---|---|
| `controller` | HTTP, DTOs, `service` | contain business rules; touch repositories; return entities |
| `service` | business rules, repositories, entities, `mapper` | know about `HttpServletRequest`; build HTTP responses |
| `repository` | entities, Spring Data | contain business rules |
| `entity` | JPA | be returned from a controller |
| `dto` | nothing | reference entities |
| `mapper` | entities **and** DTOs | contain business rules |

`mapper` is the only class allowed to know both worlds. That is what makes
"entities never leave the service layer" a checkable statement rather than a hope.

**Why this matters in an interview:** the question is never "what is a controller" —
it is "what happens when a requirement changes". With these boundaries, adding a
"featured products" flag touches: migration → entity → DTO → mapper → service →
controller. No layer is skipped and no layer needs to know why.

## 3. Package layout

```
com.shop.ecommerce
├── config/          OpenApiConfig, DataSeeder
├── controller/      AuthController, ProductController, ...
├── dto/
│   ├── auth/        RegisterRequest, LoginRequest, AuthResponse
│   ├── product/     ProductRequest, ProductResponse
│   ├── category/    CategoryRequest, CategoryResponse
│   ├── cart/        AddToCartRequest, UpdateCartItemRequest, CartResponse, CartItemResponse
│   ├── order/       PlaceOrderRequest, OrderResponse, OrderItemResponse, UpdateOrderStatusRequest
│   ├── user/        UserResponse, UpdateProfileRequest
│   └── common/      PageResponse, ApiErrorResponse, MessageResponse
├── entity/          BaseEntity, User, Category, Product, Cart, CartItem, Order, OrderItem, enums
├── repository/      one per aggregate
├── service/         interfaces + impl/ subpackage
├── security/
│   ├── jwt/         JwtService, JwtAuthenticationFilter, AuthenticatedUser
│   ├── oauth/       OAuth2LoginSuccessHandler
│   └── config/      SecurityConfig, PrincipalResolver
├── exception/       ApiException hierarchy + GlobalExceptionHandler
└── mapper/          MapStruct mappers
```

## 4. Request flow, traced end to end

Take `POST /api/cart/items` — "add this product to my cart":

```
1. Browser        api.js attaches  Authorization: Bearer <jwt>
2. Tomcat         parses the request, builds HttpServletRequest
3. JwtAuthFilter  verifies signature + exp, reads the `sub` claim (user id),
                  loads the live User row, installs a UsernamePasswordAuthenticationToken
                  into the SecurityContext
4. SecurityFilterChain
                  /api/cart/** is not in PUBLIC_ROUTES -> requires authentication.
                  The context is non-null, so the request continues.
5. DispatcherServlet
                  routes to CartController.addItem(...)
6. @Valid         Jakarta Bean Validation rejects quantity <= 0 with 400
                  before the method body runs
7. CartController receives (AuthenticatedUser, AddToCartRequest), delegates
8. CartService    @Transactional:
                    - load the product, reject if inactive
                    - reject if quantity > stock
                    - find-or-create the user's cart
                    - find-or-create the CartItem, then increment
9. CartRepository flush -> INSERT/UPDATE
10. mapper        CartItem -> CartItemResponse -> CartResponse (recomputed subtotal)
11. Jackson       serialises to JSON
12. Browser       updates cart badge from AuthContext
```

Steps 3–5 happen before any of our code is called. That is why a JWT filter cannot be
tested through a controller — it has to be tested through the whole chain.

## 5. Authentication: two entry points, one identity

```
                    ┌──────────────────────────┐
  email + password ─┤  AuthService.login       │
                    │  BCrypt.matches(raw,hash)│──┐
                    └──────────────────────────┘  │
                                                  │  both roads converge on
                                                  │  AuthResponse { token, user }
                    ┌──────────────────────────┐  │
  Google OIDC ──────┤ OAuth2LoginSuccessHandler│──┘
                    │  find-or-create CUSTOMER │
                    └──────────────────────────┘
```

The second road is the one people get wrong. Google's OAuth2 login is handled by
*Spring's* filter, which ends in a redirect — not a JSON response. So the handler's job
is: resolve the Google identity → find or create the local User with role CUSTOMER →
mint the **same** application JWT → redirect to the frontend with the token in the URL
fragment. From that point on the client is holding an ordinary application token and
nothing downstream knows or cares which road it came from.

**A Google user is never an admin.** The role is assigned in exactly one place in the
codebase, with a literal `Role.CUSTOMER`, and there is no request field anywhere that
can influence it.

## 6. Two independent authorization layers

| Layer | Where | What it protects against |
|---|---|---|
| Filter chain | `SecurityConfig` URL matchers | Anything, before a controller is even selected |
| Method security | `@PreAuthorize` on service methods | A controller method that forgets to check — defence in depth |

Both refusal paths produce the **identical** error body, because both go through the
same `ApiErrorWriter`. A client cannot tell which one refused it, and does not need to.

## 7. Order placement: the transactional centrepiece

```
          @Transactional
          ┌──────────────────────────────────────────────────┐
          │ 1. load cart + items (with product rows locked)   │
          │ 2. reject if cart is empty                        │
          │ 3. for each line:                                 │
          │      product active?                              │
          │      stock >= quantity?     ── no ──> 409/400 ──┐ │
          │ 4. create Order (status PENDING)                 │ │
          │ 5. create OrderItem rows, copying unit price     │ │
          │ 6. decrement stock on each product               │ │
          │ 7. delete cart items                             │ │
          └──────────────────────────────────────────────────┼─┘
                                                             │
                       any exception ──────────────────────┘
                                                             ▼
                                              ┌──────────────────────────┐
                                              │ ROLLBACK — nothing saved │
                                              └──────────────────────────┘
```

### Why pessimistic locks

Two customers buying the last unit at the same instant is not a rare case in a shop —
it is the normal case during a sale. Optimistic locking (`@Version`) *detects* the
conflict and makes the loser retry; pessimistic locking (`PESSIMISTIC_WRITE`) makes the
loser *wait*, and the wait is short. For "decrement a counter", waiting is the right
answer because retrying means re-pricing and possibly re-validating the whole cart.

### Why the locks are taken in ascending product-id order

Deadlock is a cycle. If thread A locks product 7 then wants product 3, while thread B
locks 3 then wants 7, neither can proceed. Imposing a global order on lock acquisition
makes a cycle impossible — this is the dining-philosophers fix, applied to rows.

The ordering is done by sorting the requested ids **before** querying, so the
`SELECT ... FOR UPDATE` statements are issued in the same sequence by every thread.

## 8. Error contract

One body, every failure path:

```json
{
  "timestamp": "2026-09-24T07:12:33Z",
  "status": 404,
  "error": "PRODUCT_NOT_FOUND",
  "message": "Product not found with id: 10",
  "path": "/api/products/10",
  "fieldErrors": [ { "field": "quantity", "message": "must be greater than 0" } ]
}
```

`error` is the stable part a client can branch on; `message` is for humans and may
change. `fieldErrors` is present only for validation failures.

Paths that reach it:

- `@RestControllerAdvice` — for anything thrown inside a controller or service.
- `AuthenticationEntryPoint` / `AccessDeniedHandler` — for refusals raised inside the
  servlet filter chain, which never reach the advice because a filter runs outside the
  dispatcher.
- `GlobalExceptionHandler.handleAccessDenied` — because `@PreAuthorize` throws
  `AccessDeniedException` **during handler invocation**, so it is resolved by the advice
  long before `ExceptionTranslationFilter` could see it. Without that handler, a
  catch-all `Exception` handler would turn every 403 into a 500.

## 9. Frontend state: what lives where

| State | Lives in | Why |
|---|---|---|
| Token + current user | `AuthContext` + `localStorage` | Survives a page refresh; needed by every request |
| Cart | Server (fetched into `CartContext`) | The cart is a database row; two devices must agree |
| Product list, filters, page | URL query string | Shareable, back-button works, refresh-safe |
| 3D scene state | Inside the R3F component | Nothing outside the canvas needs it |

The product list deliberately keeps its filters in the URL rather than in component
state. It is the difference between "send me the link to that filtered view" working
and not working.

## 10. Where 3D sits, and why it is optional

3D is a **leaf**, never a dependency:

```
ProductDetailPage
├── product data (API)          ← always required
├── <Suspense fallback={<ProductImage/>}>
│     └── lazy(() => import('../../3d/ProductViewer'))
│           └── <Canvas> — three, @react-three/fiber, @react-three/drei
└── Add to cart, price, stock   ← plain React, works with 3D disabled
```

`ProductViewer` is behind `React.lazy`, so `three` (≈600 KB) only downloads on a product
page. The `Suspense` fallback is the product image, which means the fallback is also the
*success* path for anyone whose device cannot run WebGL. A `useIsMobile` hook gates the
mount so a phone never even requests the chunk.

Everything commercial — search, cart, checkout, orders, admin — is plain React with no
3D import anywhere in its dependency graph.
