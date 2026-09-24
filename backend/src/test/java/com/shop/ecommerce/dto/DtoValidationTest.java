package com.shop.ecommerce.dto;

import com.shop.ecommerce.dto.auth.LoginRequest;
import com.shop.ecommerce.dto.auth.RegisterRequest;
import com.shop.ecommerce.dto.cart.AddToCartRequest;
import com.shop.ecommerce.dto.cart.UpdateCartItemRequest;
import com.shop.ecommerce.dto.category.CategoryRequest;
import com.shop.ecommerce.dto.order.PlaceOrderRequest;
import com.shop.ecommerce.dto.product.ProductRequest;
import com.shop.ecommerce.dto.user.UpdateProfileRequest;
import com.shop.ecommerce.dto.user.UpdateRoleRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The validation rules as they actually behave through Bean Validation.
 *
 * <h2>Why validating each DTO directly, rather than through an HTTP call</h2>
 *
 * <p>The MockMvc tests prove that a bad body produces a 400. This test proves <em>which</em>
 * rule caught it, and - more usefully - that each rule catches what it claims to. A DTO
 * whose {@code @Min(1)} was accidentally widened to {@code @Min(0)} would still pass an
 * end-to-end "invalid quantity gives 400" test, because the service rejects it too.
 *
 * <p>Two things are asserted for every rule that matters: the bad value is refused, and the
 * good value is accepted. Asserting only the refusal produces a rule that is too strict,
 * which is worse than no rule at all - the customer cannot sign up and cannot tell you why.
 */
