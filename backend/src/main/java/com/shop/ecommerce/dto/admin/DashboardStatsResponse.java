package com.shop.ecommerce.dto.admin;

import com.shop.ecommerce.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aggregated numbers for the admin dashboard.
 *
 * <h2>Why one endpoint instead of six</h2>
 *
 * <p>An admin landing on a dashboard needs all of these at once. Six endpoints would mean
 * six round trips and six loading spinners resolving independently, for data that is
 * displayed together. One aggregate endpoint is a single request and a single loading
 * state, and the queries behind it are all trivial counts.
 *
 * @param totalProducts  products including deactivated ones - the admin needs the real
 *                       total, not the storefront's view of it
 * @param activeProducts products currently on sale. The gap between this and
 *                       {@code totalProducts} is the withdrawn catalogue, which is exactly
 *                       the number an admin is looking for.
 * @param totalCategories all categories
 * @param totalOrders    every order ever placed
 * @param totalCustomers accounts with the CUSTOMER role. Counted by role rather than as
 *                       "all users", because including admins would overstate the customer
 *                       base and slowly drift from what the number claims to mean.
 * @param totalRevenue   the sum of {@code orders.total_amount} for orders that still
 *                       represent money owed.
 *
 *                       <p><b>This deliberately excludes CANCELLED orders</b> - a cancelled
 *                       order is not revenue. Note the subtlety: it <em>includes</em>
 *                       PENDING orders, because the goods have been sold and the stock
 *                       reserved; if your definition of revenue is "money received" rather
 *                       than "money owed", this number is wrong for that definition and
 *                       should be changed in one place
 *                       ({@code OrderRepository.sumRevenueExcludingCancelled}). Naming the
 *                       definition rather than leaving it implicit is the point.
 * @param ordersByStatus a count per status, for the dashboard's status breakdown.
 *
 *                       <p>A map keyed by the enum rather than a nested array, so the JSON
 *                       reads naturally ({@code {"PENDING": 4}}) and the frontend can index
 *                       it directly. Every status is present with a zero count when it has
 *                       none, so a chart does not have to handle missing keys - the
 *                       guarantee is provided by {@code AdminServiceImpl}, which seeds the
 *                       map from the enum rather than from the query results.
 * @param lowStockProducts how many products are at or below the low-stock threshold.
 *                       A count rather than a list: the dashboard needs to show a badge
 *                       that says "3 need attention", and clicking it opens the product
 *                       list already filtered.
 */
@Schema(description = "Aggregate statistics for the admin dashboard")
public record DashboardStatsResponse(

        @Schema(example = "48")
        long totalProducts,

        @Schema(example = "42")
        long activeProducts,

        @Schema(example = "6")
        long totalCategories,

        @Schema(example = "137")
        long totalOrders,

        @Schema(example = "92")
        long totalCustomers,

        @Schema(example = "418750.00")
        BigDecimal totalRevenue,

        @Schema(example = "{\"PENDING\":4,\"CONFIRMED\":12,\"PROCESSING\":3,\"SHIPPED\":2,\"DELIVERED\":110,\"CANCELLED\":6}")
        Map<OrderStatus, Long> ordersByStatus,

        @Schema(example = "3", description = "Products at or below the low-stock threshold")
        long lowStockProducts

) {
}
