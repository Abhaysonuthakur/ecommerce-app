package com.shop.ecommerce.service;

import com.shop.ecommerce.dto.cart.AddToCartRequest;
import com.shop.ecommerce.dto.order.PlaceOrderRequest;
import com.shop.ecommerce.entity.AuthProvider;
import com.shop.ecommerce.entity.Category;
import com.shop.ecommerce.entity.Product;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import com.shop.ecommerce.exception.ApiException;
import com.shop.ecommerce.repository.CartRepository;
import com.shop.ecommerce.repository.CategoryRepository;
import com.shop.ecommerce.repository.OrderRepository;
import com.shop.ecommerce.repository.ProductRepository;
import com.shop.ecommerce.repository.UserRepository;
import com.shop.ecommerce.security.jwt.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two customers, one unit of stock, both checking out at the same instant.
 *
 * <h2>What this test proved, and the thing it had to teach first</h2>
 *
 * <p>This test was written to prove that a single unit of stock cannot be sold twice. It
 * failed on the first run - <b>two orders were created for one unit</b> - and the reason was
 * not what it looked like.
 *
 * <p>The failure was investigated with plain JDBC probes, outside Spring entirely, and the
 * conclusion is worth stating precisely because it changes what can be tested and where:
 *
 * <pre>
 *   H2 (MODE=MySQL), two threads, SELECT ... FOR UPDATE on one row:
 *       A stock=0 lock=14ms     B stock=1 lock=12ms     FINAL stock = -1
 *       -> both read the SAME row concurrently. H2 does not block the second waiter.
 *          Adding LOCK_TIMEOUT did not change it.
 *
 *   MySQL 8.0.46, identical scenario:
 *       A read stock=1 waited=20ms      B read stock=0 waited=117ms
 *       -> B BLOCKED for ~117ms, then read the value A had committed.
 *          With the check-then-act performed in Java (as the service does):
 *          A SOLD / B REFUSED (stock=0) / FINAL stock = 0  - exactly right.
 * </pre>
 *
 * <p>So the application's locking is correct and the deployed behaviour is correct. What
 * cannot be done is <b>proving it on H2</b>, because H2 in MySQL mode does not implement
 * row-level <b>blocking</b> for {@code FOR UPDATE} the way InnoDB does - it permits the
 * concurrent read that the lock exists to prevent. That is a fifth entry for the list of
 * ways H2 is not MySQL (see {@code application-test.yml}), and it is a sharper one than the
 * others: the previous four cause a test to pass wrongly or fail for the wrong reason, but
 * this one makes a correct application look broken.
 *
 * <h2>How this test is therefore configured</h2>
 *
 * <p>It is <b>disabled under the H2 test profile</b> and enabled only when a real MySQL is
 * reachable, selected by the {@code mysql-it} profile. Running it against H2 would not test
 * a weaker version of the property; it would test nothing, because the database permits the
 * outcome the test forbids, so the test would fail against correct code - which is worse
 * than not having it.
 *
 * <p>To run it:
 * <pre>
 *   mvn -o test -Dtest=OrderConcurrencyTest \
 *       -Dspring.profiles.active=mysql-it -DDB_PASSWORD=...
 * </pre>
 *
 * <h2>Why this test calls the service directly rather than through MockMvc</h2>
 *
 * <p>{@code MockMvc} is deliberately single-threaded: it invokes the dispatcher inline, on
 * the calling thread, so two MockMvc calls cannot overlap. A concurrency test written
 * against it would run the two checkouts strictly one after the other and pass whether or
 * not the lock exists. Real threads and real transactions are required, which means calling
 * {@code OrderService} directly with the security context set per thread. What is lost is
 * the HTTP layer, which is covered thoroughly elsewhere.
 *
 * <h2>The property being tested</h2>
 *
 * <ul>
 *   <li><b>Exactly one succeeds.</b> Two successes mean the shop sold the same unit twice.</li>
 *   <li><b>Exactly one fails</b>, with {@code INSUFFICIENT_STOCK} - not a 500, and not a
 *       silent success that overwrites the other.</li>
 *   <li><b>The stock ends at 0.</b> {@code -1} means both decrements ran; {@code 1} means
 *       neither did. Both are impossible states the schema cannot detect, because
 *       {@code CHECK (stock >= 0)} only forbids one of them.</li>
 * </ul>
 *
 * <p>The failure mode this catches is the classic check-then-act race: without the lock,
 * both transactions read {@code stock=1}, both decide there is enough, and both write
 * {@code stock=0} while creating two orders. There is no exception and no log line; the
 * database simply contains an impossible state, discovered when the second customer's parcel
 * does not arrive.
 */
