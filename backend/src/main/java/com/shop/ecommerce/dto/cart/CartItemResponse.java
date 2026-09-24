package com.shop.ecommerce.dto.cart;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One line in the cart response.
 *
 * <h2>The two booleans are the point</h2>
 *
 * <p>A cart is a snapshot of intent against live data, and the two can disagree: a product
 * can sell out or be withdrawn while it sits in someone's basket. Telling the customer at
 * that moment is useful; telling them at checkout, after they have filled in an address,
 * is a bad experience.
 *
 * <p>{@code available} and {@code hasEnoughStock} exist so the UI can flag the problem
 * inline - grey out the line, show "Only 2 left", disable the checkout button - instead of
 * letting the customer discover it from a failed request. The frontend decides how to
 * present it; the server's job is to report the facts.
 *
 * @param id              cart line id, used to address this line in update/delete calls
 * @param productId       the product's id
 * @param productName     the product's current name
 * @param imageUrl        the product's image, for the cart thumbnail
 * @param unitPrice       the product's <b>current</b> price. Not a snapshot - the order is
 *                        where prices freeze. If a price changed while the item sat in the
 *                        cart, the customer should see the new one here, before checkout.
 * @param quantity        how many the customer wants
 * @param subtotal        {@code unitPrice * quantity}, computed by the server
 * @param availableStock  how many the product actually has, so the UI can cap the
 *                        quantity stepper and say "only N left"
 * @param available       false when the product has been withdrawn from sale
 * @param hasEnoughStock  false when {@code quantity > availableStock}. Distinct from
 *                        {@code available}: an active product can still be short of stock,
 *                        and the two need different messages ("no longer available" versus
 *                        "only 2 left").
 */
@Schema(description = "One line of a shopping cart")
public record CartItemResponse(

        @Schema(example = "10")
        Long id,

        @Schema(example = "1")
        Long productId,

        @Schema(example = "Premium Linen Shirt")
        String productName,

        @Schema(example = "https://images.example.com/linen-shirt.jpg")
        String imageUrl,

        @Schema(example = "1499.00")
        BigDecimal unitPrice,

        @Schema(example = "2")
        Integer quantity,

        @Schema(example = "2998.00")
        BigDecimal subtotal,

        @Schema(example = "50", description = "Units currently in stock for this product")
        int availableStock,

        @Schema(example = "true", description = "False if the product was withdrawn from sale")
        boolean available,

        @Schema(example = "true", description = "False if quantity exceeds availableStock")
        boolean hasEnoughStock

) {
}
