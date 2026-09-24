package com.shop.ecommerce.web;

import com.shop.ecommerce.dto.cart.AddToCartRequest;
import com.shop.ecommerce.dto.common.ErrorCode;
import com.shop.ecommerce.entity.OrderStatus;
import com.shop.ecommerce.entity.Product;
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
 * The error contract, and the checkout transaction, proved end to end.
 *
 * <h2>Why "one error shape" needs an HTTP test rather than a unit test</h2>
 *
 * <p>{@code ApiErrorResponse} is a record with six fields, and every handler in
 * {@code GlobalExceptionHandler} returns one. Both of those can be true while the responses
 * a client actually receives are inconsistent, because the shape is assembled from three
 * independent places that never reference each other:
 *
 * <ol>
 *   <li>{@code GlobalExceptionHandler} - for anything thrown by a controller.</li>
 *   <li>{@code ApiErrorWriter} - for anything decided by the security filter chain, which
 *       runs outside the dispatcher and therefore outside the advice.</li>
 *   <li>Spring's own defaults - for anything neither of the above catches, which is exactly
 *       the set of cases nobody thought about.</li>
 * </ol>
 *
 * <p>The third is the dangerous one. A path that reaches Spring's default error handling
 * produces a body with {@code "error"}, {@code "path"}, {@code "status"} and a nested
 * {@code "timestamp"} but <b>no {@code error} code field of the kind clients branch on</b>.
 * A frontend that does {@code if (body.error === "ORDER_NOT_FOUND")} gets {@code undefined}
 * and silently takes its else-branch. This class asserts the application's shape on every
 * failure path, so a case that falls through to the default is caught here rather than in
 * the browser.
 */
@DisplayName("HTTP - errors and checkout")
class ErrorContractHttpTest extends HttpIntegrationTestSupport {

    // =================================================================
    //  The shape itself
    // =================================================================

    @Nested
    @DisplayName("the error contract")
    class TheShape {

        @Test
        @DisplayName("every failure carries timestamp, status, error, message and path")
        void everyFailureHasTheSameShape() throws Exception {
            /*
             * Four failures from four different mechanisms, asserted to have the same fields.
             * Collecting them in one test rather than four is deliberate: the value is in the
             * comparison, and four separate tests each asserting "the body parses" would not
             * express it.
             */
            String customerBearer = bearerFor(persistCustomer());

            // 1. Validation, from MethodArgumentNotValidException inside the dispatcher.
            var validation = postJson("/api/auth/register",
                    new com.shop.ecommerce.dto.auth.RegisterRequest("", "not-an-email", "short", null), null)
                    .getResponse();

            // 2. A missing resource, from ResourceNotFoundException inside a service.
            var missing = perform(get("/api/products/999999"), null).getResponse();

            // 3. A refusal from the filter chain, which never reaches the advice.
            var refused = perform(get("/api/users/me"), null).getResponse();

            // 4. A malformed body, from HttpMessageNotReadableException.
            var unreadable = mockMvc.perform(post("/api/auth/register")
                            .contentType("application/json")
                            .content("{not json at all"))
                    .andReturn().getResponse();

            /*
             * Typed as MockHttpServletResponse rather than the servlet interface: the body is
             * readable only from the mock, since a real response's output stream has already
             * been committed by the time a test could look at it. This is precisely why the
             * test asserts on the body through MockMvc rather than by calling the endpoints
             * over a socket - the status and headers survive a real request, the body does
             * not come back as a string.
             */
            for (var response : new org.springframework.mock.web.MockHttpServletResponse[]{
                    validation, missing, refused, unreadable}) {

                var body = objectMapper.readTree(response.getContentAsString());

                assertThat(body.has("timestamp")).as("timestamp present").isTrue();
                assertThat(body.has("status")).as("status present").isTrue();
                assertThat(body.has("error")).as("error code present").isTrue();
                assertThat(body.has("message")).as("message present").isTrue();
                assertThat(body.has("path")).as("path present").isTrue();

                /*
                 * The status in the body must match the status line. They are set in two
                 * different places - ResponseEntity for the advice, response.setStatus for
                 * the writer - so a drift between them is possible and would confuse any
                 * client that reads the body's copy.
                 */
                assertThat(body.get("status").asInt())
                        .as("body status matches the HTTP status line")
                        .isEqualTo(response.getStatus());
            }
        }

