package com.shop.ecommerce.service;

import com.shop.ecommerce.dto.category.CategoryRequest;
import com.shop.ecommerce.dto.category.CategoryResponse;

import java.util.List;

/**
 * Category management. Reads are public; writes are admin-only, enforced by
 * {@code @PreAuthorize} on the implementation.
 */
public interface CategoryService {

    /** Every active category, for the storefront's navigation. */
    List<CategoryResponse> listActive();

    /** Every category including inactive ones, for the admin console. */
    List<CategoryResponse> listAll();

    CategoryResponse getById(Long id);

    /** @throws com.shop.ecommerce.exception.ConflictException if the name is taken */
    CategoryResponse create(CategoryRequest request);

    CategoryResponse update(Long id, CategoryRequest request);

    /**
     * Deactivates a category.
     *
     * <p>A soft delete, always. The database has {@code ON DELETE RESTRICT} from products to
     * categories, so a category holding products <em>cannot</em> be deleted - and the service
     * says so clearly rather than letting a foreign-key error surface as a 500.
     */
    CategoryResponse deactivate(Long id);
}
