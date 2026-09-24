package com.shop.ecommerce.dto.category;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The minimal category reference embedded in a {@link com.shop.ecommerce.dto.product.ProductResponse}.
 *
 * <p><b>Why a separate type instead of reusing {@link CategoryResponse}?</b>
 * A client only needs three things to render a product's category - enough to show the
 * label and link to it. The full record additionally carries a description, an image URL,
 * timestamps and a product count. Embedded in a page of 24 products, that is 24 copies of
 * the same description and 24 count queries, which is the difference between a fast list
 * endpoint and a slow one.
 *
 * <p>Having two types also means the two can evolve independently: adding a field to the
 * category admin screen does not change every product response in the system.
 *
 * @param id   category id, for building a link
 * @param name display label
 * @param slug URL-safe handle, for a readable link
 */
@Schema(description = "Minimal category reference used inside product responses")
public record CategorySummaryResponse(

        @Schema(example = "1")
        Long id,

        @Schema(example = "Shirts")
        String name,

        @Schema(example = "shirts")
        String slug

) {
}