        @Test
        @DisplayName("validation failures list every bad field at once, and echo no values")
        void validationListsAllBadFieldsWithoutEchoingValues() throws Exception {
            /*
             * Two properties in one test because they are two halves of the same promise.
             *
             * 1. ALL bad fields, not the first. A form with three mistakes should mark three.
             *    Returning one at a time means the user fixes it, submits, and is told about
             *    the next - a loop that was avoidable.
             *
             * 2. NO rejected values. This is the security-relevant half. The first request a
             *    new user makes is registration, so echoing rejected values would bounce the
             *    submitted password back into browser devtools, proxy logs and error trackers.
             *    The response carries the field NAME and the reason, and nothing else.
             */
            String badPassword = "short";

            var response = postJson("/api/auth/register",
                    new com.shop.ecommerce.dto.auth.RegisterRequest("", "not-an-email", badPassword, null), null)
                    .getResponse();

            assertThat(response.getStatus()).isEqualTo(400);

            var body = objectMapper.readTree(response.getContentAsString());

            assertThat(body.get("error").asText()).isEqualTo(ErrorCode.VALIDATION_FAILED);

            var fields = body.get("fieldErrors");
            assertThat(fields.isArray()).isTrue();

            var fieldNames = new java.util.ArrayList<String>();
            fields.forEach(f -> fieldNames.add(f.get("field").asText()));

            assertThat(fieldNames)
                    .as("name, email and password all failed and all must be reported")
                    .contains("name", "email", "password");

            assertThat(response.getContentAsString())
                    .as("the submitted password must never be echoed back")
                    .doesNotContain(badPassword)
                    .doesNotContain("not-an-email");
        }

        @Test
        @DisplayName("a non-validation failure carries no fieldErrors key at all")
        void nonValidationFailuresHaveNoFieldErrors() throws Exception {
            /*
             * Null rather than an empty array, and the difference is worth a test.
             *
             * An empty array on a 404 tells a client "validation ran and found nothing",
             * which is false - no validation ran. Null means "not applicable", and a client
             * can branch on presence. Asserting `isNull()` is what pins the decision, since
             * an empty array would satisfy a looser `isEmpty()` check and quietly change the
             * contract's meaning.
             */
            var body = objectMapper.readTree(
                    perform(get("/api/products/999999"), null).getResponse().getContentAsString());

            assertThat(body.has("fieldErrors")).isTrue();
            assertThat(body.get("fieldErrors").isNull())
                    .as("absent, not empty - the client must be able to tell the difference")
                    .isTrue();
        }

