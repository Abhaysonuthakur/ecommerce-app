package com.shop.ecommerce.dto.product;

import com.shop.ecommerce.dto.category.CategorySummaryResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a client receives for a product.
 *
 * <p>Note that the category arrives as a nested {@link CategorySummaryResponse} rather
 * than as a bare {@code categoryId}. A product list rendered in a browser needs the
 * category's <em>name</em> ("Shirts") to display, not its id - so sending only the id
 * would force the frontend to fetch every category separately and join them client-side,
 * for data the server already had in memory.
 *
 * <p>The nested object is deliberately a <em>summary</em>: id, name and slug, but not the
 * category's description or image. A list of 24 products does not need the same category
 * description 24 times.
 *
 * @param id          product id
 * @param name        display name
 * @param description long-form copy
 * @param price       unit price, as a string-serialised decimal in JSON (see note below)
 * @param stock       units available. Shown as "Only 3 left" and used to cap the quantity
 *                    selector.
 * @param imageUrl    image URL
 * @param category    the owning category, summarised
 * @param active      whether it is on sale. The storefront filters to {@code true}, but the
 *                    admin view needs to see deactivated products in order to reactivate
 *                    them - so the field is sent rather than filtered out of the DTO.
 * @param createdAt   when it was added
 * @param updatedAt   when it last changed
 */
@Schema(description = "A product as returned to clients")
public record ProductResponse(

        @Schema(example = "1")
        Long id,

        @Schema(example = "Premium Linen Shirt")
        String name,

        @Schema(example = "Breathable 100% linen, tailored fit.")
        String description,

        /*
         * Jackson serialises BigDecimal as a JSON number by default, so this arrives as
         * 1499.00 and JavaScript parses it into an IEEE-754 double - which cannot represent
         * every 2-decimal value exactly.
         *
         * For DISPLAY this is harmless and is what every other API does. It would be a
         * problem only if a client computed a total from these values and sent it back to
         * be charged - and this API never accepts a client-computed amount. The order
         * total is summed server-side from server-side values, so the precision question
         * never arises where it matters.
         */
        @Schema(example = "1499.00")
        BigDecimal price,

        @Schema(example = "50")
        Integer stock,

        @Schema(example = "https://images.example.com/linen-shirt.jpg")
        String imageUrl,

        @Schema(description = "The owning category, summarised")
        CategorySummaryResponse category,

        @Schema(example = "true")
        boolean active,

        @Schema(example = "2026-09-24T07:12:33Z")
        Instant createdAt,

        @Schema(example = "2026-09-24T07:12:33Z")
        Instant updatedAt

) {
}
