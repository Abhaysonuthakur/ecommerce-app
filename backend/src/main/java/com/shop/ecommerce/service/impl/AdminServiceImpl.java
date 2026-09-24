package com.shop.ecommerce.service.impl;

import com.shop.ecommerce.dto.admin.DashboardStatsResponse;
import com.shop.ecommerce.entity.OrderStatus;
import com.shop.ecommerce.repository.CategoryRepository;
import com.shop.ecommerce.repository.OrderRepository;
import com.shop.ecommerce.repository.ProductRepository;
import com.shop.ecommerce.repository.UserRepository;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.service.AdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Aggregates for the admin dashboard.
 *
 * <h2>One request, nine counts, no N+1</h2>
 *
 * <p>Every number here comes from a purpose-built count or aggregate query. The tempting
 * alternative - load the collections and count them in Java - is the reason dashboards get
 * slow as a store grows: {@code productRepository.findAll().stream().filter(...).count()}
 * reads the whole catalogue into memory to produce a single integer, and it does it once
 * per metric.
 *
 * <p>Three of the queries are worth pointing at by name because each solves a specific
 * problem:
 * <ul>
 *   <li>{@code countGroupedByStatus} - one query, one row per status, instead of six
 *       count-by-status calls.</li>
 *   <li>{@code sumRevenueExcludingCancelled} - uses {@code coalesce} so an empty database
 *       returns 0 rather than null. Without it, the first admin to log in to a fresh install
 *       gets a NullPointerException instead of a dashboard.</li>
 *   <li>{@code countProductsPerCategory} is <em>not</em> used here - the dashboard only
 *       needs the category total, so it asks for a count rather than a grouped breakdown.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasRole('ADMIN')")
public class AdminServiceImpl implements AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminServiceImpl.class);

    /**
     * Products at or below this stock level are flagged as needing attention.
     *
     * <p>A named constant rather than a magic number in the query, because the admin UI's
     * badge says "3 need attention" and that sentence has to mean the same thing here as
     * it does in the product list's filter. One constant, referenced from both places.
     */
    public static final int LOW_STOCK_THRESHOLD = 10;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public AdminServiceImpl(ProductRepository productRepository,
                            CategoryRepository categoryRepository,
                            OrderRepository orderRepository,
                            UserRepository userRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    @Override
    public DashboardStatsResponse getDashboardStats() {
        long totalProducts = productRepository.count();
        long activeProducts = productRepository.countByActiveTrue();
        long totalCategories = categoryRepository.count();
        long totalOrders = orderRepository.count();

        /*
         * Customers by role, not "all users minus me".
         *
         * Counting the CUSTOMER role is the honest definition: the number claims to be the
         * size of the customer base, and an admin is not a customer. The tempting shortcut -
         * count all users and subtract one - is wrong the moment there are two admins, and
         * wrong in a way nobody notices because the number is only ever glanced at.
         */
        long totalCustomers = userRepository.countByRole(Role.CUSTOMER);

        /*
         * Revenue excludes CANCELLED orders and includes PENDING ones. That definition is
         * not obvious, so it is written in DashboardStatsResponse's javadoc, in the
         * repository method's javadoc, and here. A revenue figure whose definition is
         * implicit is a figure two people will disagree about in a meeting.
         */
        BigDecimal totalRevenue = orderRepository.sumRevenueExcludingCancelled();

        long lowStockProducts = productRepository.countByStockLessThanEqual(LOW_STOCK_THRESHOLD);

        Map<OrderStatus, Long> ordersByStatus = buildStatusCounts();

        log.debug("Dashboard stats: {} products ({} active), {} orders, revenue {}",
                totalProducts, activeProducts, totalOrders, totalRevenue);

        return new DashboardStatsResponse(
                totalProducts,
                activeProducts,
                totalCategories,
                totalOrders,
                totalCustomers,
                totalRevenue,
                ordersByStatus,
                lowStockProducts);
    }

    /**
     * Counts orders per status, with every status present.
     *
     * <h2>Seed from the enum, then overlay the query results</h2>
     *
     * <p>The grouped query only returns rows for statuses that have orders. On a fresh
     * install it returns <em>nothing at all</em>, and on a mature one it omits whichever
     * status happens to be empty.
     *
     * <p>That matters because the response feeds a chart. A frontend iterating
     * {@code ["PENDING", "CONFIRMED", ...]} and looking up each key would render a missing
     * bar as a crash or as a zero-height rectangle that is indistinguishable from a bug;
     * either way the frontend now has to defend against a missing key, which means the
     * frontend has to know the full list of statuses - a second copy of the enum.
     *
     * <p>Iterating {@code OrderStatus.values()} first means every key is present with a
     * value, and the guarantee lives in exactly one place. The frontend can then just
     * render what it is given.
     *
     * <p>An {@link EnumMap} rather than a {@code HashMap}: it iterates in ordinal order
     * (so the JSON key order is stable, which makes responses diffable), and it is backed
     * by an array rather than a hash table. For an enum-keyed map it is simply the correct
     * type.
     */
    private Map<OrderStatus, Long> buildStatusCounts() {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);

        for (OrderStatus status : OrderStatus.values()) {
            counts.put(status, 0L);
        }

        for (Object[] row : orderRepository.countGroupedByStatus()) {
            OrderStatus status = (OrderStatus) row[0];
            Long count = (Long) row[1];
            counts.put(status, count);
        }

        return counts;
    }
}
