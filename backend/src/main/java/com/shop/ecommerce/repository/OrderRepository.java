package com.shop.ecommerce.repository;

import com.shop.ecommerce.entity.Order;
import com.shop.ecommerce.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link Order}.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Loads one order together with its lines and each line's product.
     *
     * <p>The fetch join is what this method is for. {@code Order.items} is {@code LAZY},
     * so a plain {@code findById} returns an order whose lines are an uninitialised proxy
     * - touching them outside a transaction would throw, and inside one would issue a
     * second query. Joining them here means the mapper gets a fully-loaded order from a
     * single statement, which is the whole point of having this method exist alongside
     * {@code findById}.
     *
     * <p>{@code left join fetch i.product} is a second, separate join rather than a
     * redundancy: {@code OrderItem.product} is {@code @ManyToOne(fetch = EAGER)}, and
     * Hibernate resolves an eager {@code @ManyToOne} on an entity reached through a
     * collection fetch with a <em>separate select</em> unless it is named in the fetch
     * clause. Writing it out collapses that into the same query.
     *
     * <p>{@code distinct} is required once more than one collection is joined: the SQL
     * row set contains one row per line, and without {@code distinct} Hibernate returns
     * that many copies of the same {@code Order}. With it, the duplicates are collapsed
     * back into one entity with a fully populated collection.
     *
     * <p>Scoping by user id in the query - rather than loading the order and comparing
     * owners in Java - is what makes "one customer cannot read another's order" a property
     * of the SQL. A caller that forgot the comparison would otherwise return another
     * customer's order, and the code would look correct.
     *
     * <p><b>A note on 404 versus 403.</b> When this returns empty, the service raises a
     * 404 - even for an order that exists but belongs to somebody else. Returning 403 would
     * confirm that the order exists, letting anyone enumerate valid order ids. A 404 says
     * only "not yours to see", which is the correct amount of information to give.
     */
    @Query("""
            select distinct o from Order o
            left join fetch o.items i
            left join fetch i.product
            where o.id = :orderId and o.user.id = :userId
            """)
    Optional<Order> findByIdAndUserIdWithItems(@Param("orderId") Long orderId,
                                              @Param("userId") Long userId);

    /**
     * Loads one order with its lines, for admin use.
     *
     * <p>No user scoping - the caller has already been authorized as an admin by the
     * filter chain and {@code @PreAuthorize}. That is the difference between this method
     * and the previous one, and the reason both exist rather than one with an optional
     * user filter: an {@code if (userId != null)} branch inside a repository method is how
     * an ownership check becomes skippable.
     */
    @Query("""
            select distinct o from Order o
            left join fetch o.items i
            left join fetch i.product
            where o.id = :orderId
            """)
    Optional<Order> findByIdWithItems(@Param("orderId") Long orderId);

    /**
     * A customer's own orders, newest first.
     *
     * <p>Note there is <em>no</em> method here returning all orders unpaginated. Every
     * path that lists orders is paginated, because the order table is append-only and
     * grows forever - an unpaginated list is a page that works in testing and fails a year
     * later.
     *
     * <p><b>These four methods return orders WITHOUT their lines, on purpose.</b> {@code
     * Order.items} is {@code LAZY}, so a list view that renders only the order number,
     * date, status and total touches a single table. That is the difference between an
     * admin console that pages in 30 ms and one that issues twenty extra queries per page.
     *
     * <p><b>The trap this creates.</b> A mapper that also reads {@code order.getItems()}
     * to render a line count will now either throw {@code LazyInitializationException}
     * (outside a transaction) or quietly issue one query per order (inside one). Neither
     * is a bug in the repository - the repository is doing what it was asked. It is a
     * signal that the call site needs one of the methods below.
     */
    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<Order> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, OrderStatus status, Pageable pageable);

    /** Every order, for the admin console. */
    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    /**
     * The same list views, but with {@code items} initialised for the rows on the page.
     *
     * <p>Use these only where the response actually includes the lines. The
     * counter-intuitive detail is that an {@code @EntityGraph} on a {@link Page} query is
     * <em>safe</em> while a {@code join fetch} is not: Spring Data applies the entity graph
     * through the query Hibernate generates for the page, so {@code limit}/{@code offset}
     * stay correct. Writing {@code left join fetch o.items} with a {@code Pageable} instead
     * throws "{@code firstResult/maxResults specified with collection fetch; applying in
     * memory}" - Hibernate has to load every order and then slice in Java, which is exactly
     * the unbounded query the pagination was meant to prevent.
     */
    @EntityGraph(attributePaths = "items")
    Page<Order> findWithItemsByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "items")
    Page<Order> findWithItemsByUserIdAndStatusOrderByCreatedAtDesc(Long userId, OrderStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "items")
    Page<Order> findWithItemsAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = "items")
    Page<Order> findWithItemsByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    long countByStatus(OrderStatus status);

    // =================================================================
    //  Dashboard aggregates
    // =================================================================

    /**
     * Counts orders per status in one query.
     *
     * <p>Returns {@code Object[]} rows of {@code (status, count)}. The service seeds the
     * map from the enum first and then overlays these counts, so a status with no orders
     * still appears with a zero - without that, a status that happens to be empty would be
     * missing from the dashboard's JSON and the chart would have to cope.
     */
    @Query("select o.status, count(o) from Order o group by o.status")
    List<Object[]> countGroupedByStatus();

    /**
     * Total revenue, excluding cancelled orders.
     *
     * <p><b>{@code coalesce} is not optional.</b> On an empty database {@code sum} returns
     * SQL {@code NULL}, and mapping that onto a {@code BigDecimal} throws a
     * {@code NullPointerException} at the point the value is used - which is the dashboard,
     * on a fresh install, for the very first admin to log in. {@code coalesce(sum(...), 0)}
     * makes the empty case return zero, which is also the arithmetically correct answer.
     *
     * <p>Cancelled orders are excluded because a cancelled order is not revenue. PENDING
     * orders <em>are</em> included - the goods are sold and the stock is reserved. Both
     * choices are stated in {@code DashboardStatsResponse}'s javadoc, because the number is
     * meaningless without its definition.
     */
    @Query("select coalesce(sum(o.totalAmount), 0) from Order o where o.status <> com.shop.ecommerce.entity.OrderStatus.CANCELLED")
    BigDecimal sumRevenueExcludingCancelled();

    /** Total revenue for one customer, for their dashboard. Same exclusion rule. */
    @Query("select coalesce(sum(o.totalAmount), 0) from Order o where o.user.id = :userId and o.status <> com.shop.ecommerce.entity.OrderStatus.CANCELLED")
    BigDecimal sumRevenueForUser(@Param("userId") Long userId);

    long countByUserId(Long userId);
}
