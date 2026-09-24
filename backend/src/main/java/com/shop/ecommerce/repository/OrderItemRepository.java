package com.shop.ecommerce.repository;

import com.shop.ecommerce.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Data access for {@link OrderItem}.
 *
 * <p>Deliberately small. Order lines are created as part of placing an order and are then
 * immutable, so there is no update or delete path - the only operations are reads for
 * reporting and for the "has this product ever been sold?" check.
 *
 * <p>The absence of a save/update method is not an oversight. A facade over
 * {@code JpaRepository} still exposes {@code save} and {@code delete}, which is fine
 * because nothing calls them on this type; introducing a method that <em>reads</em> like an
 * intentional update path is what would be wrong.
 */
@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** All lines of one order. */
    List<OrderItem> findByOrderId(Long orderId);

    /** How many units of a product have ever been sold. */
    @Query("select coalesce(sum(oi.quantity), 0) from OrderItem oi where oi.product.id = :productId")
    long sumQuantitySoldForProduct(@Param("productId") Long productId);

    /**
     * The best-selling products, by units sold - for the admin dashboard.
     *
     * <p>Returns {@code (productId, productName, unitsSold, revenue)} rows, already ordered
     * and limited. Joining back to {@code Product} is not necessary for the name: the order
     * line snapshots it, and using the snapshot here means the report shows what the
     * product was called when it sold.
     *
     * <p>Cancelled orders are excluded, for the same reason as in the revenue total - a
     * cancelled sale is not a sale.
     */
    @Query("""
            select oi.product.id, oi.productName, sum(oi.quantity), sum(oi.subtotal)
            from OrderItem oi
            where oi.order.status <> com.shop.ecommerce.entity.OrderStatus.CANCELLED
            group by oi.product.id, oi.productName
            order by sum(oi.quantity) desc
            """)
    List<Object[]> findBestSellers();

    /** Revenue attributable to one product, across non-cancelled orders. */
    @Query("""
            select coalesce(sum(oi.subtotal), 0)
            from OrderItem oi
            where oi.product.id = :productId
              and oi.order.status <> com.shop.ecommerce.entity.OrderStatus.CANCELLED
            """)
    BigDecimal sumRevenueForProduct(@Param("productId") Long productId);
}