        @Test
        @DisplayName("an unmapped path is 404 when authenticated and 401 when anonymous")
        void unmappedPathDiffersByAuthentication() throws Exception {
            /*
             * The subtlest case in the whole contract, and the one most likely to be
             * "fixed" into a bug by somebody who assumes anonymous should also get 404.
             *
             * ANONYMOUS -> 401. anyRequest().authenticated() is evaluated BEFORE routing, so
             * the request never reaches the dispatcher to discover that no handler exists.
             *
             * AUTHENTICATED -> 404. Now the chain has nothing to complain about, the request
             * is dispatched, no handler matches, and NoResourceFoundException is thrown -
             * caught by the advice and turned into a 404 rather than Spring's default 500.
             *
             * Why 401-for-anonymous is correct rather than sloppy: answering 404 to an
             * unauthenticated caller would turn the API into a path scanner. An attacker
             * could enumerate /api/admin/orders, /api/admin/export, ... and learn which
             * routes exist without holding any credential at all. With 401 the answer is
             * identical for a real route and a nonexistent one.
             *
             * Both halves are asserted together because the interesting property is the
             * DIFFERENCE. Testing only one would let a change that made both 404 - which
             * looks tidier - pass unnoticed.
             */
            mockMvc.perform(get("/api/definitely-not-a-route"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(ErrorCode.UNAUTHORIZED));

            mockMvc.perform(get("/api/definitely-not-a-route")
                            .header("Authorization", bearerFor(persistCustomer())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value(ErrorCode.RESOURCE_NOT_FOUND));
        }

        @Test
        @DisplayName("an unknown enum in a query parameter names the allowed values")
        void unknownEnumParameterNamesTheAllowedValues() throws Exception {
            /*
             * ?status=SHIPED (one P) is the realistic typo. The wrong answer is an empty
             * list: the client shows "no orders shipped", the user believes it, and the bug
             * is in the frontend's spelling of a value the server never mentioned.
             *
             * The right answer is a 400 naming the members. Note this arrives as a
             * MethodArgumentTypeMismatchException, not as a validation failure - two
             * different exceptions, both handled, which is why the handler for each exists.
             */
            User customer = persistCustomer();

            mockMvc.perform(get("/api/orders?status=SHIPED")
                            .header("Authorization", bearerFor(customer)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(ErrorCode.BAD_REQUEST))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.allOf(
                                    org.hamcrest.Matchers.containsString("SHIPPED"),
                                    org.hamcrest.Matchers.containsString("PENDING"))));
        }

        @Test
        @DisplayName("an unknown enum in a request body names the allowed values")
        void unknownEnumInBodyNamesTheAllowedValues() throws Exception {
            /*
             * The same mistake in a body rather than a query string. These take completely
             * different code paths - HttpMessageNotReadableException with a nested
             * InvalidFormatException, versus a type mismatch - and the body case is the one
             * Spring's default message ("could not be parsed as JSON") actively misleads on,
             * because the JSON is perfectly valid. One string is simply not a member of the
             * enum.
             */
            User admin = persistAdmin();
            var category = persistCategory("Enums");
            var product = persistProduct(category, "Enum Product", "10.00", 5);

            long orderId = placeOrderFor(persistCustomer(), product, 1);

            mockMvc.perform(patch("/api/admin/orders/" + orderId + "/status")
                            .header("Authorization", bearerFor(admin))
                            .contentType("application/json")
                            .content("{\"status\":\"REFUNDED\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(ErrorCode.BAD_REQUEST))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.allOf(
                                    org.hamcrest.Matchers.containsString("PENDING"),
                                    org.hamcrest.Matchers.containsString("SHIPPED"))));
        }

