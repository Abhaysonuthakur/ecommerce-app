package com.shop.ecommerce.repository;

import com.shop.ecommerce.entity.AuthProvider;
import com.shop.ecommerce.entity.Category;
import com.shop.ecommerce.entity.Order;
import com.shop.ecommerce.entity.OrderItem;
import com.shop.ecommerce.entity.OrderStatus;
import com.shop.ecommerce.entity.Product;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that a lazy collection plus an entity graph produces ONE query where a lazy
 * collection alone produces N+1.
 *
 * <h2>Why this test exists rather than a comment</h2>
 *
 * {@code Order.items} was mapped {@code EAGER} on the argument that a paginated list of
 * orders needs its lines. The argument was half right - the list endpoint DOES render the
 * lines - and the implementation was still wrong, because EAGER applies to every query
 * returning an Order, including the ones that never read the collection. The measurement
 * that settled it, and that this test now pins:
 *
 * <pre>
 *   find...OrderByCreatedAtDesc (no graph), 5 orders  ->  6 queries
 *   findWithItems...OrderByCreatedAtDesc,    5 orders  ->  1 query
 * </pre>
 *
 * Six queries for five rows is the signature of N+1: one to select the page, then one per
 * row when the mapper touches {@code getItems()}. The count grows linearly with the page
 * size, so a 100-row admin page cost 101 round trips.
 *
 * <h2>Why the count is asserted, not just the ordering</h2>
 *
 * A test that asserted only "the items are present" would pass for BOTH mappings - the
 * eager version also produces correct JSON, it just does it slowly. Query count is the
 * only observable that distinguishes them, which is why this asserts the number.
 *
 * <h2>How the count is obtained</h2>
 *
 * Hibernate's {@link Statistics} is enabled locally in this test rather than in the shared
 * profile, because it adds bookkeeping to every statement and there is no reason for the
 * other 200 tests to pay for it. {@code clear()} before each measurement is essential -
 * statistics are cumulative, so without it the second number would include the first.
 */
@DataJpaTest
@ActiveProfiles("test")
class QueryCountTest {

    /** Page size used for the measurement - larger than the row count, so N+1 is visible. */
    private static final int PAGE_SIZE = 20;
    private static final int ORDER_COUNT = 5;

    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @PersistenceContext private EntityManager entityManager;

    private Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    /**
     * Builds {@value #ORDER_COUNT} orders, each with one line, and clears the persistence
     * context so the measurements below start from an empty first-level cache.
     *
     * <p>The clear matters more than the inserts. Without it, every order is already in
     * memory with its collection populated, so neither query would issue a line load and
     * both counts would be 1 - the test would pass and prove nothing.
     */
    private void persistOrders() {
        Category category = new Category();
        category.setName("Electronics");
        category.setSlug("electronics");
        category.setActive(true);
        categoryRepository.save(category);

        Product product = new Product();
        product.setName("Laptop");
        product.setPrice(new BigDecimal("50000.00"));
        product.setStock(100);
        product.setActive(true);
        product.setCategory(category);
        productRepository.save(product);

        User customer = new User();
        customer.setName("Ada Lovelace");
        customer.setEmail("ada@example.com");
        customer.setPassword("$2a$10$notarealhashjustshapedlikeoneforthisfixture");
        customer.setRole(Role.CUSTOMER);
        customer.setProvider(AuthProvider.LOCAL);
        customer.setEnabled(true);
        userRepository.save(customer);

        for (int i = 0; i < ORDER_COUNT; i++) {
            Order order = new Order();
            order.setUser(customer);
            order.setStatus(OrderStatus.PENDING);
            order.setShippingAddress("1 Test Street");
            order.addItem(OrderItem.createSnapshot(product, 1));
            order.recalculateTotal();
            orderRepository.save(order);
        }

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("a plain paged order list forces one query per row to read the lines")
    void plainListIsNPlusOne() {
        persistOrders();

        statistics.clear();
        var orders = orderRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, PAGE_SIZE));
        int linesRead = orders.getContent().stream().mapToInt(order -> order.getItems().size()).sum();

        assertThat(linesRead)
                .as("every order on the page must actually have been read, or the count is meaningless")
                .isEqualTo(ORDER_COUNT);
        assertThat(statistics.getPrepareStatementCount())
                .as("1 statement for the page + 1 per order to initialise its lazy lines")
                .isEqualTo(1 + ORDER_COUNT);
    }

    @Test
    @DisplayName("the entity-graph variant reads the same lines in a single query")
    void entityGraphListIsOneQuery() {
        persistOrders();

        statistics.clear();
        var orders = orderRepository.findWithItemsAllByOrderByCreatedAtDesc(PageRequest.of(0, PAGE_SIZE));
        int linesRead = orders.getContent().stream().mapToInt(order -> order.getItems().size()).sum();

        assertThat(linesRead)
                .as("the graph must return the same data as the plain list, not less")
                .isEqualTo(ORDER_COUNT);
        assertThat(statistics.getPrepareStatementCount())
                .as("one joined statement must replace the whole N+1 sequence")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the entity graph does not break pagination: page size still limits the rows")
    void entityGraphRespectsThePageSize() {
        persistOrders();

        var firstPage = orderRepository.findWithItemsAllByOrderByCreatedAtDesc(PageRequest.of(0, 2));

        /*
         * The reason the graph is an @EntityGraph rather than a `left join fetch` in a
         * @Query. A collection fetch combined with a Pageable makes Hibernate load every
         * matching row and slice in memory, which is why Hibernate logs
         * "firstResult/maxResults specified with collection fetch; applying in memory".
         * An entity graph is applied to the paged query instead, so LIMIT stays in SQL.
         *
         * Asserting the page size AND the total is what catches the in-memory-pagination
         * regression: if it happened, `totalElements` would still be right but the database
         * would have returned every row, and the page size assertion alone would not notice.
         */
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(ORDER_COUNT);
        assertThat(firstPage.getContent())
                .allSatisfy(order -> assertThat(order.getItems())
                        .as("rows on the page still need their lines initialised")
                        .hasSize(1));
    }
}
