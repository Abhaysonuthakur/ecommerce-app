# Interview Guide

How to explain this project under pressure. Organised as: the 60-second version, the topics
you will actually be asked about, and the deep-dive you should be *hoping* for.

> Answers to the **Practice questions** at the end are deliberately withheld. Work them first.

---

## Part 1 — The 60-second version

Deliver this without notes. It is the most useful thing in this document.

> "It's an e-commerce application — Spring Boot REST API, React frontend, MySQL. Seven entities:
> user, category, product, cart, cart item, order, order item.
>
> "Two things I'd point at. First, checkout is genuinely atomic: stock is decremented under a
> pessimistic lock taken in ascending id order, and if anything fails the whole thing rolls
> back. Second, and this is the part I'd actually want to talk about — that wasn't enough to
> stop an oversell. The lock was being held and it still oversold, because MySQL's REPEATABLE
> READ pins a read view at the transaction's first plain read, and Hibernate was adding an
> eager join that did that read before the lock was taken. So there were two bugs: one in my
> code, and one in my mental model."
>
> "I proved it with MySQL's general log and `performance_schema.data_locks`, fixed it in four
> parts, and the concurrency test now passes five consecutive runs. There are 289 tests, plus
> a 110-check end-to-end suite."

That lands. It has a claim, a surprise, and evidence.

### The three-sentence fallback

If you get 20 seconds:

> "E-commerce API in Spring Boot with JWT auth and a transactional checkout. The interesting bug
> was a race condition where a pessimistic lock was held but didn't prevent an oversell —
> caused by MySQL read-view semantics compounded by an eager JPA join. I found it in the
> database's general log, not the code."

---

## Part 2 — The story you must be able to tell

Interviewers ask "tell me about a hard bug". Have this one ready. It is genuinely good because
it has a wrong turn in it.

### The structure

```
1. Symptom       →  concurrency test expected 1 order, got 2
2. Obvious fix   →  pessimistic lock, ascending id order   (already there)
3. Key observation → the lock was GRANTED/WAITING in data_locks
4. The wrong turn → spent a day on locking, isolation, pooling, autocommit
5. The turn       → read MySQL's general log, not the Java
6. Root cause     →  eager @ManyToOne injected a product join before the lock
                     → that plain read pinned the REPEATABLE READ snapshot
7. Fix            →  4 parts
8. Proof          →  5 consecutive runs, data_locks polling, 289 tests + E2E
9. Lesson         →  when a trusted primitive misbehaves in situ, bisect the call path
```

### The two sentences to get right

**On the observation:**
> "The lock was definitely working — `data_locks` showed one session `GRANTED` and the other
> `WAITING` for about a second. Both still read the same stale stock. That's the insight:
> *holding a lock* and *reading a current value* are independent properties."

**On the root cause:**
> "Hibernate adds its own join to satisfy an eager `@ManyToOne` on every query returning the
> owning entity — even a query that never mentions the association. My query read only cart
> items, but the SQL read products too. That read pinned the snapshot before the lock."

Do not skip the wrong turn. "I spent a day on the wrong layer, then changed my method" is a
stronger answer than "I immediately found it", and it is true.

---

## Part 3 — Topics you will be asked about

### 3.1 Transactions

**Q: Where do transaction boundaries belong?**
Services, not controllers. A controller is an HTTP adapter; a transaction is a business
concept. Putting `@Transactional` on a controller means the transaction spans JSON
serialisation, and any lazy loading during serialisation happens inside it — which hides N+1
problems instead of surfacing them.

**Q: Why `rollbackFor = Exception.class`?**
Spring only rolls back on unchecked exceptions by default. A checked exception thrown inside a
transaction commits everything before it. That is almost never what you want, and it fails
silently.

**Q: Why is the orchestrator not `@Transactional`?**
Three-bean pattern:

```java
TransactionService          // NOT transactional — orchestrates
    ├─ LedgerService            @Transactional     — moves money/stock
    └─ FailedTransactionRecorder REQUIRES_NEW      — writes the FAILED audit row
```

