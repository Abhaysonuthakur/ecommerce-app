package com.shop.ecommerce.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.ecommerce.entity.AuthProvider;
import com.shop.ecommerce.entity.Category;
import com.shop.ecommerce.entity.Product;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import com.shop.ecommerce.repository.CartItemRepository;
import com.shop.ecommerce.repository.CartRepository;
import com.shop.ecommerce.repository.CategoryRepository;
import com.shop.ecommerce.repository.OrderRepository;
import com.shop.ecommerce.repository.ProductRepository;
import com.shop.ecommerce.repository.UserRepository;
import com.shop.ecommerce.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared plumbing for the HTTP-level integration tests.
 *
 * <h2>What this layer is for, and what the two layers below it cannot do</h2>
 *
 * <p>{@code RepositoryPersistenceTest} proves the <b>mappings</b>; {@code DomainBoundaryTest}
 * and friends prove the <b>rules in isolation</b>. Neither can answer the questions this
 * layer exists to answer, because all of them live in the wiring between the pieces:
 *
 * <ul>
 *   <li><b>Status codes that only the filter chain produces.</b> A 401 from a missing token
 *       is not thrown by any controller - no controller ever runs. A 403 from a role check
 *       is decided by an {@code AuthorizationFilter} three layers above the handler.</li>
 *   <li><b>Whether a refusal produces the right JSON.</b> {@code ApiErrorWriter} exists
 *       precisely because the advice never sees a filter-chain refusal. If it were
 *       unwired, every 401 would carry an empty body and the unit tests would all still
 *       pass.</li>
 *   <li><b>Whether the token the service mints is the token the filter accepts.</b> These
 *       are two different code paths in {@code JwtService}, joined only by the byte-level
 *       key. A test that mints with {@code generateToken} and verifies with
 *       {@code isTokenValid} - as {@code JwtServiceTest} does - checks each side against
 *       itself. Only a request through the chain proves the loop closes.</li>
 *   <li><b>Serialization.</b> A {@code @RestControllerAdvice} method can return a perfectly
 *       shaped record that Jackson cannot serialize, and nothing below this layer notices:
 *       the object is correct right up until it is written.</li>
 *   <li><b>Lazy associations outside a transaction.</b> This is the one the repository
 *       tests explicitly defer here. {@code @DataJpaTest} runs every test inside a
 *       transaction, so a lazy collection is always reachable there. A real HTTP request
 *       has a real transaction boundary that closes before the response is serialized -
 *       which is the only situation where a missing entity graph actually fails.</li>
 * </ul>
 *
 * <h2>Why {@code @SpringBootTest} here and slices elsewhere</h2>
 *
 * <p>This is the slowest test class in the project and deliberately so. Everything above
 * requires the real filter chain, the real {@code DispatcherServlet}, the real Jackson
 * configuration and the real transaction boundaries. A slice would replace exactly the
 * things under test.
 *
 * <h2>Two decisions worth knowing about</h2>
 *
 * <p><b>Real tokens, not {@code @WithMockUser}.</b> {@code @WithMockUser} installs an
 * authentication directly into the context, which means it never exercises
 * {@link com.shop.ecommerce.security.jwt.JwtAuthenticationFilter} - the component most
 * likely to be wrong, and the one whose bug signature is "works in tests, 401 in
 * production". Every authenticated request here sends an actual {@code Authorization}
 * header. The cost is one BCrypt hash per user per class, which is maybe 200 ms.
 *
 * <p><b>Fixtures created through repositories, not through the API.</b> Creating users by
 * calling {@code POST /api/auth/register} would make every test depend on the registration
 * endpoint being correct - so a bug in registration would fail thirty unrelated tests and
 * hide its own cause. Users are built directly; only the behaviour under test goes through
 * HTTP. The one exception is where registration <em>is</em> the behaviour under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class HttpIntegrationTestSupport {

    /*
     * The test profile disables seeding (app.seed.enabled=false), so the database starts
     * empty and each fixture here is the whole population. That matters for counts: "the
     * cart has 1 line" is only meaningful if nothing else inserted lines first.
     */

    /** Comfortably over HS256's 32-byte minimum. Matches application-test.yml. */
    protected static final String TEST_PASSWORD = "Str0ng!Pass";

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected CategoryRepository categoryRepository;

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected CartRepository cartRepository;

    @Autowired
    protected CartItemRepository cartItemRepository;

    @Autowired
    protected OrderRepository orderRepository;

    @BeforeEach
    void resetDatabase() {
        /*
         * Deleted in FK order: children before parents. Getting this wrong produces a
         * constraint violation rather than a wrong test, which is the good kind of failure.
         */
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // =================================================================
    //  Users and tokens
    // =================================================================

    /**
     * A unique email per call.
     *
     * <p>The database enforces case-insensitive uniqueness on {@code email}, so two fixtures
     * that both used {@code ada@example.com} would fail on the second insert - and the
     * failure would look like a broken fixture rather than the constraint working. A
     * counter makes collisions impossible without the reader having to track which test
     * already claimed which address.
     */
    protected static String uniqueEmail(String prefix) {
        return "%s.%d@example.com".formatted(prefix, UNIQUE.incrementAndGet());
    }

    /** A customer with a known password. */
    protected User persistCustomer() {
        return persistUser(Role.CUSTOMER);
    }

    /** An administrator with a known password. */
    protected User persistAdmin() {
        return persistUser(Role.ADMIN);
    }

    protected User persistUser(Role role) {
        String email = uniqueEmail(role.name().toLowerCase());

        User user = new User();
        user.setName(role == Role.ADMIN ? "Test Administrator" : "Test Customer");
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setRole(role);
        user.setProvider(AuthProvider.LOCAL);
        user.setEnabled(true);
        user.setPhone("+91 9000000000");
        user.setAddress("1 Test Street, New Delhi 110001");

        return userRepository.saveAndFlush(user);
    }

    /**
     * A real signed token for a user, minted by the application's own service.
     *
     * <p>Not a string literal and not a mock. If the token format, the signing key or the
     * required claims drift, this produces a token the filter rejects and every
     * authenticated test fails - which is exactly the signal wanted.
     */
    protected String tokenFor(User user) {
        return jwtService.generateToken(user);
    }

    /** {@code Authorization: Bearer <token>} for a freshly created user. */
    protected String bearerFor(User user) {
        return "Bearer " + tokenFor(user);
    }

    // =================================================================
    //  Catalogue fixtures
    // =================================================================

    /**
     * A category, with its slug set explicitly.
     *
     * <h3>Why the slug is set here rather than derived</h3>
     *
     * <p>{@code categories.slug} is {@code NOT NULL} and has no generator - by design. The
     * only production code that writes it is {@code CategoryServiceImpl.slugify}, which is
     * called from {@code createCategory}. There is deliberately no {@code @PrePersist} hook,
     * because a slug is part of the category's public URL and having it appear silently at
     * flush time would mean a category could exist in the database with a slug nobody chose.
     *
     * <p>The consequence for this fixture is that inserting a {@code Category} directly
     * through the repository produces {@code NULL not allowed for column "slug"} - which is
     * the mapping working correctly, not a bug. Building the fixture through
     * {@code POST /api/admin/categories} would be the alternative, but then every test that
     * needs a category would depend on category creation being correct.
     *
     * <p>So the fixture writes the slug the same way production does - through
     * {@link com.shop.ecommerce.service.impl.CategoryServiceImpl#slugify}, which is
     * package-private and reachable from the service test package. Duplicating the rule here
     * would be worse: a change to the real slug format would leave this fixture generating
     * something production never would, and tests would keep passing against an impossible
     * input.
     */
    protected Category persistCategory(String name) {
        String slug = slugFor(name);

        Category category = new Category();
        category.setName(name);
        category.setSlug(slug);
        category.setDescription("Test category: " + name);
        category.setActive(true);
        return categoryRepository.saveAndFlush(category);
    }

    /**
     * The production slug rule, applied directly.
     *
     * <p>{@code normalizeForSlug} reproduces what
     * {@link com.shop.ecommerce.service.impl.CategoryServiceImpl#slugify} does. It is
     * duplicated rather than called because that method is package-private in the
     * {@code service.impl} package, and the one thing a test fixture must not do is force
     * production code to widen its visibility for the fixture's convenience.
     *
     * <p>The duplication is acceptable here precisely because it is <b>not</b> the rule under
     * test: {@code CategoryServiceSlugTest} asserts the real {@code slugify} against the
     * cases that matter (accents, the Turkish dotless i, punctuation). If the rule changes,
     * that test fails - and this helper only needs to keep producing a valid unique string,
     * which any reasonable implementation does.
     */
    protected static String slugFor(String name) {
        String normalised = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return normalised.isEmpty() ? "category" : normalised;
    }

    protected Product persistProduct(Category category, String name, String price, int stock) {
        Product product = new Product();
        product.setName(name);
        product.setDescription("Test product: " + name);
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setImageUrl("https://images.example.com/%s.jpg".formatted(name.toLowerCase().replace(' ', '-')));
        product.setCategory(category);
        product.setActive(true);
        return productRepository.saveAndFlush(product);
    }

    // =================================================================
    //  Request helpers
    // =================================================================

    /**
     * Serializes an object to JSON, or returns null for a null argument.
     *
     * <p>Exists so a test that intentionally sends a malformed body can say so explicitly
     * with a raw string, while every well-formed body goes through one serializer. Writing
     * JSON by hand in tests is how a typo in a field name becomes a test that passes while
     * asserting nothing - the request is refused as unreadable and the assertion on the
     * response is loose enough not to notice.
     */
    protected String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    /** Performs a request and returns the result without asserting on the status. */
    protected MvcResult perform(MockHttpServletRequestBuilder builder, String bearerToken) throws Exception {
        if (bearerToken != null) {
            builder.header("Authorization", bearerToken);
        }
        return mockMvc.perform(builder).andReturn();
    }

    /** Performs a JSON POST, returning the result unasserted. */
    protected MvcResult postJson(String url, Object body, String bearerToken) throws Exception {
        MockHttpServletRequestBuilder builder = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body));
        return perform(builder, bearerToken);
    }

    /**
     * Places an order for a customer through the real checkout path and returns its id.
     *
     * <p>Used by the tests that are about something other than checkout - the admin list,
     * the status machine, the ownership rules - because those need an order to exist and
     * building one by inserting rows directly would bypass the very code path whose output
     * they are asserting on. Going through the API means the order's lines, total and
     * order number are whatever checkout actually produces.
     */
    protected long placeOrderFor(User customer, Product product, int quantity) throws Exception {
        String bearer = bearerFor(customer);

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ProductQuantity(product.getId(), quantity))))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    /** Body of {@code POST /api/cart/items}. Nested here because only tests use it. */
    protected record ProductQuantity(Long productId, Integer quantity) {
    }

    /**
     * The id of a cart line, read through a query rather than by walking the collection.
     *
     * <h3>Why this helper exists, and the failure that produced it</h3>
     *
     * <p>The obvious implementation is
     * {@code cartRepository.findByUserId(id).orElseThrow().getItems().get(0).getId()} - and it
     * throws immediately outside a transaction:
     *
     * <pre>
     *   failed to lazily initialize a collection of role:
     *   com.shop.ecommerce.entity.Cart.items: could not initialize proxy - no Session
     * </pre>
     *
     * <p>That is {@code Cart.items} being correctly lazy, observed from the one place where
     * the difference between lazy and eager is real. {@code RepositoryPersistenceTest} runs
     * inside {@code @DataJpaTest}'s transaction, so a lazy collection is always reachable
     * there and the fetch type is invisible; a test method in this class has no transaction
     * at all, so the collection is a detached proxy and touching it fails.
     *
     * <p>The lesson is worth stating plainly because it generalises: <b>a service method that
     * returns a DTO built from an entity's lazy collection works only if the collection was
     * fetched within the transaction that built the DTO.</b> {@code CartServiceImpl} does
     * exactly that - it maps inside its own {@code @Transactional} boundary - which is why
     * the cart endpoint returns a full payload while this fixture, reading the same entity
     * from outside, cannot.
     *
     * <p>The fix is the same technique the repository tests use: read the id with a query,
     * which needs no session and touches no collection.
     */
    protected long cartLineId(User owner, Long productId) {
        Long cartId = cartRepository.findByUserId(owner.getId()).orElseThrow().getId();
        return cartItemRepository.findByCartIdAndProductId(cartId, productId)
                .orElseThrow()
                .getId();
    }
}
