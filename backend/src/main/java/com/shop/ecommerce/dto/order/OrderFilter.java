package com.shop.ecommerce.dto.order;

import com.shop.ecommerce.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Query parameters for listing orders.
 *
 * <p>Used by two endpoints with different scopes - a customer's own orders and the admin's
 * view of all orders - which is why there is no {@code userId} here. Ownership is never a
 * request parameter in this API: the customer endpoint resolves the user from the security
 * context. A {@code userId} query parameter would be an authorization decision delegated
 * to the client, and every caller would be free to pass someone else's id.
 *
 * @param page   zero-based page index
 * @param size   page size, capped at 100
 * @param status optional filter on a single status. An enum rather than a string, so an
 *               unknown value is rejected with a clear message rather than silently
 *               matching nothing.
 * @param sort   sort expression, {@code "field,direction"}
 */
@Schema(description = "Filters and pagination for order lists")
public record OrderFilter(

        @Schema(example = "0")
        @Min(value = 0, message = "Page index must not be negative")
        Integer page,

        @Schema(example = "20", description = "Page size, 1-100")
        @Min(value = 1, message = "Page size must be at least 1")
        @Max(value = 100, message = "Page size must not exceed 100")
        Integer size,

        @Schema(example = "PENDING", description = "Filter to a single status")
        OrderStatus status,

        @Schema(example = "createdAt,desc",
                description = "One of: createdAt, updatedAt, totalAmount, status")
        String sort

) {

    /**
     * Defaults, applied before validation - the same compact-constructor trick used by
     * {@code ProductFilter}.
     *
     * <p>A default page size of 20 for orders rather than 12: an order list is a
     * management view read by someone scanning many rows, not a visual grid, so a larger
     * page is more useful than a prettier one.
     *
     * <p>Default sort is newest first, which is what both an admin triaging incoming orders
     * and a customer checking a recent purchase expect to see at the top.
     */
    public OrderFilter {
        if (page == null) {
            page = 0;
        }
        if (size == null) {
            size = 20;
        }
        if (sort == null || sort.isBlank()) {
            sort = "createdAt,desc";
        }
    }
}