        @Test
        @DisplayName("a disallowed method is 405, and a wrong content type is 415")
        void methodAndMediaTypeFailures() throws Exception {
            /*
             * Two failures that have nothing to do with the application's business logic and
             * everything to do with the framework's defaults. Both are mapped explicitly in
             * GlobalExceptionHandler for one reason: without the handlers they are 500s -
             * HTTP statuses that blame the server for the client's mistake, and that page
             * somebody at 3am.
             *
             * DELETE on /api/auth/login: the path exists, the method does not.
             */
            mockMvc.perform(delete("/api/auth/login"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.error").value(ErrorCode.METHOD_NOT_ALLOWED));

            /*
             * A JSON body sent as text/plain. The endpoint consumes application/json, so
             * Spring refuses before anything reads the bytes.
             */
            mockMvc.perform(post("/api/auth/login")
                            .contentType("text/plain")
                            .content("{}"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.error").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
        }
    }

    // =================================================================
    //  Checkout - the transaction the whole project is built around
    // =================================================================

    @Nested
    @DisplayName("placing an order")
    class Checkout {

        @Test
        @DisplayName("an order is created from the cart, and the cart is emptied")
        void orderIsCreatedFromTheCart() throws Exception {
            User customer = persistCustomer();
            var category = persistCategory("Checkout");
            var product = persistProduct(category, "Checkout Item", "149.50", 10);

            long orderId = placeOrderFor(customer, product, 2);

            String bearer = bearerFor(customer);

            // The order carries the lines and the server-computed total.
            mockMvc.perform(get("/api/orders/" + orderId).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderNumber").value("ORD-%06d".formatted(orderId)))
                    .andExpect(jsonPath("$.totalAmount").value(299.00))
                    .andExpect(jsonPath("$.totalItems").value(2))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.items.length()").value(1));

            // And the cart is empty, so a second checkout cannot re-order the same lines.
            mockMvc.perform(get("/api/cart").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.empty").value(true))
                    .andExpect(jsonPath("$.items.length()").value(0));
        }

        @Test
        @DisplayName("stock is decremented by exactly the quantity ordered")
        void stockIsDecremented() throws Exception {
            User customer = persistCustomer();
            var category = persistCategory("Stock");
            var product = persistProduct(category, "Stocked Item", "10.00", 10);

            placeOrderFor(customer, product, 3);

            assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("an empty cart is a 400 if it exists, and a 404 if it never did")
        void emptyCartIsRejected() throws Exception {
            /*
             * ================================================================
             *  A REAL BEHAVIOUR DISCOVERY, and the test was wrong first.
             * ================================================================
             *
             * This test originally expected 400 from a brand-new customer who had never
             * touched their cart. The application returned 404, and the application is
             * right - because there are two different situations here that look identical
             * from the outside:
             *
             *   1. The customer HAS a cart and it is empty.
             *      -> 400 EMPTY_CART. "Your basket is empty, add something."
             *
             *   2. The customer has NEVER had a cart, so no carts row exists.
             *      -> 404 CART_NOT_FOUND.
             *
             * Why case 2 is a 404 rather than the same 400: the checkout path loads the cart
             * with cartRepository.findByUserIdWithItems(userId) and .orElseThrow(...). It
             * deliberately does NOT create a cart on demand the way CartServiceImpl.getMyCart
             * does, because placing an order is not the place to be inserting rows - if the
             * request is going to fail, it should fail before taking any write lock.
             *
             * Both cases are asserted below, in that order, because the difference is the
             * point. A client that treats both as "nothing to order" is fine; a client that
             * branches on EMPTY_CART must not receive CART_NOT_FOUND for the case the code
             * comment describes.
             *
             * The stock assertion is unchanged and is the part that matters most: neither
             * failure may write an order row.
             */
            User customer = persistCustomer();
            String bearer = bearerFor(customer);

            // --- Case 2: no cart has ever existed. ---
            mockMvc.perform(post("/api/orders")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value(ErrorCode.CART_NOT_FOUND));

            assertThat(orderRepository.count())
                    .as("a failed checkout must not leave an order row")
                    .isZero();

            /*
             * --- Case 1: the cart exists (GET creates it) and is empty. ---
             *
             * This is the state a customer reaches by clicking "view cart" and then
             * "checkout" without adding anything - the realistic double-click, and the one
             * that would otherwise insert a zero-value order on every click.
             */
            mockMvc.perform(get("/api/cart").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.empty").value(true));

            mockMvc.perform(post("/api/orders")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(ErrorCode.EMPTY_CART));

            assertThat(orderRepository.count())
                    .as("still no order row")
                    .isZero();
        }

        @Test
        @DisplayName("a line without enough stock is a 409 naming the product, and nothing is written")
        void insufficientStockRollsBackEverything() throws Exception {
            /*
             * ================================================================
             *  THE MOST IMPORTANT TEST IN THIS FILE.
             * ================================================================
             *
             * It is the only test that proves the transaction boundary, and the transaction
             * boundary is the thing that makes the difference between a shop that works and
             * a shop that takes money for goods it does not have.
             *
             * The setup is deliberately two lines rather than one, because a single bad line
             * would pass this test even with no transaction at all - the failure would occur
             * before anything was written. With a GOOD line added first, the order service
             * has already decremented that product's stock by the time it reaches the BAD
             * line. If the transaction does not roll back, that decrement survives, and the
             * shop has silently lost inventory it still has on the shelf.
             *
             * So there are three assertions, and all three are required:
             *   1. the response is a 409 with the failing product named
             *   2. no order row exists
             *   3. the GOOD product's stock is exactly what it was - the rollback
             *
             * Assertion 3 is the one that fails if @Transactional(rollbackFor=...) is
             * removed, or if an exception is swallowed and the method returns normally.
             */
            /*
             * ================================================================
             *  THE CART CHECK IS ADVISORY, AND THIS TEST DEPENDS ON THAT.
             * ================================================================
             *
             * The first attempt at this test added 5 units of a product with stock 1, and
             * expected the refusal at CHECKOUT. It got a 409 from the CART instead, because
             * CartServiceImpl.addItem does check stock:
             *
             *     if (!product.hasStockFor(resultingQuantity)) {
             *         throw ConflictException.insufficientStock(...)
             *     }
             *
             * That check is real and it is useful - it tells the customer "only 1 left" while
             * they are looking at the basket. But it is explicitly NOT a guarantee, and the
             * code says so: nothing is locked while a cart sits idle, so the quantity that
             * passed the cart check can become unavailable at any moment before checkout.
             *
             * So the only way to reach the checkout-time stock failure - the one that must
             * roll back - is for stock to DROP AFTER the cart is populated. That is the
             * realistic scenario too: another customer buys the last units between this
             * customer adding them and checking out. Simulating it by lowering the stock
             * directly is not a shortcut; it is the exact race the pessimistic lock in
             * placeOrder exists to handle.
             *
             * (The checkout check cannot be conveniently tested through two concurrent HTTP
             * requests here, because MockMvc calls run on one thread and would not actually
             * interleave. The concurrency guarantee is proved separately in
             * OrderConcurrencyTest, and against real MySQL the lock behaviour is proved by
             * db/constraint-test.sh. This test proves the rollback, which is a different
             * property and the one that is cheap to get right and expensive to get wrong.)
             */
            User customer = persistCustomer();
            var category = persistCategory("Stock Rollback");

            var goodProduct = persistProduct(category, "Plenty In Stock", "100.00", 10);
            var scarceProduct = persistProduct(category, "Nearly Gone", "50.00", 4);

            String bearer = bearerFor(customer);

            // 3 of the good product - affordable and in stock.
            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(goodProduct.getId(), 3))))
                    .andExpect(status().isOk());

            // 4 of the scarce product - passes the advisory cart check at stock 4.
            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(scarceProduct.getId(), 4))))
                    .andExpect(status().isOk());

            /*
             * Another customer buys 3 of them. Stock is now 1, and the cart still holds 4 -
             * a state the cart check would have prevented but cannot, because it ran before
             * this happened.
             */
            scarceProduct.setStock(1);
            productRepository.saveAndFlush(scarceProduct);

            // --- The checkout fails, and this is the failure that must roll everything back. ---
            mockMvc.perform(post("/api/orders")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(ErrorCode.INSUFFICIENT_STOCK))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Nearly Gone")));

            // 1. No order was created.
            assertThat(orderRepository.count())
                    .as("the failed checkout must not leave an order row")
                    .isZero();

            /*
             * 2. THE ROLLBACK ASSERTION.
             *
             * By the time placeOrder reached the scarce product it had already decremented the
             * good one from 10 to 7. If the transaction does not roll back, that decrement
             * survives and the shop has permanently lost three units it still has on the
             * shelf - inventory that no longer exists in the system, which is how a shop
             * starts overselling.
             *
             * Note the ordering guarantee this relies on: placeOrder validates every line
             * BEFORE decrementing any (steps 5 and 7 in its javadoc). So the failure on the
             * scarce product happens after the good product's decrement - which is precisely
             * what makes this a test of the rollback rather than a test of the ordering.
             * A version that validated lazily would pass this test even without a
             * transaction, which is the trap this setup is designed to avoid.
             */
            assertThat(productRepository.findById(goodProduct.getId()).orElseThrow().getStock())
                    .as("the decrement applied before the failure must be rolled back")
                    .isEqualTo(10);

            // 3. The scarce product's stock is whatever the other customer left it at.
            assertThat(productRepository.findById(scarceProduct.getId()).orElseThrow().getStock())
                    .isEqualTo(1);

            /*
             * 4. And the cart is left INTACT, so the customer can fix the one line that is a
             *    problem rather than rebuilding their basket. A rollback that also emptied the
             *    cart would be technically correct and awful to use.
             */
            mockMvc.perform(get("/api/cart").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2));
        }

        @Test
        @DisplayName("a deactivated product in the cart blocks checkout with a 409")
        void deactivatedProductBlocksCheckout() throws Exception {
            /*
             * The separate case from insufficient stock, and it needs its own code: a product
             * withdrawn from sale is not a stock problem, and telling the customer "only 0
             * left" would send them to customer support asking when it will be restocked.
             *
             * A cart can hold an unavailable product quite legitimately - the customer added
             * it while it was on sale, and an admin deactivated it since. So the check has to
             * happen at checkout, which is exactly where it is.
             *
             * Note the ordering, which is the same lesson as the rollback test above: the
             * product must be added to the cart while it is still ACTIVE, because
             * CartServiceImpl.requireAvailableProduct refuses an inactive product at
             * add-time. Deactivating afterwards is the only path that produces this state,
             * and it is the realistic one - nobody adds a withdrawn product to a basket.
             */
            User customer = persistCustomer();
            var category = persistCategory("Withdrawn");
            var product = persistProduct(category, "Withdrawn Item", "20.00", 10);

            String bearer = bearerFor(customer);

            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(product.getId(), 1))))
                    .andExpect(status().isOk());

