package com.shop.ecommerce.dto.category;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create or update a category. Admin only.
 *
 * <p>Note the absence of {@code slug}. It is derived from {@code name} in the service
 * layer rather than accepted from the client, for two reasons:
 *
 * <ul>
 *   <li>A client-supplied slug is a second unique field to validate, and two clients can
 *       generate the same one from different names - "Men's Shirts" and "Mens Shirts"
 *       both slugify to {@code mens-shirts}, which is a conflict the admin did not cause
 *       and cannot explain.</li>
 *   <li>It is derivable. A field that the server can compute from another field is a
 *       field that should not be in the request at all: accepting it means validating
 *       that the two agree, and handling the case where they do not.</li>
 * </ul>
 *
 * @param name        display name, unique (case-insensitively, via the database collation)
 * @param description optional copy for the category page
 * @param imageUrl    optional category image
 * @param active      whether it is visible to shoppers. Boxed Boolean so an update that
 *                    omits it does not silently deactivate the category.
 */
@Schema(description = "Create or update a category (admin only)")
public record CategoryRequest(

        @Schema(example = "Shirts", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Category name is required")
        @Size(min = 2, max = 100, message = "Category name must be between 2 and 100 characters")
        String name,

        @Schema(example = "Casual and formal shirts for every occasion")
        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description,

        @Schema(example = "https://images.example.com/categories/shirts.jpg")
        @Size(max = 500, message = "Image URL must not exceed 500 characters")
        String imageUrl,

        @Schema(example = "true", description = "Defaults to true when omitted")
        Boolean active

) {
}
