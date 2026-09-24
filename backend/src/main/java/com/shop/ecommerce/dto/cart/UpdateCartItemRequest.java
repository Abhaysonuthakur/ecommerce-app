package com.shop.ecommerce.dto.cart;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Set a cart line to an absolute quantity.
 *
 * <p><b>Absolute, not a delta.</b> The endpoint says "this line now holds N", not "add N".
 * That choice matters when a user edits the quantity field in a cart UI: a delta-based API
 * needs the client to know the current value to compute a delta, so two tabs open on the
 * same cart can send deltas that cancel out or double. Setting an absolute value is
 * naturally idempotent - sending it twice leaves the same state, which is what a
 * quantity input does.
 *
 * <p>Removing a line is a separate DELETE rather than a quantity of zero, because "set to
 * zero" and "remove" read differently to a user and land in different places in an audit
 * trail.
 *
 * @param quantity the new quantity for this line. Must be at least 1 - the stock check
 *                 happens separately against the live product row.
 */
@Schema(description = "Set the quantity of an existing cart line")
public record UpdateCartItemRequest(

        @Schema(example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        @Max(value = 99, message = "Quantity must not exceed 99")
        Integer quantity

) {
}
