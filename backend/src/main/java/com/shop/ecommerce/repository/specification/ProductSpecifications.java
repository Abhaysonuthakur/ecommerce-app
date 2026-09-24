package com.shop.ecommerce.repository.specification;

import com.shop.ecommerce.dto.product.ProductFilter;
import com.shop.ecommerce.entity.Product;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds dynamic WHERE clauses for the product list.
 *
 * <h2>Why Specifications and not derived queries</h2>
 *
 * <p>The list endpoint has four optional filters: keyword, category, price range and active.
 * A derived-query method per combination needs 2^4 = 16 methods, and 2^5 the moment a fifth
 * filter is added - so a "simple" addition doubles the interface. Specifications compose:
 * each filter is an independent object, and the query is the AND of whichever ones apply.
 *
 * <h2>The rule that must not be broken</h2>
 *
 * <p>Every predicate is combined with {@code AND} through the same {@code allOf}. That
 * sounds obvious and is worth stating, because the failure mode is silent: if a security
 * scope and a filter were composed as alternatives instead of conjunctions, the result
 * would be another user's data returned with a 200 and no error to notice.
 *
 * <p>In this application the ownership scope is not part of the product query at all -
 * products are public - but the same discipline applies to {@code OrderSpecifications},
 * where it matters.
 */
public final class ProductSpecifications {

    private ProductSpecifications() {
        // Namespace only.
    }

    /**
     * A specification matching nothing, used as the identity for the AND-composition below.
     *
     * <p>{@code cb.conjunction()} is the criteria API's "always true" and it is important
     * that it is genuinely empty rather than {@code 1 = 1}: with an empty conjunction,
     * Hibernate omits the WHERE clause entirely, whereas a literal would be emitted and
     * prevent index-only plans. The difference is small but free.
     */
    private static Specification<Product> alwaysTrue() {
        return (root, query, cb) -> cb.conjunction();
    }

    /**
     * Turns a {@link ProductFilter} into a single composed specification.
     *
     * <p>Starts from {@code alwaysTrue()} rather than from the first filter, so there is no
     * null-checking and no "which filter is the base" special case. An empty filter
     * produces a specification that matches everything, which is the correct meaning of
     * "no filters applied".
     *
     * <p><b>Note that {@code query} is used as {@code null}-safe.</b> Spring Data sometimes
     * calls a specification with a {@code query} and sometimes without, and a count query
     * has no meaningful sort. {@code OrderSpecifications} uses this when appending the sort
     * tiebreaker; here the composed specifications never touch it.
     */
    public static Specification<Product> from(ProductFilter filter) {
        return alwaysTrue()
                .and(byActive(filter.active()))
                .and(byCategory(filter.categoryId()))
                .and(byPriceRange(filter.minPrice(), filter.maxPrice()))
                .and(byKeyword(filter.keyword()));
    }

    /**
     * Restricts to active or inactive products.
     *
     * <p>A null {@code active} means "either", and returns a true conjunction rather than
     * skipping the predicate. Returning {@code null} from a specification method is the
     * more common way to express "no filter", and it works - but it also means every caller
     * has to know that null is meaningful, and one that treats the result as a real
     * predicate gets a {@code NullPointerException}. An explicitly permissive
     * specification has one behaviour instead of two.
     */
    public static Specification<Product> byActive(Boolean active) {
        if (active == null) {
            return alwaysTrue();
        }
        return (root, query, cb) -> cb.equal(root.get("active"), active);
    }

    public static Specification<Product> byCategory(Long categoryId) {
        if (categoryId == null) {
            return alwaysTrue();
        }
        /*
         * `root.get("category").get("id")` rather than `root.get("categoryId")`: the
         * entity holds a Category reference, not a raw foreign key. The criteria API turns
         * this into a join on the foreign-key column, which MySQL answers from
         * idx_products_category without touching the categories table at all.
         */
        return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    /**
     * Price bounds, inclusive on both ends.
     *
     * <p>Both bounds are optional and independent, so a client can send only a maximum
     * ("show me cheap things") or only a minimum. Each is applied on its own rather than
     * requiring both.
     *
     * <p>Validating that {@code minPrice <= maxPrice} is the service's job, not this
     * method's. A specification cannot produce a useful error message - it can only return
     * an empty result, which would look to the user like "we have no products in that
     * range" when the truth is that they typed the bounds backwards.
     */
    public static Specification<Product> byPriceRange(java.math.BigDecimal min, java.math.BigDecimal max) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (min != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("price"), min));
            }
            if (max != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("price"), max));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Case-insensitive substring search over name and description.
     *
     * <h3>The escaping, and why it is not optional</h3>
     *
     * <p>In SQL's {@code LIKE}, {@code %} means "any sequence" and {@code _} means "any one
     * character". A user who types {@code 50%} into the search box means the literal
     * characters - but passed straight into a pattern it becomes "50 followed by anything",
     * which matches products the user did not ask for. Worse, a crafted pattern is a
     * primitive oracle for probing a column's contents.
     *
     * <p>The escape order matters and is the classic bug: the backslash must be escaped
     * <b>first</b>. Reversing the two lines below would turn an escaped {@code \%} into
     * {@code \\%} - a literal backslash followed by a wildcard - so the escaping itself
     * would produce the injection it exists to prevent.
     *
     * <p>{@code lower()} on both sides duplicates what the {@code utf8mb4_0900_ai_ci}
     * collation already does, and it is kept deliberately: relying on the collation would
     * make case-insensitivity a property of the schema, so a move to a case-sensitive
     * comparison would change search results with nothing in the Java code to show it. It
     * costs nothing while the pattern is {@code %term%} - but it *would* block an index if
     * the pattern ever became prefix-based ({@code term%}), which is the trade-off to keep
     * in mind.
     */
    public static Specification<Product> byKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return alwaysTrue();
        }
        String escaped = keyword.trim()
                .replace("\\", "\\\\")   // backslash FIRST - see the javadoc
                .replace("%", "\\%")
                .replace("_", "\\_");
        String pattern = "%" + escaped.toLowerCase() + "%";

        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, '\\'),
                cb.like(cb.lower(root.get("description")), pattern, '\\')
        );
    }
}
