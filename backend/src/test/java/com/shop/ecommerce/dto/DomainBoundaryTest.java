package com.shop.ecommerce.dto;

import com.shop.ecommerce.dto.auth.RegisterRequest;
import com.shop.ecommerce.dto.auth.LoginRequest;
import com.shop.ecommerce.dto.product.ProductRequest;
import com.shop.ecommerce.dto.user.UpdateProfileRequest;
import com.shop.ecommerce.dto.user.UserResponse;
import com.shop.ecommerce.validation.ByteLength;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API's <em>negative space</em>: what a request record must not contain.
 *
 * <h2>Why this test exists at all</h2>
 *
 * <p>Every other test in this suite asserts that something works. This one asserts that
 * something is <b>absent</b>, and absence is the one property no runtime test can observe -
 * you cannot call an endpoint and notice that a field it never accepts would have been
 * dangerous.
 *
 * <p>The concrete threat: the classic privilege-escalation bug in a CRUD API is a
 * registration endpoint that binds the request onto the entity, so {@code {"role":
 * "ADMIN"}} in the signup form creates an admin. It works perfectly for every legitimate
 * user, which is why it survives code review and manual testing.
 *
 * <h2>The three layers, and why only this one is a guarantee</h2>
 * <ol>
 *   <li>The record has no such field, so Jackson cannot bind it.</li>
 *   <li>The service assigns {@code Role.CUSTOMER} as a literal, reading a role from
 *       nowhere.</li>
 *   <li><b>This test</b> reads the records by reflection and fails the build the day
 *       somebody adds the field.</li>
 * </ol>
 *
 * <p>Layers 1 and 2 are correct today and depend on nobody editing them. Layer 3 is the
 * only one that cannot be forgotten, which is why this test is written against
 * <em>reflection</em> rather than against a request: only reflection can see a field that
 * should not exist.
 */
@DisplayName("API boundary - what request records must NOT contain")
class DomainBoundaryTest {