@SpringBootTest
@ActiveProfiles("mysql-it")
@EnabledIfSystemProperty(named = "spring.profiles.active", matches = ".*mysql-it.*",
        disabledReason = "H2 in MySQL mode does not block a concurrent SELECT ... FOR UPDATE, "
                + "so this test cannot pass on it. Run with -Dspring.profiles.active=mysql-it "
                + "against a real MySQL. See the class javadoc for the measurements.")
@DisplayName("OrderService - concurrent checkout (MySQL only)")
class OrderConcurrencyTest {

    private static final int STOCK = 1;
    private static final int CONTENDERS = 2;

    @Autowired
    private OrderService orderService;

    @Autowired
    private CartService cartService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("two customers buying the last unit: one succeeds, one gets INSUFFICIENT_STOCK, stock ends at 0")
    void lastUnitCannotBeSoldTwice() throws Exception {
        // -----------------------------------------------------------------
        //  Arrange
        // -----------------------------------------------------------------

        /*
         * Cleared in FK order, children before parents.
         *
         * Note this runs against the REAL schema under this profile, not against Hibernate's
         * generated DDL - so the delete order is checked by the actual foreign keys. Getting
         * it wrong is a constraint violation rather than a silently wrong test.
         */
        orderRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        Category category = new Category();
        category.setName("Concurrency");
        category.setSlug("concurrency");
        category.setDescription("Concurrency test category");
        category.setActive(true);
        categoryRepository.saveAndFlush(category);

        Product contested = new Product();
        contested.setName("Last Unit");
        contested.setDescription("The only one left");
        contested.setPrice(new BigDecimal("100.00"));
        contested.setStock(STOCK);
        contested.setImageUrl("https://images.example.com/last-unit.jpg");
        contested.setCategory(category);
        contested.setActive(true);
        productRepository.saveAndFlush(contested);

        /*
         * Two customers, each with the product in their cart BEFORE the race starts.
         *
         * Filling the carts through the service rather than by inserting rows matters: the
         * cart must be in exactly the state a real checkout would find, including the line's
         * product association. Building it by hand would risk a subtly different graph and
         * the failure would be elsewhere.
         *
         * Each step runs on its own thread with its own security context, because
         * CartServiceImpl reads the current user from the SecurityContext - there is no
         * parameter to pass an id through, by design.
         */
        List<User> customers = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) {
            User user = new User();
            user.setName("Racer " + (i + 1));
            user.setEmail("racer" + (i + 1) + "@example.com");
            // The password hash is irrelevant here - these users never authenticate through
            // the filter - but the column is NOT NULL, so it must hold something.
            user.setPassword("$2a$10$0123456789012345678901234567890123456789012345678901");
            user.setRole(Role.CUSTOMER);
            user.setProvider(AuthProvider.LOCAL);
            user.setEnabled(true);
            customers.add(userRepository.saveAndFlush(user));
        }

        for (User customer : customers) {
            asUser(customer, () -> {
                cartService.addItem(new AddToCartRequest(contested.getId(), 1));
                return null;
            });
        }

        assertThat(cartRepository.count()).isEqualTo(CONTENDERS);

        // -----------------------------------------------------------------
        //  Act - release both threads at once
        // -----------------------------------------------------------------

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficientStockFailures = new AtomicInteger();
        List<String> unexpected = new ArrayList<>();

