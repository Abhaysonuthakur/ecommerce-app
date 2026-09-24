package com.shop.ecommerce.dto.user;

import com.shop.ecommerce.entity.AuthProvider;
import com.shop.ecommerce.entity.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A user's public profile - every field safe to show its owner.
 *
 * <h2>The set of fields is the security control</h2>
 *
 * <p>A response DTO exists to make "what can leak" a compile-time question rather than a
 * code-review question. An entity returned directly from a controller publishes whatever
 * the table happens to hold <em>today</em>, so a column added next month is exposed the
 * moment it exists, whether or not that was intended.
 *
 * <p>The most important thing absent here is <b>{@code password}</b>. It is not filtered
 * out with {@code @JsonIgnore} on the entity - it is simply not a component of this
 * record, so there is no line of code that could fail to omit it. The test
 * {@code UserDtoBoundaryTest} asserts the field's absence, which is what keeps it absent.
 *
 * <p>Also absent: {@code enabled}. It is administratively interesting but not the user's
 * business, and exposing it invites a client to try to change it.
 *
 * @param id         the user's id
 * @param name       display name
 * @param email      login identifier
 * @param phone      optional contact number
 * @param address    optional default shipping address. Returned so a checkout form can
 *                   pre-fill it; the order copies it at placement time.
 * @param role       {@code CUSTOMER} or {@code ADMIN}. The frontend uses this to decide
 *                   which navigation to render.
 *
 *                   <p><b>The frontend's use of this value is presentation only.</b> Every
 *                   admin endpoint is independently protected by the backend. Hiding a
 *                   button is not access control, and the code that hides it must never be
 *                   the only thing standing between a customer and an admin operation.
 * @param provider   how the account was created, so the UI can show "Signed in with Google"
 *                   and correctly hide the change-password form.
 * @param createdAt  when the account was created
 */
@Schema(description = "A user's public profile. Never contains a password.")
public record UserResponse(

        @Schema(example = "1")
        Long id,

        @Schema(example = "Ada Lovelace")
        String name,

        @Schema(example = "ada@example.com")
        String email,

        @Schema(example = "+91 9876543210")
        String phone,

        @Schema(example = "12 Analytical Avenue, New Delhi")
        String address,

        @Schema(example = "CUSTOMER")
        Role role,

        @Schema(example = "LOCAL", description = "LOCAL for password accounts, GOOGLE for social sign-in")
        AuthProvider provider,

        @Schema(example = "2026-09-24T07:12:33Z")
        Instant createdAt

) {
}
