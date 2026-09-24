package com.shop.ecommerce.controller;

import com.shop.ecommerce.dto.category.CategoryRequest;
import com.shop.ecommerce.dto.category.CategoryResponse;
import com.shop.ecommerce.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Category endpoints.
 *
 * <h2>Why the reads and the writes share one controller</h2>
 *
 * <p>The alternative - a public {@code CategoryController} and an admin
 * {@code AdminCategoryController} - splits one resource's operations across two files that
 * must be kept in step. What actually protects the writes is not which class they live in
 * but the {@code @PreAuthorize("hasRole('ADMIN')")} on each method and the URL rules in
 * {@code SecurityConfig}, and the methods here are annotated so a reader can see which is
 * which at a glance.
 *
 * <p>The path prefix differs deliberately: reads answer {@code /api/categories/*}, writes
 * answer {@code /api/admin/categories/*}. That is not cosmetic - it means the filter chain
 * can protect the whole {@code /api/admin/**} prefix as a belt to the {@code @PreAuthorize}
 * braces, so a new admin endpoint added here and forgotten is still protected by the URL
 * rule. Two independent layers, neither relying on the other.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Categories", description = "Category catalogue (public reads, admin writes)")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    // =================================================================
    //  Public reads
    // =================================================================

    /**
     * Active categories, for the storefront navigation and filter sidebar.
     *
     * <p><b>Not paginated, deliberately.</b> A category list is a menu, and a menu is
     * complete or it is broken: a shopper cannot page through "browse by category". The set
     * is small by nature - a shop with four hundred categories has a navigation problem that
     * pagination would hide rather than solve. Each item carries a {@code productCount}, so
     * a UI can hide empty categories without a second request.
     */
    @GetMapping("/categories")
    @Operation(summary = "List active categories",
            description = "Unpaginated - a category list is a navigation menu. Includes a product count per category.")
    @ApiResponse(responseCode = "200", description = "The active categories, ordered by name")
    public ResponseEntity<List<CategoryResponse>> listActive() {
        return ResponseEntity.ok(categoryService.listActive());
    }

    @GetMapping("/categories/{id}")
    @Operation(summary = "Get one category")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The category"),
            @ApiResponse(responseCode = "404", description = "No category with that id")
    })
    public ResponseEntity<CategoryResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(categoryService.getById(id));
    }

    // =================================================================
    //  Admin reads
    // =================================================================

    /**
     * Every category, including deactivated ones.
     *
     * <p>Exposed under {@code /api/admin/**} rather than as a query parameter on the public
     * list ({@code ?includeInactive=true}). The parameter version requires the public
     * endpoint's authorization to depend on a parameter value, which is the shape of bug
     * where a client passes the magic value and reads data it should not. Two paths, two
     * rules, no branch.
     */
    @GetMapping("/admin/categories")
    @Operation(summary = "List all categories including inactive (admin)",
            description = "The admin console's view. Requires the ADMIN role.")
    @ApiResponse(responseCode = "200", description = "Every category, ordered by name")
    public ResponseEntity<List<CategoryResponse>> listAll() {
        return ResponseEntity.ok(categoryService.listAll());
    }

    // =================================================================
    //  Admin writes
    // =================================================================

    /**
     * Creates a category.
     *
     * <p>201 with a {@code Location} header would be the fullest response, but this API's
     * list endpoint is not addressable per item in a way a client needs to follow, so the
     * body is returned without it. What matters is that the client gets the created id and
     * the derived slug back immediately, rather than having to re-query.
     */
    @PostMapping("/admin/categories")
    @Operation(summary = "Create a category (admin)",
            description = "The slug is derived from the name on the server; it is not accepted from the client.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(schema = @Schema(implementation = CategoryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Not an admin"),
            @ApiResponse(responseCode = "409", description = "A category with that name already exists")
    })
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        CategoryResponse created = categoryService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Updates a category.
     *
     * <p>{@code PUT} rather than {@code PATCH}, despite the fact that {@code active} is
     * optional and treated as "leave unchanged". The resource is replaced by the submitted
     * representation; the one optional field exists so a rename does not silently take a
     * category off sale, not to make this a partial update. Documenting that here is more
     * honest than choosing PATCH and then sending a mostly-complete body to it.
     */
    @PutMapping("/admin/categories/{id}")
    @Operation(summary = "Update a category (admin)",
            description = "Renames regenerate the slug. Omitting `active` leaves visibility unchanged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Not an admin"),
            @ApiResponse(responseCode = "404", description = "No category with that id"),
            @ApiResponse(responseCode = "409", description = "Another category already uses that name")
    })
    public ResponseEntity<CategoryResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.ok(categoryService.update(id, request));
    }

    /**
     * Deactivates a category.
     *
     * <p><b>PATCH, not DELETE - and there is no DELETE.</b> A category is referenced by its
     * products with {@code ON DELETE RESTRICT}, so deletion is impossible for any category
     * that holds stock, and a hard delete of an empty one would still orphan the intent of
     * any report that grouped by it. Deactivation is the operation that exists: the category
     * disappears from the storefront and remains visible to admins.
     *
     * <p>Note the deliberate absence of a DELETE mapping. A client that sends one gets a
     * 405 with a message, which is a clearer outcome than a 200 from an endpoint that
     * quietly did something other than what it was asked.
     */
    @PatchMapping("/admin/categories/{id}/deactivate")
    @Operation(summary = "Deactivate a category (admin)",
            description = "Soft delete. Categories are never hard-deleted because products reference them. "
                    + "Deactivating a category does not deactivate its products.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deactivated"),
            @ApiResponse(responseCode = "403", description = "Not an admin"),
            @ApiResponse(responseCode = "404", description = "No category with that id")
    })
    public ResponseEntity<CategoryResponse> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(categoryService.deactivate(id));
    }
}
