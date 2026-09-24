package com.shop.ecommerce.dto.auth;

import com.shop.ecommerce.validation.ByteLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A registration request.
 *
 * <h2>What is deliberately absent, and why it is a security control</h2>
 *
 * <p>There is <b>no {@code role} field</b>, no {@code id}, no {@code enabled} and no
 * {@code provider}. Their absence is not an omission - it is the defence.
 *
 * <p>The classic privilege-escalation bug in a CRUD API is a registration endpoint that
 * binds a request straight onto an entity. The attacker adds {@code "role": "ADMIN"} to
 * the JSON, the binding framework assigns it, and they have an admin account. It is not
 * a bug you notice: the endpoint works perfectly for every legitimate user.
 *
 * <p>Three layers guard against it here, and only the last is a real guarantee:
 * <ol>
 *   <li>This record has no such field, so Jackson discards an unknown {@code role}
 *       property (and, with the configuration in this project, fails the request
 *       outright rather than silently ignoring it).</li>
 *   <li>{@code AuthServiceImpl.register} assigns {@code Role.CUSTOMER} as a literal -
 *       it does not read a role from anywhere.</li>
 *   <li>{@code DomainBoundaryTest} reads the record's own components by reflection and
 *       asserts the forbidden names are absent. That test fails the build the day
 *       somebody adds the field, which is the only one of the three that cannot be
 *       forgotten.</li>
 * </ol>
 *
 * @param name     the customer's display name
 * @param email    the login identifier. Must be unique; the database enforces
 *                 case-insensitivity via the {@code utf8mb4_0900_ai_ci} collation.
 * @param password the raw password. Hashed with BCrypt before it touches the database,
 *                 and never logged or echoed back.
 */
@Schema(description = "Registration payload. Unknown properties such as `role` are rejected.")
public record RegisterRequest(

        @Schema(example = "Ada Lovelace", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
        String name,

        @Schema(example = "ada@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        @Size(max = 150, message = "Email must not exceed 150 characters")
        String email,

        /*
         * TWO constraints on one field, because they measure different things.
         *
         * @Size(min = 8) is the rule a user understands: "at least eight characters".
         *
         * @ByteLength(max = 72) is the rule the algorithm imposes. BCrypt reads only
         * the first 72 BYTES of its input and silently discards the rest. So with a
         * character-only limit, 72 Chinese characters is 216 bytes - and two different
         * passwords sharing their first 72 bytes hash identically and both authenticate.
         * A length rule that measures characters cannot see that.
         *
         * Why not a byte MINIMUM? Because three CJK characters are nine bytes. A byte
         * minimum of 8 would accept those three characters while a reader interprets
         * the rule as "eight characters". The minimum belongs in the unit the human
         * thinks in; the maximum belongs in the unit the algorithm measures in.
         */
        @Schema(example = "Str0ngPassw0rd!", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "8+ characters, at most 72 bytes (BCrypt's limit)")
        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        @ByteLength(max = 72, message = "Password must not exceed 72 bytes")
        String password,

        /*
         * No policy beyond a length range. A phone number's format is a local matter,
         * and a regex that rejects a valid international number is worse than no
         * validation: the customer cannot sign up and cannot tell you why.
         */
        @Schema(example = "+91 9876543210")
        @Size(max = 20, message = "Phone must not exceed 20 characters")
        @Pattern(regexp = "^[+]?[0-9 ()\\-]{0,20}$",
                message = "Phone may contain only digits, spaces, and + ( ) -")
        String phone

) {
}
