# Security

A working description of how this application authenticates, authorises, and fails. Every
claim here was tested against the running application; where a control is partial, it says so.

---

## 1. Threat model, honestly scoped

What this application defends against:

| Threat | Defended? | How |
|---|---|---|
| Password theft at rest | ✅ | BCrypt, cost 10, per-password salt |
| Credential forgery | ✅ | HS256 signature, verified every request |
| Privilege escalation via the API | ✅ | Role read from the DB row, never from the request |
| Accessing another user's data | ✅ | Ownership in the query, not just the service |
| SQL injection | ✅ | Parameterised JPA throughout; sort fields whitelisted |
| Account enumeration | ✅ | Identical response for wrong email and wrong password |
| Mass-assignment (setting `role` on register) | ✅ | The field does not exist on the DTO |

What it does **not** defend against, by design:

| Threat | Not defended | Why, and what would be needed |
|---|---|---|
| XSS stealing the token | ⚠️ Partial | Token is in `localStorage`. `httpOnly` cookies + CSRF would be the fix |
| CSRF | N/A | No cookie auth, so no ambient credential to forge |
| Token revocation | ❌ | Stateless JWT. A revocation store (or short tokens + refresh) is needed |
| Rate limiting / brute force | ❌ | No limiter. Needs a gateway or bucket per IP+account |
| Double-submitted checkout | ⚠️ Partial | UI disables the button; an idempotency key would be the real fix |
| Denial of service | ❌ | Out of scope; needs infrastructure |

**Saying what is missing is part of the design.** A security document that claims everything is
handled is less useful than one that names its gaps.

---

## 2. Authentication

### Password login

```
POST /api/auth/login  { email, password }
        │
        ▼
   load user by email
        │
        ├─ not found ────▶ 401 INVALID_CREDENTIALS
        │
        ▼
   BCrypt.matches(raw, hash)
        │
        ├─ false ────────▶ 401 INVALID_CREDENTIALS      (identical response)
        │
        ▼
   issue JWT  { sub, role, iat, exp, iss }
```

**Why identical responses matter.** If a wrong email returned `USER_NOT_FOUND` and a wrong
password returned `BAD_PASSWORD`, then `login` becomes an oracle: an attacker submits a list of
emails and learns which are registered. Same code, same message, same timing shape.

**The 72-byte cap.** BCrypt reads at most 72 bytes of input and ignores the rest, so without a
cap `password` and `password` + 50 characters would both authenticate — a silent weakening that
no one would notice. `@ByteLength(max = 72)` rejects early. Note it counts **bytes**, not
characters: an emoji is 4 bytes, so a 20-emoji password is 80 bytes and correctly rejected.

**Cost 10** is the default and a reasonable balance. It is a deliberate, tunable cost: raising
it slows attackers and legitimate logins equally.

### Google OAuth2 / OIDC

Optional. Blank credentials disable it entirely.

```
browser ──▶ Google ──▶ /login/oauth2/code/google ──▶ OAuth2SuccessHandler
                                                            │
                                              find-or-create a local user
                                                            │
                                              issue the SAME JWT as password login
                                                            │
                                              redirect to frontend with the token
```

Two decisions worth stating:

**1. Google users always get `CUSTOMER`.**

```java
// The role is NOT taken from the provider's claims.
User user = User.builder()
        .email(profile.getEmail())
        .provider(AuthProvider.GOOGLE)
        .role(Role.CUSTOMER)          // <- hardcoded, deliberately
        .build();
```

An identity provider authenticates — it tells you *who* someone is. It does not authorise — it
does not tell you what they may do *here*. Deriving `role` from a provider claim would mean
whoever administers that provider's configuration could mint an admin in this application. The
two concerns are separate, and Google is authoritative only for the first.

**2. One identity format downstream.** OAuth2 produces the same JWT structure as password
login, so nothing below the security layer needs to know which path was taken. Two token
formats would mean two code paths in every filter, and the second one always rots.

