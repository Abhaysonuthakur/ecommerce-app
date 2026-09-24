package com.shop.ecommerce.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A login request.
 *
 * <h2>Note what is NOT validated here</h2>
 *
 * <p>There is no {@code @Size(min = 8)} on the password, and that omission is deliberate.
 *
 * <p>If a minimum length were applied to the login payload, a short password would be
 * rejected with <b>400 Bad Request</b> instead of <b>401 Unauthorized</b>. Those two
 * responses say different things: 400 means "your request was malformed", 401 means
 * "those credentials are not valid". An attacker who can tell the difference has learned
 * something real - that no valid password on this system is shorter than eight
 * characters. That is a small but free piece of information about every account.
 *
 * <p>So the login endpoint applies the weakest possible validation - the fields must be
 * present and an email - and lets a single, uniform 401 answer every mismatch. Whether
 * the password is wrong, whether the account exists, and whether the account is Google-only
 * all look identical from outside.
 *
 * @param email    the login identifier
 * @param password the raw password, compared against the stored BCrypt hash
 */
@Schema(description = "Login payload")
public record LoginRequest(

        @Schema(example = "ada@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        String email,

        /*
         * No minimum length - see the class javadoc. Only "present" is asserted, and
         * only so that an absent field produces a clear message instead of an obscure
         * failure inside the password encoder.
         */
        @Schema(example = "Str0ngPassw0rd!", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Password is required")
        String password

) {
}
