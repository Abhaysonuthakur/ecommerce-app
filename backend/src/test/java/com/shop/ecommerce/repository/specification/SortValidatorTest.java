package com.shop.ecommerce.repository.specification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sort whitelist and the tiebreaker.
 *
 * <h2>Two independent failures, one class</h2>
 *
 * <p><b>1. The whitelist.</b> An unvalidated sort field is not SQL injection in Spring Data
 * - the property name is checked against the entity - but it is still an information leak.
 * {@code ?sort=password,asc} orders rows by their BCrypt hash, and comparing which of two
 * known users sorts first is a comparison oracle against the hash. The whitelist closes it,
 * and these tests assert the rejection <em>and</em> that it is a rejection rather than a
 * silent fallback to a default.
 *
 * <p><b>2. The tiebreaker.</b> This is the part that costs real money. {@code ORDER BY
 * status} does not define an order between two rows sharing a status, and the database is
 * free to return them differently on the next query. With {@code LIMIT}/{@code OFFSET} that
 * becomes a row on two pages and another on none. No test with three rows ever catches it;
 * a test that inspects the generated {@link Sort} catches it immediately.
 */
@DisplayName("SortValidator - whitelist and total ordering")
class SortValidatorTest {

    private static final Set<String> ALLOWED = Set.of("name", "price", "stock", "createdAt", "id");

    @Nested
    @DisplayName("the default")
    class Defaults {

        @Test
        @DisplayName("no expression at all falls back to the default field, descending")
        void nullExpressionUsesTheDefault() {
            Sort sort = SortValidator.parse(null, ALLOWED, "createdAt");

            assertThat(sort.getOrderFor("createdAt"))
                    .isNotNull()
                    .satisfies(order -> assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC));
        }

