package com.shop.ecommerce.dto.cart;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * The whole cart.
 *
 * <p>Unlike an order, the cart carries no user information. There is exactly one caller
 * who can see it - the owner - because the service resolves the cart from the security
 * context rather than from a request parameter. A {@code userId} in this response would
 * describe a relationship the client cannot act on.
 *
 * @param id         cart id
 * @param items      the lines. Empty list rather than null when the cart is empty, so a
 *                   client can render {@code items.map(...)} without a guard.
 * @param subtotal   sum of every line's subtotal, computed by the server
 * @param totalItems total units across all lines, for the navbar badge
 * @param itemCount  number of distinct lines
 * @param empty      whether there is anything to check out. Sent as a field so a UI can
 *                   disable the checkout button without recomputing it - and so the
 *                   definition of "empty" lives in one place, on the server.
 * @param checkoutReady whether every line can actually be fulfilled <em>right now</em>.
 *
 *                   <p>This is the server answering "can this order be placed?" so the
 *                   frontend does not have to derive it by combining each line's
 *                   {@code available} and {@code hasEnoughStock} flags. Deriving it
 *                   client-side would mean two implementations of the same rule, and the
 *                   client's version would be the one that drifts.
 */
@Schema(description = "A customer's shopping cart")
public record CartResponse(

        @Schema(example = "1")
        Long id,

        @Schema(description = "Cart lines. Empty array when the cart is empty.")
        List<CartItemResponse> items,

        @Schema(example = "2998.00")
        BigDecimal subtotal,

        @Schema(example = "2", description = "Total units across all lines")
        int totalItems,

        @Schema(example = "1", description = "Number of distinct lines")
        int itemCount,

        @Schema(example = "false")
        boolean empty,

        @Schema(example = "true", description = "Whether every line can currently be fulfilled")
        boolean checkoutReady

) {
}