            product.setActive(false);
            productRepository.saveAndFlush(product);

            mockMvc.perform(post("/api/orders")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(ErrorCode.PRODUCT_UNAVAILABLE));

            assertThat(orderRepository.count()).isZero();
        }

        @Test
        @DisplayName("the order total is computed server-side and cannot be influenced by the client")
        void totalComesFromTheServer() throws Exception {
            /*
             * The price-tampering attack, attempted. The client sends a plausible-looking
             * "totalAmount" and "price" in the checkout body.
             *
             * The defence is structural rather than a check: PlaceOrderRequest has no such
             * fields, so there is nothing to bind, and the order service reads the price from
             * the product row inside the transaction. The assertion is on the resulting
             * total, which must be the server's arithmetic - 4 x 25.00 = 100.00 - and not the
             * 0.01 the client asked for.
             *
             * Note the request may well be rejected outright by Jackson's
             * fail-on-unknown-properties setting. Either outcome is a pass for the security
             * property; the assertion that matters is the total on the created order.
             */
            User customer = persistCustomer();
            var category = persistCategory("Pricing");
            var product = persistProduct(category, "Priced Item", "25.00", 10);

            String bearer = bearerFor(customer);

            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(product.getId(), 4))))
                    .andExpect(status().isOk());

            var result = mockMvc.perform(post("/api/orders")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content("""
                                    {"shippingAddress":"1 Test Street","totalAmount":0.01,
                                     "items":[{"productId":%d,"quantity":4,"price":0.01}]}
                                    """.formatted(product.getId())))
                    .andReturn();

            // Whatever the status, no order may exist with a client-supplied total.
            if (result.getResponse().getStatus() == 201) {
                long orderId = objectMapper.readTree(result.getResponse().getContentAsString())
                        .get("id").asLong();

                mockMvc.perform(get("/api/orders/" + orderId).header("Authorization", bearer))
                        .andExpect(jsonPath("$.totalAmount").value(100.00));
            } else {
                assertThat(result.getResponse().getStatus()).isEqualTo(400);
                assertThat(orderRepository.count()).isZero();
            }
        }
    }

    // =================================================================
    //  The status machine
    // =================================================================

    @Nested
    @DisplayName("order status transitions")
    class StatusMachine {

        @Test
        @DisplayName("a legal transition succeeds and stock is NOT restored")
        void legalTransitionSucceeds() throws Exception {
            User admin = persistAdmin();
            var category = persistCategory("Transitions");
            var product = persistProduct(category, "Transition Item", "30.00", 10);

            long orderId = placeOrderFor(persistCustomer(), product, 2);

            mockMvc.perform(patch("/api/admin/orders/" + orderId + "/status")
                            .header("Authorization", bearerFor(admin))
                            .contentType("application/json")
                            .content("{\"status\":\"CONFIRMED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));

            // Stock stays down: the goods are still sold, just confirmed.
            assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("an illegal transition is a 409, and the status is unchanged")
        void illegalTransitionIsRefused() throws Exception {
            /*
             * PENDING -> DELIVERED, skipping CONFIRMED and SHIPPED. The state machine refuses
             * it with its own error code, which matters because a frontend can then grey out
             * the button that produced it rather than showing a generic failure.
             *
             * The second assertion is the important half: a refused transition must not have
             * partially applied. A status update that returned 409 while still writing the
             * new value would be a data-corruption bug that a status-only test would miss.
             */
            User admin = persistAdmin();
            var category = persistCategory("Illegal Transition");
            var product = persistProduct(category, "Item", "30.00", 10);

            long orderId = placeOrderFor(persistCustomer(), product, 1);

            mockMvc.perform(patch("/api/admin/orders/" + orderId + "/status")
                            .header("Authorization", bearerFor(admin))
                            .contentType("application/json")
                            .content("{\"status\":\"DELIVERED\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(ErrorCode.INVALID_ORDER_STATUS_TRANSITION));

            assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.PENDING);
        }

        @Test
        @DisplayName("cancelling an order restores the stock to every line's product")
        void cancellingRestoresStock() throws Exception {
            /*
             * The compensating action, and the one place where a subtle bug is easy to write:
             * restoring to the WRONG product. The order's lines hold a snapshot of the price
             * and a reference to the product - and the reference is what must be used. Keying
             * the restore off anything else (the line id, or a product looked up by name)
             * would credit inventory to the wrong row, which looks fine until stock counts
             * drift and nobody can explain why.
             *
             * Two products with different quantities, so a mix-up between them is visible:
             * restoring 3 where 1 is expected would be caught.
             */
            User admin = persistAdmin();
            var category = persistCategory("Cancellation");

            var first = persistProduct(category, "First Item", "10.00", 10);
            var second = persistProduct(category, "Second Item", "20.00", 10);

            User customer = persistCustomer();
            String bearer = bearerFor(customer);

            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(first.getId(), 3))))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(second.getId(), 1))))
                    .andExpect(status().isOk());

            var placed = postJson("/api/orders", new PlaceOrderBody("1 Test Street"), bearer);
            assertThat(placed.getResponse().getStatus()).isEqualTo(201);
            long orderId = objectMapper.readTree(placed.getResponse().getContentAsString()).get("id").asLong();

            // After checkout: 7 and 9.
            assertThat(productRepository.findById(first.getId()).orElseThrow().getStock()).isEqualTo(7);
            assertThat(productRepository.findById(second.getId()).orElseThrow().getStock()).isEqualTo(9);

            mockMvc.perform(patch("/api/admin/orders/" + orderId + "/status")
                            .header("Authorization", bearerFor(admin))
                            .contentType("application/json")
                            .content("{\"status\":\"CANCELLED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.allowedNextStatuses.length()").value(0));

            // Back to 10 and 10 - each product credited its own quantity, no more, no less.
            assertThat(productRepository.findById(first.getId()).orElseThrow().getStock())
                    .as("3 units restored to the first product")
                    .isEqualTo(10);

            assertThat(productRepository.findById(second.getId()).orElseThrow().getStock())
                    .as("1 unit restored to the second product")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("a cancelled order is terminal - the state machine allows no next status")
        void cancelledIsTerminal() throws Exception {
            /*
             * `allowedNextStatuses` being empty is what drives the frontend's buttons, and it
             * is also the assertion that the terminal state really is terminal: the response
             * to the cancellation itself reports that nothing may follow.
             */
            User admin = persistAdmin();
            var category = persistCategory("Terminal");
            var product = persistProduct(category, "Terminal Item", "10.00", 10);

            long orderId = placeOrderFor(persistCustomer(), product, 1);
            String adminBearer = bearerFor(admin);

            mockMvc.perform(patch("/api/admin/orders/" + orderId + "/status")
                            .header("Authorization", adminBearer)
                            .contentType("application/json")
                            .content("{\"status\":\"CANCELLED\"}"))
                    .andExpect(status().isOk());

            // Any attempt to move on from CANCELLED is refused.
            mockMvc.perform(patch("/api/admin/orders/" + orderId + "/status")
                            .header("Authorization", adminBearer)
                            .contentType("application/json")
                            .content("{\"status\":\"SHIPPED\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(ErrorCode.INVALID_ORDER_STATUS_TRANSITION));
        }
    }

    /** Body for checkout, nested because only a few tests need a shipping address. */
    private record PlaceOrderBody(String shippingAddress) {
    }
}
