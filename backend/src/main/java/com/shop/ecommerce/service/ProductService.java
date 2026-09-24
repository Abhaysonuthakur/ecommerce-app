package com.shop.ecommerce.service;

import com.shop.ecommerce.dto.common.PageResponse;
import com.shop.ecommerce.dto.product.ProductFilter;
import com.shop.ecommerce.dto.product.ProductRequest;
import com.shop.ecommerce.dto.product.ProductResponse;

/**
 * Product catalogue. Reads are public; writes are admin-only.
 */
public interface ProductService {

    /**
     * The storefront list: filters, search, sort and pagination combined.
     *
     * <p>A single method rather than one per filter combination, because the filters compose.
     * The {@code active} flag in the filter decides whether withdrawn products are included.
     */
    PageResponse<ProductResponse> list(ProductFilter filter);

    ProductResponse getById(Long id);

    /** @throws com.shop.ecommerce.exception.ConflictException on a duplicate name */
    ProductResponse create(ProductRequest request);

    ProductResponse update(Long id, ProductRequest request);

    /**
     * Permanently removes a product, or refuses to.
     *
     * <p>A product that has ever been sold cannot be deleted: {@code order_items.product_id}
     * is {@code ON DELETE RESTRICT} so the order history survives. The service checks first
     * and raises a 409 naming the reason, rather than letting the foreign key produce a 500.
     */
    void delete(Long id);

    /** Soft delete: withdrawn from sale but still visible to admins. */
    ProductResponse deactivate(Long id);

    long countActive();
}