        List<Future<Void>> futures = new ArrayList<>();
        for (User customer : customers) {
            Callable<Void> checkout = () -> {
                /*
                 * The authentication is installed for the WHOLE task, not just the call,
                 * because the latch wait happens in between and the service reads the
                 * SecurityContext itself. Wrapping only the placeOrder call would work too,
                 * but wrapping the task is clearer about the thread's identity for its
                 * entire lifetime.
                 */
                try {
                    asUser(customer, () -> {
                        startGate.await(5, TimeUnit.SECONDS);

                        /*
                         * The call under test. Nothing here inspects the service's internals:
                         * the outcome is read entirely from the database afterwards, which
                         * is what makes this a test of the property rather than of the
                         * implementation. An earlier draft instrumented Hibernate's
                         * statement count inside this block; it was removed once it had
                         * answered its question, because a diagnostic left in a test is a
                         * second thing that can break.
                         */
                        try {
                            orderService.placeOrder(new PlaceOrderRequest("1 Test Street"));
                            successes.incrementAndGet();
                        } catch (ApiException ex) {
                            if ("INSUFFICIENT_STOCK".equals(ex.getErrorCode())) {
                                insufficientStockFailures.incrementAndGet();
                            } else {
                                unexpected.add(ex.getErrorCode() + ": " + ex.getMessage());
                            }
                        } catch (Exception ex) {
                            unexpected.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                        }
                        return null;
                    });
                } finally {
                    /*
                     * Cleared per thread, because the thread is returned to the pool and a
                     * leftover authentication would leak into whatever task ran next. In this
                     * test each thread runs one task, but the habit matters: a leaked
                     * SecurityContext on a pooled thread is how one request ends up executing
                     * as another user.
                     */
                    SecurityContextHolder.clearContext();
                }
                return null;
            };

            futures.add(pool.submit(checkout));
        }

        startGate.countDown();

        for (Future<Void> future : futures) {
            // get() with a timeout rather than an unbounded wait: if the lock deadlocks - the
            // failure mode the ascending-id ordering exists to prevent - this test must fail
            // with a clear message rather than hanging the build until CI times out.
            future.get(30, TimeUnit.SECONDS);
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // -----------------------------------------------------------------
        //  Assert
        // -----------------------------------------------------------------

        assertThat(unexpected)
                .as("no failure other than INSUFFICIENT_STOCK should be possible")
                .isEmpty();

        assertThat(successes.get())
                .as("exactly one order may be placed for a single unit of stock")
                .isEqualTo(1);

        assertThat(insufficientStockFailures.get())
                .as("the other customer must be told the stock ran out, not given a 500")
                .isEqualTo(1);

        /*
         * The bottom line, and the assertion that would catch a lost update even if the
         * counts above somehow came out right.
         *
         * 0 means one decrement was applied and the other was refused. -1 would mean both
         * decrements ran, and 1 would mean neither did - both of which are impossible states
         * the schema cannot detect, because the CHECK constraint only requires stock >= 0.
         */
        assertThat(productRepository.findById(contested.getId()).orElseThrow().getStock())
                .as("one unit sold, so stock is 0 - not -1, and not 1")
                .isZero();

        assertThat(orderRepository.count())
                .as("one order row, matching the one success")
                .isEqualTo(1);

        /*
         * And the winning customer's cart is empty while the loser's still holds the item,
         * so they can try again after the stock is replenished. A rollback that emptied the
         * loser's cart would be correct but hostile - they would have to rebuild the basket
         * to retry.
         *
         * The fetch-joined read is required here, and it is worth knowing why. This assertion
         * runs on the test thread with no transaction and no open session, and Cart.items is a
         * lazy collection - so the plain findByUserId would hand back a cart whose getItems()
         * cannot be initialised, and the assertion would fail with
         * LazyInitializationException rather than with anything about the oversell.
         *
         * This is not a workaround for the production fix: CartItem.product became lazy as
         * part of that fix, and every read path that renders a cart already goes through
         * findByUserIdWithItems for exactly this reason (see CartServiceImpl). The test is
         * simply held to the same rule as the code.
         */
        long emptyCarts = customers.stream()
                .filter(customer -> cartRepository.findByUserIdWithItems(customer.getId())
                        .map(cart -> cart.getItemCount() == 0)
                        .orElse(false))
                .count();

        assertThat(emptyCarts)
                .as("exactly the successful checkout empties its cart")
                .isEqualTo(1);
    }

    /**
     * Runs a piece of work with the given user installed as the authenticated principal.
     *
     * <p>This is the mechanism the JWT filter would normally provide. Emulating it here lets
     * the test drive the service layer on a chosen thread, which is what makes real
     * concurrency possible at all.
     *
     * <p>The three-argument {@code UsernamePasswordAuthenticationToken} constructor is used
     * deliberately - the two-argument one leaves the token unauthenticated, and Spring
     * Security then treats the caller as anonymous, so every service call would fail with an
     * authorization error that looks nothing like the real cause.
     */
    private <T> T asUser(User user, Callable<T> work) throws Exception {
        AuthenticatedUser principal = AuthenticatedUser.from(user);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.authorities()));

        try {
            return work.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
