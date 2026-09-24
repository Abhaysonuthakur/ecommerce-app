package com.shop.ecommerce.repository;

import com.shop.ecommerce.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link Category}.
 */
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * Looks a category up by its URL slug.
     *
     * <p>Not used by the current API, which addresses categories by id - but it is what a
     * public, bookmarkable {@code /category/shirts} route would need, and providing it here
     * keeps the intent visible.
     */
    Optional<Category> findBySlug(String slug);

    /**
     * Whether a name is already taken.
     *
     * <p>Case-insensitive for the same reason as email: {@code "Shirts"} and {@code "shirts"}
     * are the same category to a human, and the database's unique index agrees
     * ({@code utf8mb4_0900_ai_ci}). Without the explicit {@code lower()}, the service's
     * pre-check and the database's constraint could disagree, producing a 500 from a
     * constraint violation instead of a friendly 409.
     */
    @Query("select count(c) > 0 from Category c where lower(c.name) = lower(:name)")
    boolean existsByNameIgnoreCase(@Param("name") String name);

    /** Duplicate-name check that ignores one row - used when updating a category. */
    @Query("select count(c) > 0 from Category c where lower(c.name) = lower(:name) and c.id <> :excludeId")
    boolean existsByNameIgnoreCaseExcluding(@Param("name") String name, @Param("excludeId") Long excludeId);

    /** Duplicate-slug check, with the same exclusion purpose. */
    @Query("select count(c) > 0 from Category c where c.slug = :slug and c.id <> :excludeId")
    boolean existsBySlugExcluding(@Param("slug") String slug, @Param("excludeId") Long excludeId);

    /**
     * Lists only the categories a shopper should see.
     *
     * <p>Ordered by name, because a category list is a navigation menu and alphabetical
     * order is the only ordering a visitor can predict.
     */
    List<Category> findByActiveTrueOrderByNameAsc();

    /** Paginated list of every category, for the admin console. */
    Page<Category> findAllByOrderByNameAsc(Pageable pageable);

    /**
     * Counts the products in each category in a single query.
     *
     * <p><b>Why this exists rather than {@code category.getProducts().size()}.</b> That
     * call loads every product row of the category into memory just to count them - and
     * since {@code @OneToMany} is LAZY, on an unpaginated list of categories it becomes an
     * N+1: one query per category, each one fetching full rows.
     *
     * <p>This projects straight to a count in the database. Two integers and a string come
     * back per category, regardless of how many products each holds.
     *
     * <p>The {@code Object[]} return is the price of the projection, and it is worth
     * paying: an interface-based projection would read better, but the count needs grouping
     * and a {@code left join}, and the caller is a single private method in the service
     * that immediately turns the rows into a map. Contained in one place, the ugliness does
     * not spread.
     */
    @Query("""
            select c.id, count(p.id)
            from Category c
            left join Product p on p.category = c
            group by c.id
            """)
    List<Object[]> countProductsPerCategory();

    /**
     * Counts products in one category.
     *
     * <p>A {@code left join} would be wrong here - this answers "how many products does
     * category X hold", and a category with none should report 0. Counting the join's
     * rows gives exactly that.
     */
    @Query("select count(p) from Product p where p.category.id = :categoryId")
    long countProductsInCategory(@Param("categoryId") Long categoryId);
}