### The JWT filter

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
```

It **is** a `@Component`, and it is also passed explicitly to the chain:

```java
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
```

This combination is worth understanding, because the usual advice is to avoid it. A
`@Component` filter is auto-registered by Spring Boot as a servlet filter for **every** request.
Adding it to the security chain as well means it is registered in two places — so the natural
assumption is that it runs twice.

**It does not.** It extends `OncePerRequestFilter`, which sets a request attribute on entry and
skips the body on any re-entry. One request, one execution.

Measured rather than assumed — three requests with a malformed token, and the filter's warning
line counted in the log:

```
requests sent        : 3
filter log lines     : 3
threads observed     : nio-8080-exec-4, -2, -3   (three distinct)
```

Three requests, three lines. If the filter ran twice per request there would be six.

**So why not rely on it?** Because the guard is doing real work, and it is worth being explicit
about what depends on it:

- `OncePerRequestFilter` is the reason the double registration is harmless. Remove that
  superclass and the filter silently starts authenticating every request twice — including
  loading the user row twice, which turns a cheap request into two queries.
- The explicit `addFilterBefore` is still necessary: auto-registration places the filter at the
  container's default position, **outside** Spring Security's chain, where the `SecurityContext`
  it sets is not yet meaningful.
- So both registrations are needed, and one superclass is what makes that safe.

That is a fragile-looking arrangement that happens to be correct, which is worth recognising:
the safety comes from a framework behaviour, not from the annotation choice. `shouldNotFilter`
is not overridden, so every request does pass through.

The filter's only job is to establish *who* is calling. It never decides *what* they may do —
that belongs to `SecurityFilterChain`. Mixing the two is how a filter ends up quietly
authorising a route nobody reviewed.

**It reloads the user row rather than trusting the `role` claim**, and the reason is a real
failure mode:

```java
// The token carries role, but the claim is a snapshot from issue time.
// Trusting it means an admin demoted to CUSTOMER keeps admin access until
// their token expires - and a disabled account keeps working for the same window.
// Both fail SILENTLY: the endpoint still works, so nothing looks wrong.
```

The cost is one primary-key lookup per authenticated request. The alternative — tokens short
enough that a role cannot change within their lifetime — is worse for the user.

---

## 3. Authorisation

### Route rules

```java
http
  .authorizeHttpRequests(auth -> auth
      .requestMatchers("/api/auth/**", "/api/products/**", "/api/categories/**",
                       "/api/health", "/swagger-ui/**", "/v3/api-docs/**",
                       "/login/oauth2/**").permitAll()
      .requestMatchers("/api/admin/**").hasRole("ADMIN")
      .anyRequest().authenticated())
```

`.anyRequest().authenticated()` is the important line. A new controller added tomorrow is
protected by default; forgetting to add it to a list is the standard way an endpoint leaks, and
this design makes forgetting safe.

### Three levels of enforcement

**Level 1 — the route.** Covered above.

**Level 2 — the service.** Ownership is re-checked where it matters.

**Level 3 — the query.** The one that actually matters:

```java
// Ownership is a WHERE clause, not an if-statement.
Optional<Order> findByIdAndUserId(Long id, Long userId);
```

The difference is failure behaviour. With a service-level `if (order.getUser().getId() !=
currentUser) throw ...`, the row is already loaded, and a refactor that moves that check — or a
new caller that forgets it — leaks data. With the clause, there is no code path that returns
the row at all. **Unreachable is stronger than forbidden.**

### The last admin, and a race that is accepted on purpose

There is no "last admin" check in the `SecurityFilterChain` — it cannot be expressed as a URL
pattern. It lives in the domain, and has two guards:

```java
// Rule 2: no self-demotion. An admin setting their own role to ADMIN when it
// already is ADMIN is harmless and caught by the no-op check below. What must be
// impossible is removing your own access - the likely moment is a mis-click, and
// the consequence is being locked out of the console with recovery requiring
// direct database access.
if (target.getId().equals(callerId) && target.getRole() != newRole) {
    throw ForbiddenException.cannotDemoteSelf();
}