The audit row cannot be written by a transaction that is about to roll back — it would vanish.
It needs its own transaction. But a `REQUIRES_NEW` method invoked on `this` bypasses the proxy
and silently joins the caller's transaction, so it must be a **separate bean**. And if the
orchestrator *were* transactional, the `REQUIRES_NEW` insert would block on the foreign-key
lock held by the outer transaction — the audit write would deadlock the checkout it documents.

**Q: What is the cost of `auto-commit: false`?**
An idle pooled connection holds an open but empty InnoDB transaction, so `idle-in-transaction`
time grows. Acceptable at pool size 10 with millisecond requests; revisit if the pool grows.

### 3.2 Locking and isolation

**Q: Pessimistic or optimistic?**
Pessimistic when conflicts are the **normal** case — a flash sale on the last unit is exactly
that. Optimistic is better when conflicts are rare and retry is cheap: it does not hold
database resources, but it needs retry logic and an `@Version` column, and under contention
you pay for the retries anyway.

**Q: Why lock in ascending id order?**
Transaction A locks product 1 then 2; transaction B locks 2 then 1. A holds 1 and waits for 2,
B holds 2 and waits for 1 — deadlock, and InnoDB kills one. A deterministic order makes that
cycle impossible.

**Q: `@Lock` alone is not enough — why?**
It does not bound the wait. Without a timeout hint a transaction can block indefinitely, and
the connection is held while it does. `@QueryHint(name = "jakarta.persistence.lock.timeout",
value = "5000")` gives a bounded failure.

**Q: ★ Why can a `SELECT ... FOR UPDATE` return a stale value?**
`REPEATABLE READ` pins the read view at the transaction's **first plain read**. If a plain read
already happened, a later locking read takes its locks and blocks normally — then returns the
snapshot value. `EntityManager.refresh()` is also a plain read, so it cannot repair this.

This is the question to hope for. Have the measurement: lock first → `999` (correct); plain
read first → `7` (stale), both waiting ~1070 ms.

**Q: Why did `@Transactional(readOnly = true)` not explain it?**
It was one of my early hypotheses and it was **wrong**; I recorded it as a correction rather
than quietly dropping it. The actual cause was the eager join.

### 3.3 JPA / Hibernate

**Q: What does `@ManyToOne(fetch = EAGER)` actually do?**

Not "loads it when accessed". It makes Hibernate add a **join to every query returning the
owning entity** — including queries that never mention the association. This is how
`CartItem.product` being eager broke a deliberately lines-only query. Fetch type is not
just a performance knob; it can change *correctness*.

**Q: Default fetch types?**
`@ManyToOne`/`@OneToOne`: EAGER. `@OneToMany`/`@ManyToMany`: LAZY. The eager to-one default is
the most common cause of accidental N+1.

**Q: How did you guard against N+1?**
`QueryCountTest` asserts query counts. Also `spring.jpa.open-in-view=false`, which surfaces
`LazyInitializationException` instead of silently issuing extra queries during serialisation.
In production open-in-view hides performance problems behind a session that outlives the
service call.

**Q: What is a fetch join, and its cost?**
`join fetch` loads an association in the same query. Cost: with a to-many it duplicates the
parent row per child, which is why `distinct` is needed — and why paginating a fetch-joined
collection is unreliable in Hibernate (it paginates in memory). For a paginated list, use a
separate query or a batch fetch.

**Q: Why not `ddl-auto: update`?**
It mutates the schema to match the entities. You get schema changes that appear in no
migration, cannot be reviewed, and cannot be rolled back. `validate` fails fast instead. Honest
caveat, measured: `validate` catches a missing table or column but **not** a varchar length
mismatch or a missing index.

### 3.4 Money and data modelling

**Q: Why `BigDecimal` and not `double`?**
`double` is binary floating point; `0.1 + 0.2 != 0.3`. For money that is not a rounding
nuisance, it is a correctness bug that accumulates.

**Q: Why `compareTo` and not `equals`?**
`BigDecimal.equals` compares **scale** as well as value, so `1.0` and `1.00` are unequal.
`compareTo` compares value only.

