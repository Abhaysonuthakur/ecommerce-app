package com.shop.ecommerce.service.impl;

import com.shop.ecommerce.dto.common.PageResponse;
import com.shop.ecommerce.dto.product.ProductFilter;
import com.shop.ecommerce.dto.product.ProductRequest;
import com.shop.ecommerce.dto.product.ProductResponse;
import com.shop.ecommerce.entity.Category;
import com.shop.ecommerce.entity.Product;
import com.shop.ecommerce.exception.BadRequestException;
import com.shop.ecommerce.exception.ConflictException;
import com.shop.ecommerce.exception.ResourceNotFoundException;
import com.shop.ecommerce.mapper.EntityMapper;
import com.shop.ecommerce.mapper.EntityWriteMapper;
import com.shop.ecommerce.repository.CategoryRepository;
import com.shop.ecommerce.repository.ProductRepository;
import com.shop.ecommerce.repository.specification.ProductSpecifications;
import com.shop.ecommerce.repository.specification.SortValidator;
import com.shop.ecommerce.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product catalogue.
 *
 * <h2>The three things this class exists to get right</h2>
 *
 * <p><b>1. Listing is one composable query.</b> Search, category, price range, active flag,
 * sort and pagination all arrive in a single {@link ProductFilter} and become one
 * {@code Specification} plus one {@link Pageable}. There is no
 * {@code listByCategory}/{@code searchByKeyword}/{@code listCheap} family of methods, because
 * that family needs a new method for every combination the UI invents - six filters is
 * sixty-four endpoints if you enumerate them.
 *
 * <p><b>2. Prices are never taken from the request at checkout.</b> {@code ProductRequest.price}
 * is how an admin <em>sets</em> a price. Nothing a customer sends is ever read as a price
 * here; the order service copies the column value at placement time.
 *
 * <p><b>3. Deletion is refused, not failed.</b> {@code order_items.product_id} is
 * {@code ON DELETE RESTRICT}, so a sold product cannot be removed. Without the check in
 * {@link #delete}, the database refuses at commit and the caller sees a 500 for what is
 * really "you have sold this, archive it instead".
 */
@Service
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);

    /** Used when the filter carries no sort - newest first, which is what a shop grid expects. */
    private static final String DEFAULT_SORT_FIELD = "createdAt";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final EntityMapper entityMapper;
    private final EntityWriteMapper writeMapper;

    public ProductServiceImpl(ProductRepository productRepository,
                              CategoryRepository categoryRepository,
                              EntityMapper entityMapper,
                              EntityWriteMapper writeMapper) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.entityMapper = entityMapper;
        this.writeMapper = writeMapper;
    }

    // -----------------------------------------------------------------
    //  Reads (public)
    // -----------------------------------------------------------------

    @Override
    public PageResponse<ProductResponse> list(ProductFilter filter) {
        validatePriceRange(filter);

        Pageable pageable = toPageable(filter);

        /*
         * The filter is already a non-null record with defaults applied by its compact
         * constructor, so there is no null check here and no "did the client send active?"
         * branch. ProductFilter.active is a boxed Boolean that defaults to TRUE for the
         * storefront, so a request that forgets it cannot accidentally publish withdrawn
         * products.
         */
        Page<Product> page = productRepository.findAll(ProductSpecifications.from(filter), pageable);

        PageResponse<ProductResponse> response =
                PageResponse.from(page, entityMapper::toProductResponse);

        /*
         * The effective sort is echoed back, tiebreaker included. A client that stored this
         * value and returned it would be accepted, because "id" is in
         * SortValidator.PRODUCT_SORT_FIELDS - without that the echoed value would be
         * rejected on the very next request.
         */
        log.debug("Product list page={} size={} sort={} total={}",
                filter.page(), filter.size(), SortValidator.describe(pageable.getSort()),
                page.getTotalElements());

        return response;
    }

    @Override
    public ProductResponse getById(Long id) {
        /*
         * findByIdAndActiveTrue rather than findById plus a visibility check. The filter is
         * in the query, so a withdrawn product is indistinguishable from a nonexistent one -
         * which is exactly right for a public endpoint: a shopper must not be able to probe
         * "/api/products/7" and learn that a draft product exists.
         */
        Product product = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> ResourceNotFoundException.product(id));
        return entityMapper.toProductResponse(product);
    }

    @Override
    public long countActive() {
        return productRepository.countByActiveTrue();
    }

    // -----------------------------------------------------------------
    //  Writes (admin)
    // -----------------------------------------------------------------

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse create(ProductRequest request) {
        /*
         * A missing category is a 404, not a validation error. The DTO's @NotNull catches
         * "no category supplied"; "a category id that does not exist" is a reference
         * problem, and 404 with the category's id in the message is the only response that
         * tells the admin what to fix.
         */
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> ResourceNotFoundException.category(request.categoryId()));

        Product product = writeMapper.toProductEntity(request);
        product.setName(request.name().trim());
        product.setCategory(category);
        if (request.active() == null) {
            // Absent means "on sale" on create. The boxed Boolean is what makes this
            // distinguishable from an explicit false.
            product.setActive(true);
        }

        Product saved = productRepository.save(product);
        log.debug("Created product id={} name='{}' category={}", saved.getId(), saved.getName(), category.getId());
        return entityMapper.toProductResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.product(id));

        /*
         * The category may be changing, so it is re-resolved on every update rather than
         * only when the id differs. Fetching a category by primary key is a single indexed
         * lookup; an "is it different?" branch would save nothing and would need its own
         * test.
         */
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> ResourceNotFoundException.category(request.categoryId()));

        writeMapper.updateProductEntity(request, product);
        product.setCategory(category);

        // null means "do not change visibility" - the whole point of the boxed Boolean.
        // An admin fixing a typo in the description must not take the product off sale.
        if (request.active() != null) {
            product.setActive(request.active());
        }

        /*
         * Flush before mapping. updatedAt is maintained by Spring Data auditing
         * (@LastModifiedDate) and that value is written at flush time, not when the setter
         * runs. Mapping first would return the previous updatedAt, and a client that
         * re-read the product would see a timestamp that contradicts the change it just
         * made.
         */
        productRepository.flush();

        return entityMapper.toProductResponse(product);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.product(id));

        /*
         * The check that turns a 500 into a 409.
         *
         * order_items.product_id is ON DELETE RESTRICT, so the database will refuse this
         * delete for any product that appears in an order. Without this guard, the refusal
         * happens at flush - after the method has already returned - and the caller sees a
         * DataIntegrityViolationException translated into a generic 500.
         *
         * Asking first costs one indexed count and produces a message that names the real
         * problem: this product is part of order history, so deactivate it instead. Order
         * history is not negotiable: an old order that cannot render its own line items is
         * a broken invoice.
         */
        if (productRepository.isProductOrdered(id)) {
            throw ConflictException.productInUse(product.getName());
        }

        /*
         * CartItem rows for this product are cleared by the caller of the repository
         * cascade - in practice nothing else references a product except cart_items
         * (transient, safe to drop) and order_items (permanent, blocked above). So once
         * isProductOrdered has said no, the delete is guaranteed to succeed.
         */
        productRepository.delete(product);
        log.info("Deleted product id={} name='{}'", id, product.getName());
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse deactivate(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.product(id));

        product.setActive(false);
        productRepository.flush();

        log.info("Deactivated product id={} name='{}'", id, product.getName());
        return entityMapper.toProductResponse(product);
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    /**
     * Rejects a price range that can never match anything.
     *
     * <p>{@code minPrice = 2000, maxPrice = 500} is not a validation failure - both values
     * are individually valid prices. It is a contradiction, and the honest answer is a 400
     * explaining the contradiction rather than a 200 with an empty list, which a client
     * would reasonably read as "we do not stock anything in that range".
     */
    private void validatePriceRange(ProductFilter filter) {
        if (filter.minPrice() != null && filter.maxPrice() != null
                && filter.minPrice().compareTo(filter.maxPrice()) > 0) {
            throw new BadRequestException(
                    "Minimum price (" + filter.minPrice() + ") must not exceed maximum price ("
                            + filter.maxPrice() + ").");
        }
    }

    private Pageable toPageable(ProductFilter filter) {
        Sort sort;
        try {
            sort = SortValidator.parse(filter.sort(), SortValidator.PRODUCT_SORT_FIELDS, DEFAULT_SORT_FIELD);
        } catch (IllegalArgumentException ex) {
            /*
             * SortValidator throws IllegalArgumentException, which by default becomes a
             * 500. It is a client error - the client sent a field that is either misspelt
             * or not sortable - so it is translated into a 400 here rather than in the
             * exception handler, because only this call site knows the failure was about a
             * sort expression.
             */
            throw new BadRequestException(ex.getMessage());
        }
        return PageRequest.of(filter.page(), filter.size(), sort);
    }
}
