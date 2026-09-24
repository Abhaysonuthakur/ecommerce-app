package com.shop.ecommerce.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Query parameters for the product list endpoint.
 *
 * <p>Every field is optional, and a request with an empty query string returns the first
 * page of everything. Filters combine with AND: {@code ?categoryId=1&minPrice=500} means
 * "in category 1 <b>and</b> priced at least 500".
 *
 * <h2>Why a record with defaults instead of twelve {@code @RequestParam} arguments</h2>
 *
 * <p>Spring binds query parameters onto a record's components. That buys three things a
 * long argument list does not:
 * <ul>
 *   <li><b>Defaults in one place.</b> The compact constructor below is exactly the
 *       mechanism that makes defaults-plus-constraints work: it runs <em>before</em>
 *       validation, so {@code page} is already {@code 0} by the time {@code @Min(0)}
 *       checks it. Nulls the client omitted never reach the constraints.</li>
 *   <li><b>Passing one object around.</b> The service takes this record rather than six
 *       positional arguments, so adding a filter later does not change every signature.</li>
 *   <li><b>A single place to document.</b> Swagger reads the record, so the API docs and
 *       the code cannot disagree.</li>
 * </ul>
 *
 * <h2>Why {@code sort} is parsed by hand</h2>
 *
 * <p>Spring splits a comma-separated request value <em>before</em> binding it to a
 * collection. So {@code ?sort=price,desc} bound to a {@code List<String>} arrives as
 * {@code ["price", "desc"]} - the field and direction have been torn apart and there is
 * no way to tell which was which. Binding to a single {@code String} receives the value
 * whole, so the service can split it deliberately and reject a malformed form with a
 * clear message.
 *
 * <p>The trade-off, stated plainly: with a single {@code String} target, only the first
 * value survives if a client sends {@code ?sort=a,asc&sort=b,desc}. Supporting that
 * would need a custom argument resolver reading the raw parameter values. This API
 * documents a single sort expression instead, and validates it.
 */
@Schema(description = "Filters, sorting and pagination for the product list")
public record ProductFilter(

        @Schema(example = "0", description = "Zero-based page index")
        @Min(value = 0, message = "Page index must not be negative")
        Integer page,

        /*
         * The cap is the point. Without an upper bound, ?size=1000000 is a
         * denial-of-service primitive: one request makes the server load the entire
         * table into memory. 100 is generous for a grid UI and harmless for the database.
         */
        @Schema(example = "12", description = "Page size, 1-100")
        @Min(value = 1, message = "Page size must be at least 1")
        @Max(value = 100, message = "Page size must not exceed 100")
        Integer size,

        @Schema(example = "linen", description = "Case-insensitive search across name and description")
        @Size(max = 100, message = "Search keyword must not exceed 100 characters")
        String keyword,

        @Schema(example = "1", description = "Restrict to a single category")
        Long categoryId,

        @Schema(example = "500.00", description = "Minimum price, inclusive")
        BigDecimal minPrice,

        @Schema(example = "2000.00", description = "Maximum price, inclusive")
        BigDecimal maxPrice,

        /*
         * A boxed Boolean so that "not supplied" is different from "false".
         *
         * The storefront omits it and gets only active products (the service defaults to
         * true). The admin console sends false to list withdrawn stock. A primitive
         * boolean would make the storefront's default depend on a client remembering to
         * send it - and a client that forgot would publish deactivated products.
         */
        @Schema(example = "true", description = "Defaults to true - only active products")
        Boolean active,

        /*
         * Format: "field,direction" - e.g. "price,desc". Validated by pattern rather
         * than by a whitelist here, because the whitelist depends on the entity's
         * fields and belongs next to the query that uses it (see ProductSpecifications).
         * Validating it here would mean the allowed set exists in two places.
         */
        @Schema(example = "price,desc",
                description = "One of: name, price, stock, createdAt, updatedAt - optionally followed by ',asc' or ',desc'")
        @Pattern(regexp = "^[A-Za-z]{2,20}(,(asc|desc))?$",
                message = "Sort must look like 'field' or 'field,asc' or 'field,desc'")
        String sort

) {

    /**
     * Fills in defaults for omitted parameters.
     *
     * <p><b>A record's compact constructor runs before validation.</b> That ordering is
     * what makes this work at all: {@code page} is set to 0 here, and only then does
     * {@code @Min(0)} evaluate it. If validation ran first, an omitted page would be null
     * and {@code @Min} would fail on a value the client never sent.
     */
    public ProductFilter {
        if (page == null) {
            page = 0;
        }
        if (size == null) {
            size = 12;   // a grid-friendly default: 12 fits 2/3/4-column layouts evenly
        }
        if (active == null) {
            active = true;
        }
        if (keyword != null) {
            keyword = keyword.trim();
            if (keyword.isEmpty()) {
                keyword = null;   // treat "?keyword=" as "no keyword" rather than "match nothing"
            }
        }
        if (sort == null || sort.isBlank()) {
            // Newest first is what a shopper expects from a product grid, and since
            // ProductSort appends id as a tiebreaker, the order is total and stable.
            sort = "createdAt,desc";
        }
    }

    /**
     * Whether a price range was supplied, in either direction.
     *
     * <p>The service calls this rather than comparing the two bounds, because "minimum
     * above maximum" is a different error from "no range given" and deserves a different
     * message.
     */
    public boolean hasPriceRange() {
        return minPrice != null || maxPrice != null;
    }

    /**
     * Whether a search keyword was supplied.
     *
     * <p>Exists so the service asks one question instead of three: {@code keyword != null
     * && !keyword.isBlank()}. The constructor already normalises a blank keyword to null,
     * so this is a single null check at the point of use - and there is exactly one place
     * that decides what "no keyword" means.
     */
    public boolean hasKeyword() {
        return keyword != null;
    }
}
