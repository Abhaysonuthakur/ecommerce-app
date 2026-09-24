package com.shop.ecommerce.web;

import com.shop.ecommerce.dto.common.ErrorCode;
import com.shop.ecommerce.entity.Cart;
import com.shop.ecommerce.entity.CartItem;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cart behaviour, and the domain rules that live above the filter chain.
 *
 * <h2>Why the cart needs HTTP-level tests at all</h2>
 *
 * <p>Two of the cart's most load-bearing decisions are invisible from the service layer:
 *
 * <ol>
 *   <li><b>{@code getMyCart} must not run read-only.</b> The class is
 *       {@code @Transactional(readOnly = true)} and this one method overrides it with a plain
 *       {@code @Transactional} - because it <em>creates</em> a cart row on first access. Under
 *       a read-only transaction, MySQL refuses the insert with "Connection is read-only" and
 *       the endpoint 500s. That is a runtime failure on the very first request a new customer
 *       makes, and no unit test with a mocked repository can see it, because the failure is in
 *       the JDBC driver's interaction with the transaction.</li>
 *   <li><b>The cart response is built from a lazy collection.</b> {@code Cart.items} is LAZY,
 *       so the payload is only complete if the mapping happens inside the transaction that
 *       loaded the cart. Outside it, the mapper throws LazyInitializationException. This class
 *       is the proof that the boundary is in the right place.</li>
 * </ol>
 *
 * <p>The second point is the one the repository tests explicitly named this layer for: they
 * assert the fetch <em>mapping</em>, because {@code @DataJpaTest} runs inside a transaction
 * where the fetch type is unobservable. Here there is no surrounding transaction, so a lazy
 * collection would fail loudly.
 */
@DisplayName("HTTP - cart")
class CartHttpTest extends HttpIntegrationTestSupport {

    // =================================================================
    //  First access - the read-only trap
    // =================================================================

    @Nested
    @DisplayName("first access")
    class FirstAccess {

