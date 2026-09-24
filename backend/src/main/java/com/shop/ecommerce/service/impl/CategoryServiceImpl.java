package com.shop.ecommerce.service.impl;

import com.shop.ecommerce.dto.category.CategoryRequest;
import com.shop.ecommerce.dto.category.CategoryResponse;
import com.shop.ecommerce.entity.Category;
import com.shop.ecommerce.exception.ConflictException;
import com.shop.ecommerce.exception.ResourceNotFoundException;
import com.shop.ecommerce.mapper.EntityMapper;
import com.shop.ecommerce.mapper.EntityWriteMapper;
import com.shop.ecommerce.repository.CategoryRepository;
import com.shop.ecommerce.repository.ProductRepository;
import com.shop.ecommerce.service.CategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Category management.
 *
 * <h2>Two decisions worth reading</h2>
 *
 * <p><b>1. The slug is derived, never accepted.</b> {@link CategoryRequest} has no slug
 * field, so the only way a category acquires one is {@link #slugify}. That means there is
 * exactly one place where "Men's Shirts" becomes {@code mens-shirts}, and exactly one place
 * to change it. A client-supplied slug would be a second unique constraint to reconcile
 * against the name, and the two can disagree.
 *
 * <p><b>2. Deletion is a soft delete, and the reason is a database constraint.</b>
 * {@code products.category_id} is {@code ON DELETE RESTRICT}, so a category with products
 * cannot be removed - the database will refuse, and that refusal surfaces as a
 * {@code DataIntegrityViolationException} at commit time, far from the code that caused it,
 * producing a 500 for what is really a user error. Rather than let that happen we never
 * issue a {@code DELETE} at all: {@link #deactivate} flips a flag.
 *
 * <p>The count-based eviction of products is deliberately <em>not</em> what blocks deletion
 * here - {@code RESTRICT} blocks it for every non-empty category. The soft delete makes the
 * question moot and keeps order history (which references products, which reference
 * categories) intact forever.
 */
@Service
@Transactional(readOnly = true)
public class CategoryServiceImpl implements CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryServiceImpl.class);

    /** Fallback slug for a name that slugifies to nothing, e.g. a name written entirely in
     *  a script with no ASCII equivalent. Rare, but "unslugifiable input" must have an answer. */
    private static final String FALLBACK_SLUG = "category";

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final EntityMapper entityMapper;
    private final EntityWriteMapper writeMapper;

    public CategoryServiceImpl(CategoryRepository categoryRepository,
                               ProductRepository productRepository,
                               EntityMapper entityMapper,
                               EntityWriteMapper writeMapper) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.entityMapper = entityMapper;
        this.writeMapper = writeMapper;
    }

    // -----------------------------------------------------------------
    //  Reads (public)
    // -----------------------------------------------------------------

    @Override
    public List<CategoryResponse> listActive() {
        List<Category> categories = categoryRepository.findByActiveTrueOrderByNameAsc();
        return withProductCounts(categories);
    }

    @Override
    public List<CategoryResponse> listAll() {
        List<Category> categories = categoryRepository.findAllByOrderByNameAsc(Pageable.unpaged()).getContent();
        return withProductCounts(categories);
    }

    @Override
    public CategoryResponse getById(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.category(id));
        long productCount = categoryRepository.countProductsInCategory(id);
        return withProductCount(entityMapper.toCategoryResponse(category), productCount);
    }

    // -----------------------------------------------------------------
    //  Writes (admin)
    // -----------------------------------------------------------------

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse create(CategoryRequest request) {
        String name = request.name().trim();

        /*
         * The pre-check exists to produce a good error message, not to guarantee uniqueness.
         * Two concurrent admins can both pass this line and both reach the insert; the
         * guarantee is the unique index on the table, and the loser of that race gets a
         * DataIntegrityViolationException which the global handler turns into the same 409.
         * Checking here simply means the common case never touches the exception path.
         */
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw ConflictException.categoryNameExists(name);
        }

        Category category = writeMapper.toCategoryEntity(request);
        category.setName(name);
        category.setSlug(uniqueSlug(slugify(name), null));
        if (request.active() == null) {
            /*
             * Create defaults to visible. The boxed Boolean on the DTO distinguishes "not
             * supplied" from "explicitly false"; on create, not supplied means true, because
             * an admin who creates a category almost certainly wants to use it.
             */
            category.setActive(true);
        }

        Category saved = categoryRepository.save(category);
        log.debug("Created category id={} slug={}", saved.getId(), saved.getSlug());
        return withProductCount(entityMapper.toCategoryResponse(saved), 0L);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.category(id));

        String name = request.name().trim();

        if (categoryRepository.existsByNameIgnoreCaseExcluding(name, id)) {
            throw ConflictException.categoryNameExists(name);
        }

        writeMapper.updateCategoryEntity(request, category);
        category.setName(name);

        /*
         * Renaming a category regenerates its slug. This is the one place a slug can change,
         * and the uniqueSlug call below passes the current id so the category is allowed to
         * keep its own slug rather than colliding with itself.
         */
        category.setSlug(uniqueSlug(slugify(name), id));

        /*
         * null means "leave the visibility alone". Without this branch, an admin editing only
         * the description would deactivate the category, because MapStruct's ignored-null
         * strategy would have left active as-is but the DTO defaulting story is easier to
         * read if the intent is stated explicitly here than inferred from a mapper setting.
         */
        if (request.active() != null) {
            category.setActive(request.active());
        }

        // Flush so @LastModifiedDate is applied before the response is mapped - the auditing
        // timestamp is written at flush time, so mapping first would return the old value.
        categoryRepository.flush();

        long productCount = categoryRepository.countProductsInCategory(id);
        return withProductCount(entityMapper.toCategoryResponse(category), productCount);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse deactivate(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.category(id));

        category.setActive(false);
        categoryRepository.flush();

        long productCount = categoryRepository.countProductsInCategory(id);
        log.debug("Deactivated category id={} ({} products unaffected)", id, productCount);
        return withProductCount(entityMapper.toCategoryResponse(category), productCount);
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    /**
     * Attaches a live product count to every category in one extra query.
     *
     * <p>The obvious alternative - calling {@code countProductsInCategory} per category -
     * is the classic N+1: N categories means N+1 queries, and the page gets slower as the
     * catalogue grows. {@link CategoryRepository#countProductsPerCategory()} does it with
     * one grouped query, and this method joins the result in memory.
     */
    private List<CategoryResponse> withProductCounts(List<Category> categories) {
        if (categories.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : categoryRepository.countProductsPerCategory()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return categories.stream()
                .map(category -> withProductCount(
                        entityMapper.toCategoryResponse(category),
                        counts.getOrDefault(category.getId(), 0L)))
                .toList();
    }

    /**
     * Returns a copy of {@code response} carrying the given product count.
     *
     * <p>{@link CategoryResponse} is a record, so it is immutable - there is no setter and
     * {@code productCount} cannot be patched in place. The two options are to rebuild it
     * through its canonical constructor here, or to have MapStruct leave the field at zero
     * and mutate it afterwards (impossible). Rebuilding is the only correct choice, and
     * confining it to one private method means the nine-argument constructor appears in
     * exactly one place.
     *
     * <p>Why not let MapStruct fill it? Because the count is not on {@link Category} - it
     * comes from a second query. A mapper maps one object to one object; a value that
     * requires its own database round trip is the service's job.
     */
    private static CategoryResponse withProductCount(CategoryResponse response, long productCount) {
        if (response.productCount() == productCount) {
            return response;
        }
        return new CategoryResponse(
                response.id(),
                response.name(),
                response.slug(),
                response.description(),
                response.imageUrl(),
                response.active(),
                productCount,
                response.createdAt(),
                response.updatedAt());
    }

    /**
     * Produces a URL-safe slug from a display name.
     *
     * <ol>
     *   <li>{@code Normalizer.normalize(form = NFD)} splits accented characters into a base
     *       letter plus a combining mark - "café" becomes "cafe" + U+0301 - so the mark can
     *       be stripped rather than transliterated or dropped along with the letter.</li>
     *   <li>The {@code \\p{M}} strip removes those combining marks.</li>
     *   <li>{@code Locale.ROOT} on {@code toLowerCase} avoids the Turkish-locale trap where
     *       "I".toLowerCase() is "ı" (dotless), which would silently produce a different slug
     *       depending on the server's locale.</li>
     *   <li>Collapse any run of non-alphanumerics into a single hyphen, then trim the
     *       leading/trailing hyphens that the collapse can leave behind.</li>
     * </ol>
     */
    static String slugify(String name) {
        String normalised = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return normalised.isEmpty() ? FALLBACK_SLUG : normalised;
    }

    /**
     * Makes a slug unique by appending {@code -2}, {@code -3}, ... until one is free.
     *
     * <p>{@code currentId} is the category being updated, or {@code null} on create. Passing
     * it lets "Shirts" keep the slug {@code shirts} when its description is edited - without
     * it, the uniqueness check would find the category's own row and rename it to
     * {@code shirts-2} on every save, which is how slugs drift.
     *
     * <p>The loop is bounded implicitly by the number of colliding names; in practice it ends
     * on the first or second iteration. This is not the uniqueness guarantee - the unique
     * index is - it just avoids making the common case hit the exception path.
     */
    private String uniqueSlug(String base, Long currentId) {
        String candidate = base;
        int suffix = 2;
        while (currentId == null
                ? categoryRepository.existsBySlugExcluding(candidate, -1L)
                : categoryRepository.existsBySlugExcluding(candidate, currentId)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
