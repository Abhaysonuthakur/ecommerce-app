package com.shop.ecommerce.repository.specification;

import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Turns a client's sort expression into a Spring Data {@link Sort}, safely.
 *
 * <h2>Two problems this solves, both of which are exploitable or corrupting</h2>
 *
 * <h3>1. An unvalidated sort field is an injection primitive</h3>
 * <p>{@code Sort.by(userSuppliedString)} passes that string into the generated SQL's
 * {@code ORDER BY}. Spring Data does check the property name against the entity, so it is
 * not a free-form SQL injection - but it still lets a caller ask the database to order by
 * any mapped column, including ones the endpoint never intended to expose. Ordering by
 * {@code user.password} and observing which of two rows comes first is a comparison oracle
 * for the hash. The fix is a whitelist, and the whitelist must produce a 400 for anything
 * else rather than silently falling back to a default - a silent fallback means a client
 * that sent a typo believes its sort was applied.
 *
 * <h3>2. A non-unique sort makes pagination return duplicates and gaps</h3>
 * <p>This is the bug that never appears in a small test and always appears in production.
 *
 * <p>{@code ORDER BY status} defines no order between two rows that share a status. The
 * database may return them in either sequence, and is under no obligation to make the same
 * choice for the next query. With {@code LIMIT}/{@code OFFSET}, that becomes a row
 * appearing on two consecutive pages while another appears on none - and the client cannot
 * tell whether the data or the API is at fault.
 *
 * <p>A "sensible default" does not save you: {@code ORDER BY created_at DESC} has exactly
 * the same flaw. Timestamp collisions are rare, but <em>rare</em> and <em>impossible</em>
 * are different words, and only one of them is safe to page on. Two orders created in the
 * same microsecond by a batch import will do it.
 *
 * <p>The fix is to append a column that is guaranteed unique. {@code id} is the obvious
 * choice - it is the primary key, so it is unique by definition and already indexed.
 */
public final class SortValidator {

    private SortValidator() {
        // Namespace only.
    }

    /**
     * The column appended to every sort to make the ordering total.
     *
     * <p>Every entity in this project extends {@code BaseEntity}, so {@code id} is always
     * present. If a future entity had a different key, this would need to be a parameter.
     */
    private static final String TIE_BREAKER = "id";

    /** Fields a client may sort products by. */
    public static final Set<String> PRODUCT_SORT_FIELDS =
            Set.of("name", "price", "stock", "active", "createdAt", "updatedAt", TIE_BREAKER);

    /** Fields a client may sort orders by. */
    public static final Set<String> ORDER_SORT_FIELDS =
            Set.of("createdAt", "updatedAt", "totalAmount", "status", TIE_BREAKER);

    /**
     * Parses {@code "field,direction"} into a {@link Sort}.
     *
     * @param sortExpression the client's value, e.g. {@code "price,desc"} or {@code "name"}
     * @param allowedFields  the whitelist for the entity being queried
     * @param defaultField   used when no expression was supplied
     * @throws IllegalArgumentException if the field is not whitelisted or the form is wrong
     */
    public static Sort parse(String sortExpression, Set<String> allowedFields, String defaultField) {
        String expression = (sortExpression == null || sortExpression.isBlank())
                ? defaultField + ",desc"
                : sortExpression.trim();

        String[] parts = expression.split(",");
        if (parts.length > 2) {
            throw new IllegalArgumentException(
                    "Invalid sort expression: '" + expression + "'. Expected 'field' or 'field,asc|desc'.");
        }

        String field = parts[0].trim();
        if (field.isEmpty()) {
            throw new IllegalArgumentException("Sort field must not be empty.");
        }

        /*
         * The whitelist check. Throwing - rather than substituting a default - is what
         * makes a typo visible. A silent fallback would let a client believe it was
         * sorting by price when the response was actually ordered by creation date, and
         * there is no way for that client to find out.
         */
        if (!allowedFields.contains(field)) {
            throw new IllegalArgumentException(
                    "Cannot sort by '" + field + "'. Allowed fields: " + String.join(", ", allowedFields.stream().sorted().toList()));
        }

        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length == 2) {
            String rawDirection = parts[1].trim();
            if (rawDirection.equalsIgnoreCase("asc")) {
                direction = Sort.Direction.ASC;
            } else if (rawDirection.equalsIgnoreCase("desc")) {
                direction = Sort.Direction.DESC;
            } else {
                throw new IllegalArgumentException(
                        "Invalid sort direction: '" + rawDirection + "'. Expected 'asc' or 'desc'.");
            }
        }

        return withTieBreaker(Sort.by(direction, field), allowedFields);
    }

    /**
     * Appends the tiebreaker so the ordering is total.
     *
     * <h3>Why ascending, and not matched to the preceding key</h3>
     * <p>Only determinism matters. The tiebreaker is never <em>why</em> a row is where it
     * is; it is only what makes the sequence stable when the real key ties. Matching the
     * direction to the preceding sort would imply that the tiebreaker carried meaning, and
     * it does not. Ascending is also what MySQL would do anyway for a primary key, so the
     * generated SQL matches the obvious intent.
     *
     * <h3>Why the guard</h3>
     * <p>If a client explicitly sorts by {@code id}, appending it again produces
     * {@code ORDER BY id ASC, id ASC}. Harmless to MySQL, but it pollutes the generated
     * SQL and makes the echoed sort misleading. The guard costs one line.
     *
     * <h3>Why {@code id} is in the whitelist</h3>
     * <p>Because the effective sort is echoed back to the client in
     * {@code ProductServiceImpl}. A client that stores that echoed value and sends it back
     * on the next request must not be rejected for a field it never chose - and it must not
     * get a doubled {@code ORDER BY id ASC, id ASC} either. Adding the tiebreaker to the
     * whitelist is what prevents both.
     */
    private static Sort withTieBreaker(Sort sort, Set<String> allowedFields) {
        if (!allowedFields.contains(TIE_BREAKER)) {
            return sort;
        }
        if (sort.getOrderFor(TIE_BREAKER) != null) {
            // A client sorting explicitly by id already has a total order.
            return sort;
        }
        return sort.and(Sort.by(Sort.Direction.ASC, TIE_BREAKER));
    }

    /**
     * Renders the effective sort back into the {@code "field,direction"} form the client
     * sent, tiebreaker included.
     *
     * <p>Echoing the <em>effective</em> sort rather than the requested one is deliberate: a
     * client that asked for {@code "status"} and got a response apparently sorted by
     * {@code "status"} would have no way to learn that a tiebreaker was applied - and a
     * developer debugging a duplicate-rows report needs to see the mechanism, not be
     * reassured that nothing extra happened.
     */
    public static String describe(Sort sort) {
        return sort.stream()
                .map(order -> order.getProperty() + "," + order.getDirection().name().toLowerCase())
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
    }
}
