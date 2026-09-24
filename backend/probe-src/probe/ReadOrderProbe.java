package probe;

import com.shop.ecommerce.entity.Cart;
import com.shop.ecommerce.entity.CartItem;
import com.shop.ecommerce.entity.Category;
import com.shop.ecommerce.entity.Product;
import com.shop.ecommerce.repository.CartRepository;
import com.shop.ecommerce.repository.CategoryRepository;
import com.shop.ecommerce.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * IS THE LOCKING READ THE TRANSACTION'S FIRST READ?
 *
 * <h2>What this checks and why it is the important one</h2>
 *
 * <p>The oversell in this application has one cause, and it is not the database, the driver,
 * the connection pool, the annotation shape or the lock itself. It is <b>read ordering</b>:
 *
 * <p>MySQL's REPEATABLE READ creates the transaction's read view at its first <b>plain</em>
 * read. A {@code SELECT ... FOR UPDATE} bypasses that view and sees the committed row - but
 * only while no view exists yet. Once a plain read has pinned one, the locking read still
 * takes its locks and still waits for a competing transaction, <b>and then returns the
 * snapshot values anyway</b>.
 *
 * <p>So the invariant this application depends on is: <b>nothing may read a product row
 * before the locking read that guards it.</b> That invariant is invisible in code review -
 * an innocent-looking eager association or a fetch join is enough to break it - so it needs
 * a test.
 *
 * <p>This probe is that check, run against the real repository methods:
 *
 * <pre>
 *   check 1: load the cart the way order placement used to   (fetch join on products)
 *            -> did a product select run BEFORE the lock?
 *   check 2: load the cart the way order placement does now  (lines only, FK ids)
 *            -> is the FOR UPDATE really the first product read?
 * </pre>
 *
 * <p>It reads MySQL's general log, which is the only source that shows what the server
 * actually executed and in what order. Hibernate's SQL log shows what the ORM sent, and it
 * interleaves threads in a way that reads as sequential.
 *
 * <p>Run it with the general log already on:
 * <pre>
 *   SET GLOBAL general_log = ON;
 *   java -cp ... -Dspring.profiles.active=mysql-it probe.ReadOrderProbe
 * </pre>
 */
@SpringBootApplication(scanBasePackages = "com.shop.ecommerce")
public class ReadOrderProbe {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ReadOrderProbe.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setDefaultProperties(java.util.Map.of("server.port", "-1"));