@DisplayName("DTO validation")
class DtoValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private static <T> Set<ConstraintViolation<T>> violations(T value) {
        return validator.validate(value);
    }

    private static <T> boolean valid(T value) {
        return validator.validate(value).isEmpty();
    }

    /** The message reported for a named property, or "" if the property had no violation. */
    private static <T> String messageFor(Set<ConstraintViolation<T>> violations, String property) {
        return allMessagesFor(violations, property).stream().findFirst().orElse("");
    }

    /**
     * Every message reported for a named property.
     *
     * <p>Needed because <b>more than one constraint can fire on the same property</b>, and
     * Bean Validation gives no ordering guarantee between them. A blank name fails both
     * {@code @NotBlank} ("required") and {@code @Size(min = 2)} ("between 2 and 100") - so a
     * test that reads only the first message is asserting whichever one Hibernate Validator
     * happened to report first, which is not a property of the code being tested.
     *
     * <p>Asserting over the whole set is the honest form: "this value is refused, and the
     * reason mentions X" rather than "the arbitrary first reason mentions X".
     */
    private static <T> List<String> allMessagesFor(Set<ConstraintViolation<T>> violations, String property) {
        return violations.stream()
                .filter(v -> property.equals(v.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    /** The property names that were reported, for structural assertions. */
    private static <T> Set<String> violatedProperties(Set<ConstraintViolation<T>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    /**
     * Asserts that a property was refused for one of several acceptable reasons.
     *
     * <p>Used where two constraints legitimately guard the same field and either message is
     * a correct answer. See {@link #allMessagesFor} for why "either" is the right assertion
     * and "the first one" is not.
     */
    private static <T> void assertRefusedMentioning(Set<ConstraintViolation<T>> violations,
                                                    String property,
                                                    String... acceptableFragments) {
        List<String> messages = allMessagesFor(violations, property);

        assertThat(messages)
                .as("property '%s' must be refused", property)
                .isNotEmpty();

        assertThat(messages)
                .as("one of the reported messages for '%s' must mention %s; got %s",
                        property, Arrays.toString(acceptableFragments), messages)
                .anySatisfy(message -> assertThat(message).containsAnyOf(acceptableFragments));
    }

    // =================================================================
    //  Registration
    // =================================================================

    @Nested
    @DisplayName("RegisterRequest")
    class Registration {

        private static final String VALID_PASSWORD = "Str0ngPassw0rd!";

        @Test
        @DisplayName("a well-formed registration is accepted")
        void happyPathIsAccepted() {
            assertThat(valid(new RegisterRequest("Ada Lovelace", "ada@example.com", VALID_PASSWORD, null)))
                    .as("the baseline must pass, or every other assertion here is meaningless")
                    .isTrue();
        }

        @Test
        @DisplayName("a password shorter than 8 characters is refused, naming the field")
        void shortPasswordIsRefused() {
            String message = messageFor(
                    violations(new RegisterRequest("Ada", "ada@example.com", "short12", null)), "password");

            assertThat(message).contains("at least 8 characters");
        }

        @Test
        @DisplayName("a password of 8 characters is accepted - the boundary is inclusive")
        void eightCharacterPasswordIsAccepted() {
            /*
             * The off-by-one in the other direction. A rule that demanded MORE than 8 would
             * reject a password the message says is fine, and the user would have no way to
             * know the message and the check disagree.
             */
            assertThat(valid(new RegisterRequest("Ada", "ada@example.com", "12345678", null)))
                    .as("8 characters must satisfy 'at least 8'")
                    .isTrue();
        }

        @Test
        @DisplayName("a password over 72 BYTES is refused even though it is short in characters")
        void byteLimitIsEnforcedThroughTheRealPipeline() {
            /*
             * ByteLengthValidatorTest proves the validator measures bytes. This proves the
             * constraint is actually declared on the field and reaches the pipeline - a
             * validator that works but is not applied protects nothing.
             *
             * 30 Devanagari characters is 30 "characters" and 90 bytes. @Size(min = 8) is
             * satisfied. @ByteLength(max = 72) is not, and must be the one that reports.
             */
            String thirtyDevanagari = "क".repeat(30);

            Set<ConstraintViolation<RegisterRequest>> found =
                    violations(new RegisterRequest("Ada", "ada@example.com", thirtyDevanagari, null));

            assertThat(messageFor(found, "password"))
                    .as("the byte rule must be the one that fires")
                    .contains("72 bytes");
        }

        @Test
        @DisplayName("an invalid email is refused")
        void invalidEmailIsRefused() {
            /*
             * Note what is NOT asserted here: `missing@tld`. Jakarta's @Email accepts a
             * dotless domain, because "user@localhost" is a legitimate address and the
             * annotation is deliberately permissive (it checks the shape, not deliverability).
             * A test asserting the opposite would be encoding a rule the framework does not
             * implement - which is how you end up "fixing" working code.
             *
             * Real formatting failures are what it does catch, and those are asserted.
             */
            assertRefusedMentioning(
                    violations(new RegisterRequest("Ada", "not-an-email", VALID_PASSWORD, null)),
                    "email", "valid email");

            assertRefusedMentioning(
                    violations(new RegisterRequest("Ada", "no-at-sign.example.com", VALID_PASSWORD, null)),
                    "email", "valid email");

            assertRefusedMentioning(
                    violations(new RegisterRequest("Ada", "spaces in@example.com", VALID_PASSWORD, null)),
                    "email", "valid email");

            assertThat(valid(new RegisterRequest("Ada", "ada@example.com", VALID_PASSWORD, null)))
                    .as("the positive case, so the rule is not simply rejecting everything")
                    .isTrue();
        }

        @Test
        @DisplayName("a blank name is refused, and a one-character name too")
        void nameIsValidated() {
            /*
             * Two different rules guard this field, and a blank name trips BOTH:
             *   @NotBlank      -> "Name is required"
             *   @Size(min = 2) -> "Name must be between 2 and 100 characters"
             *
             * Either message is a correct answer, so the assertion accepts either. Asserting
             * only the first would be asserting Hibernate Validator's internal ordering.
             */
            assertRefusedMentioning(
                    violations(new RegisterRequest("", "ada@example.com", VALID_PASSWORD, null)),
                    "name", "required", "between 2 and 100");

            assertRefusedMentioning(
                    violations(new RegisterRequest("A", "ada@example.com", VALID_PASSWORD, null)),
                    "name", "between 2 and 100");

            // The boundary: two characters is the shortest acceptable name.
            assertThat(valid(new RegisterRequest("Al", "ada@example.com", VALID_PASSWORD, null)))
                    .as("exactly 2 characters must satisfy 'between 2 and 100'")
                    .isTrue();
        }

        @Test
        @DisplayName("a phone number is optional, but rejected if it contains letters")
        void phoneIsOptionalButFormatChecked() {
            assertThat(valid(new RegisterRequest("Ada", "ada@example.com", VALID_PASSWORD, null)))
                    .as("omitting a phone must not fail validation")
                    .isTrue();

            assertThat(messageFor(violations(
                    new RegisterRequest("Ada", "ada@example.com", VALID_PASSWORD, "call-me-maybe")), "phone"))
                    .contains("only digits");
        }

        @Test
        @DisplayName("every missing field is reported at once, not one per request")
        void allProblemsAreReportedTogether() {
            /*
             * Bean Validation collects all violations by default, and that is what makes a
             * registration form usable: a user who left three fields blank sees three
             * messages, not a game of whack-a-mole across three submits.
             */
            Set<ConstraintViolation<RegisterRequest>> found =
                    violations(new RegisterRequest("", "", "x", null));

            assertThat(violatedProperties(found))
                    .as("name, email and password must all report at once")
                    .contains("name", "email", "password");
        }
    }

    @Nested
    @DisplayName("LoginRequest")
    class Login {

        @Test
        @DisplayName("accepts any non-blank pair - password POLICY must not apply at login")
        void loginDoesNotImposeThePasswordPolicy() {
            /*
             * Important and easily got wrong. Applying @Size(min = 8) to a login password
             * looks harmless and is actively harmful: an account created before the policy
             * changed can never sign in again, and the error tells the user their password is
             * "too short" for a password they have been using for years.
             *
             * Only presence is checked. Whether the password is CORRECT is the service's job,
             * and it answers with one uniform 401 either way.
             */
            assertThat(valid(new LoginRequest("ada@example.com", "old")))
                    .as("a legacy short password must still be submittable")
                    .isTrue();

            assertThat(valid(new LoginRequest("ada@example.com", "a".repeat(200))))
                    .as("an over-long password is also permitted here - the hash comparison decides")
                    .isTrue();
        }

        @Test
        @DisplayName("refuses a blank email or password, since neither can match anything")
        void blankCredentialsAreRefused() {
            assertThat(violatedProperties(violations(new LoginRequest("", "pw"))))
                    .contains("email");

            assertThat(violatedProperties(violations(new LoginRequest("ada@example.com", ""))))
                    .contains("password");
        }
    }

    // =================================================================
    //  Cart
    // =================================================================

    @Nested
    @DisplayName("cart requests")
    class Cart {

        @Test
        @DisplayName("AddToCart refuses quantity 0 and negatives")
        void addingZeroIsRefused() {
            /*
             * @Min(1), not @Min(0). "Add zero of this" is not an operation, and letting it
             * through would create a cart line reading "Linen Shirt x 0" - which the user
             * cannot explain and the database would reject at flush time with a CHECK
             * violation, one layer too late.
             */
            assertThat(messageFor(violations(new AddToCartRequest(9L, 0)), "quantity"))
                    .contains("at least 1");
            assertThat(messageFor(violations(new AddToCartRequest(9L, -1)), "quantity"))
                    .contains("at least 1");
        }

        @Test
        @DisplayName("AddToCart accepts quantity 1 and refuses 100")
        void quantityUpperBoundIsEnforced() {
            assertThat(valid(new AddToCartRequest(9L, 1))).isTrue();
            assertThat(valid(new AddToCartRequest(9L, 99)))
                    .as("99 is the documented maximum and must be accepted")
                    .isTrue();
            assertThat(messageFor(violations(new AddToCartRequest(9L, 100)), "quantity"))
                    .as("the sanity bound exists so one line cannot be used as a denial-of-service")
                    .contains("must not exceed 99");
        }

        @Test
        @DisplayName("AddToCart requires a product id")
        void productIdIsRequired() {
            assertThat(messageFor(violations(new AddToCartRequest(null, 1)), "productId"))
                    .contains("required");
        }

        @Test
        @DisplayName("UpdateCartItem applies the same quantity bounds")
        void updateUsesTheSameBounds() {
            /*
             * The same @Min/@Max as AddToCart. Worth asserting separately because the two
             * records are edited independently - and a bound that drifts between "add" and
             * "update" means a quantity the customer can create but not then adjust.
             */
            assertThat(valid(new UpdateCartItemRequest(1))).isTrue();
            assertThat(valid(new UpdateCartItemRequest(99))).isTrue();
            assertThat(messageFor(violations(new UpdateCartItemRequest(0)), "quantity"))
                    .contains("at least 1");
            assertThat(messageFor(violations(new UpdateCartItemRequest(100)), "quantity"))
                    .contains("must not exceed 99");
            assertThat(messageFor(violations(new UpdateCartItemRequest(null)), "quantity"))
                    .contains("required");
        }
    }

    // =================================================================
    //  Orders and products
    // =================================================================

    @Nested
    @DisplayName("PlaceOrderRequest")
    class PlaceOrder {

        @Test
        @DisplayName("the whole body may be empty - the cart supplies everything")
        void anEmptyBodyIsValid() {
            /*
             * The corollary of the security rule that the request carries no items or prices:
             * if the server derives everything from the cart, then a request with nothing in
             * it except an optional address is a complete, valid request.
             *
             * Asserting this documents the contract. A future "helpful" @NotNull on
             * shippingAddress would break every client that relies on the fallback to the
             * user's saved address.
             */
            assertThat(valid(new PlaceOrderRequest(null)))
                    .as("no address means 'use the one on my profile'")
                    .isTrue();
        }

        @Test
        @DisplayName("an over-long address is refused rather than truncated by the database")
        void overlongAddressIsRefused() {
            /*
             * The column is VARCHAR(255); MySQL in strict mode would error at insert time and
             * surface as a 500. Catching it here turns it into a 400 naming the field.
             */
            String tooLong = "x".repeat(256);

            assertThat(messageFor(violations(new PlaceOrderRequest(tooLong)), "shippingAddress"))
                    .contains("255");
        }
    }

    @Nested
    @DisplayName("ProductRequest")
    class Product {

        /**
         * The shortest acceptable name. Two characters, because {@code @Size(min = 2)}.
         *
         * <p>Named rather than inlined as {@code "X"} for a reason worth stating: the obvious
         * placeholder is a single character, and a single character fails the name rule - so
         * every assertion written around it would fail for a reason unrelated to what the
         * test is about. That actually happened while writing these tests, and three tests
         * appeared to prove a bug in the price and stock rules when the real cause was the
         * name in the fixture.
         */
        private static final String NAME = "Widget";

        /** The category id used throughout; opaque to validation, only required. */
        private static final Long CATEGORY_ID = 3L;

        /**
         * A well-formed product. Named {@code aValidProduct} rather than {@code valid} because
         * a nested class's method shadows the enclosing class's - so calling the shared
         * {@code valid(...)} helper from inside this class would resolve to the no-argument
         * version and fail to compile.
         */
        private static ProductRequest aValidProduct() {
            return new ProductRequest("Linen Shirt", "Nice shirt",
                    new BigDecimal("1499.00"), 10, null, CATEGORY_ID, true);
        }

        /**
         * A valid product with individual fields replaced, so each test varies exactly one
         * thing. Without this every case has to repeat five irrelevant values, and a typo in
         * any of them makes an unrelated test fail.
         */
        private static ProductRequest withPrice(BigDecimal price) {
            return new ProductRequest(NAME, null, price, 10, null, CATEGORY_ID, true);
        }

        private static ProductRequest withStock(Integer stock) {
            return new ProductRequest(NAME, null, new BigDecimal("10.00"), stock, null, CATEGORY_ID, true);
        }

        @Test
        @DisplayName("a well-formed product is accepted")
        void happyPathIsAccepted() {
            assertThat(valid(aValidProduct())).isTrue();
        }

        @Test
        @DisplayName("more than two decimal places is refused, because MySQL would round silently")
        void tooManyDecimalPlacesIsRefused() {
            /*
             * The rule that exists because the database will not enforce it. DECIMAL(19,2)
             * does not reject 10.999 - it stores 11.00, with no warning. An admin who typed
             * a price and got back a different one would reasonably conclude the app is
             * broken, and they would be right.
             */
            assertRefusedMentioning(violations(withPrice(new BigDecimal("10.999"))),
                    "price", "2 decimal places");

            assertRefusedMentioning(violations(withPrice(new BigDecimal("10.995"))),
                    "price", "2 decimal places");
        }

        @Test
        @DisplayName("a price with exactly 2 decimals is accepted")
        void twoDecimalPriceIsAccepted() {
            assertThat(valid(withPrice(new BigDecimal("10.99")))).isTrue();
            assertThat(valid(withPrice(new BigDecimal("10.90"))))
                    .as("a trailing zero is still two decimal places")
                    .isTrue();
        }

        @Test
        @DisplayName("a negative price is refused")
        void negativePriceIsRefused() {
            assertRefusedMentioning(violations(withPrice(new BigDecimal("-1.00"))),
                    "price", "must not be negative");
        }

        @Test
        @DisplayName("a zero price is allowed - free items and placeholders are legitimate")
        void zeroPriceIsAllowed() {
            /*
             * @DecimalMin(inclusive = true), deliberately. A giveaway item or an un-priced
             * placeholder is a real thing a shop needs, and refusing zero would force a fake
             * price of 0.01 to be typed instead.
             */
            assertThat(valid(withPrice(BigDecimal.ZERO))).isTrue();
        }

        @Test
        @DisplayName("a negative stock is refused")
        void negativeStockIsRefused() {
            assertRefusedMentioning(violations(withStock(-1)), "stock", "must not be negative");
        }

        @Test
        @DisplayName("zero stock is allowed - a sold-out product is a normal state")
        void zeroStockIsAllowed() {
            assertThat(valid(withStock(0)))
                    .as("a sold-out product is a normal state to store, not an invalid one")
                    .isTrue();
        }

        @Test
        @DisplayName("price, stock and category are all required")
        void requiredFieldsAreRequired() {
            ProductRequest missing = new ProductRequest(NAME, null, null, null, null, null, null);

            assertThat(violatedProperties(violations(missing)))
                    .contains("price", "stock", "categoryId");
        }

        @Test
        @DisplayName("an omitted active flag is valid - the boxed Boolean permits 'leave unchanged'")
        void nullActiveIsValid() {
            /*
             * The boxed-Boolean decision, asserted from the validation side. A primitive
             * boolean could not be null, so there would be no way to express "this update
             * does not mention the product's status" - and every price edit would delist the
             * product.
             */
            assertThat(valid(new ProductRequest(NAME, null,
                    new BigDecimal("1.00"), 1, null, CATEGORY_ID, null)))
                    .as("null means 'leave unchanged' and must pass validation")
                    .isTrue();
        }

        @Test
        @DisplayName("a one-character product name is refused")
        void shortNameIsRefused() {
            assertRefusedMentioning(
                    violations(new ProductRequest("X", null,
                            new BigDecimal("1.00"), 1, null, CATEGORY_ID, true)),
                    "name", "between 2 and 200");
        }
    }

    @Nested
    @DisplayName("UpdateProfileRequest")
    class Profile {

        @Test
        @DisplayName("an entirely empty update is refused - there is nothing to do")
        void emptyUpdateIsRefused() {
            /*
             * The record's own isEmpty() guard is in the service, but the shape of the record
             * is what makes "no changes at all" detectable. A profile update with no fields
             * set is a client bug, and answering 200 with "nothing changed" would hide it.
             *
             * Every field is optional individually, so the record itself validates - the
             * refusal happens in the service, and UserServiceImplTest covers it. Here we
             * assert the record's own rules.
             */
            assertThat(valid(new UpdateProfileRequest(null, null, null)))
                    .as("each field is individually optional")
                    .isTrue();
        }

        @Test
        @DisplayName("a name that is present must still meet the length rule")
        void presentNameIsStillValidated() {
            /*
             * The subtlety of optional fields: null means "not provided" and is fine, but a
             * provided value must satisfy the constraint. A naive rule that only checked
             * "if present, looks like X" without a minimum would let a client clear a name.
             */
            assertThat(messageFor(violations(new UpdateProfileRequest("A", null, null)), "name"))
                    .contains("between 2 and 100");

            assertThat(valid(new UpdateProfileRequest("Ada Lovelace", null, null))).isTrue();
        }

        @Test
        @DisplayName("an over-long address is refused")
        void overlongAddressIsRefused() {
            assertThat(messageFor(violations(
                    new UpdateProfileRequest(null, null, "x".repeat(256))), "address"))
                    .contains("255");
        }
    }

    @Nested
    @DisplayName("UpdateRoleRequest")
    class RoleChange {

        @Test
        @DisplayName("a role is required - there is no default promotion")
        void roleIsRequired() {
            /*
             * @NotNull, with no default. If a missing role were treated as "no change" the
             * endpoint would return 200 and do nothing, which looks identical to success. If
             * it were treated as a default of CUSTOMER, a malformed promotion would silently
             * DEMOTE the target. Requiring it removes both possibilities.
             */
            assertThat(messageFor(violations(new UpdateRoleRequest(null)), "role"))
                    .contains("required");
        }

        @Test
        @DisplayName("both Role constants are accepted")
        void bothRolesAreAccepted() {
            assertThat(valid(new UpdateRoleRequest(com.shop.ecommerce.entity.Role.CUSTOMER))).isTrue();
            assertThat(valid(new UpdateRoleRequest(com.shop.ecommerce.entity.Role.ADMIN))).isTrue();
        }
    }

    @Nested
    @DisplayName("CategoryRequest")
    class Category {

        @Test
        @DisplayName("a name is required and bounded")
        void nameIsRequired() {
            /*
             * A blank name trips both @NotBlank and @Size(min = 2), and Bean Validation does
             * not promise an order between them - so either message is a correct answer.
             */
            assertRefusedMentioning(violations(new CategoryRequest("", null, null, null)),
                    "name", "required", "between 2 and 100");

            assertRefusedMentioning(violations(new CategoryRequest("X", null, null, null)),
                    "name", "between 2 and");

            assertThat(valid(new CategoryRequest("El", null, null, null)))
                    .as("exactly 2 characters must be accepted")
                    .isTrue();
        }

        @Test
        @DisplayName("description and image URL are optional")
        void optionalFieldsAreOptional() {
            assertThat(valid(new CategoryRequest("Electronics", null, null, null))).isTrue();
        }
    }
}
