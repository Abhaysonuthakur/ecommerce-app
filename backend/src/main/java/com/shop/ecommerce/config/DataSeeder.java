package com.shop.ecommerce.config;

import com.shop.ecommerce.entity.AuthProvider;
import com.shop.ecommerce.entity.Category;
import com.shop.ecommerce.entity.Product;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import com.shop.ecommerce.repository.CategoryRepository;
import com.shop.ecommerce.repository.ProductRepository;
import com.shop.ecommerce.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Seeds demo data on first run, so the application is usable immediately.
 *
 * <h2>Why this is safe to leave in, and what makes it safe</h2>
 *
 * <p>A seeder that inserts an admin with a known password is a vulnerability waiting for a
 * deployment mistake: ship it to production and anyone who reads the repository owns the
 * store. Four things make this one acceptable:
 *
 * <ol>
 *   <li><b>It is idempotent.</b> Every insert is guarded by an existence check, so restarting
 *       the application does not duplicate data - and, more importantly, <b>it does not
 *       reset a password an admin has already changed.</b> A seeder that overwrote the admin
 *       password on every boot would silently undo a rotation.</li>
 *   <li><b>The credentials are printed, not hidden.</b> A developer who cannot find the
 *       admin password finds something else: a hardcoded one. Printing it once at startup is
 *       the honest version.</li>
 *   <li><b>It refuses to touch a database that already has users.</b> The guard is not "has
 *       the admin row gone missing" but "is this database already in use" - so a production
 *       database can never have a demo admin inserted into it by a restart.</li>
 *   <li><b>Its password is flagged as demo-only</b> in both the log line and the README.</li>
 * </ol>
 *
 * <p>An alternative would be a {@code @Profile("dev")} annotation, and that is the right
 * answer for a shop. It is not used here because the project is a learning artefact and a
 * fresh clone should run with something to look at - but the guard in
 * {@link #run} provides most of the same protection, and the trade-off is stated rather than
 * glossed over.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    /** Demo-only credential. See the class javadoc - never reuse this anywhere real. */
    private static final String DEMO_ADMIN_EMAIL = "admin@shop.com";
    private static final String DEMO_ADMIN_PASSWORD = "Admin@123";
    private static final String DEMO_CUSTOMER_EMAIL = "customer@shop.com";
    private static final String DEMO_CUSTOMER_PASSWORD = "Customer@123";

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Whether to seed at all.
     *
     * <p>Defaults to true so a developer running the application locally gets demo data
     * without configuring anything. The test profile sets it to {@code false}, and the
     * reason is not tidiness: a full-context test ({@code @SpringBootTest}) runs
     * {@link ApplicationRunner}s, so the seeder would insert an admin, six categories and
     * nineteen products <em>underneath</em> a test that believes it is working with a clean
     * database. Every count assertion would then be wrong in a way that depends on how many
     * other tests had run first.
     *
     * <p>The user-count guard below already protects a real deployment; this flag protects
     * the test suite's ability to control its own fixtures, which is a different problem.
     */
    private final boolean seedEnabled;

    public DataSeeder(UserRepository userRepository,
                      CategoryRepository categoryRepository,
                      ProductRepository productRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${app.seed.enabled:true}") boolean seedEnabled) {
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedEnabled = seedEnabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("Demo seeding is disabled (app.seed.enabled=false) - no demo data was inserted");
            return;
        }

        /*
         * The safety guard, and the reason it is checked on user count rather than on the
         * demo admin's existence.
         *
         * If this asked "does admin@shop.com exist?", a production database that happened to
         * have no such user would get a demo admin inserted on the next restart - with a
         * password published in the repository. Asking "does this database have any users at
         * all?" means a database in real use is never touched, regardless of which accounts
         * it holds.
         */
        if (userRepository.count() > 0) {
            log.debug("Database already seeded ({} users) - skipping demo data", userRepository.count());
            return;
        }

        log.info("Empty database detected - seeding demo data");
        seedUsers();
        seedCatalogue();
        logDemoCredentials();
    }

    // -----------------------------------------------------------------
    //  Users
    // -----------------------------------------------------------------

    private void seedUsers() {
        User admin = new User();
        admin.setEmail(DEMO_ADMIN_EMAIL);
        admin.setName("Store Administrator");
        admin.setPhone("+91 9000000001");
        admin.setAddress("1 Admin Plaza, New Delhi 110001");
        admin.setRole(Role.ADMIN);
        admin.setProvider(AuthProvider.LOCAL);
        admin.setEnabled(true);
        admin.setPassword(passwordEncoder.encode(DEMO_ADMIN_PASSWORD));
        userRepository.save(admin);

        User customer = new User();
        customer.setEmail(DEMO_CUSTOMER_EMAIL);
        customer.setName("Demo Customer");
        customer.setPhone("+91 9000000002");
        customer.setAddress("42 Customer Street, New Delhi 110002");
        customer.setRole(Role.CUSTOMER);
        customer.setProvider(AuthProvider.LOCAL);
        customer.setEnabled(true);
        customer.setPassword(passwordEncoder.encode(DEMO_CUSTOMER_PASSWORD));
        userRepository.save(customer);
    }

    // -----------------------------------------------------------------
    //  Catalogue
    // -----------------------------------------------------------------

    /**
     * Six categories with a handful of products each.
     *
     * <p>Chosen over a single "Misc" category and three products because the frontend needs
     * something that actually exercises it: enough products to paginate, prices spread wide
     * enough for the price filter to return different sets, and at least one product with
     * low stock so the dashboard's low-stock badge is not zero.
     *
     * <p>Prices are in INR and image URLs point at a public placeholder service, so the grid
     * renders without any uploaded assets. The 3D viewer in the frontend works from these
     * same records - it generates geometry rather than loading a model file, so no demo asset
     * is required for it either.
     */
    private void seedCatalogue() {
        Category electronics = category("Electronics", "electronics",
                "Phones, laptops, audio and everything powered");
        Category clothing = category("Clothing", "clothing",
                "Everyday wear, formal wear and outerwear");
        Category books = category("Books", "books",
                "Technical, fiction and reference titles");
        Category home = category("Home & Kitchen", "home-kitchen",
                "Furniture, cookware and household essentials");
        Category sports = category("Sports & Outdoors", "sports-outdoors",
                "Fitness, camping and outdoor gear");
        Category accessories = category("Accessories", "accessories",
                "Bags, watches and small leather goods");

        // --- Electronics -------------------------------------------
        product("Aurora 14 Ultrabook", electronics, "2299.00", 12,
                "A 14-inch aluminium laptop with a 12-hour battery and a 2.8K display.",
                "https://picsum.photos/seed/ultrabook/800/800");
        product("Pulse Wireless Earbuds", electronics, "349.00", 48,
                "Active noise cancellation, 30-hour total battery with the case.",
                "https://picsum.photos/seed/earbuds/800/800");
        product("Nimbus Bluetooth Speaker", electronics, "189.00", 7,
                "Waterproof portable speaker with 20 hours of playback.",
                "https://picsum.photos/seed/speaker/800/800");
        product("Vertex 27\" Monitor", electronics, "1299.00", 5,
                "27-inch 4K IPS panel with USB-C power delivery.",
                "https://picsum.photos/seed/monitor/800/800");

        // --- Clothing ----------------------------------------------
        product("Premium Linen Shirt", clothing, "149.00", 34,
                "Breathable 100% linen, tailored fit. Machine washable.",
                "https://picsum.photos/seed/linenshirt/800/800");
        product("Merino Wool Sweater", clothing, "219.00", 18,
                "Fine-gauge merino, temperature regulating and machine washable.",
                "https://picsum.photos/seed/sweater/800/800");
        product("Water-Resistant Field Jacket", clothing, "389.00", 9,
                "Four-pocket waxed cotton jacket with a quilted lining.",
                "https://picsum.photos/seed/jacket/800/800");

        // --- Books -------------------------------------------------
        product("Designing Data-Intensive Applications", books, "89.00", 25,
                "The definitive guide to the trade-offs behind distributed systems.",
                "https://picsum.photos/seed/ddia/800/800");
        product("Clean Code", books, "59.00", 40,
                "A handbook of agile software craftsmanship.",
                "https://picsum.photos/seed/cleancode/800/800");
        product("Effective Java, Third Edition", books, "79.00", 6,
                "Ninety items covering the parts of Java that are easy to get subtly wrong.",
                "https://picsum.photos/seed/effectivejava/800/800");

        // --- Home & Kitchen ----------------------------------------
        product("Ceramic Pour-Over Set", home, "79.00", 22,
                "Hand-glazed dripper, carafe and reusable stainless filter.",
                "https://picsum.photos/seed/pourover/800/800");
        product("Cast Iron Skillet, 12\"", home, "119.00", 15,
                "Pre-seasoned, oven-safe to 260 degrees, and improves with use.",
                "https://picsum.photos/seed/skillet/800/800");
        product("Solid Oak Side Table", home, "449.00", 4,
                "Solid white oak with a hand-rubbed oil finish.",
                "https://picsum.photos/seed/sidetable/800/800");

        // --- Sports & Outdoors -------------------------------------
        product("Trail Running Shoes", sports, "279.00", 30,
                "Grippy lugged outsole with a rock plate for technical descents.",
                "https://picsum.photos/seed/trailshoes/800/800");
        product("2-Person Trekking Tent", sports, "699.00", 8,
                "Four-season, freestanding, and packs to 4.2 kg.",
                "https://picsum.photos/seed/tent/800/800");
        product("Adjustable Dumbbell Set", sports, "899.00", 11,
                "Two dumbbells, 5 to 52 kg per hand, dial-adjustable.",
                "https://picsum.photos/seed/dumbbells/800/800");

        // --- Accessories -------------------------------------------
        product("Leather Weekender Bag", accessories, "529.00", 13,
                "Full-grain leather with a cotton twill lining and brass hardware.",
                "https://picsum.photos/seed/weekender/800/800");
        product("Automatic Field Watch", accessories, "1199.00", 6,
                "38 mm automatic movement with a sapphire crystal and 100 m water resistance.",
                "https://picsum.photos/seed/fieldwatch/800/800");
        product("Minimalist Card Wallet", accessories, "69.00", 60,
                "Holds six cards and folded notes in 2 mm of leather.",
                "https://picsum.photos/seed/cardwallet/800/800");

        log.info("Seeded {} categories and {} products",
                categoryRepository.count(), productRepository.count());
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private Category category(String name, String slug, String description) {
        // Idempotent per entity, so a partially-seeded database (a crash mid-run) heals
        // rather than duplicating. The user count guard above covers the common case; this
        // covers the uncommon one.
        return categoryRepository.findBySlug(slug).orElseGet(() -> {
            Category category = new Category();
            category.setName(name);
            category.setSlug(slug);
            category.setDescription(description);
            category.setImageUrl("https://picsum.photos/seed/" + slug + "/1200/600");
            category.setActive(true);
            return categoryRepository.save(category);
        });
    }

    private void product(String name, Category category, String price, int stock,
                         String description, String imageUrl) {
        if (productRepository.findByNameIgnoreCase(name).isPresent()) {
            return;
        }
        Product product = new Product();
        product.setName(name);
        product.setCategory(category);
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setDescription(description);
        product.setImageUrl(imageUrl);
        product.setActive(true);
        productRepository.save(product);
    }

    /**
     * Prints the demo credentials, once, at INFO.
     *
     * <p>A seeder whose credentials are only in the README means a developer who skipped the
     * README will hardcode a user into the database by hand, and then that hand-made account
     * is the one that ends up in a demo. Printing them puts the real credentials in front of
     * whoever is running the application.
     *
     * <p>Deliberately at INFO rather than DEBUG: DEBUG lines are invisible in a default run,
     * and this line's entire purpose is to be seen.
     *
     * <h3>Why the interpolation is nested the way it is</h3>
     *
     * <p>SLF4J substitutes {@code {}} placeholders from the arguments passed to the log call.
     * An earlier version of this method called {@code "…{}…".formatted(email, password)} and
     * passed the result to {@code log.info(String)} with no arguments - so the placeholders
     * were filled by {@link String#formatted} but the boxed box characters were built from a
     * text block whose {@code {}} markers SLF4J still tried to resolve, and the credentials
     * printed as literal {@code {}}. The fix is to format the box first and log the finished
     * string, which is what the {@link String#formatted} call below does - with the box's own
     * {@code {}} already consumed.
     */
    private void logDemoCredentials() {
        String boxed = """
                ┌──────────────────────────────────────────────────────────────┐
                │  DEMO DATA SEEDED - development credentials only              │
                ├──────────────────────────────────────────────────────────────┤
                │  Admin    : %s / %s
                │  Customer : %s / %s
                ├──────────────────────────────────────────────────────────────┤
                │  Change these before any non-local deployment.                │
                │  The seeder never runs again on a database that has users.    │
                └──────────────────────────────────────────────────────────────┘
                """.formatted(
                DEMO_ADMIN_EMAIL, DEMO_ADMIN_PASSWORD,
                DEMO_CUSTOMER_EMAIL, DEMO_CUSTOMER_PASSWORD);

        // Logged as a finished string: no `{}` remain for SLF4J to substitute.
        log.info("\n{}", boxed);
    }
}