    /** Convenience for the reflection assertions below. */
    private static Set<String> componentNames(Class<? extends Record> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    @Nested
    @DisplayName("RegisterRequest")
    class Registration {

        @Test
        @DisplayName("has no 'role' component - the privilege-escalation defence")
        void hasNoRoleField() {
            assertThat(componentNames(RegisterRequest.class))
                    .as("a 'role' field on the registration request lets a client self-promote to ADMIN")
                    .doesNotContain("role", "roles", "authority", "authorities");
        }

        @Test
        @DisplayName("has no id, enabled, or provider component")
        void hasNoServerOwnedFields() {
            /*
             * Each of these is a field the server decides, and each would be a real bug:
             *
             *   id       - a client choosing its own primary key can collide with an
             *              existing row or probe for which ids are free.
             *   enabled  - a disabled account re-enabling itself defeats an admin's
             *              suspension, which is the whole point of the flag.
             *   provider - declaring provider=GOOGLE on a password signup would create an
             *              account that later cannot be logged into, or (worse) one that
             *              skips the password check on the OAuth2 path.
             */
            assertThat(componentNames(RegisterRequest.class))
                    .doesNotContain("id", "enabled", "provider", "passwordHash", "createdAt", "updatedAt");
        }

        @Test
        @DisplayName("is exactly the four fields a customer legitimately supplies")
        void hasExactlyTheExpectedFields() {
            /*
             * An exact set, not just exclusions. A new field added for a good reason (say
             * `referralCode`) should force a conscious decision here rather than slipping in
             * - because "add a field to the registration DTO" is precisely the change that
             * has historically added `role` by accident.
             */
            assertThat(componentNames(RegisterRequest.class))
                    .containsExactlyInAnyOrder("name", "email", "password", "phone");
        }

        @Test
        @DisplayName("the password carries both a character minimum and a byte maximum")
        void passwordHasBothLengthConstraints() {
            /*
             * Two constraints on one field because they measure different things:
             *   @Size(min = 8)        - "at least 8 characters", the unit a user understands
             *   @ByteLength(max = 72) - "BCrypt will consume all of it", the unit the
             *                           algorithm measures in
             *
             * Asserting both are present is what stops a later "cleanup" from deleting the
             * byte rule as redundant. They are redundant only for ASCII input - which is the
             * one case where the bug they prevent cannot occur.
             */
            List<java.lang.annotation.Annotation> passwordAnnotations =
                    annotationsOn(RegisterRequest.class, "password");

            assertThat(passwordAnnotations)
                    .as("@Size is the human-facing minimum")
                    .anySatisfy(annotation -> assertThat(annotation).isInstanceOf(Size.class));

            assertThat(passwordAnnotations)
                    .as("@ByteLength is BCrypt's real limit and must not be removed")
                    .anySatisfy(annotation -> {
                        assertThat(annotation).isInstanceOf(ByteLength.class);
                        assertThat(((ByteLength) annotation).max())
                                .as("BCrypt ignores input past 72 bytes")
                                .isEqualTo(72);
                    });

            assertThat(passwordAnnotations)
                    .as("presence is @NotBlank's job - @ByteLength treats null as valid by contract")
                    .anySatisfy(annotation -> assertThat(annotation).isInstanceOf(NotBlank.class));
        }
    }

    @Nested
    @DisplayName("LoginRequest")
    class Login {

        @Test
        @DisplayName("takes only email and password")
        void hasExactlyTwoFields() {
            assertThat(componentNames(LoginRequest.class)).containsExactlyInAnyOrder("email", "password");
        }

        @Test
        @DisplayName("carries no remembered-role or provider hint")
        void hasNoRoleHint() {
            /*
             * A login request that names a role is an invitation to "log in as admin" by
             * choosing a role in the body. The role must come from the user's database row,
             * and nothing else - which is also why the JWT filter reloads the row rather
             * than trusting the token's role claim.
             */
            assertThat(componentNames(LoginRequest.class)).doesNotContain("role", "roles", "provider");
        }
    }

    @Nested
    @DisplayName("UserResponse")
    class UserOutput {

        @Test
        @DisplayName("never exposes a password or hash field")
        void neverExposesAPassword() {
            /*
             * The output side of the same coin. A DTO that carries `password` serialises it,
             * and the leak is invisible in tests that do not look for it - the response is
             * valid JSON and every functional assertion passes.
             */
            assertThat(componentNames(UserResponse.class))
                    .doesNotContain("password", "passwordHash", "hash", "credentials", "secret", "token");
        }

        @Test
        @DisplayName("exposes the role but nothing that grants one")
        void exposesRoleReadOnly() {
            /*
             * `role` belongs on the response - the frontend needs it to decide whether to
             * render the admin nav. The distinction is direction, not presence: reading a
             * role is fine, accepting one on a profile update is escalation.
             */
            assertThat(componentNames(UserResponse.class)).contains("role");
        }
    }

    @Nested
    @DisplayName("UpdateProfileRequest")
    class ProfileUpdate {

        @Test
        @DisplayName("cannot change email, role, id or enabled")
        void cannotChangeProtectedFields() {
            /*
             * The subtlest of the three. A profile update that accepts `email` allows an
             * account takeover in one request if the email is not re-verified - and
             * accepting `role` allows self-promotion through an endpoint whose name suggests
             * it is harmless.
             *
             * `email` is deliberately excluded: changing it needs a verification flow
             * (confirm the new address, then the old one), which is out of scope. Leaving it
             * out of the DTO is what keeps the scope honest.
             */
            assertThat(componentNames(UpdateProfileRequest.class))
                    .doesNotContain("id", "email", "role", "roles", "enabled", "provider", "password");
        }
    }

    @Nested
    @DisplayName("ProductRequest")
    class ProductWrite {

        @Test
        @DisplayName("accepts a price, and that price is validated as non-negative and scale-capped")
        void priceIsValidated() {
            /*
             * The price DOES belong in a product request - an admin sets it. What must never
             * happen is a price arriving from a *customer*: PlaceOrderRequest is asserted
             * below to carry none, because the order total is computed from the database.
             *
             * Three constraints, and each guards a different failure:
             *   @NotNull     - a product with no price has no defensible default
             *   @DecimalMin  - a negative price turns a product into a refund mechanism
             *   @Digits(17,2)- DECIMAL(19,2) does not reject 10.999, it SILENTLY stores
             *                  11.00. Without @Digits an admin would see a saved price that
             *                  differs from the one they typed, with no error anywhere.
             *
             * The @NotNull check is here rather than in stockIsValidated because price and
             * stock are the two fields whose absence is not recoverable.
             */
            List<java.lang.annotation.Annotation> priceAnnotations =
                    annotationsOn(ProductRequest.class, "price");

            assertThat(priceAnnotations)
                    .as("@NotNull - a product with no price has no defensible default")
                    .anySatisfy(annotation -> assertThat(annotation).isInstanceOf(NotNull.class));
            assertThat(priceAnnotations)
                    .as("@DecimalMin - a negative price turns a product into a refund mechanism")
                    .anySatisfy(annotation -> assertThat(annotation).isInstanceOf(DecimalMin.class));
            assertThat(priceAnnotations)
                    .as("@Digits - MySQL silently rounds 10.999 to 11.00, so the rule must be explicit")
                    .anySatisfy(annotation -> {
                        assertThat(annotation).isInstanceOf(Digits.class);
                        assertThat(((Digits) annotation).integer()).isEqualTo(17);
                        assertThat(((Digits) annotation).fraction()).isEqualTo(2);
                    });
        }

        @Test
        @DisplayName("stock is required and bounded below by zero")
        void stockIsValidated() {
            /*
             * @NotNull rather than a primitive int, because a primitive would default to 0
             * and an admin creating a product without mentioning stock would silently create
             * an unsellable one. @Min(0) because negative inventory is not a state this shop
             * recognises - and the database's INT UNSIGNED plus CHECK (stock >= 0) would
             * reject it anyway, one layer too late to tell the admin what they got wrong.
             */
            List<java.lang.annotation.Annotation> stockAnnotations =
                    annotationsOn(ProductRequest.class, "stock");

            assertThat(stockAnnotations)
                    .as("@NotNull - a product with unknown stock cannot be sold honestly")
                    .anySatisfy(annotation -> assertThat(annotation).isInstanceOf(NotNull.class));
            assertThat(stockAnnotations)
                    .as("@Min(0)")
                    .anySatisfy(annotation -> {
                        assertThat(annotation).isInstanceOf(Min.class);
                        assertThat(((Min) annotation).value()).isZero();
                    });
        }

        @Test
        @DisplayName("active is a boxed Boolean, so 'absent' is distinguishable from 'false'")
        void activeIsBoxedNotPrimitive() {
            /*
             * The trap this pins, for the second time on this record:
             *
             *   private boolean active;   // primitive
             *
             * An admin updating only the price sends {"name":..., "price":..., "stock":...}
             * with no "active". Jackson leaves the primitive at its default, false - and the
             * product is silently delisted by an edit that never mentioned its status.
             *
             * A boxed Boolean binds to null when absent, and the service treats null as
             * "leave unchanged" on update and "true" on create. Asserting the *type* here is
             * what stops a later "simplification" from reintroducing the bug.
             */
            assertThat(componentType(ProductRequest.class, "active"))
                    .as("active must be a boxed Boolean, never a primitive boolean")
                    .isEqualTo(Boolean.class);
        }
    }

    @Nested
    @DisplayName("PlaceOrderRequest")
    class OrderPlacement {

        @Test
        @DisplayName("carries no price, total, or item list")
        void carriesNoClientSuppliedMoney() {
            /*
             * The rule that matters most on the order path: a client that can send a total
             * can buy a ₹50,000 television for ₹1. The request names an address and nothing
             * else - the items come from the server-side cart and the prices from the
             * database, inside the transaction that locks the rows.
             */
            assertThat(componentNames(com.shop.ecommerce.dto.order.PlaceOrderRequest.class))
                    .doesNotContain("total", "totalAmount", "subtotal", "unitPrice", "price",
                            "items", "orderItems", "lines", "status", "userId", "user");
        }
    }

    // -----------------------------------------------------------------
    //  Reflection helper
    // -----------------------------------------------------------------

    /**
     * The annotations declared on one component of a record.
     *
     * <p>A record's component annotations are not reachable from the accessor method alone -
     * Bean Validation copies them to the field, the parameter and the accessor depending on
     * the target list. Looking at the field is the reliable route, and it is what a
     * validator does at runtime, so a test reading the same place is testing the same thing.
     */
    private static List<java.lang.annotation.Annotation> annotationsOn(Class<? extends Record> recordType,
                                                                      String componentName) {
        try {
            return List.of(recordType.getDeclaredField(componentName).getAnnotations());
        } catch (NoSuchFieldException e) {
            throw new AssertionError(
                    "No component named '%s' on %s - did it get renamed?".formatted(componentName, recordType.getSimpleName()),
                    e);
        }
    }

    /**
     * The declared type of one record component.
     *
     * <p>Needed because "is this a boxed Boolean or a primitive boolean?" is a question about
     * the declaration, not about any value the record can hold - so it cannot be answered by
     * constructing one and inspecting it. A record with {@code boolean active} can only ever
     * produce true or false, and both look normal.
     */
    private static Class<?> componentType(Class<? extends Record> recordType, String componentName) {
        try {
            return recordType.getDeclaredField(componentName).getType();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(
                    "No component named '%s' on %s - did it get renamed?".formatted(componentName, recordType.getSimpleName()),
                    e);
        }
    }
}