**Q: Why store the order line's price and name?**
An order is a historical record. If a product's price changes, the past order must not change
with it. So `OrderItem` copies the price and name at the time of sale — this is deliberate
denormalisation, and the same reason an invoice does not re-read the catalogue.

**Q: Why no `@Transactional` on the read of an order?**
Actually it is there, as `readOnly = true` — which lets Hibernate skip dirty checking, and lets
the connection be routed read-only if the driver supports it.

### 3.5 Spring Security

**Q: Why is the JWT filter not a `@Component`?**
It **is** a `@Component` — and that is the more interesting answer, because it looks like a
mistake. A `@Component` filter gets auto-registered by Spring Boot as a servlet filter for every
request, *and* it is added explicitly with `.addFilterBefore(...)`. So it is registered twice,
and the natural assumption is that it runs twice.

It does not, because it extends `OncePerRequestFilter`, which sets a request attribute on entry
and skips re-entry. I measured it rather than trusting the reasoning: three requests with a
malformed token produced exactly **three** filter log lines on three distinct threads. Six would
mean double execution.

Two things are load-bearing and neither is obvious:

- The explicit `addFilterBefore` is still needed, because auto-registration places the filter at
  the container's default position — **outside** Spring Security's chain, where the
  `SecurityContext` it sets is not yet meaningful.
- The double registration is only harmless because of that superclass. Remove
  `OncePerRequestFilter` and every request starts loading the user row twice.

So the correct answer is not "don't annotate it" — it is "know why this arrangement is safe".

**Q: How is a filter different from the `SecurityFilterChain`?**
The filter establishes **who** is calling. The chain decides **what** they may do. Mixing them
means an authorisation rule lives in a filter nobody reviews when adding a route.

**Q: Why does an unmapped path return 401 to an anonymous caller?**

`.anyRequest().authenticated()` is evaluated before routing, so the request is rejected without
confirming the path does not exist. Returning 404 would make the API a path scanner: an
attacker learns which endpoints exist by comparing responses. This is asserted in tests, because
a future reader will otherwise "fix" it.

**Q: Why never accept `role` on registration?**
Mass assignment. If the DTO has the field, someone will post it. The strongest validation of a
field you do not want set is the field not existing.

**Q: Why are Google users always `CUSTOMER`?**
An identity provider authenticates; it does not authorise. Deriving a role from a provider's
claims means whoever controls that provider's config can mint an admin here.

**Q: How do you stop the last admin being demoted?**
Two guards in the domain, because neither can be expressed as a URL pattern: no self-demotion
(the likely cause is a mis-click and the consequence is being locked out of the console), and a
count check — if `countByRole(ADMIN) <= 1`, refuse. Both return **403**, since the caller is
authenticated and the action is forbidden regardless of who they are.

There is a **known race**: two admins demoting each other concurrently can both read a count of
2 and both succeed. I accepted it rather than fixed it — preventing it costs a pessimistic lock
on the whole `users` table for every role change, and recovery is one `UPDATE`. The reason to
mention it unprompted is that a reviewer will spot it, and if I have not named it they will
assume I missed it.

**Q: How does a demoted admin lose access if their token is still valid?**
The token is **not** revoked — that would need a revocation store. It does not matter, because
`JwtAuthenticationFilter` reloads the role from the database row on every request instead of
trusting the `role` claim. The cost is one primary-key lookup per authenticated request; the
alternative is trusting a snapshot, which means a demoted admin keeps admin access until their
token expires, and a disabled account keeps working for the same window. Both fail silently.

Note that these two decisions are coupled: if the filter *did* trust the claim, the missing
revocation would be a real vulnerability rather than a managed trade-off.

### 3.6 API design

**Q: Why DTOs everywhere?**
An entity is a persistence object: its shape follows the schema, and its associations are lazy
proxies that throw outside a transaction. Returning one publishes your schema as public API,
leaks fields like a password hash, and makes every refactor breaking. `UserResponse` has no
password field, so the hash cannot be serialised by accident.

