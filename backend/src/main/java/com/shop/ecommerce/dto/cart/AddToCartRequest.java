package com.shop.ecommerce.dto.cart;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Add a product to the cart.
 *
 * <h2>Note what is missing: the price</h2>
 *
 * <p>There is no {@code price} field, and there never will be. The server reads the price
 * from the product row. A cart request that carried a price would be a request that could
 * say "charge me one rupee for this television", and no amount of server-side checking
 * makes accepting such a field a good idea - the correct number is already known, so a
 * number supplied by the client is either redundant or an attack.
 *
 * <p>The same principle governs the whole money path: <b>no amount that the server can
 * derive from the database is ever accepted from the client.</b>
 *
 * @param productId the product to add. Must reference an existing, active product.
 * @param quantity  how many units.
 *
 *                  <p>{@code @Min(1)}, not {@code @Min(0)}: adding zero of something is
 *                  not an operation, and the database's {@code CHECK (quantity > 0)} would
 *                  reject it anyway. Rejecting it here produces a 400 naming the field
 *                  instead of a 500 from a constraint violation.
 *
 *                  <p>{@code @Max(99)} is a sanity bound, not the stock check. Stock is
 *                  checked against the live product row, because the available quantity
 *                  is not a property of the request - it changes minute to minute. This
 *                  bound exists only to reject an obviously absurd number before it
 *                  reaches the database.
 */
@Schema(description = "Add a product to the cart, or increase its quantity if already present")
public record AddToCartRequest(

        @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Product id is required")
        Long productId,

        @Schema(example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        @Max(value = 99, message = "Quantity must not exceed 99")
        Integer quantity

) {
}
