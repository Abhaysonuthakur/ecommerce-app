package com.shop.ecommerce.dto.order;

import com.shop.ecommerce.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A placed order, as returned to a client.
 *
 * @param id              order id
 * @param orderNumber     a human-friendly reference, derived from the id.
 *
 *                        <p>The id is what the API uses; the order number is what a
 *                        support agent reads out over the phone and what a customer
 *                        quotes. Deriving it ({@code ORD-000042}) rather than storing it
 *                        means there is no second identifier to keep unique and no extra
 *                        column - the id is already unique and immutable.
 * @param userId          the customer's id
 * @param customerName    the customer's name, so an admin viewing the order list does not
 *                        have to cross-reference a user table by hand
 * @param customerEmail   the customer's email, for the same reason
 * @param items           the order lines. Each carries its own price snapshot.
 * @param totalAmount     what the customer owed, stored on the order rather than summed
 *                        from the lines on every read
 * @param status          where the order is in its lifecycle
 * @param allowedNextStatuses the statuses this order may legally move to.
 *
 *                        <p>Sent so an admin UI can render exactly the valid buttons
 *                        instead of hardcoding the state machine a second time in
 *                        JavaScript. Two implementations of a rule are two rules, and the
 *                        client's copy is the one that goes stale.
 * @param shippingAddress where it was shipped, snapshotted at placement
 * @param totalItems      total units across all lines
 * @param createdAt       when the order was placed
 * @param updatedAt       when it last changed - a status change moves this
 */
@Schema(description = "A placed order")
public record OrderResponse(

        @Schema(example = "1")
        Long id,

        @Schema(example = "ORD-000001")
        String orderNumber,

        @Schema(example = "1")
        Long userId,

        @Schema(example = "Ada Lovelace")
        String customerName,

        @Schema(example = "ada@example.com")
        String customerEmail,

        @Schema(description = "Order lines, each with its price frozen at purchase time")
        List<OrderItemResponse> items,

        @Schema(example = "2998.00")
        BigDecimal totalAmount,

        @Schema(example = "PENDING")
        OrderStatus status,

        @Schema(example = "[\"CONFIRMED\", \"CANCELLED\"]",
                description = "Statuses this order may legally move to next")
        List<OrderStatus> allowedNextStatuses,

        @Schema(example = "12 Analytical Avenue, New Delhi 110001")
        String shippingAddress,

        @Schema(example = "2", description = "Total units across all lines")
        int totalItems,

        @Schema(example = "2026-09-24T07:12:33Z")
        Instant createdAt,

        @Schema(example = "2026-09-24T07:12:33Z")
        Instant updatedAt

) {

    /**
     * Builds the human-readable order reference from the id.
     *
     * <p>Static so it can be used by the mapper and asserted by a test without constructing
     * a whole response. One definition means the format is consistent everywhere it appears.
     */
    public static String formatOrderNumber(Long id) {
        return id == null ? null : "ORD-%06d".formatted(id);
    }
}