**Q: How do you handle errors consistently?**
One contract from `@RestControllerAdvice`: `{timestamp, status, error, message, path,
fieldErrors}`. Branch on `error` — a stable code — never on `message`.

**Q: Why does `INVALID_ORDER_STATUS_TRANSITION` return the allowed transitions?**
So the client can render correct buttons without duplicating the state machine. Duplicated
state machines drift, and the drift shows up as a button that produces a 409.

**Q: Why `NoResourceFoundException` handling?**
Without it, an unmapped path returns 500 instead of 404, because Spring throws on the resource
lookup and the generic handler treats it as a server error.


### 3.7 Guards that were never wired up

Two bugs found while writing the end-to-end suite, both the same shape: **a guard that reads
as protection but is not connected.** Interviewers like these because the code looks correct —
you cannot find them by reading, only by asking "is this actually invoked?"

**Q: You declared `@Max(100)` on the page size. Is that enforced?**
It was not. The list endpoint declares its parameters individually (so Swagger renders each
one) and then builds the record by hand: `new ProductFilter(page, size, ...)`.

**Bean Validation does not run on a plain constructor call.** It runs when Spring binds an
object, when `@Valid` is applied to a request body, or when a `Validator` is invoked explicitly.
A hand-built record is none of those — so `@Min`, `@Max` and `@Pattern` were all inert, and
`?size=1000000` asked MySQL for a million rows while the endpoint's own `@ApiResponse`
advertised a 400 for invalid input.

The fix is `validator.validate(filter)` before the service call. The same bug was on both
order-list endpoints.

**Q: Why did the 289-test suite not catch that?**

Because `DtoValidationTest` validated the records **with a `Validator` directly** — supplying
exactly the invocation the controller was missing. The test proved the constraints were
*correct* while never proving they were *applied*. That is a genuinely useful distinction: a
unit test on a rule and an integration test that the rule runs are different tests, and only
one of them would have failed here.

**Q: How do you know your regression test actually works?**
I temporarily reverted the fix. Five failures in the product-list group, build red. A regression
test that has never been seen to fail is an assumption, not a test.

**Q: The admin console's "show inactive" toggle — what was wrong with it?**
It sent `active: undefined` when the checkbox was on, with a comment claiming the server reads
an omitted parameter as "no filter". The server reads it as `active = true`, deliberately, so a
storefront request that forgets the parameter cannot publish withdrawn stock. So the toggle
returned the storefront list and hid the products it existed to reveal.

The parameter is genuinely tri-state and there is **no single value meaning "both"**. The fix
was to issue two requests when the admin wants everything, rather than weaken a default that
protects the storefront.

**Q: What is the general lesson?**
A confidently-worded comment is not evidence. Both bugs had comments explaining the intended
behaviour, and in both cases the comment described what the author meant rather than what the
code did. The comment on the admin toggle was outright wrong about the server contract and
nobody re-read the constructor.

The related habit: when a value is "documented" to have a bound, test the bound over the real
protocol. Configuration-shaped protection is exactly the kind that silently does not apply.

### 3.8 Frontend

**Q: Why are filters in the URL?**
Back button, shareable links, refresh survival, deep links. `useState` breaks the first one
immediately and the others silently.

**Q: Why code-split Three.js?**
It is ~900 kB. A static import puts it in the initial bundle for every visitor, including those
who never open a product page. `React.lazy` + `Suspense` took the initial gzipped payload from
~322 kB to ~74 kB.

**Q: Why three context providers in that order?**
`ToastProvider` → `AuthProvider` → `CartProvider`. `CartProvider` calls `useAuth()` to decide
whether to fetch a cart at all; an anonymous fetch would 401 on every page load. The order is
forced by the dependency, not chosen.

**Q: Why does the cart context return `{ok, error}` instead of throwing?**
A failed quantity change is an ordinary outcome the UI must render per-line, not an exception
that unwinds to an error boundary.

**Q: How do you avoid a stale-response race on the search page?**
`AbortController` **plus** a sequence number. Abort alone does not cover two requests resolving
in the same tick, where the earlier one can still land last.

