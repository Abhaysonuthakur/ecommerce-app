package com.shop.ecommerce.dto.user;

import com.shop.ecommerce.entity.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Promote or demote a user. Admin only.
 *
 * <p>This is the <b>only</b> legitimate way a role can change, and it is the reason the
 * registration and OAuth2 DTOs are free of any role field. Having exactly one entry point
 * means the authorization question - "who is allowed to do this?" - has exactly one
 * answer to audit.
 *
 * <p>Two guard rails live in the service layer rather than here, because neither can be
 * expressed without reading other rows:
 * <ul>
 *   <li><b>An admin cannot demote themselves.</b> Otherwise a single mistaken click locks
 *       the last administrator out of the system permanently, and recovery needs direct
 *       database access.</li>
 *   <li><b>The last remaining admin cannot be demoted.</b> The same failure, reached from
 *       a different direction: two admins each demote the other, and the store has nobody
 *       who can administer it.</li>
 * </ul>
 *
 * @param role the role this user should have
 */
@Schema(description = "Change a user's role (admin only)")
public record UpdateRoleRequest(

        @Schema(example = "ADMIN", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "CUSTOMER or ADMIN")
        @NotNull(message = "Role is required")
        Role role

) {
}
