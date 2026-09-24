package com.shop.ecommerce.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Place an order from the current cart.
 *
 * <h2>This record is three fields short of what you might expect - on purpose</h2>
 *
 * <p>There is no {@code items} array, no {@code totalAmount}, and no {@code unitPrice}
 * anywhere. Every one of those is supplied by the server:
 *
 * <ul>
 *   <li><b>The lines come from the cart.</b> The client does not resend them, so it cannot
 *       add a line for a product it never put in the cart, and cannot omit one it did.
 *       The cart is the single source of truth for what is being ordered.</li>
 *   <li><b>The total is summed server-side</b> from the snapshotted unit prices, so it
 *       cannot disagree with the lines.</li>
 *   <li><b>The unit price is copied from the product row</b> inside the transaction. This
 *       is the defence against the most obvious e-commerce attack there is: posting
 *       {@code {"price": 1}} for a ₹50,000 television.</li>
 * </ul>
 *
 * <p>The pattern is worth naming, because it is the answer to a very common interview
 * question: <b>never accept from the client a value the server can derive from its own
 * data.</b> Accepting it does not merely risk the client lying - it creates a second
 * source of truth for a number that must have exactly one.
 *
 * @param shippingAddress where to deliver. Optional; when omitted the order falls back to
 *                        the customer's profile address, and if that is empty too the
 *                        order is still accepted with no address recorded - refusing a
 *                        sale because a profile field is blank would be worse than
 *                        letting an admin follow up.
 * @param notes           free-text notes for the store. Stored on the order... except it
 *                        is not, in this version: see the note below.
 */
@Schema(description = "Place an order from the current cart. Lines and prices come from the server.")
public record PlaceOrderRequest(

        @Schema(example = "12 Analytical Avenue, New Delhi 110001")
        @Size(max = 255, message = "Shipping address must not exceed 255 characters")
        String shippingAddress

        /*
         * A `notes` field was considered and left out deliberately. There is no column for
         * it in `orders`, and adding one "just in case" is how a schema accumulates fields
         * that nothing reads. When order notes are actually needed they get a column, a
         * migration and a place in the admin UI - which is a fifteen-minute change, versus
         * a permanently unused column that every future reader has to evaluate.
         */

) {

    /**
     * Normalises a blank address to null so the service's fallback logic has one case to
     * handle, not two.
     *
     * <p>Without this, {@code ""} and {@code null} would both mean "not provided" but
     * compare differently, and the fallback to the profile address would work for one and
     * not the other. Normalising at the boundary means "absent" has exactly one
     * representation everywhere downstream.
     */
    public PlaceOrderRequest {
        if (shippingAddress != null && shippingAddress.isBlank()) {
            shippingAddress = null;
        }
    }
}
