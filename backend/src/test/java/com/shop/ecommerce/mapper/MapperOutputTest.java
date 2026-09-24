package com.shop.ecommerce.mapper;

import com.shop.ecommerce.dto.cart.CartItemResponse;
import com.shop.ecommerce.dto.cart.CartResponse;
import com.shop.ecommerce.dto.category.CategoryResponse;
import com.shop.ecommerce.dto.order.OrderItemResponse;
import com.shop.ecommerce.dto.order.OrderResponse;
import com.shop.ecommerce.dto.product.ProductResponse;
import com.shop.ecommerce.dto.user.UserResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the generated mapper actually produces.
 *
 * <h2>Why a hand-written test for generated code</h2>
 *
 * <p>MapStruct generates the implementation at compile time, and a generated class that
 * compiles is not a generated class that is <em>right</em>. Its signature failure mode is
 * silence: renaming a source field, or adding a target component without a corresponding
 * source, produces a warning at build time and a DTO with a null field at runtime. A null
 * in a response is a {@code ₹NaN} on the storefront, not an exception in a log.
 *
 * <p>Two things in particular are worth asserting rather than assuming:
 *
 * <ul>
 *   <li><b>The unmapped-target warning.</b> This project configures
 *       {@code unmappedTargetPolicy = ERROR}, so a DTO component with no source <em>fails
 *       the build</em>. The test is the check that the policy is still in force and that
 *       nothing is annotated {@code @Mapping(target = ..., ignore = true)} to work around
 *       it quietly.</li>
 *   <li><b>The derived fields.</b> {@code CartResponse.subtotal}, {@code totalItems},
 *       {@code checkoutReady} and {@code CartItemResponse.subtotal} do not exist on the
 *       entity at all - they are computed by an expression or a default method. A
 *       computation is exactly the kind of thing that is correct when written and wrong
 *       after the next refactor.</li>
 * </ul>
 */
@DisplayName("EntityMapper - generated output")
class MapperOutputTest {