        try (ConfigurableApplicationContext ctx = app.run(args)) {
            ctx.getBean(Runner.class).runProbe();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Component
    public static class Runner {

        @Autowired private ProductRepository productRepository;
        @Autowired private CategoryRepository categoryRepository;
        @Autowired private CartRepository cartRepository;
        @Autowired private com.shop.ecommerce.repository.UserRepository userRepository;
        @Autowired private Ordering ordering;

        private Long userId;

        public void runProbe() {
            Long productId = setUp();
            userId = ordering.createUserWithCart(productId);

            System.out.println();
            System.out.println("---------------------------------------------------------");
            System.out.println("CHECK 1: the OLD cart read (fetch join on products)");
            System.out.println("---------------------------------------------------------");
            Long cartId1 = ordering.createCartFor(userId, productId);
            ordering.oldShape(userId);
            System.out.println();

            System.out.println("---------------------------------------------------------");
            System.out.println("CHECK 2: the NEW cart read (lines only, product ids from the FK)");
            System.out.println("---------------------------------------------------------");
            ordering.refillCart(userId, productId);
            ordering.newShape(userId);
            System.out.println();
            System.out.println("CHECK 3: the whole fixed placeOrder path, end to end");
            System.out.println("---------------------------------------------------------");
            ordering.refillCart(userId, productId);
            ordering.fixedOrderPath(userId);
            System.out.println();
            System.out.println("Read the general log for the statements each check issued, in order.");
            System.out.println("The rule: in CHECK 2 the 'for update' must come BEFORE any");
            System.out.println("plain 'select ... from products'.");
        }

        private Long setUp() {
            cartRepository.deleteAll();
            productRepository.deleteAll();
            categoryRepository.deleteAll();
            userRepository.deleteAll();
            Category c = new Category();
            c.setName("ReadOrder");
            c.setSlug("read-order");
            c.setActive(true);
            categoryRepository.saveAndFlush(c);

            Product p = new Product();
            p.setName("Ordered");
            p.setDescription("x");
            p.setPrice(new BigDecimal("10.00"));
            p.setStock(5);
            p.setImageUrl("https://images.example.com/x.jpg");
            p.setCategory(c);
            p.setActive(true);
            productRepository.saveAndFlush(p);
            return p.getId();
        }
    }

    @Component
    public static class Ordering {

        @Autowired private ProductRepository productRepository;
        @Autowired private CartRepository cartRepository;
        @Autowired private com.shop.ecommerce.repository.UserRepository userRepository;

        @Transactional(rollbackFor = Exception.class)
        public Long createUserWithCart(Long productId) {
            com.shop.ecommerce.entity.User u = new com.shop.ecommerce.entity.User();
            u.setName("ReadOrder");
            u.setEmail("readorder@example.com");
            u.setPassword("$2a$10$0123456789012345678901234567890123456789012345678901");
            u.setRole(com.shop.ecommerce.entity.Role.CUSTOMER);
            u.setProvider(com.shop.ecommerce.entity.AuthProvider.LOCAL);
            u.setEnabled(true);
            return userRepository.saveAndFlush(u).getId();
        }

        @Transactional(rollbackFor = Exception.class)
        public Long createCartFor(Long userId, Long productId) {
            Cart cart = new Cart();
            cart.setUser(userRepository.findById(userId).orElseThrow());
            cart.addItem(productRepository.findById(productId).orElseThrow(), 1);
            return cartRepository.saveAndFlush(cart).getId();
        }

        /** The shape that caused the oversell. */
        @Transactional(rollbackFor = Exception.class)
        public void oldShape(Long userId) {
            System.out.println("  [old] BEGIN - about to fetch-join the cart and its products");
            Cart cart = cartRepository.findByUserIdWithItems(userId).orElseThrow();
            System.out.println("  [old] cart loaded, lines=" + cart.getItems().size()
                    + "  <-- a products select has already run here");
            List<Product> locked =
                    productRepository.findAllByIdWithLock(List.of(cart.getItems().get(0).getProductId()));
            System.out.println("  [old] FOR UPDATE returned stock=" + locked.get(0).getStock()
                    + "  (this value is from the snapshot, not the locked row)");
        }

        /** The shape that fixes it. */
        @Transactional(rollbackFor = Exception.class)
        public void newShape(Long userId) {
            System.out.println("  [new] BEGIN - about to load the cart WITHOUT its products");
            Cart cart = cartRepository.findByUserIdWithItemsOnly(userId).orElseThrow();
            System.out.println("  [new] cart loaded, lines=" + cart.getItems().size()
                    + "  <-- no products select yet");
            Long productId = cart.getItems().get(0).getProductId();
            System.out.println("  [new] product id from the FK column, no row read: " + productId);
            List<Product> locked = productRepository.findAllByIdWithLock(List.of(productId));
            System.out.println("  [new] FOR UPDATE is the FIRST product read; stock="
                    + locked.get(0).getStock() + "  (this value is current)");
        }

        /**
         * The fixed path, with the eager association exercised: the cart LINES are read, then
         * anything on a line that touches its Product must happen only after the lock.
         */
        @Transactional(rollbackFor = Exception.class)
        public void fixedOrderPath(Long userId) {
            System.out.println("  [fixed] BEGIN");
            Cart cart = cartRepository.findByUserIdWithItemsOnly(userId).orElseThrow();
            List<Long> ids = cart.getItems().stream().map(CartItem::getProductId).sorted().toList();
            System.out.println("  [fixed] cart lines read; ids from FK = " + ids);

            List<Product> locked = productRepository.findAllByIdWithLock(ids);
            System.out.println("  [fixed] LOCKED; first product read is the FOR UPDATE");

            /*
             * Now, and only now, read through the association. This is the check that the
             * EAGER CartItem.product does not quietly issue its own select before the lock:
             * if it had, a products select would appear above the FOR UPDATE in the log.
             */
            for (CartItem item : cart.getItems()) {
                System.out.println("  [fixed] line product name = " + item.getProduct().getName()
                        + ", stock = " + item.getProduct().getStock()
                        + "  (read after the lock, so it is current)");
            }
            System.out.println("  [fixed] DONE - compare the log: FOR UPDATE must precede every"
                    + " plain products select");
        }

        @Transactional(rollbackFor = Exception.class)
        public void clearCart(Long userId) {
            cartRepository.findByUserId(userId).ifPresent(cart -> {
                cart.clearItems();
                cartRepository.saveAndFlush(cart);
            });
        }

        /** Puts one line back on the user's cart, for the next check. */
        @Transactional(rollbackFor = Exception.class)
        public void refillCart(Long userId, Long productId) {
            Cart cart = cartRepository.findByUserId(userId).orElseThrow();
            cart.clearItems();
            // Flush the DELETEs before the INSERT, or the UNIQUE (cart_id, product_id) fires.
            cartRepository.saveAndFlush(cart);
            cart.addItem(productRepository.findById(productId).orElseThrow(), 1);
            cartRepository.saveAndFlush(cart);
        }
    }
}
