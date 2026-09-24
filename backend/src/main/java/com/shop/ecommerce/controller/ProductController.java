package com.shop.ecommerce.controller;

import com.shop.ecommerce.dto.common.MessageResponse;
import com.shop.ecommerce.dto.common.PageResponse;
import com.shop.ecommerce.dto.product.ProductFilter;
import com.shop.ecommerce.dto.product.ProductRequest;
import com.shop.ecommerce.dto.product.ProductResponse;
import com.shop.ecommerce.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Product catalogue endpoints.
 *
 * <h2>The list endpoint is the interesting one</h2>
 *
 * <p>{@link #list} takes six optional query parameters and returns one paginated result.
 * There are no {@code /products/search}, {@code /products/filter} or
 * {@code /products/by-category} variants, and that is a deliberate design choice rather
 * than laziness: filters compose, so enumerating the combinations needs one route per
 * combination. Six binary filters is sixty-four routes; adding a seventh doubles it.
 *
 * <p>Spring binds the individual {@code @RequestParam}s onto the {@link ProductFilter}
 * record, whose compact constructor fills in defaults before validation runs. That ordering
 * is what makes "optional parameter with a default" and "the parameter has a constraint"
 * coexist - see the record's javadoc.
 *
 * <h2>★ Why {@link #list} calls the validator by hand</h2>
 *
 * <p>This is the subtle part, and it was a real bug before it was fixed.
 *
 * <p>{@code ProductFilter} carries {@code @Min(0)} on {@code page}, {@code @Min(1) @Max(100)}
 * on {@code size}, and a {@code @Pattern} on {@code sort}. It is constructed here with
 * {@code new ProductFilter(...)} because the parameters are declared individually so Swagger
 * renders them with their own descriptions and examples.
 *
 * <p><b>Bean Validation does not run on a plain constructor call.</b> Constraints are
 * evaluated only when an object is bound by Spring ({@code @ModelAttribute}), passed through
 * {@code @Valid} on a {@code @RequestBody}, or explicitly handed to a {@code Validator}.
 * None of those happened here, so every constraint on the record was inert - decoration that
 * read like protection.
 *
 * <p>The visible consequence: {@code ?size=1000000} was accepted and forwarded to
 * {@code PageRequest.of(...)}, asking MySQL for a million rows. The record's own comment on
 * that cap says "Without an upper bound, ?size=1000000 is a..." - the guard it describes
 * simply did not exist. The 400 promised by this endpoint's {@code @ApiResponse} was equally
 * fictional.
 *
 * <p>Declaring a constraint is not enforcing one. Since the parameters must stay individual
 * for the documentation, the fix is to validate the constructed record explicitly. The
 * resulting {@link ConstraintViolationException} is mapped to a 400 with per-field detail by
 * the same {@code @RestControllerAdvice} that handles {@code @Valid} failures, so the error
 * contract is unchanged.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Products", description = "Product catalogue: browse, search, filter (public) and manage (admin)")
public class ProductController {

    private final ProductService productService;

    /**
     * Injected so {@link #list} can evaluate the constraints on a hand-constructed
     * {@link ProductFilter}. Spring Boot auto-configures this from the validation starter.
     */
    private final Validator validator;

    public ProductController(ProductService productService, Validator validator) {
        this.productService = productService;
        this.validator = validator;
    }

    /**
     * Rejects a filter that violates its own declared constraints.
     *
     * <p>Throws the same {@link ConstraintViolationException} that Spring's own binding
     * produces, so it reaches the existing handler and the client sees one error contract
     * regardless of whether a constraint failed on a query parameter or a request body.
     */
    private void validateFilter(ProductFilter filter) {
        Set<ConstraintViolation<ProductFilter>> violations = validator.validate(filter);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    // =================================================================
    //  Public reads
    // =================================================================

    /**
     * The storefront list: search, filter, sort and paginate in one endpoint.
     *
     * <p>Every parameter is optional, and an empty query string returns the first page of
     * active products, newest first.
     *
     * <p><b>Why five separate {@code @RequestParam} declarations rather than a
     * {@code @ModelAttribute ProductFilter}.</b> Spring can bind either way, but Swagger
     * renders named parameters with their own {@code @Parameter} descriptions, constraints
     * and examples; an object-bound model renders as a single opaque schema in some
     * configurations. Since this endpoint's documentation is a genuine part of its usability
     * - it is the one route a client will call most - the explicit form is worth the extra
     * lines. The record still does the work; it is constructed at the end of the parameter
     * list.
     *
     * <p>Because the record is constructed by hand rather than bound, its constraints have to
     * be evaluated by hand too - see the note on this class. Doing so is what makes
     * {@code ?size=1000} a 400 instead of a request for a thousand rows.
     */
    @GetMapping("/products")
    @Operation(summary = "List products with search, filtering, sorting and pagination",
            description = """
                    All parameters are optional and combine with AND.
                    `active` defaults to true, so the storefront never sees withdrawn products.
                    `sort` is "field,direction" and accepts name, price, stock, active, createdAt, updatedAt, id.
                    The effective sort (including the id tiebreaker) is echoed in the response headers' Vary-free form only if requested - see the admin list.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of products"),
            @ApiResponse(responseCode = "400", description = "Invalid page, size, price range, or an unsortable field")
    })
    public ResponseEntity<PageResponse<ProductResponse>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) java.math.BigDecimal minPrice,
            @RequestParam(required = false) java.math.BigDecimal maxPrice,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String sort) {

        ProductFilter filter = new ProductFilter(
                page, size, keyword, categoryId, minPrice, maxPrice, active, sort);

        validateFilter(filter);   // <- see the class note: a plain constructor call validates nothing

        return ResponseEntity.ok(productService.list(filter));
    }

    /**
     * One product's full detail.
     *
     * <p>Only active products are visible here. A withdrawn product returns a 404 rather
     * than a 200 with an {@code active: false} flag, because from a shopper's point of view
     * the two are the same fact, and telling them apart lets anyone enumerate the draft
     * catalogue by id. Admins read inactive products through the admin list.
     */
    @GetMapping("/products/{id}")
    @Operation(summary = "Get one active product")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The product"),
            @ApiResponse(responseCode = "404", description = "No active product with that id")
    })
    public ResponseEntity<ProductResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getById(id));
    }

    // =================================================================
    //  Admin
    // =================================================================

    /**
     * Creates a product.
     *
     * <p>Note what the admin cannot do: set a price with more than two decimal places. The
     * column is {@code DECIMAL(19,2)} and MySQL <b>silently rounds</b> - the probe in
     * {@code db/constraint-test.sql} stores {@code 10.999} as {@code 11.00} with no warning.
     * {@code @Digits} on the DTO rejects it at the boundary, where the admin can see the
     * error, rather than letting the database round it and reporting a different number back.
     */
    @PostMapping("/admin/products")
    @Operation(summary = "Create a product (admin)",
            description = "Price must have at most 2 decimal places. `active` defaults to true when omitted.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Not an admin"),
            @ApiResponse(responseCode = "404", description = "The referenced category does not exist")
    })
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Updates a product.
     *
     * <p>Omitting {@code active} leaves visibility unchanged, which is what stops an admin
     * fixing a typo in a description from silently taking the product off sale. The field is
     * a boxed {@code Boolean} on the DTO precisely so "absent" and "false" are different
     * values rather than the same one.
     */
    @PutMapping("/admin/products/{id}")
    @Operation(summary = "Update a product (admin)",
            description = "Omitting `active` leaves visibility unchanged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Not an admin"),
            @ApiResponse(responseCode = "404", description = "No product with that id, or the category does not exist")
    })
    public ResponseEntity<ProductResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(productService.update(id, request));
    }

    /**
     * Withdraws a product from sale.
     *
     * <p>The safe alternative to deletion, and the one an admin should reach for in almost
     * every case. Products referenced by an order cannot be deleted at all - the foreign key
     * is {@code ON DELETE RESTRICT} so that order history stays readable - so deactivation is
     * the operation that always works and never damages a record.
     */
    @PatchMapping("/admin/products/{id}/deactivate")
    @Operation(summary = "Deactivate a product (admin)",
            description = "Soft delete: hidden from the storefront, still visible to admins. Always succeeds, "
                    + "unlike DELETE, which is blocked for anything that has been sold.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deactivated"),
            @ApiResponse(responseCode = "403", description = "Not an admin"),
            @ApiResponse(responseCode = "404", description = "No product with that id")
    })
    public ResponseEntity<ProductResponse> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(productService.deactivate(id));
    }

    /**
     * Permanently removes a product.
     *
     * <p>Refused with a 409 for any product that appears in an order. That check is a
     * service-level query, not the database's foreign key, and the difference is visible to
     * the caller: the FK refusal would surface as a 500 with a constraint name in the log,
     * whereas the explicit check produces a 409 whose message names the product and says to
     * deactivate it instead.
     *
     * <p>Returns a body rather than 204. A 204 success and a request that never arrived look
     * identical in a network tab; a small confirmation is cheaper than the debugging session.
     */
    @DeleteMapping("/admin/products/{id}")
    @Operation(summary = "Permanently delete a product (admin)",
            description = "Only possible for a product that has never been ordered. Use deactivate instead for anything sold.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deleted",
                    content = @Content(schema = @Schema(implementation = MessageResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not an admin"),
            @ApiResponse(responseCode = "404", description = "No product with that id"),
            @ApiResponse(responseCode = "409", description = "The product appears in an order and cannot be deleted")
    })
    public ResponseEntity<MessageResponse> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.ok(MessageResponse.of("Product " + id + " deleted successfully"));
    }
}