        @Test
        @DisplayName("a customer who has never had a cart gets an empty one, not a 404 and not a 500")
        void firstAccessCreatesAnEmptyCart() throws Exception {
            /*
             * =================================================================
             *  THE readOnly REGRESSION TEST.
             * =================================================================
             *
             * This request must INSERT a carts row. That is what makes it the regression
             * test for the read-only boundary rather than a test of the happy path.
             *
             * How it fails if the annotation is wrong: CartServiceImpl is annotated
             * @Transactional(readOnly = true), and Hibernate sets the JDBC connection
             * read-only for the duration of such a transaction. MySQL then refuses the
             * insert outright:
             *
             *     Connection is read-only. Queries leading to data modification are not
             *     allowed [insert into carts (created_at,updated_at,user_id) values (?,?,?)]
             *
             * and the endpoint returns 500. Nothing about the code reads as wrong - the
             * method is called "getMyCart", it is reached by an HTTP GET, and it looks like
             * a read. The annotation is load-bearing and this is the only place its absence
             * is observable.
             *
             * Note also what the response is NOT: a 404. A customer who has bought nothing
             * has a cart; it is empty. Returning "not found" would force every client to
             * treat "no cart" as a recoverable state on the navbar badge, the cart page and
             * after every login. Creating on demand means the client has one state to render.
             */
            User customer = persistCustomer();

            mockMvc.perform(get("/api/cart").header("Authorization", bearerFor(customer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.empty").value(true))
                    .andExpect(jsonPath("$.itemCount").value(0))
                    .andExpect(jsonPath("$.totalItems").value(0))
                    .andExpect(jsonPath("$.subtotal").value(0))
                    .andExpect(jsonPath("$.items.length()").value(0))
                    // Nothing to check out, so the flag the frontend uses to enable the
                    // checkout button must be false.
                    .andExpect(jsonPath("$.checkoutReady").value(false));

            // And the row really was written - the endpoint did not merely return a
            // plausible empty object.
            assertThat(cartRepository.findByUserId(customer.getId())).isPresent();
        }

        @Test
        @DisplayName("the second access returns the same cart rather than creating another")
        void secondAccessIsIdempotent() throws Exception {
            /*
             * The natural bug in a "create on demand" endpoint is creating every time, which
             * silently gives a customer a new empty cart on every page load - and their items
             * appear to vanish at random.
             *
             * Two requests, one row, same id.
             */
            User customer = persistCustomer();
            String bearer = bearerFor(customer);

            long firstId = objectMapper.readTree(
                    mockMvc.perform(get("/api/cart").header("Authorization", bearer))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString()).get("id").asLong();

            long secondId = objectMapper.readTree(
                    mockMvc.perform(get("/api/cart").header("Authorization", bearer))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString()).get("id").asLong();

            assertThat(secondId).isEqualTo(firstId);
            assertThat(cartRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("a new customer cannot see anyone else's cart")
        void cartsAreIsolated() throws Exception {
            /*
             * The vulnerability this design makes structurally impossible: the service
             * interface has no getCart(Long userId) method, so a controller cannot pass a
             * client-supplied id even by accident. There is no parameter to forget to check.
             *
             * This asserts the consequence - two customers, two carts, each seeing only
             * their own - which is what would break if somebody "helpfully" added an id
             * parameter later.
             */
            User ada = persistCustomer();
            User grace = persistCustomer();

            long adaCartId = objectMapper.readTree(
                    mockMvc.perform(get("/api/cart").header("Authorization", bearerFor(ada)))
                            .andReturn().getResponse().getContentAsString()).get("id").asLong();

            long graceCartId = objectMapper.readTree(
                    mockMvc.perform(get("/api/cart").header("Authorization", bearerFor(grace)))
                            .andReturn().getResponse().getContentAsString()).get("id").asLong();

            assertThat(graceCartId).isNotEqualTo(adaCartId);
            assertThat(cartRepository.count()).isEqualTo(2);
        }
    }

    // =================================================================
    //  Mutating the cart
    // =================================================================

    @Nested
    @DisplayName("mutations")
    class Mutations {

        @Test
        @DisplayName("adding a product returns the whole updated cart with a server-computed total")
        void addItemReturnsTheUpdatedCart() throws Exception {
            /*
             * Returning the whole cart rather than a bare confirmation is a deliberate design
             * choice: the UI has to re-render the subtotal, the item count and the empty
             * state after every mutation, so returning the new state removes both a follow-up
             * request and the window in which the client's optimistic guess at the total is
             * wrong.
             *
             * The subtotal is asserted against the SERVER's arithmetic: 3 x 149.50 = 448.50.
             * If the client could influence this number it would be the price-tampering bug,
             * so the assertion is on the server's value, not on any calculation done here.
             */
            User customer = persistCustomer();
            var category = persistCategory("Cart Add");
            var product = persistProduct(category, "Added Item", "149.50", 10);

            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearerFor(customer))
                            .contentType("application/json")
                            .content(json(new ProductQuantity(product.getId(), 3))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].quantity").value(3))
                    .andExpect(jsonPath("$.items[0].subtotal").value(448.50))
                    .andExpect(jsonPath("$.subtotal").value(448.50))
                    .andExpect(jsonPath("$.totalItems").value(3))
                    .andExpect(jsonPath("$.itemCount").value(1))
                    .andExpect(jsonPath("$.empty").value(false))
                    .andExpect(jsonPath("$.checkoutReady").value(true));
        }

        @Test
        @DisplayName("adding the same product twice increments the line instead of duplicating it")
        void addingTheSameProductIncrements() throws Exception {
            /*
             * The database enforces UNIQUE (cart_id, product_id), so inserting a second row
             * would be a constraint violation surfacing as a 500 for a completely ordinary
             * user action - clicking "add to cart" twice.
             *
             * The assertion on itemCount == 1 is the important one: a response with two lines
             * would be the visible symptom, and it would also mean the checkout would
             * decrement the same product twice.
             */
            User customer = persistCustomer();
            var category = persistCategory("Cart Increment");
            var product = persistProduct(category, "Incremented Item", "10.00", 10);

            String bearer = bearerFor(customer);

            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(product.getId(), 2))))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(product.getId(), 3))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].quantity").value(5))
                    .andExpect(jsonPath("$.subtotal").value(50.00));

            assertThat(cartItemRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("setting a quantity is absolute, so sending it twice leaves the same state")
        void updateQuantityIsAbsoluteNotADelta() throws Exception {
            /*
             * Absolute rather than a delta makes a quantity input naturally idempotent. A
             * delta-based endpoint would need the client to know the current value, so two
             * tabs open on one cart could send deltas that cancel out or double up - and the
             * bug is intermittent and depends on how fast the user clicks.
             *
             * PUT twice with the same body, assert the same result both times.
             */
            User customer = persistCustomer();
            var category = persistCategory("Cart Update");
            var product = persistProduct(category, "Updated Item", "20.00", 10);

            String bearer = bearerFor(customer);

            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(product.getId(), 1))))
                    .andExpect(status().isOk());