    /**
     * The generated implementation, obtained the way MapStruct intends.
     *
     * <p>Not autowired from a Spring context: this is a pure function of entity to DTO with
     * no dependencies, so starting an application to test it would make the test slow and
     * tell us nothing extra. If the mapper ever gained a dependency, this test would fail to
     * construct - which is the correct signal to reconsider that dependency.
     */
    private EntityMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = Mappers.getMapper(EntityMapper.class);
    }

    // -----------------------------------------------------------------
    //  Fixtures
    // -----------------------------------------------------------------

    private static Category category(Long id, String name) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setSlug(name.toLowerCase().replace(' ', '-'));
        category.setDescription("Description of " + name);
        category.setImageUrl("https://images.example.com/" + id + ".jpg");
        category.setActive(true);
        return category;
    }

    private static Product product(Long id, String name, String price, int stock, Category category) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setDescription("Description of " + name);
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setImageUrl("https://images.example.com/products/" + id + ".jpg");
        product.setActive(true);
        product.setCategory(category);
        return product;
    }

    private static User user(Long id, String name, String email, Role role) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setPhone("+91 9000000001");
        user.setAddress("1 Test Street");
        user.setRole(role);
        user.setProvider(AuthProvider.LOCAL);
        user.setEnabled(true);
        user.setPassword("$2a$10$thisIsAHashThatMustNeverAppearInAResponse");
        return user;
    }

    // =================================================================
    //  User
    // =================================================================

    @Nested
    @DisplayName("toUserResponse")
    class ToUser {

        @Test
        @DisplayName("maps the profile fields and carries no credential material")
        void mapsProfileWithoutCredentials() {
            User user = user(5L, "Store Administrator", "admin@shop.com", Role.ADMIN);

            UserResponse response = mapper.toUserResponse(user);

            assertThat(response.id()).isEqualTo(5L);
            assertThat(response.name()).isEqualTo("Store Administrator");
            assertThat(response.email()).isEqualTo("admin@shop.com");
            assertThat(response.phone()).isEqualTo("+91 9000000001");
            assertThat(response.address()).isEqualTo("1 Test Street");
            assertThat(response.role()).isEqualTo(Role.ADMIN);
            assertThat(response.provider()).isEqualTo(AuthProvider.LOCAL);
        }

        @Test
        @DisplayName("the record has no field that could carry the hash, so it cannot leak")
        void cannotLeakThePasswordHash() {
            /*
             * Belt and braces with DomainBoundaryTest: that one asserts the component list,
             * this one asserts the practical consequence. Even with a password set on the
             * entity - as it always would be in a real read - the response type offers no
             * place to put it.
             *
             * The reflection check on the rendered record is what makes this a real
             * assertion rather than a hopeful one: if someone added a `password` component,
             * the assertion below would find it whether or not the mapper populated it.
             */
            UserResponse response = mapper.toUserResponse(user(1L, "Ada", "ada@example.com", Role.CUSTOMER));

            assertThat(response.toString())
                    .as("the record's own toString must not contain the hash")
                    .doesNotContain("$2a$")
                    .doesNotContain("hash");

            assertThat(java.util.Arrays.stream(UserResponse.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName))
                    .doesNotContain("password", "passwordHash");
        }
    }

    // =================================================================
    //  Category and Product
    // =================================================================

    @Nested
    @DisplayName("toCategoryResponse")
    class ToCategory {

        @Test
        @DisplayName("productCount comes out as the mapper's default, to be replaced by the service")
        void productCountIsIgnoredByTheMapper() {
            /*
             * `@Mapping(target = "productCount", ignore = true)` - and this test pins WHY.
             *
             * Counting products is a database question, and the mapper is not allowed to ask
             * it. If it did, mapping a list of six categories would issue six COUNT queries -
             * the N+1 that CategoryServiceImpl deliberately avoids with one grouped query.
             *
             * The value that comes out is 0, not null: the component is a primitive `long`,
             * so an ignored target can only be zero. The documented contract is therefore
             * "every caller must overwrite this", and the assertion is written to match the
             * code rather than to match what would be tidier - a test that demanded null
             * would be asserting a design the record does not have.
             *
             * The real protection against a service forgetting is structural: `listAll()`
             * and the single-category read both funnel through withProductCount(s), and
             * CategoryServiceImplTest asserts the count is populated on the way out.
             */
            CategoryResponse response = mapper.toCategoryResponse(category(3L, "Electronics"));

            assertThat(response.id()).isEqualTo(3L);
            assertThat(response.name()).isEqualTo("Electronics");
            assertThat(response.slug()).isEqualTo("electronics");
            assertThat(response.productCount())
                    .as("the mapper must not invent a count - it has no way to know one")
                    .isZero();
        }

        @Test
        @DisplayName("the mapper's zero is the value the service must overwrite, not a safe default")
        void zeroIsIndistinguishableFromAGenuinelyEmptyCategory() {
            /*
             * Stating the hazard explicitly, because it is the reason productCount is
             * asserted end-to-end in CategoryServiceImplTest rather than trusted here.
             *
             * A mapper default of 0 is *plausible*: "Clothing (0 products)" looks like real
             * data. That is what makes it dangerous - a service that forgot to count would
             * produce a response no reviewer would question. The defence is not this test,
             * it is that CategoryServiceImpl always rebuilds the record through
             * withProductCount(...) before returning it.
             */
            CategoryResponse withNoProducts = mapper.toCategoryResponse(category(3L, "Empty Category"));
            CategoryResponse ignoredCount = mapper.toCategoryResponse(category(4L, "Electronics"));

            assertThat(withNoProducts.productCount())
                    .isEqualTo(ignoredCount.productCount())
                    .as("indistinguishable - which is why the service is what must set it");
        }

        @Test
        @DisplayName("a category with no products maps without querying anything")
        void mappingDoesNotTouchTheProductsCollection() {
            /*
             * The practical form of the assertion above: a category built without a
             * persistence context has a LAZY products collection that cannot be initialised.
             * Touching it would throw LazyInitializationException - so the fact that this
             * mapping succeeds is itself the proof that the mapper did not touch it.
             */
            Category category = category(4L, "Books");
            assertThat(category.getProducts()).isEmpty();   // never initialised

            assertThat(mapper.toCategoryResponse(category)).isNotNull();
        }
    }

    @Nested
    @DisplayName("toProductResponse")
    class ToProduct {

        @Test
        @DisplayName("nests the category as a summary rather than the full CategoryResponse")
        void nestsCategoryAsASummary() {
            /*
             * The recursion that would otherwise happen: CategoryResponse has a productCount,
             * every product has a category, so mapping a category inside a product would
             * raise the question "how many products are in this category?" - per product, on
             * a list of twenty. CategorySummaryResponse has no such field, which ends the
             * recursion structurally rather than by convention.
             */
            Product product = product(9L, "Premium Linen Shirt", "1499.00", 12, category(3L, "Clothing"));

            ProductResponse response = mapper.toProductResponse(product);

            assertThat(response.id()).isEqualTo(9L);
            assertThat(response.name()).isEqualTo("Premium Linen Shirt");
            assertThat(response.price()).isEqualByComparingTo("1499.00");
            assertThat(response.stock()).isEqualTo(12);
            assertThat(response.category()).isNotNull();
            assertThat(response.category().id()).isEqualTo(3L);
            assertThat(response.category().name()).isEqualTo("Clothing");
        }

        @Test
        @DisplayName("a product with no category maps without throwing")
        void nullCategoryIsTolerated() {
            /*
             * Nullable in the schema would be a design flaw - a product must belong to a
             * category - but "must" is the database's constraint, not the mapper's
             * precondition. A null here should produce a response with a null category, not
             * an NPE in the middle of list rendering, so a partially-migrated row degrades
             * to a missing badge instead of a 500 on the whole catalogue.
             */
            Product orphan = product(1L, "Uncategorised", "10.00", 1, null);

            ProductResponse response = mapper.toProductResponse(orphan);

            assertThat(response).isNotNull();
            assertThat(response.category()).isNull();
        }
    }

    // =================================================================
    //  Cart
    // =================================================================

    @Nested
    @DisplayName("toCartResponse")
    class ToCart {

        private Cart cartWith(Product... products) {
            Cart cart = new Cart();
            cart.setId(7L);
            int quantity = 1;
            for (Product product : products) {
                cart.addItem(product, quantity++);
            }
            return cart;
        }

        @Test
        @DisplayName("a fresh Cart starts empty")
        void aFreshCartHasNoLines() {
            /*
             * Not a mapper assertion - a precondition check, and it is here because of a real
             * trap in Cart's JPA mapping.
             *
             * `Cart.items` is initialised to `new ArrayList<>()`. That is the right choice
             * for a managed entity (Hibernate needs a mutable, non-null collection), but
             * because the field is initialised by the *declaration*, every Cart comes into
             * existence with that list already present. A test that assumed otherwise would
             * be testing a Cart that does not exist.
             *
             * Cheap to write, and it documents the assumption the other tests rely on.
             */
            assertThat(cartWith().getItems()).isEmpty();
            assertThat(cartWith().getItemCount()).isZero();
        }

        @Test
        @DisplayName("derived fields are computed: subtotal, totalItems, itemCount, empty")
        void derivedFieldsAreComputed() {
            /*
             * Four fields on CartResponse do not exist on Cart: they are MapStruct
             * expressions calling entity methods. A computation is exactly the kind of thing
             * that is right when written and wrong after a refactor, so each is asserted
             * against a number computed by hand.
             *
             * cartWith() assigns quantities 1, 2, 3... to successive products, so:
             *
             *   1 x 149.00  =  149.00
             *   2 x  50.00  =  100.00
             *                  -------
             *   subtotal    =  249.00    totalItems (units, the navbar badge) = 3
             *                            itemCount  (distinct lines)          = 2
             *
             * The two counts are deliberately different here: asserting them against the same
             * number would not detect a mapper that swapped totalItems for itemCount.
             */
            Product shirt = product(9L, "Linen Shirt", "149.00", 10, category(3L, "Clothing"));
            Product socks = product(10L, "Wool Socks", "50.00", 20, category(3L, "Clothing"));

            CartResponse response = mapper.toCartResponse(cartWith(shirt, socks));

            assertThat(response.subtotal()).isEqualByComparingTo("249.00");
            assertThat(response.totalItems())
                    .as("totalItems counts units, not lines - it is the navbar badge")
                    .isEqualTo(3);
            assertThat(response.itemCount())
                    .as("itemCount counts distinct lines")
                    .isEqualTo(2);
            assertThat(response.empty()).isFalse();
        }

        @Test
        @DisplayName("an empty cart computes zero without a null subtotal")
        void emptyCartIsZeroNotSumOfNothing() {
            /*
             * The classic stream-reduction bug: `items.stream().map(...).reduce(BigDecimal::add)`
             * returns null for an empty list, and an empty cart is the most common case of
             * all. Cart seeds the reduction with BigDecimal.ZERO, and this test is what keeps
             * that seed from being "simplified" away.
             *
             * A null subtotal would render as "₹NaN" in the cart badge on every fresh visit.
             */
            CartResponse response = mapper.toCartResponse(cartWith());

            assertThat(response.subtotal()).isNotNull().isEqualByComparingTo("0");
            assertThat(response.subtotal().scale())
                    .as("the frontend formats this to two decimal places")
                    .isEqualTo(0);
            assertThat(response.totalItems()).isZero();
            assertThat(response.itemCount()).isZero();
            assertThat(response.empty()).isTrue();
            assertThat(response.items()).isEmpty();
        }

        @Test
        @DisplayName("checkoutReady is false for an empty cart, though every line is trivially fine")
        void emptyCartIsNotReadyToCheckOut() {
            /*
             * The subtle rule in EntityMapper.isCheckoutReady. `allMatch` over an empty
             * stream returns TRUE - vacuously, every element satisfies the predicate. Left at
             * that, a fresh cart would report checkoutReady and the UI would enable a button
             * that leads straight to a 400 EMPTY_CART.
             *
             * The explicit `cart.isEmpty()` guard is what makes this correct, and this test
             * is the only thing that would notice if it were removed.
             */
            assertThat(mapper.toCartResponse(cartWith()).checkoutReady())
                    .as("checking out nothing is not a meaningful operation")
                    .isFalse();
        }

        @Test
        @DisplayName("checkoutReady is true when every line can be fulfilled")
        void fulfilableCartIsReadyToCheckOut() {
            Product shirt = product(9L, "Linen Shirt", "149.00", 10, category(3L, "Clothing"));
            assertThat(mapper.toCartResponse(cartWith(shirt)).checkoutReady()).isTrue();
        }

        @Test
        @DisplayName("checkoutReady is false when one line cannot be fulfilled")
        void oneUnfulfilableLineBlocksCheckout() {
            /*
             * The realistic scenario: a product sold out while it sat in someone's basket.
             * The customer must be told at the cart, not at checkout - which means the cart
             * has to report it, and the flag has to be a genuine allMatch rather than
             * "the first line is fine".
             */
            Product fine = product(9L, "Linen Shirt", "149.00", 10, category(3L, "Clothing"));
            Product soldOut = product(10L, "Wool Socks", "50.00", 0, category(3L, "Clothing"));

            assertThat(mapper.toCartResponse(cartWith(fine, soldOut)).checkoutReady()).isFalse();
        }

        @Test
        @DisplayName("checkoutReady is false when a line's product has been withdrawn")
        void withdrawnProductBlocksCheckout() {
            Product withdrawn = product(9L, "Linen Shirt", "149.00", 999, category(3L, "Clothing"));
            withdrawn.setActive(false);

            assertThat(mapper.toCartResponse(cartWith(withdrawn)).checkoutReady())
                    .as("999 in the warehouse does not help a product that is off sale")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("toCartItemResponse")
    class ToCartItem {

        @Test
        @DisplayName("reads the product's live name, image and price, not a snapshot")
        void usesTheLiveProductValues() {
            /*
             * The mirror image of OrderItem. A cart line has no snapshot of its own - the
             * response borrows the product's current name, image and price, so the customer
             * sees today's price. The snapshot begins at checkout.
             */
            Product shirt = product(9L, "Linen Shirt", "149.00", 10, category(3L, "Clothing"));
            CartItem item = new CartItem();
            item.setProduct(shirt);
            item.setQuantity(3);

            CartItemResponse response = mapper.toCartItemResponse(item);

            assertThat(response.productId()).isEqualTo(9L);
            assertThat(response.productName()).isEqualTo("Linen Shirt");
            assertThat(response.unitPrice()).isEqualByComparingTo("149.00");
            assertThat(response.subtotal()).isEqualByComparingTo("447.00");
            assertThat(response.availableStock()).isEqualTo(10);
            assertThat(response.available()).isTrue();
            assertThat(response.hasEnoughStock()).isTrue();
        }

        @Test
        @DisplayName("available and hasEnoughStock are distinct questions")
        void availabilityAndStockAreSeparateFlags() {
            /*
             * Two flags because the UI needs to say two different things:
             *   available = false  -> "This item is no longer sold"      (remove it)
             *   hasEnoughStock = false (and available = true)
             *                      -> "Only 2 left - reduce your quantity" (adjust it)
             *
             * Collapsing them into one boolean would give the customer one message where
             * there are two remedies, and they would pick the wrong one.
             */
            Product scarce = product(9L, "Linen Shirt", "149.00", 2, category(3L, "Clothing"));
            CartItem item = new CartItem();
            item.setProduct(scarce);
            item.setQuantity(5);

            CartItemResponse response = mapper.toCartItemResponse(item);

            assertThat(response.available())
                    .as("the product is still on sale, so it is 'available'")
                    .isTrue();
            assertThat(response.hasEnoughStock())
                    .as("but only 2 remain while 5 were requested")
                    .isFalse();
            assertThat(response.availableStock()).isEqualTo(2);
        }
    }

    // =================================================================
    //  Order
    // =================================================================

    @Nested
    @DisplayName("toOrderResponse")
    class ToOrder {

        private Order orderWith(OrderStatus status, OrderItem... items) {
            Order order = new Order();
            order.setId(2L);
            order.setUser(user(7L, "Ada Lovelace", "ada@example.com", Role.CUSTOMER));
            order.setStatus(status);
            order.setShippingAddress("12 Analytical Way, London");
            for (OrderItem item : items) {
                order.addItem(item);
            }
            order.recalculateTotal();
            return order;
        }

        @Test
        @DisplayName("formats the order number from the id, and flattens the customer's identity")
        void formatsOrderNumberAndFlattensCustomer() {
            /*
             * Two transformations worth pinning:
             *
             *  - `ord-2` becomes `ORD-000002`. The customer-facing reference is zero-padded
             *    so every order number has the same width and reads as a reference rather
             *    than as an id. Formatting it in the mapper (rather than storing it) means
             *    the pattern can change without migrating data.
             *
             *  - the customer's name and email are flattened onto the response rather than
             *    nesting a UserResponse. An admin order list does not need the customer's
             *    phone, address, role or provider - and sending the whole user object would
             *    also mean sending their role to anyone who can read the order.
             */
            OrderResponse response = mapper.toOrderResponse(orderWith(OrderStatus.PENDING));

            assertThat(response.orderNumber()).isEqualTo("ORD-000002");
            assertThat(response.userId()).isEqualTo(7L);
            assertThat(response.customerName()).isEqualTo("Ada Lovelace");
            assertThat(response.customerEmail()).isEqualTo("ada@example.com");
            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(response.shippingAddress()).isEqualTo("12 Analytical Way, London");
        }

        @Test
        @DisplayName("allowedNextStatuses mirrors the state machine, so the UI cannot offer an illegal move")
        void allowedNextStatusesMirrorsTheStateMachine() {
            /*
             * The client should not have to reimplement the state machine to decide which
             * buttons to show. Deriving the list from OrderStatus.allowedNext() on the way
             * out means there is one definition of the rule, and the admin UI can only ever
             * offer a move the server will accept.
             *
             * A hardcoded list in the frontend would drift the first time a status is added.
             */
            assertThat(mapper.toOrderResponse(orderWith(OrderStatus.PENDING)).allowedNextStatuses())
                    .containsExactlyInAnyOrder(OrderStatus.CONFIRMED, OrderStatus.CANCELLED);

            assertThat(mapper.toOrderResponse(orderWith(OrderStatus.CONFIRMED)).allowedNextStatuses())
                    .containsExactlyInAnyOrder(OrderStatus.PROCESSING, OrderStatus.CANCELLED);

            assertThat(mapper.toOrderResponse(orderWith(OrderStatus.DELIVERED)).allowedNextStatuses())
                    .as("a terminal order offers no moves at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("the status list is a mutable copy, not a view of the enum's internal set")
        void allowedNextStatusesIsADefensiveCopy() {
            /*
             * allowedNext() returns an EnumSet built fresh on each call, so this is safe
             * today - but the mapper wraps it in `new ArrayList<>(...)` rather than handing
             * it straight out. That is the difference between a response object that a
             * caller could mutate and one that could corrupt shared state if the enum ever
             * cached its sets. Asserting mutability pins the copy.
             */
            OrderResponse response = mapper.toOrderResponse(orderWith(OrderStatus.PENDING));

            response.allowedNextStatuses().clear();   // must not throw UnsupportedOperationException
            assertThat(response.allowedNextStatuses()).isEmpty();
        }

        @Test
        @DisplayName("the total comes from the order, and the lines keep their frozen prices")
        void totalsAndLineSnapshotsSurviveMapping() {
            Product tv = product(11L, "Television", "50000.00", 5, category(3L, "Electronics"));
            OrderItem line = OrderItem.createSnapshot(tv, 1);

            OrderResponse response = mapper.toOrderResponse(orderWith(OrderStatus.PENDING, line));

            assertThat(response.totalAmount()).isEqualByComparingTo("50000.00");
            assertThat(response.totalItems()).isEqualTo(1);
            assertThat(response.items()).hasSize(1);

            OrderItemResponse mappedLine = response.items().get(0);
            assertThat(mappedLine.productName())
                    .as("the receipt keeps the name as it was at purchase time")
                    .isEqualTo("Television");
            assertThat(mappedLine.unitPrice()).isEqualByComparingTo("50000.00");
            assertThat(mappedLine.subtotal()).isEqualByComparingTo("50000.00");
        }

        @Test
        @DisplayName("an order with no id yet does not throw while formatting the number")
        void unsavedOrderFormatsToNullNumber() {
            /*
             * formatOrderNumber returns null for a null id rather than throwing, and the
             * guard matters because MapStruct calls it during mapping - so an NPE here would
             * surface as a 500 while building a response, long after the business logic
             * succeeded. The status list is still produced, because it does not depend on id.
             */
            Order unsaved = new Order();
            unsaved.setStatus(OrderStatus.PENDING);

            OrderResponse response = mapper.toOrderResponse(unsaved);

            assertThat(response.orderNumber()).isNull();
            assertThat(response.allowedNextStatuses()).containsExactlyInAnyOrder(
                    OrderStatus.CONFIRMED, OrderStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("list mappings")
    class Lists {

        @Test
        @DisplayName("an empty list maps to an empty list, never to null")
        void emptyListMapsToEmptyList() {
            /*
             * MapStruct generates `if (list == null) return null;` followed by a new
             * ArrayList, so an empty input gives an empty output. Asserting it is cheap and
             * prevents a frontend `v-for` over null, which is one of the most common
             * "blank page, no error" causes in a JS client.
             */
            assertThat(mapper.toProductResponseList(List.of())).isNotNull().isEmpty();
            assertThat(mapper.toUserResponseList(List.of())).isNotNull().isEmpty();
            assertThat(mapper.toCategorySummaryResponseList(List.of())).isNotNull().isEmpty();
            assertThat(mapper.toOrderResponseList(List.of())).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("a list maps element-for-element, preserving order")
        void listOrderIsPreserved() {
            /*
             * Order preservation matters because the list arrives already sorted by the
             * repository. A mapper that reordered (say, by using a HashSet internally) would
             * silently undo the sorting work - and the pages would look shuffled with no
             * error anywhere.
             */
            List<Product> products = List.of(
                    product(9L, "A", "1.00", 1, category(3L, "C")),
                    product(10L, "B", "2.00", 1, category(3L, "C")),
                    product(11L, "C", "3.00", 1, category(3L, "C")));

            assertThat(mapper.toProductResponseList(products))
                    .extracting(ProductResponse::name)
                    .containsExactly("A", "B", "C");
        }
    }
}