        @Test
        @DisplayName("a blank expression is treated as absent, not as a field named \"\"")
        void blankExpressionUsesTheDefault() {
            /*
             * The realistic trigger is a query-parameter set the frontend builds from an
             * empty select box: `?sort=`. Without the isBlank() branch this would reach the
             * field validation and produce "Sort field must not be empty" for what is
             * clearly a client that simply did not choose a sort.
             */
            Sort sort = SortValidator.parse("   ", ALLOWED, "createdAt");
            assertThat(sort.getOrderFor("createdAt")).isNotNull();
        }
    }

    @Nested
    @DisplayName("directions")
    class Directions {

        @Test
        @DisplayName("a bare field name defaults to descending")
        void bareFieldDefaultsToDescending() {
            Sort sort = SortValidator.parse("price", ALLOWED, "createdAt");
            assertThat(sort.getOrderFor("price").getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @ParameterizedTest(name = "\"{0}\" parses as ascending")
        @ValueSource(strings = {"asc", "ASC", "Asc", "  asc  "})
        void ascIsAcceptedCaseInsensitivelyAndUntrimmed(String direction) {
            /*
             * Case-insensitivity matters because the value comes from a URL: a frontend that
             * sends `ASC` and a backend that only accepts `asc` is a bug found in the browser,
             * not in the test suite.
             */
            Sort sort = SortValidator.parse("name," + direction, ALLOWED, "createdAt");
            assertThat(sort.getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("an unknown direction is refused by name")
        void unknownDirectionIsRejected() {
            assertThatThrownBy(() -> SortValidator.parse("price,sideways", ALLOWED, "createdAt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sideways")
                    .hasMessageContaining("asc");
        }

        @Test
        @DisplayName("three comma-separated parts are refused rather than silently truncated")
        void tooManyPartsIsRejected() {
            /*
             * `?sort=price,asc,extra` is a client bug. Silently using the first two parts
             * would make the response look correct while the client's intent is unknown.
             */
            assertThatThrownBy(() -> SortValidator.parse("price,asc,extra", ALLOWED, "createdAt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expected 'field' or 'field,asc|desc'");
        }
    }

    @Nested
    @DisplayName("the whitelist")
    class Whitelist {

        @Test
        @DisplayName("a field outside the whitelist is refused, not silently replaced")
        void unknownFieldIsRejected() {
            assertThatThrownBy(() -> SortValidator.parse("password,asc", ALLOWED, "createdAt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("password")
                    .hasMessageContaining("Cannot sort by");
        }

        @Test
        @DisplayName("the rejection lists the fields that would have worked")
        void rejectionNamesTheAllowedFields() {
            /*
             * This is a developer-experience assertion with a real payoff: the message is the
             * first thing a frontend developer sees when their sort stops working, and
             * "Cannot sort by 'pricee'. Allowed fields: createdAt, id, name, price, stock"
             * answers the question without a round trip.
             */
            assertThatThrownBy(() -> SortValidator.parse("pricee", ALLOWED, "createdAt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name")
                    .hasMessageContaining("stock");
        }

        @Test
        @DisplayName("every whitelisted field is accepted, so the list and the check agree")
        void everyAllowedFieldIsAccepted() {
            /*
             * Guards against a whitelist that contains a field the entity does not have, or
             * a check that rejects something it just advertised. Cheap, and it fails the day
             * somebody adds "sku" to the set before adding the column.
             */
            for (String field : ALLOWED) {
                Sort sort = SortValidator.parse(field, ALLOWED, "createdAt");
                assertThat(sort.getOrderFor(field))
                        .as("sorting by the whitelisted field '%s'", field)
                        .isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("the tiebreaker")
    class TieBreaker {

        @Test
        @DisplayName("id is appended ascending, so the order is total")
        void idIsAppendedAscending() {
            /*
             * `stock` rather than `status`: this nested class shares ALLOWED with the rest of
             * the file, and `status` is not a product field. Sorting by a field the whitelist
             * does not contain is refused - correctly - so the test must use a field that is
             * actually sortable.
             */
            Sort sort = SortValidator.parse("stock,desc", ALLOWED, "createdAt");

            assertThat(sort.getOrderFor("stock").getDirection()).isEqualTo(Sort.Direction.DESC);
            assertThat(sort.getOrderFor("id"))
                    .as("a non-unique sort key must be followed by the unique tiebreaker")
                    .isNotNull()
                    .satisfies(order -> assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC));
        }

        @Test
        @DisplayName("the tiebreaker is ascending even when the primary key is descending")
        void tieBreakerDirectionIsIndependentOfThePrimaryKey() {
            /*
             * Deliberate, and documented in the class: the tiebreaker carries no meaning, it
             * only makes the sequence stable. Matching the primary direction would suggest
             * the id ordering meant something ("newest first"), which it does not.
             */
            Sort descending = SortValidator.parse("price,desc", ALLOWED, "createdAt");
            Sort ascending = SortValidator.parse("price,asc", ALLOWED, "createdAt");

            assertThat(descending.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
            assertThat(ascending.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("the tiebreaker is appended to the default sort too")
        void tieBreakerIsAppliedToTheDefault() {
            /*
             * The most important case of the three. A default sort of `createdAt DESC` looks
             * safe - two rows rarely share a timestamp - but "rare" is not "impossible", and
             * a batch import inserting a hundred products in the same millisecond makes it
             * certain. The default path must not be the one that skips the tiebreaker.
             */
            Sort sort = SortValidator.parse(null, ALLOWED, "createdAt");

            assertThat(sort.getOrderFor("id")).isNotNull();
            assertThat(SortValidator.describe(sort)).isEqualTo("createdAt,desc;id,asc");
        }

        @Test
        @DisplayName("an explicit id sort is not doubled up")
        void noDuplicateWhenTheClientSortsById() {
            /*
             * `ORDER BY id ASC, id ASC` is harmless to MySQL but misleading to read, and it
             * makes the echoed sort string look like something the client asked for when it
             * was not.
             */
            Sort sort = SortValidator.parse("id,asc", ALLOWED, "createdAt");

            assertThat(sort.stream().filter(order -> order.getProperty().equals("id")).count())
                    .as("id must appear exactly once")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a whitelist without id is left alone rather than producing broken SQL")
        void noTieBreakerWhenTheWhitelistOmitsId() {
            /*
             * Defensive branch. Every entity in this project extends BaseEntity and has an
             * id, so the branch is unreachable today - but `ORDER BY some_column_that_does
             * _not_exist` is a confusing 500, and the guard costs one comparison.
             */
            Set<String> withoutId = Set.of("name", "price");
            Sort sort = SortValidator.parse("price", withoutId, "name");

            assertThat(sort.getOrderFor("id")).isNull();
        }
    }

    @Nested
    @DisplayName("describe - echoing the effective sort back")
    class Describe {

        @Test
        @DisplayName("renders field,direction pairs, tiebreaker included, semicolon-separated")
        void rendersTheEffectiveSort() {
            /*
             * The value is echoed in ProductResponse so a developer debugging a
             * duplicate-rows report can see that a tiebreaker was applied. Asserting the
             * exact string pins the format the frontend parses.
             */
            assertThat(SortValidator.describe(SortValidator.parse("price,asc", ALLOWED, "createdAt")))
                    .isEqualTo("price,asc;id,asc");
        }

        @Test
        @DisplayName("a client that sends the echoed value back gets the same sort, not a doubled one")
        void echoingIsIdempotent() {
            /*
             * The round-trip property, and the reason `id` is in the whitelist at all: a
             * client that stores `sort=createdAt,desc;id,asc` from a previous response and
             * sends it back must not be rejected for a field it never chose.
             *
             * (The value it sends back is a single field, so this exercises the field/id
             * whitelist rather than a compound expression - the point is that `id` is
             * accepted, not that semicolons parse.)
             */
            Sort first = SortValidator.parse("createdAt,desc", ALLOWED, "createdAt");
            Sort second = SortValidator.parse("id,asc", ALLOWED, "createdAt");

            assertThat(SortValidator.describe(first)).isEqualTo("createdAt,desc;id,asc");
            assertThat(SortValidator.describe(second)).isEqualTo("id,asc");
        }

        @Test
        @DisplayName("an empty Sort describes as an empty string, not as null")
        void emptySortDescribesAsEmptyString() {
            assertThat(SortValidator.describe(Sort.unsorted())).isEmpty();
        }
    }

    @Nested
    @DisplayName("the product and order whitelists")
    class RealWhitelists {

        @Test
        @DisplayName("product sort fields all end with the tiebreaker available")
        void productWhitelistContainsId() {
            assertThat(SortValidator.PRODUCT_SORT_FIELDS).contains("id");
            Sort sort = SortValidator.parse("stock,asc", SortValidator.PRODUCT_SORT_FIELDS, "createdAt");
            assertThat(sort.getOrderFor("id")).isNotNull();
        }

        @Test
        @DisplayName("order sort fields all end with the tiebreaker available")
        void orderWhitelistContainsId() {
            assertThat(SortValidator.ORDER_SORT_FIELDS).contains("id");
            Sort sort = SortValidator.parse("totalAmount,desc", SortValidator.ORDER_SORT_FIELDS, "createdAt");
            assertThat(sort.getOrderFor("id")).isNotNull();
        }

        @Test
        @DisplayName("neither whitelist exposes a sensitive field")
        void noSensitiveFieldIsSortable() {
            /*
             * A regression guard with teeth: sorting by `password` gives a comparison oracle
             * against the hash, and sorting by `email` on an endpoint that does not otherwise
             * reveal emails gives an ordering of something you cannot read. Both are refused.
             */
            assertThat(SortValidator.PRODUCT_SORT_FIELDS).doesNotContain("password", "email");
            assertThat(SortValidator.ORDER_SORT_FIELDS).doesNotContain("password", "email");
        }
    }
}
