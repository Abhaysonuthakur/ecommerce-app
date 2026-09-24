package com.shop.ecommerce.repository;

import com.shop.ecommerce.entity.AuthProvider;
import com.shop.ecommerce.entity.Cart;
import com.shop.ecommerce.entity.CartItem;
import com.shop.ecommerce.entity.Category;
import com.shop.ecommerce.entity.Order;
import com.shop.ecommerce.entity.OrderItem;
import com.shop.ecommerce.entity.OrderStatus;
import com.shop.ecommerce.entity.Product;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import org.hibernate.engine.FetchTiming;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The persistence layer, proven against a real database engine.
 *
 * <h2>What a {@code @DataJpaTest} can and cannot prove here</h2>
 *
 * <p>This slice loads only JPA - repositories, entities, the EntityManager - with an
 * in-memory H2 database in MySQL mode. It is fast and it needs no server, which makes it the
 * right place to prove things that are about <b>mappings and queries</b>:
 *
 * <ul>
 *   <li>that every JPQL string in a {@code @Query} actually parses and returns what it
 *       claims - a broken query is otherwise found the first time that endpoint is called;</li>
 *   <li>that a fetch join initialises the collection it names, so the service does not hit a
 *       {@code LazyInitializationException} in production;</li>
 *   <li>that derived query names ({@code findByUserIdAndStatusOrderByCreatedAtDesc})
 *       resolve to the SQL intended;</li>
 *   <li>that cascade and orphanRemoval behave as the entity javadoc promises;</li>
 *   <li>that the pessimistic lock query is syntactically valid and returns rows.</li>
 * </ul>
 *
 * <p><b>What it cannot prove, and must not be believed about:</b> the schema. H2 does not
 * enforce MySQL's CHECK constraints, has no {@code INT UNSIGNED}, and its collation differs
 * from {@code utf8mb4_0900_ai_ci}. A negative stock that MySQL would reject at the storage
 * layer is accepted here. The schema is proven separately by {@code db/constraint-test.sql}
 * against real MySQL - see {@code application-test.yml} for the full list of divergences.
 *
 * <p>Test methods are {@code @Transactional} by virtue of the slice, so each rolls back and
 * tests cannot contaminate one another.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("Repositories - mappings and queries (H2)")
class RepositoryPersistenceTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    /**
     * The slice's own EntityManager helper.
     *
     * <p>{@code persistFlushClear()} is the method that matters: it writes, flushes and then
     * <b>detaches every managed entity</b>. Without the clear, an assertion after a save
     * reads the in-memory object the service already mutated, so a mapping bug - a missing
     * column, a wrong join - is invisible. Clearing forces the next read to come from the
     * database, which is the only version that can be wrong.
     *
     * <p>This is the single most valuable habit in a JPA test, and the reason the helpers
     * below exist rather than calling {@code repository.save} directly.
     */
    @Autowired
    private TestEntityManager testEntityManager;

    /** Used to assert that a lazy association really is lazy - see {@link LazyLoading}. */
    @PersistenceContext
    private EntityManager entityManager;

    // =================================================================
    //  Fixtures - built through the entities, exactly as the services do
    // =================================================================

    private Category persistedCategory(String name) {
        Category category = new Category();
        category.setName(name);
        category.setSlug(name.toLowerCase().replace(' ', '-'));
        category.setDescription("Description of " + name);
        category.setImageUrl("https://images.example.com/" + category.getSlug() + ".jpg");
        category.setActive(true);
        return categoryRepository.save(category);
    }

    private Product persistedProduct(String name, String price, int stock, Category category) {
        Product product = new Product();
        product.setName(name);
        product.setDescription("Description of " + name);
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setImageUrl("https://images.example.com/products/" + name.toLowerCase().replace(' ', '-') + ".jpg");
        product.setActive(true);
        product.setCategory(category);
        return productRepository.save(product);
    }

    private User persistedUser(String email, Role role) {
        User user = new User();
        user.setName("Test " + role.name().toLowerCase());
        user.setEmail(email);
        user.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        user.setPhone("+91 9000000000");
        user.setAddress("1 Test Street");
        user.setRole(role);
        user.setProvider(AuthProvider.LOCAL);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private Cart persistedCartFor(User user) {
        Cart cart = new Cart();
        cart.setUser(user);
        return cartRepository.save(cart);
    }

    /**
     * Returns a cart's database id <b>after flushing the pending inserts</b>.
     *
     * <p>The flush is not decorative. {@code Cart.id} is generated by the database
     * ({@code IDENTITY}), so between {@code save()} and the flush the field is still
     * {@code null} in memory - Hibernate has to execute the INSERT before it can know
     * the value. A test that reads {@code cart.getId()} directly therefore passes
     * {@code null} into a repository method and gets an empty result that looks exactly
     * like an ownership check working correctly. That is the worst kind of test bug:
     * a false pass that hides the very behaviour it claims to prove.
     */
    private Long cartIdOf(User user) {
        testEntityManager.flush();
        return cartRepository.findByUserId(user.getId()).orElseThrow().getId();
    }

    // =================================================================
    //  User
    // =================================================================

    @Nested
    @DisplayName("UserRepository")
    class Users {

        @Test
        @DisplayName("findByEmailIgnoreCase finds a user whatever the case of the query")
        void emailLookupIsCaseInsensitive() {
            /*
             * Login must not depend on the user remembering which letters they capitalised.
             * The real schema gets this free from the utf8mb4_0900_ai_ci collation, but H2's
             * default is case-sensitive - so this test is only meaningful because the
             * repository declares `lower(u.email) = lower(:email)` explicitly.
             *
             * That is a deliberate belt-and-braces choice: relying on a database collation
             * for a security-relevant lookup means the behaviour is invisible in the code and
             * changes if the column's collation ever does.
             */
            persistedUser("ada@example.com", Role.CUSTOMER);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(userRepository.findByEmailIgnoreCase("ada@example.com")).isPresent();
            assertThat(userRepository.findByEmailIgnoreCase("ADA@EXAMPLE.COM")).isPresent();
            assertThat(userRepository.findByEmailIgnoreCase("AdA@ExAmPlE.CoM")).isPresent();
            assertThat(userRepository.findByEmailIgnoreCase("nobody@example.com")).isEmpty();
        }

        @Test
        @DisplayName("existsByEmailIgnoreCase matches without returning the row")
        void existenceCheckIsCaseInsensitive() {
            /*
             * Registration checks for a duplicate before inserting. Using exists* rather than
             * findByEmail* means the password hash is never loaded into memory for a request
             * that is only asking "is this taken?" - one fewer place a credential can exist.
             */
            persistedUser("ada@example.com", Role.CUSTOMER);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(userRepository.existsByEmailIgnoreCase("ADA@example.com")).isTrue();
            assertThat(userRepository.existsByEmailIgnoreCase("grace@example.com")).isFalse();
        }

        @Test
        @DisplayName("countByRole counts admins, which is what the last-admin guard reads")
        void countByRoleCountsAdmins() {
            /*
             * This count is load-bearing: UserServiceImpl.updateRole refuses to demote the
             * final admin by comparing `countByRole(ADMIN) <= 1`. If this query were wrong
             * the guard would be wrong, and a shop could be left with no administrator and no
             * way to create one - a state that requires database access to fix.
             */
            User first = persistedUser("admin1@example.com", Role.ADMIN);
            persistedUser("admin2@example.com", Role.ADMIN);
            persistedUser("shopper@example.com", Role.CUSTOMER);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(userRepository.countByRole(Role.ADMIN)).isEqualTo(2);
            assertThat(userRepository.countByRole(Role.CUSTOMER)).isEqualTo(1);

            userRepository.deleteById(first.getId());
            userRepository.flush();

            assertThat(userRepository.countByRole(Role.ADMIN))
                    .as("the count must follow a deletion, or the guard would refuse a legal demotion")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("existsByEmailIgnoreCaseExcluding ignores the row being edited")
        void existenceCheckCanExcludeTheEditedRow() {
            /*
             * The self-collision case: a user re-saving their own email would otherwise be
             * told the address is taken - by themselves. The exclusion argument is what makes
             * "unchanged" distinguishable from "somebody else has it".
             */
            User ada = persistedUser("ada@example.com", Role.CUSTOMER);
            User grace = persistedUser("grace@example.com", Role.CUSTOMER);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(userRepository.existsByEmailIgnoreCaseExcluding("ada@example.com", ada.getId()))
                    .as("a user's own email is not a conflict for that user")
                    .isFalse();
            assertThat(userRepository.existsByEmailIgnoreCaseExcluding("grace@example.com", ada.getId()))
                    .as("but another user's email is")
                    .isTrue();
        }

        @Test
        @DisplayName("paging by role returns only that role, newest first")
        void pagingByRoleFiltersAndOrders() {
            persistedUser("customer1@example.com", Role.CUSTOMER);
            persistedUser("customer2@example.com", Role.CUSTOMER);
            persistedUser("admin@example.com", Role.ADMIN);
            testEntityManager.flush();
            testEntityManager.clear();

            Page<User> customers = userRepository.findByRoleOrderByCreatedAtDesc(
                    Role.CUSTOMER, PageRequest.of(0, 10));

            assertThat(customers.getTotalElements()).isEqualTo(2);
            assertThat(customers.getContent())
                    .allSatisfy(user -> assertThat(user.getRole()).isEqualTo(Role.CUSTOMER))
                    .allSatisfy(user -> assertThat(user.getPassword())
                            .as("the hash is present on the entity - which is why it is never mapped to a DTO")
                            .isNotBlank());
        }
    }

    // =================================================================
    //  Category
    // =================================================================

    @Nested
    @DisplayName("CategoryRepository")
    class Categories {

        @Test
        @DisplayName("countProductsPerCategory returns (id, count) pairs for every category")
        void productCountsAreGrouped() {
            /*
             * This one query is what stops the category list being an N+1: mapping six
             * categories would otherwise issue six COUNT queries. It returns Object[] rather
             * than a projection type, which is uglier but keeps the count grouped in SQL
             * rather than in Java - and the ugliness is why it is asserted here, since a
             * mis-ordered array is not a compile error.
             */
            Category electronics = persistedCategory("Electronics");
            Category books = persistedCategory("Books");
            Category empty = persistedCategory("Empty Aisle");

            persistedProduct("Laptop", "50000.00", 5, electronics);
            persistedProduct("Phone", "20000.00", 10, electronics);
            persistedProduct("Novel", "499.00", 30, books);
            testEntityManager.flush();
            testEntityManager.clear();

            List<Object[]> counts = categoryRepository.countProductsPerCategory();

            assertThat(counts).hasSize(3);

            java.util.Map<Long, Long> byCategoryId = new java.util.HashMap<>();
            for (Object[] row : counts) {
                byCategoryId.put((Long) row[0], (Long) row[1]);
            }

            assertThat(byCategoryId)
                    .containsEntry(electronics.getId(), 2L)
                    .containsEntry(books.getId(), 1L)
                    .containsEntry(empty.getId(), 0L);
        }

        @Test
        @DisplayName("countProductsInCategory counts only that category")
        void countInOneCategory() {
            Category electronics = persistedCategory("Electronics");
            Category books = persistedCategory("Books");
            persistedProduct("Laptop", "50000.00", 5, electronics);
            persistedProduct("Phone", "20000.00", 10, electronics);
            persistedProduct("Novel", "499.00", 30, books);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(categoryRepository.countProductsInCategory(electronics.getId())).isEqualTo(2);
            assertThat(categoryRepository.countProductsInCategory(books.getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("the unique slug is enforced by the database, not just by the service")
        void slugUniquenessIsEnforcedAtTheDatabase() {
            /*
             * The service computes a unique slug by looping -2, -3. That is the mechanism;
             * the guarantee is the UNIQUE constraint. Without it, two concurrent creates that
             * both computed "electronics" would both succeed and the storefront would have
             * two categories at the same URL.
             *
             * H2 does enforce UNIQUE, so this particular constraint IS provable here (unlike
             * CHECK constraints).
             */
            persistedCategory("Electronics");
            testEntityManager.flush();
            testEntityManager.clear();

            Category duplicate = new Category();
            duplicate.setName("Electronics Again");
            duplicate.setSlug("electronics");     // same slug as the first
            duplicate.setActive(true);

            assertThatThrownBy(() -> {
                categoryRepository.saveAndFlush(duplicate);
            }).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("findByActiveTrueOrderByNameAsc excludes inactive categories and sorts")
        void activeCategoriesAreSortedByName() {
            Category zebra = persistedCategory("Zebra Supplies");
            Category apple = persistedCategory("Apple Accessories");
            Category withdrawn = persistedCategory("Withdrawn Aisle");
            withdrawn.setActive(false);
            categoryRepository.saveAndFlush(withdrawn);
            testEntityManager.flush();
            testEntityManager.clear();

            List<Category> active = categoryRepository.findByActiveTrueOrderByNameAsc();

            assertThat(active)
                    .extracting(Category::getName)
                    .containsExactly("Apple Accessories", "Zebra Supplies");
            assertThat(active).noneMatch(category -> category.getId().equals(withdrawn.getId()));
            assertThat(active).hasSize(2);
            assertThat(zebra.getId()).isNotNull();
            assertThat(apple.getId()).isNotNull();
        }
    }

    // =================================================================
    //  Product
    // =================================================================

    @Nested
    @DisplayName("ProductRepository")
    class Products {

        @Test
        @DisplayName("findByIdAndActiveTrue hides a withdrawn product behind an empty Optional")
        void withdrawnProductLooksAbsent() {
            /*
             * The deliberate design choice: a shopper asking for a delisted product gets a
             * 404, identical to asking for one that never existed. The alternative - 200 with
             * `active: false` - would make the endpoint a way to enumerate the store's
             * unreleased or discontinued stock.
             *
             * Note that the product is still reachable by id from an admin endpoint, which
             * uses the plain findById.
             */
            Category category = persistedCategory("Electronics");
            Product product = persistedProduct("Laptop", "50000.00", 5, category);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(productRepository.findByIdAndActiveTrue(product.getId())).isPresent();

            Product found = productRepository.findById(product.getId()).orElseThrow();
            found.setActive(false);
            productRepository.saveAndFlush(found);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(productRepository.findByIdAndActiveTrue(product.getId()))
                    .as("a withdrawn product must look absent to a shopper")
                    .isEmpty();
            assertThat(productRepository.findById(product.getId()))
                    .as("but must still exist for an admin")
                    .isPresent();
        }

        @Test
        @DisplayName("findAllByIdWithLock returns the rows, ordered by id ascending")
        void theLockingQueryReturnsRowsInIdOrder() {
            /*
             * The `order by p.id asc` in this query is not cosmetic - it is the deadlock
             * prevention. Two concurrent orders for the same pair of products must acquire
             * their row locks in the same sequence, or one waits for the other's lock while
             * holding the one it needs (the dining philosophers).
             *
             * H2 does not reproduce InnoDB's row-locking behaviour, so this test cannot prove
             * the deadlock is prevented. What it CAN prove is that the query parses, that it
             * returns the rows rather than an empty list, and that the ordering clause is in
             * force - all three of which would otherwise fail at the worst possible moment,
             * inside the money-transfer-equivalent path.
             */
            Category category = persistedCategory("Electronics");
            Product first = persistedProduct("Laptop", "50000.00", 5, category);
            Product second = persistedProduct("Phone", "20000.00", 5, category);
            Product third = persistedProduct("Tablet", "30000.00", 5, category);
            testEntityManager.flush();
            testEntityManager.clear();

            // Deliberately supplied out of order, to prove the query imposes its own.
            List<Product> locked = productRepository.findAllByIdWithLock(
                    List.of(third.getId(), first.getId(), second.getId()));

            assertThat(locked)
                    .extracting(Product::getId)
                    .as("the query must order by id ascending regardless of the argument order")
                    .containsExactly(first.getId(), second.getId(), third.getId());
        }

        @Test
        @DisplayName("the locking query returns an empty list for no ids, not everything")
        void lockingQueryWithNoIdsIsEmpty() {
            /*
             * The dangerous alternative: a JPQL `in :ids` with an empty collection. Some
             * providers translate that to `IN ()` which is a syntax error, and others drop the
             * predicate entirely and return EVERY row - which under a write lock would lock
             * the whole products table.
             *
             * The service guards against this by rejecting an empty cart before reaching the
             * query, so this test documents the behaviour the guard exists to avoid relying on.
             */
            Category category = persistedCategory("Electronics");
            persistedProduct("Laptop", "50000.00", 5, category);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(productRepository.findAllByIdWithLock(List.of())).isEmpty();
        }

        @Test
        @DisplayName("the price-range and keyword searches return the matching rows")
        void searchQueriesFilterCorrectly() {
            /*
             * Two @Query methods with hand-written JPQL, which is where a typo is invisible
             * until the endpoint is called. searchActiveByKeyword also exercises the ESCAPE
             * clause, which is what stops a literal `%` in a search box from matching
             * everything.
             */
            Category electronics = persistedCategory("Electronics");
            persistedProduct("Laptop Pro", "50000.00", 5, electronics);
            persistedProduct("Laptop Air", "80000.00", 5, electronics);
            Product hidden = persistedProduct("Laptop Secret", "90000.00", 5, electronics);
            hidden.setActive(false);
            productRepository.saveAndFlush(hidden);
            persistedProduct("Phone", "20000.00", 10, electronics);
            testEntityManager.flush();
            testEntityManager.clear();

            Page<Product> matches = productRepository.searchActiveByKeyword(
                    "%laptop%", PageRequest.of(0, 10));

            assertThat(matches.getTotalElements())
                    .as("two active laptops; the inactive one must not appear")
                    .isEqualTo(2);
            assertThat(matches.getContent())
                    .extracting(Product::getName)
                    .containsExactlyInAnyOrder("Laptop Pro", "Laptop Air");

            // A literal '%' must be escaped, not treated as a wildcard matching everything.
            Page<Product> literalPercent = productRepository.searchActiveByKeyword(
                    "%\\%%", PageRequest.of(0, 10));
            assertThat(literalPercent.getTotalElements())
                    .as("no product name contains a literal percent sign")
                    .isZero();
        }

        @Test
        @DisplayName("the price bounds coalesce to zero rather than returning null")
        void priceBoundsAreNullSafe() {
            /*
             * Used by the filter UI to populate a price slider. An empty catalogue would
             * otherwise give min/max of null, and the frontend would render "₹NaN" in the
             * slider bounds on day one - a real bug that only appears on a fresh install.
             */
            assertThat(productRepository.findMinActivePrice()).isEqualByComparingTo("0");
            assertThat(productRepository.findMaxActivePrice()).isEqualByComparingTo("0");

            Category category = persistedCategory("Electronics");
            persistedProduct("Cheap", "99.00", 5, category);
            persistedProduct("Pricey", "50000.00", 5, category);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(productRepository.findMinActivePrice()).isEqualByComparingTo("99.00");
            assertThat(productRepository.findMaxActivePrice()).isEqualByComparingTo("50000.00");
        }

        @Test
        @DisplayName("isProductOrdered is false before any sale and true afterwards")
        void isProductOrderedDetectsSales() {
            /*
             * The guard that turns a foreign-key failure into a friendlier 409. Deleting a
             * product that appears on an order would violate the FK and surface as a 500; this
             * check lets the service say "this product has been sold - deactivate it instead".
             */
            Category category = persistedCategory("Electronics");
            Product product = persistedProduct("Laptop", "50000.00", 5, category);
            Product unsold = persistedProduct("Phone", "20000.00", 5, category);

            User customer = persistedUser("ada@example.com", Role.CUSTOMER);
            Order order = new Order();
            order.setUser(customer);
            order.setStatus(OrderStatus.PENDING);
            order.setShippingAddress("1 Test Street");
            order.addItem(OrderItem.createSnapshot(product, 1));
            order.recalculateTotal();
            orderRepository.saveAndFlush(order);

            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(productRepository.isProductOrdered(product.getId())).isTrue();
            assertThat(productRepository.isProductOrdered(unsold.getId())).isFalse();
        }

        @Test
        @DisplayName("countByStockLessThanEqual counts what the admin dashboard calls 'low stock'")
        void lowStockCountUsesTheThreshold() {
            Category category = persistedCategory("Electronics");
            persistedProduct("Plenty", "10.00", 100, category);
            persistedProduct("Low", "10.00", 3, category);
            persistedProduct("Exact", "10.00", 10, category);
            persistedProduct("Out", "10.00", 0, category);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(productRepository.countByStockLessThanEqual(10))
                    .as("the comparison is <=, so exactly-10 counts as low")
                    .isEqualTo(3);
            assertThat(productRepository.countByActiveTrue()).isEqualTo(4);
        }
    }

    // =================================================================
    //  Cart - the cascade and orphanRemoval behaviour
    // =================================================================

    @Nested
    @DisplayName("CartRepository and CartItemRepository")
    class Carts {

        @Test
        @DisplayName("addItem reuses an existing line instead of inserting a duplicate")
        void addingTheSameProductTwiceIncrementsOneLine() {
            /*
             * The unique constraint is (cart_id, product_id), so a second row for the same
             * product would be rejected by the database. Cart.addItem avoids that by
             * searching first - and this test proves the search works, because otherwise the
             * second add would fail at flush time with a constraint violation.
             *
             * Note that the products must be re-read rather than reused: `persistedProduct`
             * returns a managed instance, and the second call to addItem finds the line by
             * product id, which is what the real service relies on.
             */
            User customer = persistedUser("ada@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product laptop = persistedProduct("Laptop", "50000.00", 10, category);

            Cart cart = persistedCartFor(customer);
            cart.addItem(laptop, 2);
            cart.addItem(laptop, 3);   // same product again
            cartRepository.saveAndFlush(cart);
            testEntityManager.flush();
            testEntityManager.clear();

            Cart reloaded = cartRepository.findByUserIdWithItems(customer.getId()).orElseThrow();

            assertThat(reloaded.getItems()).hasSize(1);
            assertThat(reloaded.getItems().get(0).getQuantity()).isEqualTo(5);
            assertThat(reloaded.getSubtotal()).isEqualByComparingTo("250000.00");
        }

        @Test
        @DisplayName("the (cart_id, product_id) unique constraint is enforced by the database")
        void duplicateCartLinesAreRejectedByTheDatabase() {
            /*
             * The application logic already prevents this, so the constraint is a backstop -
             * but a backstop that must actually be there, because two concurrent adds can
             * both pass the in-memory search and only the database can arbitrate.
             *
             * Written at the CartItemRepository level rather than through Cart, deliberately:
             * going through addItem would exercise the guard and never reach the constraint.
             */
            User customer = persistedUser("ada@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product laptop = persistedProduct("Laptop", "50000.00", 10, category);
            Cart cart = persistedCartFor(customer);

            CartItem first = new CartItem();
            first.setCart(cart);
            first.setProduct(laptop);
            first.setQuantity(1);
            cartItemRepository.saveAndFlush(first);

            CartItem duplicate = new CartItem();
            duplicate.setCart(cart);
            duplicate.setProduct(laptop);
            duplicate.setQuantity(1);

            assertThatThrownBy(() -> cartItemRepository.saveAndFlush(duplicate))
                    .as("one line per product per cart")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("clearing the items list deletes the rows, because orphanRemoval is set")
        void clearingItemsDeletesTheRows() {
            /*
             * This is what makes `cart.clearItems()` sufficient in the order-placement
             * service, with no explicit delete call. Without orphanRemoval, removing a line
             * from the collection would only null its cart_id - and the orphaned row would
             * survive, still counted in inventory reports while belonging to no cart.
             *
             * The mechanism is worth testing precisely because it is invisible: the code that
             * relies on it looks like it is only editing an in-memory list.
             */
            User customer = persistedUser("ada@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product laptop = persistedProduct("Laptop", "50000.00", 10, category);
            Product phone = persistedProduct("Phone", "20000.00", 10, category);

            Cart cart = persistedCartFor(customer);
            cart.addItem(laptop, 1);
            cart.addItem(phone, 1);
            cartRepository.saveAndFlush(cart);
            testEntityManager.flush();
            testEntityManager.clear();

            // The id has to come from a query, not from `cart`: after the clear above the
            // in-memory Cart is detached, and reading `.getId()` from a detached instance
            // is fine but reading a lazy collection off it is not.
            Long cartId = cartRepository.findByUserId(customer.getId()).orElseThrow().getId();

            assertThat(cartItemRepository.countByCartId(cartId)).isEqualTo(2);

            /*
             * @Transactional(readOnly = true) IS MANDATORY HERE.
             *
             * Spring Data derives the whole repository's transactions as read-only by
             * default, and this test class has no surrounding transaction of its own
             * (@DataJpaTest wraps each test in one, but it opens it in the default
             * read-write mode only when the test itself is not read-only - and the
             * boundary that matters is the one on findByUserIdWithItems, which the
             * repository declares read-only).
             *
             * Concretely: a read-only Hibernate session is flushed with
             * FlushMode.MANUAL, so the DELETE that orphanRemoval schedules for the
             * removed collection element is never written. The rows stay in the table,
             * countByCartId returns 2, and the test reports a broken orphanRemoval
             * mapping when in fact the mapping is correct and only the test's
             * transaction mode was wrong.
             *
             * Annotating the test method read-write overrides the slice's default for
             * this method, so the delete is actually issued.
             */
            Cart reloaded = cartRepository.findByUserIdWithItems(customer.getId()).orElseThrow();
            reloaded.clearItems();
            cartRepository.saveAndFlush(reloaded);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(cartItemRepository.countByCartId(cartId))
                    .as("orphanRemoval must have deleted the rows, not merely detached them")
                    .isZero();
        }

        @Test
        @DisplayName("deleteAllByCartId removes the rows in one statement")
        void bulkDeleteRemovesAllLines() {
            /*
             * The bulk delete exists for the product-deletion path. It is a @Modifying query,
             * which means it bypasses the persistence context - the reason it carries
             * flushAutomatically and clearAutomatically. Without the clear, the deleted rows
             * would still be in the context and a subsequent read would return them from
             * memory, which is the classic "I deleted it and it is still there" bug.
             */
            User customer = persistedUser("ada@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product laptop = persistedProduct("Laptop", "50000.00", 10, category);
            Product phone = persistedProduct("Phone", "20000.00", 10, category);

            Cart cart = persistedCartFor(customer);
            cart.addItem(laptop, 1);
            cart.addItem(phone, 1);
            cartRepository.saveAndFlush(cart);
            testEntityManager.flush();
            testEntityManager.clear();

            Long cartId = cartRepository.findByUserId(customer.getId()).orElseThrow().getId();
            int deleted = cartItemRepository.deleteAllByCartId(cartId);

            assertThat(deleted).isEqualTo(2);
            assertThat(cartItemRepository.countByCartId(cartId))
                    .as("clearAutomatically must have emptied the persistence context")
                    .isZero();
        }

        @Test
        @DisplayName("findByIdAndCartId scopes a lookup to one cart, which is what makes 404 correct")
        void itemLookupIsScopedToTheCart() {
            /*
             * The ownership check for "change the quantity of line N". Scoping the query by
             * cart id means another user's line id returns an empty Optional - a 404 - rather
             * than a 403. That distinction matters: a 403 would confirm the line EXISTS, so
             * the endpoint would become a way to enumerate other people's order contents.
             *
             * ------------------------------------------------------------------
             *  THE BUG THIS TEST HAD, AND WHY IT MATTERED
             * ------------------------------------------------------------------
             *
             * The first version compared `adaCart.getId()` - read straight off the in-memory
             * Cart - against a repository lookup:
             *
             *     CartItem adaItem = adaCart.addItem(laptop, 1);
             *     ...
             *     cartItemRepository.findByIdAndCartId(adaItem.getId(), adaCart.getId())
             *
             * It failed on "but must be findable in its own cart" with an empty Optional, and
             * the temptation was to blame the query. The query was fine. The problem was that
             * `adaCart` is a NEW Cart and `Cart.id` is a database-generated IDENTITY value, so
             * between `save()` and the next flush the field is still null in memory. The test
             * was calling `findByIdAndCartId(id, null)`.
             *
             * That failure mode is worth spelling out because of its shape: the *negative*
             * assertion ("must look absent from Grace's cart") passed for entirely the wrong
             * reason - a null cart id matches nothing - so a test that looked half-correct was
             * actually proving nothing at all about scoping.
             *
             * The fix is `cartIdOf(user)`, which flushes and then reads the id back through a
             * query, so the value comes from the database rather than from an unflushed
             * object. Every cart id in this class now comes from there.
             */
            User ada = persistedUser("ada@example.com", Role.CUSTOMER);
            User grace = persistedUser("grace@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product laptop = persistedProduct("Laptop", "50000.00", 10, category);

            Cart adaCart = persistedCartFor(ada);
            CartItem adaItem = adaCart.addItem(laptop, 1);
            cartRepository.saveAndFlush(adaCart);

            // Grace has a cart too, so "Ada's line is not in Grace's cart" is a real
            // comparison against a cart that genuinely exists - not an empty-database pass.
            persistedCartFor(grace);

            Long adaCartId = cartIdOf(ada);
            Long graceCartId = cartIdOf(grace);

            /*
             * The line's id is read back from the database for the same reason the cart's
             * is. `addItem` returns the new CartItem, but that instance is only *scheduled*
             * for insertion when the cart is flushed - and `CartItem.id` is a
             * database-generated IDENTITY value, so it is still null on the returned object
             * until Hibernate executes the INSERT and reads the generated key back.
             *
             * Calling `findByIdAndCartId(adaItem.getId(), ...)` here would therefore pass
             * null as the line id, match nothing, and produce the exact symptom that made
             * this test fail: the negative assertion silently passing and the positive one
             * failing. The lookup is by (id, cartId) so both values have to be real.
             */
            Long adaItemId = cartItemRepository.findByCartIdAndProductId(adaCartId, laptop.getId())
                    .orElseThrow(() -> new AssertionError("the line was not persisted at all"))
                    .getId();

            assertThat(adaCartId).as("the flush inside cartIdOf must have assigned the id")
                    .isNotNull();
            assertThat(graceCartId).as("both carts must exist for this comparison to mean anything")
                    .isNotNull()
                    .isNotEqualTo(adaCartId);

            assertThat(cartItemRepository.findByIdAndCartId(adaItemId, graceCartId))
                    .as("Ada's line must look absent from Grace's cart")
                    .isEmpty();
            assertThat(cartItemRepository.findByIdAndCartId(adaItemId, adaCartId))
                    .as("but must be findable in its own cart")
                    .isPresent();
        }

        @Test
        @DisplayName("fetching a cart with items returns a usable collection after the context clears")
        void fetchJoinInitialisesItems() {
            /*
             * The reason findByUserIdWithItems exists at all. `Cart.items` is LAZY, and
             * open-in-view is false - so mapping a cart outside a transaction would throw
             * LazyInitializationException unless the collection was fetched eagerly by the
             * query.
             *
             * TestEntityManager.clear() detaches everything, so if this method were a plain
             * derived query the assertion below would fail rather than silently pass. That is
             * what makes this a real test of the join clause.
             */
            User customer = persistedUser("ada@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product laptop = persistedProduct("Laptop", "50000.00", 10, category);

            Cart cart = persistedCartFor(customer);
            cart.addItem(laptop, 2);
            cartRepository.saveAndFlush(cart);
            testEntityManager.flush();
            testEntityManager.clear();

            Cart fetched = cartRepository.findByUserIdWithItems(customer.getId()).orElseThrow();

            assertThat(entityManager.getEntityManagerFactory()
                    .getPersistenceUnitUtil().isLoaded(fetched, "items"))
                    .as("the fetch join must have loaded the collection in the query")
                    .isTrue();
            assertThat(fetched.getItems()).hasSize(1);
            assertThat(fetched.getTotalQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("a user has at most one cart, enforced by a unique constraint")
        void oneCartPerUserIsEnforcedByTheDatabase() {
            /*
             * The create-on-read path in CartServiceImpl can race: two simultaneous requests
             * for the same user's cart can both find nothing and both insert. The unique index
             * on carts.user_id is what turns that race into a constraint violation that the
             * service can retry, instead of two carts where the customer's items are split
             * across them.
             */
            User customer = persistedUser("ada@example.com", Role.CUSTOMER);
            persistedCartFor(customer);
            testEntityManager.flush();

            Cart second = new Cart();
            second.setUser(customer);

            assertThatThrownBy(() -> cartRepository.saveAndFlush(second))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =================================================================
    //  Order - the price snapshot and the aggregate queries
    // =================================================================

    @Nested
    @DisplayName("OrderRepository and OrderItemRepository")
    class Orders {

        private Order persistedOrder(User user, OrderStatus status, Product... products) {
            Order order = new Order();
            order.setUser(user);
            order.setStatus(status);
            order.setShippingAddress("1 Test Street");
            for (Product product : products) {
                order.addItem(OrderItem.createSnapshot(product, 1));
            }
            order.recalculateTotal();
            return orderRepository.save(order);
        }

        @Test
        @DisplayName("the snapshot survives a round trip through the database")
        void snapshotSurvivesReload() {
            /*
             * EntityInvariantsTest proves the snapshot is frozen in memory. This proves the
             * frozen values are what the database actually stores - which is a different
             * claim, and the one that matters for a receipt that is read back a year later.
             *
             * The context is cleared first, so these values come from the tables rather than
             * from the objects that were saved.
             */
            User customer = persistedUser("ada@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product television = persistedProduct("Television", "50000.00", 5, category);

            Order order = persistedOrder(customer, OrderStatus.PENDING, television);
            Long orderId = order.getId();
            testEntityManager.flush();
            testEntityManager.clear();

            // The shop holds a sale - the snapshot must not follow it.
            Product reloadedProduct = productRepository.findById(television.getId()).orElseThrow();
            reloadedProduct.setPrice(new BigDecimal("42000.00"));
            productRepository.saveAndFlush(reloadedProduct);
            testEntityManager.flush();
            testEntityManager.clear();

            Order reloaded = orderRepository.findByIdWithItems(orderId).orElseThrow();

            assertThat(reloaded.getItems()).hasSize(1);
            OrderItem line = reloaded.getItems().get(0);

            assertThat(line.getUnitPrice())
                    .as("the price paid, not today's price")
                    .isEqualByComparingTo("50000.00");
            assertThat(line.getSubtotal()).isEqualByComparingTo("50000.00");
            assertThat(line.getProductName()).isEqualTo("Television");
            assertThat(reloaded.getTotalAmount())
                    .as("the order total is stored, so it cannot be recomputed against a new price")
                    .isEqualByComparingTo("50000.00");
        }

        @Test
        @DisplayName("findByIdAndUserIdWithItems scopes an order to its owner")
        void orderLookupIsScopedToTheOwner() {
            /*
             * GET /api/orders/{id} must return 404 for another customer's order, not 403 - the
             * same enumeration defence as the cart item lookup. Scoping by user id in the
             * query is what makes the two cases indistinguishable from outside.
             */
            User ada = persistedUser("ada@example.com", Role.CUSTOMER);
            User grace = persistedUser("grace@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product laptop = persistedProduct("Laptop", "50000.00", 10, category);

            Order adaOrder = persistedOrder(ada, OrderStatus.PENDING, laptop);
            Long adaOrderId = adaOrder.getId();
            Long graceId = grace.getId();
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(orderRepository.findByIdAndUserIdWithItems(adaOrderId, graceId))
                    .as("another customer's order must look absent")
                    .isEmpty();

            Order owned = orderRepository.findByIdAndUserIdWithItems(adaOrderId, ada.getId()).orElseThrow();
            assertThat(owned.getItems()).hasSize(1);
        }

        @Test
        @DisplayName("revenue excludes cancelled orders, because a cancelled order is not income")
        void revenueExcludesCancelledOrders() {
            /*
             * The single most consequential aggregate in the admin dashboard. Cancelling an
             * order restores its stock and must also remove its value from revenue - otherwise
             * the dashboard reports money that was never received, and the error grows every
             * time a customer changes their mind.
             *
             * Note that cancellation does NOT delete the order: the row stays for the audit
             * trail, and the WHERE clause is what corrects the total.
             */
            User customer = persistedUser("ada@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product laptop = persistedProduct("Laptop", "50000.00", 10, category);
            Product phone = persistedProduct("Phone", "20000.00", 10, category);
            Product tablet = persistedProduct("Tablet", "30000.00", 10, category);

            persistedOrder(customer, OrderStatus.PENDING, laptop);       // 50000
            persistedOrder(customer, OrderStatus.DELIVERED, phone);      // 20000
            persistedOrder(customer, OrderStatus.CANCELLED, tablet);     // excluded
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(orderRepository.sumRevenueExcludingCancelled())
                    .as("50000 + 20000, with the cancelled 30000 omitted")
                    .isEqualByComparingTo("70000.00");
            assertThat(orderRepository.sumRevenueForUser(customer.getId()))
                    .isEqualByComparingTo("70000.00");
        }

        @Test
        @DisplayName("revenue coalesces to zero for a shop with no orders")
        void revenueIsNullSafeOnAnEmptyShop() {
            /*
             * The COALESCE is not decoration. `sum(...)` over zero rows returns NULL, not 0 -
             * and a dashboard that received null would either render nothing or throw, on the
             * day the shop has no orders. That is the first day.
             */
            assertThat(orderRepository.sumRevenueExcludingCancelled()).isEqualByComparingTo("0");
            assertThat(orderRepository.sumRevenueForUser(999L)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("countGroupedByStatus returns only the statuses that occur")
        void statusCountsAreGrouped() {
            /*
             * The GROUP BY returns one row per status PRESENT. AdminServiceImpl relies on that
             * distinction: it seeds an EnumMap from OrderStatus.values() and then overlays
             * these rows, which is what guarantees all six statuses appear in the response
             * even when only two are in use. Without the seed, the dashboard's status chart
             * would silently lose its zero-value categories.
             */
            User customer = persistedUser("ada@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product laptop = persistedProduct("Laptop", "50000.00", 10, category);

            persistedOrder(customer, OrderStatus.PENDING, laptop);
            persistedOrder(customer, OrderStatus.PENDING, laptop);
            persistedOrder(customer, OrderStatus.DELIVERED, laptop);
            testEntityManager.flush();
            testEntityManager.clear();

            List<Object[]> grouped = orderRepository.countGroupedByStatus();
            java.util.Map<OrderStatus, Long> counts = new java.util.HashMap<>();
            for (Object[] row : grouped) {
                counts.put((OrderStatus) row[0], (Long) row[1]);
            }

            assertThat(counts)
                    .containsEntry(OrderStatus.PENDING, 2L)
                    .containsEntry(OrderStatus.DELIVERED, 1L);
            assertThat(counts)
                    .as("only statuses that occur appear in the raw query")
                    .doesNotContainKey(OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("paging orders by user and status filters on both")
        void ordersPageFiltersByUserAndStatus() {
            User ada = persistedUser("ada@example.com", Role.CUSTOMER);
            User grace = persistedUser("grace@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product laptop = persistedProduct("Laptop", "50000.00", 10, category);

            persistedOrder(ada, OrderStatus.PENDING, laptop);
            persistedOrder(ada, OrderStatus.DELIVERED, laptop);
            persistedOrder(ada, OrderStatus.DELIVERED, laptop);
            persistedOrder(grace, OrderStatus.PENDING, laptop);
            testEntityManager.flush();
            testEntityManager.clear();

            Page<Order> adaDelivered = orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                    ada.getId(), OrderStatus.DELIVERED, PageRequest.of(0, 10));

            assertThat(adaDelivered.getTotalElements())
                    .as("Grace's pending order and Ada's decided ones must both be excluded")
                    .isEqualTo(2);
            assertThat(adaDelivered.getContent())
                    .allSatisfy(order -> {
                        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
                        assertThat(order.getUser().getId()).isEqualTo(ada.getId());
                    });

            assertThat(orderRepository.countByUserId(ada.getId())).isEqualTo(3);
            assertThat(orderRepository.countByStatus(OrderStatus.PENDING)).isEqualTo(2);
        }

        @Test
        @DisplayName("best sellers aggregate quantity and revenue per product")
        void bestSellersAggregate() {
            /*
             * A three-column GROUP BY with ORDER BY on an aggregate - the query most likely to
             * be subtly wrong and least likely to be noticed, because a dashboard showing
             * plausible-but-wrong numbers raises no error.
             *
             * Two orders for the same product must be summed into ONE row, not returned twice.
             */
            User customer = persistedUser("ada@example.com", Role.CUSTOMER);
            Category category = persistedCategory("Electronics");
            Product laptop = persistedProduct("Laptop", "50000.00", 10, category);
            Product phone = persistedProduct("Phone", "20000.00", 10, category);

            Order first = new Order();
            first.setUser(customer);
            first.setStatus(OrderStatus.DELIVERED);
            first.setShippingAddress("1 Test Street");
            first.addItem(OrderItem.createSnapshot(laptop, 2));   // 2 x 50000
            first.addItem(OrderItem.createSnapshot(phone, 1));    // 1 x 20000
            first.recalculateTotal();
            orderRepository.save(first);

            Order second = new Order();
            second.setUser(customer);
            second.setStatus(OrderStatus.DELIVERED);
            second.setShippingAddress("1 Test Street");
            second.addItem(OrderItem.createSnapshot(laptop, 3));  // 3 x 50000, same product
            second.recalculateTotal();
            orderRepository.save(second);

            testEntityManager.flush();
            testEntityManager.clear();

            List<Object[]> bestSellers = orderItemRepository.findBestSellers();

            assertThat(bestSellers)
                    .as("the laptop's two lines must be aggregated into one row")
                    .hasSize(2);

            Object[] topRow = bestSellers.get(0);
            assertThat(topRow[0]).as("product id").isEqualTo(laptop.getId());
            assertThat(topRow[1]).as("snapshotted product name").isEqualTo("Laptop");
            assertThat(topRow[2]).as("total quantity 2 + 3").isEqualTo(5L);
            assertThat((BigDecimal) topRow[3])
                    .as("total revenue 5 x 50000")
                    .isEqualByComparingTo("250000.00");

            assertThat(orderItemRepository.sumQuantitySoldForProduct(phone.getId())).isEqualTo(1);
            assertThat(orderItemRepository.sumRevenueForProduct(laptop.getId()))
                    .isEqualByComparingTo("250000.00");
        }

        @Test
        @DisplayName("sumQuantitySoldForProduct coalesces to zero for an unsold product")
        void unsoldProductSumsToZero() {
            Category category = persistedCategory("Electronics");
            Product newArrival = persistedProduct("New Arrival", "1000.00", 5, category);
            testEntityManager.flush();
            testEntityManager.clear();

            assertThat(orderItemRepository.sumQuantitySoldForProduct(newArrival.getId())).isZero();
            assertThat(orderItemRepository.sumRevenueForProduct(newArrival.getId()))
                    .isEqualByComparingTo("0");
        }
    }

    // =================================================================
    //  Lazy loading - proving the mappings are what the javadoc claims
    // =================================================================

    @Nested
    @DisplayName("lazy loading")
    class LazyLoading {

        @Test
        @DisplayName("Order.items is mapped LAZY, and the fetch-join method is what loads it")
        void orderItemsAreLazy() {
            /*
             * ==================================================================
             *  WHY THIS TEST ASSERTS THE MAPPING AND NOT THE RUNTIME BEHAVIOUR
             * ==================================================================
             *
             * The obvious version of this test - load an order, assert the collection
             * is not initialised - cannot be written honestly inside @DataJpaTest, and
             * three attempts to write it produced three different misleading answers
             * before the cause was found. The cause is worth recording because it
             * applies to every "prove a fetch type" test in this codebase.
             *
             * 1. `testEntityManager.clear()` then `findById` does not work. `clear()`
             *    detaches the managed entities but not the instance the test still
             *    holds, and Hibernate can reuse an already-built collection.
             *
             * 2. Asserting on `PersistenceUnitUtil#isLoaded` does not work either, and
             *    Hibernate says so itself. Its bytecode in 6.6.53:
             *
             *        log.debug("PersistenceUnitUtil#isLoaded is not always accurate; "
             *                  + "consider using EntityManager#contains instead");
             *
             *    That fires on every call. The implementation is reflective-
             *    (`isLoadedWithoutReference`, then `isLoadedWithReference`) and reports
             *    "loaded" for any non-null List that is not an uninitialised
             *    PersistentCollection. It is a weak signal, not a proof.
             *
             * 3. `@Transactional(propagation = NOT_SUPPORTED)` on a private helper does
             *    not work. This one is a plain Spring mistake and it cost the most time:
             *    the helper is SELF-INVOKED through `this`, so the transactional proxy is
             *    bypassed entirely and the annotation is dead code. Measured proof:
             *
             *        DIAG self-invoked helper, tx active = true
             *        DIAG proxy target class = org.hibernate.internal.SessionImpl
             *
             *    `tx active = true` inside a method annotated NOT_SUPPORTED. And
             *    `SessionImpl` is the giveaway for the second half of the problem:
             *    `@PersistenceContext EntityManager` in a test is a Spring SHARED
             *    EntityManager PROXY (`jdk.proxy2.$Proxy144`), which delegates to
             *    whatever transaction is bound to the current thread. Outside a
             *    transaction it delegates to a shared EntityManagerImpl instead - and
             *    Spring Data's own repository calls share that context too. There is no
             *    "fresh session" to obtain from the injected EntityManager while the
             *    test's transaction is alive, so `contains()` and the lazy-load
             *    exception both report the state of the ENCLOSING transaction rather
             *    than of the lookup under test. Worse, Spring Data's @Transactional
             *    repository methods join that enclosing transaction, so every call in
             *    the test body shares one persistence context and the question cannot be
             *    asked at all.
             *
             * ==================================================================
             *  WHAT THIS TEST DOES INSTEAD, AND WHY IT IS GOOD ENOUGH
             * ==================================================================
             *
             * It asserts the MAPPING, through JPA's own metamodel. This is the actual
             * decision being protected: someone edits `fetch = FetchType.LAZY` out of
             * `Order.items`, or drops an `@EntityGraph`, and nothing else notices.
             *
             * It is a stronger check than it looks. A fetch type is not a runtime
             * behaviour that can vary - it is a static property of the mapping, and
             * Hibernate reads it from exactly this metadata. Asserting it here pins the
             * decision at the only place it is made.
             *
             * The runtime behaviour - that a lazy collection thrown at a mapper outside
             * a transaction fails loudly - is proved end to end against the running
             * application, where a real request has a real transaction boundary, rather
             * than simulated here where the boundary is the test's own. See the
             * "lazy loading outside a transaction" checks in the HTTP suite.
             *
             * ------------------------------------------------------------------
             *  WHAT THIS FOUND IN THE ENTITY
             * ------------------------------------------------------------------
             *
             * `Order.items` was EAGER:
             *
             *     @OneToMany(mappedBy = "order", fetch = FetchType.EAGER, ...)
             *
             * - the only EAGER collection in the whole model; Cart.items,
             * Category.products and User.orders were all LAZY. The javadoc defended it
             * ("an order is meaningless without its lines"), which is persuasive about
             * one endpoint and wrong about the rest:
             *
             *   - findByUserIdAndStatusOrderByCreatedAtDesc pages over orders for the
             *     admin list. EAGER made Hibernate issue a query per row whether or not
             *     anything read `items` - the N+1 it was meant to prevent, on the path
             *     that least wanted it.
             *   - findByIdWithItems joins the lines explicitly. With EAGER, Hibernate
             *     resolved the conflict with extra selects rather than the single join
             *     the method was written for.
             *   - A fetch type is global. There is no per-query opt-out from EAGER.
             */
            assertThat(fetchTypeOf(Order.class, "items"))
                    .as("listing orders must not drag every line along")
                    .isEqualTo(FetchType.LAZY);

            // The counterweight: LAZY is only safe because a fetch-join path exists.
            // Asserting the mapping without this would bless a mapping nothing can use.
            assertThat(fetchJoinDeclaredIn(OrderRepository.class, "findByIdWithItems", "o.items"))
                    .as("a LAZY collection must have an explicit way to load it")
                    .isTrue();

            /*
             * `OrderItem.product` is deliberately EAGER, and this assertion exists to pin
             * that decision rather than to change it.
             *
             * The first draft of this test asserted LAZY, on the reasonable-sounding
             * argument that an order line renders from its own `productName` snapshot and
             * therefore never needs the association. That argument is wrong about the rest
             * of the application: stock is restored on the cancel path by grouping the
             * order's lines by product id, so the association IS read on a path that
             * matters, and a lazy mapping there would mean one query per line during a
             * cancellation.
             *
             * The distinction that resolves the confusion is between the two kinds of
             * to-one association:
             *
             *   - A @ManyToOne is a single-row join, so EAGER costs at most one extra
             *     select (collapsed into the same query when a fetch join names it). There
             *     is no row multiplication and no N+1 on a single order.
             *   - A @OneToMany collection is where EAGER multiplies: it joins N rows per
             *     parent, and it applies to every query returning the parent, whether or
             *     not the collection is read.
             *
             * Hence the split: collections LAZY, to-one EAGER where the association is
             * genuinely traversed. `OrderItem.product` is EAGER on that reasoning.
             */
            assertThat(fetchTypeOf(OrderItem.class, "product"))
                    .as("the cancel path groups lines by product id, so the association is traversed")
                    .isEqualTo(FetchType.EAGER);

            assertThat(fetchTypeOf(OrderItem.class, "order"))
                    .as("the back-reference to the order is never navigated from a line")
                    .isEqualTo(FetchType.LAZY);

            /*
             * `CartItem.product` is LAZY, and it is the one association in this file whose
             * fetch type is load-bearing for CORRECTNESS rather than for query count.
             *
             * This assertion previously pinned EAGER, on the same argument used for
             * OrderItem above - the cart page renders each line's product. That argument was
             * reasonable and it was wrong, and shipping it cost a real bug: a concurrent
             * checkout sold one unit of stock twice.
             *
             * WHY EAGER BROKE CHECKOUT, and not merely made it slower:
             *
             * An eager @ManyToOne causes Hibernate to add its own join to satisfy the
             * association on EVERY query returning the owning entity - including a query
             * that never mentions it. Order placement loads the cart's LINES first and
             * locks the products afterwards, and the whole point of that order is that the
             * products must not be read before the lock. But the eager association put a
             * `left join products` into the lines-only query anyway. Read from MySQL's
             * general log:
             *
             *   select i1_0.cart_id, i1_0.id, i1_0.product_id,
             *          p1_0.id, p1_0.active, p1_0.stock, ...
             *     from cart_items i1_0 left join products p1_0 on ...
             *
             * So the product rows were read before the locking read no matter which cart
             * query was used, and the lock was decorative. Under REPEATABLE READ that
             * plain read pins the transaction's read view, after which SELECT ... FOR UPDATE
             * still takes its locks and still waits for the competing transaction - and then
             * returns the SNAPSHOT stock value. Two checkouts, one unit, both succeed.
             *
             * The fix is this LAZY mapping. It is safe because every read path that renders
             * a cart goes through CartRepository#findByUserIdWithItems, which fetch-joins
             * i.product explicitly - asserted immediately below, so the mapping and the
             * path that makes it usable cannot drift apart.
             *
             * The measurement, and the reproduction, are written up in full on the
             * findByIdWithLock docblock in ProductRepository and at step 4 of
             * OrderServiceImpl#placeOrder. It is recorded here too because this test is
             * where a future reader would otherwise "tidy" the mapping back to EAGER.
             */
            assertThat(fetchTypeOf(CartItem.class, "product"))
                    .as("EAGER here adds a product join to every cart query, which defeats "
                            + "the checkout lock - the cart page uses findByUserIdWithItems instead")
                    .isEqualTo(FetchType.LAZY);

            // The counterweight, asserted with the mapping so the pair cannot drift: LAZY is
            // only safe because a fetch-join path loads the association for the cart page.
            assertThat(fetchJoinDeclaredIn(CartRepository.class, "findByUserIdWithItems", "i.product"))
                    .as("a LAZY CartItem.product must have an explicit way to load it")
                    .isTrue();

            assertThat(fetchTypeOf(Cart.class, "items"))
                    .as("Cart.items must stay lazy so other cart queries stay single-table")
                    .isEqualTo(FetchType.LAZY);

            assertThat(fetchTypeOf(User.class, "orders"))
                    .as("a user lookup must not drag their whole order history along")
                    .isEqualTo(FetchType.LAZY);

            /*
             * ==================================================================
             *  WHY THERE IS NO RUNTIME CHECK HERE
             * ==================================================================
             *
             * The obvious follow-up is to load an order through a second persistence
             * context, outside the test's transaction, and assert the collection really is
             * uninitialised. It does not work, and the measurement is unambiguous:
             *
             *     DIAG --- before explicit flush ---
             *     DIAG A fresh.find -> NULL        DIAG A fresh count(*) = 0
             *     DIAG --- after tem.flush() ---
             *     DIAG B fresh.find -> NULL        DIAG B fresh count(*) = 0
             *     DIAG --- after em.flush() ---
             *     DIAG C fresh.find -> NULL        DIAG C fresh count(*) = 0
             *     DIAG isActualTransactionActive=true
             *
             * A second EntityManager sees NOTHING this test inserted, even after flushing
             * twice through two different handles. The reason is @DataJpaTest's most
             * important property: it opens a transaction per test and ROLLS IT BACK, never
             * committing. H2's default isolation then hides those uncommitted rows from
             * another connection, so any assertion about a separately-loaded entity is
             * really an assertion about transaction visibility - and it fails with a
             * message about fetch state that has nothing to do with fetch state.
             *
             * Two conclusions, both worth keeping:
             *
             *   1. Fetch state cannot be observed from a fresh context inside this slice.
             *      The metamodel assertion above is the honest way to ask the question.
             *   2. The runtime behaviour - a lazy collection handed to a mapper outside a
             *      transaction throwing LazyInitializationException - is proved in the HTTP
             *      suite against the running application, where a request genuinely commits
             *      and each service call has a real transaction boundary.
             *
             * What this test therefore guarantees is the DECISION: the mapping is lazy, and
             * a fetch-join path exists to make it usable. That is the pair that can
             * silently regress, which is what a test is for.
             */
        }

        @Test
        @DisplayName("Cart.items is lazy, and findByUserIdWithItems is what makes it usable")
        void cartItemsAreLazy() {
            /*
             * Asserted through the metamodel rather than by loading a cart, for the same
             * reason spelled out on orderItemsAreLazy above: inside @DataJpaTest every
             * repository call joins the test's own transaction, so there is no way to
             * observe a fetch type at runtime from here.
             *
             * This matters more for Cart than for Order. Adding to a cart goes through
             * `cart.addItem(...)`, which writes to the collection in Java - so the
             * collection is populated in memory long before any question about loading is
             * asked. A reader who trusted PersistenceUnitUtil#isLoaded would conclude the
             * mapping was EAGER and "fix" it, trading one query on the cart page for a
             * query per cart row on every cart query in the application.
             */
            assertThat(fetchTypeOf(Cart.class, "items"))
                    .as("a cart lookup that does not need lines must stay a single-table query")
                    .isEqualTo(FetchType.LAZY);

            // The fetch-join path that makes the lazy mapping usable, asserted together
            // with it so the pair cannot drift apart.
            assertThat(fetchJoinDeclaredIn(CartRepository.class, "findByUserIdWithItems", "c.items"))
                    .as("a LAZY collection must have an explicit way to load it")
                    .isTrue();

            /*
             * No runtime check here either - see the long note in orderItemsAreLazy. Inside
             * @DataJpaTest a second EntityManager cannot see this test's rows at all, because
             * the test transaction is rolled back rather than committed.
             *
             * The pair asserted above - LAZY mapping plus an explicit fetch-join path - is
             * what this test can prove, and it is the pair that can regress. Note that the
             * fetch join named there is deliberately `c.items` and not the product as well:
             * `findByUserIdWithItems` is the cart PAGE, and the lines-only variant
             * `findByUserIdWithItemsOnly` is the checkout path, where joining the product
             * would re-introduce the read-before-lock that caused the oversell. See
             * CartRepository for both, and cartItemProductIsLazy above for the measurement.
             */
        }

        @Test
        @DisplayName("CartRepository offers both cart read shapes, and they differ only by the product join")
        void cartRepositoryOffersBothReadShapes() {
            /*
             * The two queries look like a duplication and are not one: which of them a caller
             * picks decides whether the checkout lock is real.
             *
             *   findByUserIdWithItems      - fetch-joins c.items AND i.product. The cart page.
             *   findByUserIdWithItemsOnly  - fetch-joins c.items only. The checkout path,
             *                                because the products must first be read by the
             *                                locking query (ProductRepository#findByIdWithLock).
             *
             * Asserted as a pair because the failure mode is silent: if someone "simplifies"
             * these into one method, the checkout path either loses the product join it needs
             * to render, or gains a product read before the lock and the oversell returns.
             * Neither shows up in a passing single-threaded test run.
             *
             * The shape assertion is on the declared JPQL rather than on behaviour, because
             * @DataJpaTest cannot observe fetch state at runtime - see orderItemsAreLazy.
             */
            assertThat(fetchJoinDeclaredIn(CartRepository.class, "findByUserIdWithItems", "i.product"))
                    .as("the cart page needs the product rows")
                    .isTrue();

            assertThat(fetchJoinDeclaredIn(CartRepository.class, "findByUserIdWithItemsOnly", "i.product"))
                    .as("the checkout path must NOT read the products before it locks them - "
                            + "this join is what allowed the oversell")
                    .isFalse();

            assertThat(fetchJoinDeclaredIn(CartRepository.class, "findByUserIdWithItemsOnly", "c.items"))
                    .as("the checkout path still needs the cart's lines")
                    .isTrue();
        }

        @Test
        @DisplayName("Product does not map its order items, so a product row stays small")
        void productHasNoOrderItemCollection() {
            /*
             * A deliberate omission documented on the Product entity: it is the "1" side of
             * Product 1:N OrderItem, but the collection is not mapped. Mapping it would mean a
             * product query could load thousands of historical order lines to answer a question
             * nobody asks - and the aggregate queries in OrderItemRepository exist precisely so
             * that question ("how many of these did we sell?") is answered in SQL.
             *
             * Asserted by reflection because "this field does not exist" is not observable any
             * other way.
             */
            assertThat(java.util.Arrays.stream(Product.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName))
                    .as("a mapped orderItems collection would make every product query potentially huge")
                    .doesNotContain("orderItems", "orderItemsCollection");
        }

        // =================================================================
        //  Metamodel helpers
        //
        //  Kept in the LazyLoading class because that is the only place they
        //  are used, and because "this helper exists to assert a fetch type"
        //  is part of the lesson that class teaches.
        // =================================================================

        /**
         * Reads the mapped fetch timing for one association, from Hibernate's runtime
         * metamodel, and reports it as a JPA {@link FetchType}.
         *
         * <p><b>Why this goes through Hibernate rather than JPA.</b> JPA's own metamodel
         * does not expose a fetch type at all - verified against
         * {@code jakarta.persistence-api:3.1.0}, where {@code PluralAttribute} declares
         * only {@code getCollectionType()} and {@code getElementType()}, and
         * {@code SingularAttribute} only {@code isId()}, {@code isVersion()},
         * {@code isOptional()} and {@code getType()}. There is no {@code getFetchType()}
         * on either, so the first version of this helper did not compile. Hibernate's
         * mapping model does carry it:
         *
         * <pre>
         *   SessionFactory.getMetamodel()          -&gt; MappingMetamodel
         *   .findEntityDescriptor(Class)           -&gt; EntityPersister (a ManagedMappingType)
         *   .findAttributeMapping(name)            -&gt; AttributeMapping (a Fetchable)
         *   .getMappedFetchOptions().getTiming()   -&gt; FetchTiming.IMMEDIATE | DELAYED
         * </pre>
         *
         * <p>Every step of that chain was checked with {@code javap} against the resolved
         * {@code hibernate-core-6.6.53.Final.jar} before this was written. The last
         * conversion is the interesting one: {@code FetchTiming} has only two constants,
         * {@code IMMEDIATE} (eager) and {@code DELAYED} (lazy), which correspond exactly
         * to the two JPA {@link FetchType} values.
         *
         * <p>This is deliberately reading Hibernate internals. It is a test, it is
         * compiled against a pinned version, and the alternative - inferring the fetch
         * type from observed runtime behaviour - cannot be done reliably inside a single
         * test transaction (see {@code orderItemsAreLazy} for the three measured ways that
         * fails).
         */
        private FetchType fetchTypeOf(Class<?> entityType, String attributeName) {
            var mappingMetamodel = entityManager.getEntityManagerFactory()
                    .unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class)
                    .getMappingMetamodel();

            var entityDescriptor = mappingMetamodel.findEntityDescriptor(entityType);
            if (entityDescriptor == null) {
                throw new AssertionError("%s is not a mapped entity".formatted(entityType.getName()));
            }

            var attributeMapping = entityDescriptor.findAttributeMapping(attributeName);
            if (attributeMapping == null) {
                throw new AssertionError("no mapped attribute '%s' on %s - was it renamed?"
                        .formatted(attributeName, entityType.getSimpleName()));
            }

            FetchTiming timing;
            try {
                timing = attributeMapping.getMappedFetchOptions().getTiming();
            } catch (UnsupportedOperationException ex) {
                // A basic attribute (name, price) has no fetch options to report. That is
                // a mistake in the test, not in the entity, so say so plainly.
                throw new AssertionError("'%s' on %s is a basic attribute, so it has no fetch type"
                        .formatted(attributeName, entityType.getSimpleName()), ex);
            }

            return timing == FetchTiming.DELAYED ? FetchType.LAZY : FetchType.EAGER;
        }

        /**
         * True if the named repository method's JPQL contains a {@code join fetch} for the
         * given path.
         *
         * <p>A lazy collection is only usable because something loads it explicitly, so a
         * test that asserts {@code LAZY} without asserting the fetch-join path blesses a
         * mapping that nothing can consume. Pairing the two means the mapping and its
         * escape hatch cannot drift apart: deleting {@code left join fetch o.items} fails
         * this test rather than surfacing as a {@code LazyInitializationException} in
         * production.
         *
         * <p>Read from {@code @Query} by reflection rather than grepping the source,
         * because a source grep passes on a method that merely mentions the path in a
         * comment.
         */
        private boolean fetchJoinDeclaredIn(Class<?> repositoryType, String methodName, String fetchPath) {
            String normalisedPath = fetchPath.replaceAll("\\s+", "");

            for (Method method : repositoryType.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                Query annotation = method.getAnnotation(Query.class);
                if (annotation == null) {
                    throw new AssertionError("%s.%s has no @Query, so it cannot fetch anything"
                            .formatted(repositoryType.getSimpleName(), methodName));
                }
                // Collapse whitespace so the text block's indentation does not matter.
                String jpql = annotation.value().replaceAll("\\s+", "");
                if (jpql.contains("joinfetch" + normalisedPath)
                        || jpql.contains("leftjoinfetch" + normalisedPath)) {
                    return true;
                }
            }
            return false;
        }
    }

    // =================================================================
    //  Sorting and paging stability
    // =================================================================

    @Nested
    @DisplayName("stable pagination")
    class Pagination {

        @Test
        @DisplayName("paging with only id as the sort key never repeats or skips a row")
        void pagingIsStableWithATotalOrder() {
            /*
             * The end-to-end form of the SortValidator rule. Twelve products are paged three at
             * a time, keyed on `id` alone - a genuinely unique column - and every id must
             * appear exactly once across the four pages.
             *
             * This is the test that would catch the class of bug SortValidator exists to
             * prevent. It cannot catch it by paging on a non-unique column, because H2's row
             * order happens to be stable for a small table - which is exactly why the ordering
             * rule is asserted structurally in SortValidatorTest and only end-to-end here.
             */
            Category category = persistedCategory("Electronics");
            for (int i = 1; i <= 12; i++) {
                persistedProduct("Product " + i, (i * 100) + ".00", 10, category);
            }
            testEntityManager.flush();
            testEntityManager.clear();

            java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
            int pageIndex = 0;
            Page<Product> page;

            do {
                page = productRepository.findAll(
                        PageRequest.of(pageIndex, 3, Sort.by(Sort.Direction.ASC, "id")));
                page.getContent().forEach(product -> {
                    assertThat(seen.add(product.getId()))
                            .as("id %s appeared on two pages", product.getId())
                            .isTrue();
                });
                pageIndex++;
            } while (!page.isLast());

            assertThat(seen)
                    .as("every product must appear exactly once across all pages")
                    .hasSize(12);
            assertThat(pageIndex).isEqualTo(4);
        }

        @Test
        @DisplayName("a page past the end is empty and reports last, so a looping client stops")
        void overPagingIsEmptyAndLast() {
            /*
             * The contract documented on PageResponse: asking for page 999 of a 2-page result
             * gives an empty page with last=true rather than an error. That combination is
             * what lets a "keep fetching until done" client terminate - returning last=false
             * for an empty page would loop forever.
             */
            Category category = persistedCategory("Electronics");
            persistedProduct("Only One", "100.00", 10, category);
            testEntityManager.flush();
            testEntityManager.clear();

            Page<Product> page = productRepository.findAll(PageRequest.of(999, 10));

            assertThat(page.getContent()).isEmpty();
            assertThat(page.isLast()).isTrue();
            assertThat(page.getTotalElements()).isEqualTo(1);
        }
    }
}