### 3.9 Testing

**Q: Why not `MockMvc` for the concurrency test?**
It is single-threaded, so it cannot express a race at all. That test uses real HTTP against a
real server with a real pool.

**Q: Why is `OrderConcurrencyTest` skipped by default?**

H2 in MySQL mode parses `FOR UPDATE` and does not block, so on H2 the test proves nothing while
reporting green. A test that passes for the wrong reason is worse than no test — so it refuses
to run. `mvn test -Dtest=OrderConcurrencyTest -Dspring.profiles.active=mysql-it`.

**Q: Why run the concurrency test five times?**
A race that passes once may simply not have occurred. One green run proves nothing; five
consecutive runs make an unobserved race unlikely.

**Q: What did the fetch-type test teach you?**

It originally asserted `CartItem.product` was EAGER, with a persuasive comment defending it.
**The test was protecting the bug.** A test can pin a mistake as if it were a requirement. Fixing
it meant rewriting the reasoning, not just the assertion.

**Q: What can H2 not prove?**
It does not enforce MySQL `CHECK` constraints, and it does not model InnoDB's locking. H2 tests
prove Java logic; only MySQL proves the schema and the concurrency behaviour. Both run.

**Q: `*IT` naming?**
Surefire follows the failsafe convention and silently skips `*IT` classes. Name test classes
`*Test`, and always watch the reported test count — a silently skipped class looks like a
passing suite.

---

## Part 4 — Questions to ask them

Ask two. These are diagnostic, not filler.

1. *"How do you handle schema migrations — Flyway, Liquibase, or something homegrown?"* The
   answer tells you how much they care about reviewable change.
2. *"When a concurrency bug shows up in production, what's the first thing you reach for?"* If
   the answer is "add more logging in the service", they have not yet hit a bug that lives in
   the database.

---

## Part 5 — Practice questions (answers withheld)

Answer these before comparing with anything. Do them in writing; spoken answers hide gaps.

**A. Transactions**

1. A transaction throws a checked exception and the data still commits. Why, and what is the fix?
2. Why must `FailedTransactionRecorder` be a separate bean rather than a method on the service?
3. What breaks if the orchestrator becomes `@Transactional`?

**B. Locking and isolation**

4. `SELECT ... FOR UPDATE` is held and blocking. Explain how it can still return a stale value.
5. Why ascending id order, specifically? What if you locked in the order the cart was built?
6. Would `@Version` optimistic locking fix the oversell? What would the retry path look like,
   and what does the customer see?

**C. JPA**

7. Explain precisely how `@ManyToOne(fetch = EAGER)` can change correctness, not just
   performance.
8. Why does `EntityManager.refresh()` not repair a pinned read view?
9. When is a fetch join the wrong tool?

**D. Security**

10. An unmapped path returns 401 anonymously and 404 when authenticated. Justify both.
11. Why is `role` absent from `RegisterRequest` rather than validated against a whitelist?
12. Why does the app return 404 for another user's order rather than 403?

**E. Frontend**

13. Why do filters belong in the URL? Name four consequences of using `useState`.
14. Why does aborting a fetch need a sequence number as well?
15. Order the context providers and justify the order.

**F. Meta**

16. You just told me a pessimistic lock was held and did not work. Convince me that is not simply
    a bug you failed to fix the first time.
17. Your concurrency test is skipped by default. Is that not just hiding a failing test?

---

## Part 6 — Talking about the limitation honestly

If asked "what would you do differently", do **not** say "nothing". Say this:

> "Three things. Idempotency keys on checkout — right now a double-submit creates two orders and
> the disabled button is a courtesy, not a guarantee. A double-entry ledger instead of the
> single-row one; my reconciliation works because there is one account per transfer, and it
> would not generalise. And I'd move the token out of localStorage into an httpOnly cookie with
> CSRF protection."

Then the line that makes it credible:

> "None of those are secrets — they're the first three items in the limitations section of the
> README."

Naming your own gaps accurately is the strongest signal available. Every interviewer has met
someone who claims their project handles everything.
