package com.shop.ecommerce.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Create or update a product. Admin only.
 *
 * <h2>Why {@code @Digits} matters more than it looks</h2>
 *
 * <p>The column is {@code DECIMAL(19,2)}. MySQL does not reject a value with more
 * fractional digits - it <b>silently rounds it</b>. The probe in
 * {@code db/constraint-test.sql} demonstrates this: inserting {@code 10.999} stores
 * {@code 11.00}, with no warning and no error.
 *
 * <p>So without {@code @Digits(integer = 17, fraction = 2)}, an admin submitting
 * {@code 999.999} would get back a saved price of {@code 1000.00} and would reasonably
 * conclude the application is broken. The rounding happens in the database, far from
 * anything the code can see, so the boundary is the only place to catch it.
 *
 * <p>{@code integer = 17} because {@code 19} total digits minus {@code 2} after the
 * decimal point leaves 17 before it.
 *
 * @param name        product name, unique enough for search but not database-unique -
 *                    two shops may legitimately sell "Linen Shirt"
 * @param description long-form copy, optional
 * @param price       unit price in INR. <b>Never trusted from the client at checkout</b> -
 *                    the order service copies this value from the database row. This
 *                    field is how the admin <em>sets</em> that value, not how a customer
 *                    is charged.
 * @param stock       units available
 * @param imageUrl    a URL to a product image
 * @param categoryId  the category this product belongs to. Must reference an existing,
 *                    non-deleted category.
 * @param active      whether the product is on sale. Defaults to true when omitted.
 */
@Schema(description = "Create or update a product (admin only)")
public record ProductRequest(

        @Schema(example = "Premium Linen Shirt", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Product name is required")
        @Size(min = 2, max = 200, message = "Product name must be between 2 and 200 characters")
        String name,

        @Schema(example = "Breathable 100% linen, tailored fit. Machine washable.")
        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description,

        @Schema(example = "1499.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.0", inclusive = true, message = "Price must not be negative")
        @DecimalMax(value = "99999999999999999.99", message = "Price is too large")
        @Digits(integer = 17, fraction = 2, message = "Price may have at most 2 decimal places")
        BigDecimal price,

        @Schema(example = "50", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Stock is required")
        @Min(value = 0, message = "Stock must not be negative")
        Integer stock,

        @Schema(example = "https://images.example.com/linen-shirt.jpg")
        @Size(max = 500, message = "Image URL must not exceed 500 characters")
        String imageUrl,

        /*
         * @NotNull, not @Positive: ids are opaque, and asserting that an id is "positive"
         * conflates "a valid reference" with "a valid number". A nonexistent id is the
         * service's problem to detect and report as a 404, which is a far better message
         * than "must be greater than 0".
         */
        @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Category is required")
        Long categoryId,

        /*
         * A boxed Boolean rather than a primitive, so "absent" is distinguishable from
         * "false". With a primitive, an admin updating only the price would silently
         * deactivate the product - the classic trap of a boolean default in an update
         * payload. The service treats null as "leave unchanged" on update and "true" on
         * create.
         */
        @Schema(example = "true", description = "Defaults to true when omitted")
        Boolean active

) {
}
