package com.shop.ecommerce.dto.category;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A category with its full detail - the response for a single-category read.
 *
 * <p>Distinct from {@link CategorySummaryResponse}, which is embedded inside every
 * {@code ProductResponse}. That separation is the difference between a product list
 * response of 20 KB and one of 200 KB: the summary carries what a product card needs, and
 * the full record carries the description and image that only a category page displays.
 *
 * @param id          category id
 * @param name        display name
 * @param slug        URL-safe handle, derived from the name
 * @param description long-form copy
 * @param imageUrl    image URL
 * @param active      whether it is visible to shoppers
 * @param productCount how many products reference this category.
 *
 *                    <p>Useful for an admin deciding whether a category is safe to
 *                    deactivate, and cheap to obtain - but it is a <em>count</em>, not a
 *                    nested list of products. Sending the products themselves would make
 *                    fetching a category load the entire catalogue, which is the classic
 *                    eager-collection mistake in DTO form.
 * @param createdAt   when it was created
 * @param updatedAt   when it last changed
 */
@Schema(description = "A category with full detail")
public record CategoryResponse(

        @Schema(example = "1")
        Long id,

        @Schema(example = "Shirts")
        String name,

        @Schema(example = "shirts")
        String slug,

        @Schema(example = "Casual and formal shirts for every occasion")
        String description,

        @Schema(example = "https://images.example.com/categories/shirts.jpg")
        String imageUrl,

        @Schema(example = "true")
        boolean active,

        @Schema(example = "24", description = "Number of products in this category")
        long productCount,

        @Schema(example = "2026-09-24T07:12:33Z")
        Instant createdAt,

        @Schema(example = "2026-09-24T07:12:33Z")
        Instant updatedAt

) {
}
