package com.shop.ecommerce.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of a placed order, as returned to a client.
 *
 * <h2>Notice which fields come from where</h2>
 *
 * <p>{@code productName} and {@code unitPrice} come from the <b>snapshot columns on the
 * order line</b>, not from the product row. That distinction is the entire reason
 * {@link com.shop.ecommerce.entity.OrderItem} exists in its current shape, and it is
 * invisible in a JSON response until the day a price changes and an old order still shows
 * the old number - which is exactly the behaviour being preserved here.
 *
 * <p>{@code productId} and {@code imageUrl} come from the product, because they are for
 * navigation and display only. They are allowed to change: a product's image being updated
 * should update everywhere, and a customer clicking through from an old order should reach
 * the current product page.
 *
 * @param id          order line id
 * @param productId   the product's id, for linking back to it
 * @param productName the name <b>at the time of purchase</b>, from the snapshot
 * @param imageUrl    the product's current image, for display
 * @param unitPrice   the price <b>at the time of purchase</b>, from the snapshot
 * @param quantity    how many were bought
 * @param subtotal    {@code unitPrice * quantity}, also a snapshot
 */
@Schema(description = "One line of an order. Price and name are historical snapshots.")
public record OrderItemResponse(

        @Schema(example = "1")
        Long id,

        @Schema(example = "1")
        Long productId,

        @Schema(example = "Premium Linen Shirt",
                description = "The product's name when the order was placed")
        String productName,

        @Schema(example = "https://images.example.com/linen-shirt.jpg")
        String imageUrl,

        @Schema(example = "1499.00",
                description = "Unit price when the order was placed - not the current price")
        BigDecimal unitPrice,

        @Schema(example = "2")
        Integer quantity,

        @Schema(example = "2998.00")
        BigDecimal subtotal

) {
}