            long itemId = cartLineId(customer, product.getId());

            for (int attempt = 0; attempt < 2; attempt++) {
                mockMvc.perform(put("/api/cart/items/" + itemId)
                                .header("Authorization", bearer)
                                .contentType("application/json")
                                .content("{\"quantity\":7}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].quantity").value(7))
                        .andExpect(jsonPath("$.totalItems").value(7));
            }

            assertThat(cartItemRepository.findById(itemId).orElseThrow().getQuantity())
                    .as("idempotent: two identical PUTs leave one result, not fourteen")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("a quantity over the per-line limit is a 400, and over stock is a 409")
        void quantityBoundsAreEnforcedDifferently() throws Exception {
            /*
             * Two limits that are easy to conflate and must not be:
             *
             *   > 99  -> 400, from @Max on the DTO. This is a static bound on the request:
             *            no cart state is needed to know that 500 of one item is absurd.
             *
             *   > stock -> 409, from CartServiceImpl. This needs the cart and the product
             *            row, so Bean Validation cannot express it - which is exactly why
             *            the service does it.
             *
             * The statuses differ for a real reason: the first is a malformed request (fix
             * the number you sent), the second is a conflict with current state (the shop
             * does not have that many). A client that showed the same message for both would
             * be telling the customer to change their input when the problem is inventory.
             */
            User customer = persistCustomer();
            var category = persistCategory("Cart Bounds");
            var product = persistProduct(category, "Bounded Item", "5.00", 3);

            String bearer = bearerFor(customer);

            // Over the static DTO bound -> 400, before anything looks at the cart.
            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(product.getId(), 500))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(ErrorCode.VALIDATION_FAILED))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"));

            // Within the static bound but beyond stock -> 409, from the service.
            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(product.getId(), 4))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(ErrorCode.INSUFFICIENT_STOCK));

            assertThat(cartItemRepository.count())
                    .as("neither refused request may leave a cart line")
                    .isZero();
        }

        @Test
        @DisplayName("removing a line returns the cart minus that line, and clearing empties it")
        void removingAndClearing() throws Exception {
            /*
             * Both mutations return the resulting cart rather than a confirmation, for the
             * reason given on addItem: one response shape for every cart mutation means the
             * client has one code path.
             *
             * The assertion after clearing is on the DATABASE as well as the response - an
             * orphanRemoval misconfiguration would leave the item rows behind while the cart
             * object looked empty, and the next GET would show them again.
             */
            User customer = persistCustomer();
            var category = persistCategory("Cart Removal");

            var first = persistProduct(category, "First Removable", "10.00", 10);
            var second = persistProduct(category, "Second Removable", "20.00", 10);

            String bearer = bearerFor(customer);

            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(first.getId(), 1))))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(second.getId(), 1))))
                    .andExpect(status().isOk());

            long firstItemId = cartLineId(customer, first.getId());

            mockMvc.perform(delete("/api/cart/items/" + firstItemId)
                            .header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].productId").value(second.getId()))
                    .andExpect(jsonPath("$.subtotal").value(20.00));

            mockMvc.perform(delete("/api/cart").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(0))
                    .andExpect(jsonPath("$.empty").value(true))
                    .andExpect(jsonPath("$.checkoutReady").value(false));

            assertThat(cartItemRepository.count())
                    .as("the lines must be gone from the database, not merely hidden from the response")
                    .isZero();
        }
    }

    // =================================================================
    //  The lazy-collection boundary
    // =================================================================

    @Test
    @DisplayName("the cart response is built inside the transaction, so a lazy collection is fully populated")
    void lazyCollectionIsMappedInsideTheTransaction() throws Exception {
        /*
         * =================================================================
         *  The runtime half of the fetch-type question.
         * =================================================================
         *
         * RepositoryPersistenceTest asserts that Cart.items is mapped LAZY - it can read the
         * Hibernate metamodel and check the fetch timing. What it explicitly cannot assert is
         * the consequence, because it runs inside @DataJpaTest's transaction where a lazy
         * collection is always reachable and the fetch type makes no observable difference.
         *
         * This test is that consequence. There is no transaction around the test method, so
         * if the mapping were performed after the service's transaction had committed, the
         * mapper would touch a detached PersistentCollection and throw:
         *
         *     LazyInitializationException: failed to lazily initialize a collection of role:
         *     com.shop.ecommerce.entity.Cart.items: could not initialize proxy - no Session
         *
         * This exact exception is what the FIXTURE helper hit - cartLineId() exists because
         * cartRepository.findByUserId(...).getItems() fails from a test method. The service
         * does not hit it because CartServiceImpl.getMyCart is @Transactional and maps inside
         * that boundary.
         *
         * So a non-empty items array here proves the whole chain: LAZY mapping, a transaction
         * that spans the mapping, and a controller that does not touch the entity.
         */
        User customer = persistCustomer();
        var category = persistCategory("Lazy Boundary");
        var product = persistProduct(category, "Lazy Item", "10.00", 10);

        String bearer = bearerFor(customer);

        mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(product.getId(), 2))))
                    .andExpect(status().isOk());

        /*
         * A fresh request, a fresh transaction, and the items must still be there. This is
         * the assertion that would fail with a LazyInitializationException - which, note,
         * would appear as a 500 with a correlation id, not as a message about laziness.
         */
        mockMvc.perform(get("/api/cart").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("Lazy Item"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    // =================================================================
    //  Domain rules above the filter chain
    // =================================================================

    @Nested
    @DisplayName("admin role rules")
    class AdminRoleRules {

        @Test
        @DisplayName("an admin cannot demote themselves")
        void adminCannotDemoteSelf() throws Exception {
            /*
             * The rule the filter chain cannot express: hasRole('ADMIN') answers "is the
             * caller an admin", not "is the caller THIS admin" - which needs the request body
             * and the caller's identity together.
             *
             * Why it matters: the most likely moment for a mis-click is right here, and the
             * consequence is being locked out of the console with recovery requiring direct
             * database access.
             *
             * The second assertion - that the role is unchanged - is the one that would catch
             * a guard implemented as a warning rather than a refusal.
             */
            User admin = persistAdmin();
            persistCustomer();   // so the store is not left with a single admin

            mockMvc.perform(patch("/api/admin/users/" + admin.getId() + "/role")
                            .header("Authorization", bearerFor(admin))
                            .contentType("application/json")
                            .content("{\"role\":\"CUSTOMER\"}"))
                    .andExpect(status().isForbidden());

            assertThat(userRepository.findById(admin.getId()).orElseThrow().getRole())
                    .as("the refusal must not have partially applied")
                    .isEqualTo(Role.ADMIN);
        }

        @Test
        @DisplayName("demoting one of two admins is allowed and leaves exactly one")
        void demotingOneOfTwoAdminsLeavesOne() throws Exception {
            /*
             * ================================================================
             *  What the last-admin guard can and cannot be reached by.
             * ================================================================
             *
             * Writing this test clarified the guard's reachable surface, which is narrower
             * than it first appears. The rule is:
             *
             *     if (target.role == ADMIN && newRole != ADMIN && countByRole(ADMIN) <= 1)
             *         throw ForbiddenException.lastAdmin();
             *
             * For that branch to execute, the CALLER must already be an admin (the
             * @PreAuthorize and the URL rule both require it) and must be demoting a
             * DIFFERENT admin. So the state at the moment of the check always has at least:
             *   - the caller  (an admin)
             *   - the target  (an admin)
             * i.e. a count of at least 2 - which means countByRole <= 1 is unreachable
             * through this endpoint.
             *
             * The two paths that would reach a count of 1 are covered by the guards in front
             * of it: an admin demoting themselves is caught by the self-demotion rule, and a
             * non-admin caller never gets past @PreAuthorize. What remains is the concurrent
             * case the code documents - two admins demoting each other at the same instant,
             * both reading a count of 2 - which is accepted, and which cannot be provoked
             * deterministically through MockMvc.
             *
             * So this test asserts the reachable behaviour rather than pretending to test an
             * unreachable branch: with two admins, demoting one is fine and leaves exactly
             * one. The count rule is belt-and-braces - it makes "zero admins" impossible even
             * if a future refactor removes one of the guards in front of it, which is
             * precisely why it is worth keeping despite being hard to reach today.
             */
            User firstAdmin = persistAdmin();
            User secondAdmin = persistAdmin();

            assertThat(userRepository.countByRole(Role.ADMIN)).isEqualTo(2);

            // The first admin demotes the second. One admin remains, so this is permitted.
            mockMvc.perform(patch("/api/admin/users/" + secondAdmin.getId() + "/role")
                            .header("Authorization", bearerFor(firstAdmin))
                            .contentType("application/json")
                            .content("{\"role\":\"CUSTOMER\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("CUSTOMER"));

            assertThat(userRepository.countByRole(Role.ADMIN))
                    .as("exactly one admin survives - the store is never left without one")
                    .isEqualTo(1);

            /*
             * And in the resulting state, the sole admin cannot be demoted by anybody - the
             * self-demotion guard refuses it here, which is the mechanism that keeps the
             * store from reaching zero in practice.
             */
            mockMvc.perform(patch("/api/admin/users/" + firstAdmin.getId() + "/role")
                            .header("Authorization", bearerFor(firstAdmin))
                            .contentType("application/json")
                            .content("{\"role\":\"CUSTOMER\"}"))
                    .andExpect(status().isForbidden());

            assertThat(userRepository.countByRole(Role.ADMIN)).isEqualTo(1);
        }

        @Test
        @DisplayName("setting the role a user already has is refused as a 400 no-op")
        void noOpRoleChangeIsRefused() throws Exception {
            /*
             * Refusing the no-op makes the last-admin count a meaningful thing to reason
             * about: any call that reaches the count is a call that actually changes
             * something. It also avoids writing an updated_at for a change that is not one.
             */
            User admin = persistAdmin();
            User customer = persistCustomer();

            mockMvc.perform(patch("/api/admin/users/" + customer.getId() + "/role")
                            .header("Authorization", bearerFor(admin))
                            .contentType("application/json")
                            .content("{\"role\":\"CUSTOMER\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("promoting a customer works, and the new role takes effect on their next request")
        void promotionTakesEffectImmediately() throws Exception {
            /*
             * ================================================================
             *  The test that proves the token's role claim is not authoritative.
             * ================================================================
             *
             * The customer's EXISTING token carries role=CUSTOMER in its claims, and it is
             * not reissued here. If JwtAuthenticationFilter trusted that claim - the obvious
             * implementation, and one line shorter - this request would still be a 403 and
             * the promotion would not take effect until the token expired.
             *
             * It must instead be a 200, because the filter reloads the user row and reads the
             * role from the database. That is what makes a role change immediate in both
             * directions, with no revocation list and no reissue.
             *
             * The same token, before and after. Nothing else changes.
             */
            User admin = persistAdmin();
            User customer = persistCustomer();
            String customersExistingToken = bearerFor(customer);

            // Before: refused.
            mockMvc.perform(get("/api/admin/dashboard/stats").header("Authorization", customersExistingToken))
                    .andExpect(status().isForbidden());

            // Promote using the admin's token.
            mockMvc.perform(patch("/api/admin/users/" + customer.getId() + "/role")
                            .header("Authorization", bearerFor(admin))
                            .contentType("application/json")
                            .content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("ADMIN"));

            // After: the SAME token is now accepted, because the role came from the database.
            mockMvc.perform(get("/api/admin/dashboard/stats").header("Authorization", customersExistingToken))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a demoted admin loses access on their next request, with the same token")
        void demotionTakesEffectImmediately() throws Exception {
            /*
             * The direction that matters for security, and the reason the claim cannot be
             * trusted.
             *
             * A demoted admin's token still says role=ADMIN in its payload - it was minted
             * when that was true. Token-claim authorization would grant them admin access
             * until expiry, silently, with no log line and no way to revoke. That is a
             * privilege-retention window equal to the token lifetime, and it is the kind of
             * bug that is found by an auditor rather than by a user.
             *
             * With the filter reading the row: the very next request is a 403.
             */
            User demotedAdmin = persistAdmin();
            User otherAdmin = persistAdmin();

            String demotedAdminsToken = bearerFor(demotedAdmin);

            // Before: accepted.
            mockMvc.perform(get("/api/admin/dashboard/stats").header("Authorization", demotedAdminsToken))
                    .andExpect(status().isOk());

            // Another admin demotes them.
            mockMvc.perform(patch("/api/admin/users/" + demotedAdmin.getId() + "/role")
                            .header("Authorization", bearerFor(otherAdmin))
                            .contentType("application/json")
                            .content("{\"role\":\"CUSTOMER\"}"))
                    .andExpect(status().isOk());

            // After: the SAME token is now refused.
            mockMvc.perform(get("/api/admin/dashboard/stats").header("Authorization", demotedAdminsToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ErrorCode.ACCESS_DENIED));
        }
    }
}