// Rule 3: the last admin is permanent. Counting, not "does another admin exist" -
// the count is the fact the rule is about. If admins == 1, this demotion leaves 0
// and nobody can ever promote anyone again.
if (target.getRole() == Role.ADMIN && newRole != Role.ADMIN) {
    if (userRepository.countByRole(Role.ADMIN) <= 1) {
        throw ForbiddenException.lastAdmin();
    }
}
```

Both return **403 `ACCESS_DENIED`** — not 409. `403` is correct here: the caller is
authenticated and the request is well-formed, but this action is forbidden regardless of who
the caller is.

**The race, named rather than hidden.** Two admins demoting each other at the same moment can
both read a count of `2` and both succeed, leaving zero admins. It is *accepted*, not fixed:

- It needs two admins, both demoting themselves, within the same few hundred milliseconds.
- The alternative is a pessimistic lock on the entire `users` table for **every** role change —
  a large cost paid on every request to prevent an unlikely one.
- Recovery is a single `UPDATE` statement.

The comment in the source says this explicitly. Naming a known race you have chosen not to fix
is worth more than the pretence that it cannot happen — and a reviewer who spots it will
otherwise assume it was missed.

**What is deliberately not done: tokens are not revoked.** A demoted admin's existing token
remains valid for its lifetime. This is survivable *only* because `JwtAuthenticationFilter`
reloads the role from the database on every request rather than trusting the `role` claim — so
a demotion takes effect on the very next call despite the stale token. The two decisions are
linked: remove either one and a demoted admin keeps their access.

### 404, not 403, for another user's resource

Requesting someone else's order returns **404**. `403` would confirm the order exists, turning
the endpoint into an existence oracle for order IDs. `404` is indistinguishable from a wrong
guess, which is the point.

(The exception: a `CUSTOMER` calling `/api/admin/**` gets **403**, because that endpoint's
existence is public knowledge — it is in the OpenAPI docs. There is nothing to conceal, and a
403 tells a developer immediately what went wrong.)

### The unauthenticated 401

An unmapped path returns **401** for anonymous callers and **404** once authenticated. This
looks inconsistent and is deliberate: `.anyRequest().authenticated()` is evaluated before
routing, so an anonymous request to `/api/secret-admin-path` is rejected without the router
ever confirming whether that path exists. Return `404` to anonymous callers and the API becomes
a path scanner — an attacker enumerates endpoints by comparing responses.

Both behaviours are asserted in `AuthenticationHttpTest`, because a future reader will
otherwise "fix" one of them.

---

## 4. Input validation

Bean Validation on every request DTO, with `@Valid` in the controller. A few rules encode real
reasoning rather than convention:

| Rule | Why |
|---|---|
| `@ByteLength(max = 72)` on password | BCrypt's real limit is bytes, not characters |
| `@Digits(integer = 17, fraction = 2)` on money | MySQL silently rounds; better to reject |
| `@Pattern` on sort fields | An unvalidated sort field reaches the query |
| `Email` normalised to lowercase | Otherwise `A@x.com` and `a@x.com` are two accounts |
| `role` absent from `RegisterRequest` | The field cannot be mass-assigned if it does not exist |
| Quantity `@Min(1)` | Zero-unit order lines are meaningless |

**Sort fields are a security control, not a convenience.** Spring Data will happily accept a
sort property and put it in the SQL `ORDER BY`. An unknown property throws, but a *valid* one
can leak: sorting by a column you do not expose reveals its ordering. The whitelist is explicit:

```java
private static final Set<String> PRODUCT_SORT_FIELDS =
        Set.of("name", "price", "stock", "active", "createdAt", "updatedAt", "id");
```

A tie-breaker on `id` is always appended. Without it, rows with equal sort keys can come back
in a different order on each page — so a paginated list shows duplicates and drops records.

---

## 5. Data exposure

Entities never leave the service layer. Responses are DTOs, hand-mapped.

```java
public record UserResponse(Long id, String name, String email, String phone,
                           String address, Role role, AuthProvider provider,
                           Instant createdAt) {}
```

`User` has a `password` field. `UserResponse` does not, so the hash cannot be serialised by
accident. Jackson would happily serialise a `@JsonIgnore`-forgotten field; a type that lacks it
cannot.

**Error responses leak nothing.** `include-stacktrace: never` and `include-message: never` in
`application.yml`, and `@RestControllerAdvice` maps exceptions to `ErrorCode` values. A
constraint violation returns a field name and a message, never SQL, a class name, or a stack
trace. Stack traces in a response body reveal library versions, which is a shopping list.

---

## 6. Transport and deployment notes

Not implemented here, but required for a real deployment:

- **HTTPS only.** A bearer token over plain HTTP is a token handed to anyone on the path. HSTS,
  and redirect HTTP to HTTPS.
- **Secret management.** `JWT_SECRET` from a secret store, not an environment file. Rotating it
  invalidates every token — correct for a suspected compromise, so plan for the re-login.
- **Least-privilege DB account.** The app needs `SELECT/INSERT/UPDATE/DELETE`, not `GRANT` or
  `FILE`, and should not be `root`. The default in `.env.example` is a local convenience.
- **Least-privilege actuator.** `/actuator/env` exposes configuration. Public routes are
  limited to `health` and `info`; everything else requires `ADMIN`.
- **Rate limiting** on `/api/auth/login` and `/api/auth/register`, per IP and per account.
- **Log hygiene.** Never log a token, a password, or an `Authorization` header. Note that a
  request body logged "for debugging" contains a plaintext password.

---

## 7. Verification

Security claims were tested, not asserted:

| Claim | Test | Result |
|---|---|---|
| Customer cannot reach admin endpoints | live HTTP | **403** |
| Anonymous cannot reach a protected endpoint | live HTTP | **401** |
| Anonymous cannot read a cart | live HTTP | **401** |
| Wrong password and unknown email are identical | `AuthenticationHttpTest` | same code |
| A tampered token is rejected | `JwtServiceTest` | rejected |
| An expired token is rejected distinctly | `JwtServiceTest` | `TOKEN_EXPIRED` |
| A user cannot read another user's order | `DomainBoundaryTest` | 404 |
| An illegal status transition is refused | live HTTP | **409** |
| The last admin cannot be demoted | live HTTP | refused |
| Passwords over 72 bytes are rejected | `ByteLengthValidatorTest` | rejected |
| Query-parameter caps are actually enforced over HTTP | `QueryParameterValidationHttpTest` | 400 on violation |
| The JWT secret has no usable fallback | `db/e2e.mjs` config audit | blank and <32-byte both refused |

---

## 8. The one-paragraph summary

Authentication establishes identity with a signed, short-lived token and two interchangeable
front doors. Authorisation is enforced in three independent places, and the innermost —
ownership as a query predicate rather than a branch — is the one that survives refactoring.
Input is validated against a whitelist, output is a purpose-built type that cannot leak, and
failures are indistinguishable where distinguishing them would help an attacker. The gaps —
token revocation, rate limiting, `httpOnly` cookies, idempotency — are named here rather than
left for someone to discover.
