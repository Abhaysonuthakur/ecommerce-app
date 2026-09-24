package com.shop.ecommerce.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Edit your own profile.
 *
 * <h2>Three field absences that matter</h2>
 *
 * <p>No {@code email}, no {@code password} and no {@code role}. Each is missing for its
 * own reason:
 *
 * <ul>
 *   <li><b>No {@code role}.</b> An "update profile" endpoint that accepts a role is a
 *       self-promotion endpoint. The role can only change through
 *       {@code PATCH /api/admin/users/{id}/role}, which requires an existing admin.</li>
 *   <li><b>No {@code email}.</b> Changing the login identifier is really "change the
 *       account's identity", and doing it safely needs a confirmation sent to the new
 *       address - otherwise a mistyped email locks the user out permanently, and a typo'd
 *       address belonging to somebody else hands them the account after a password reset.
 *       That flow needs email delivery, which is out of scope. Leaving email out means
 *       the account cannot be silently taken over through this endpoint.</li>
 *   <li><b>No {@code password}.</b> Password changes are their own operation because they
 *       must verify the <em>current</em> password first. Accepting a new password here
 *       would let anyone holding a stolen token set a password and keep access
 *       indefinitely.</li>
 * </ul>
 *
 * <p>All three fields are optional: this is a PATCH-shaped update, and the service treats
 * {@code null} as "leave unchanged". That is why there are no {@code @NotBlank}
 * annotations - an absent field here is a legitimate value meaning "do not touch".
 *
 * @param name    new display name, or null to keep the current one
 * @param phone   new phone number, or null to keep the current one
 * @param address new shipping address, or null to keep the current one
 */
@Schema(description = "Update your own profile. Omitted fields are left unchanged.")
public record UpdateProfileRequest(

        @Schema(example = "Ada Lovelace")
        @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
        String name,

        @Schema(example = "+91 9876543210")
        @Size(max = 20, message = "Phone must not exceed 20 characters")
        @Pattern(regexp = "^[+]?[0-9 ()\\-]{0,20}$",
                message = "Phone may contain only digits, spaces, and + ( ) -")
        String phone,

        @Schema(example = "12 Analytical Avenue, New Delhi")
        @Size(max = 255, message = "Address must not exceed 255 characters")
        String address

) {

    /**
     * True when the request carries nothing to change.
     *
     * <p>Used by the service to reject a no-op update with a clear 400 instead of writing
     * an {@code updated_at} change that implies something happened. A timestamp that moves
     * without a change is a small lie in the audit trail.
     */
    public boolean isEmpty() {
        return name == null && phone == null && address == null;
    }
}
