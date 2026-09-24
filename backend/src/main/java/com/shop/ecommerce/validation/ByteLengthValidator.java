package com.shop.ecommerce.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

/**
 * Implements {@link ByteLength}.
 *
 * <p>A validator is deliberately tiny: it answers one question about one value and has no
 * dependencies. That is what makes it testable in isolation and reusable - this same
 * constraint guards the password on registration and on password change.
 */
public class ByteLengthValidator implements ConstraintValidator<ByteLength, CharSequence> {

    private int max;

    @Override
    public void initialize(ByteLength annotation) {
        this.max = annotation.max();
    }

    /**
     * Returns true when the value fits, or when there is no value to check.
     *
     * <p><b>Why null returns true.</b> Every constraint validator must treat null as valid;
     * that is the Bean Validation contract. The reason is composability: presence is
     * {@code @NotBlank}'s job, and a validator that also rejected null would report the
     * same problem twice - "Password is required" <em>and</em> "Password must not exceed
     * 72 bytes" for an empty password. The user fixes nothing by reading the second one.
     *
     * <p>The practical consequence is that these two constraints must always be declared
     * together. {@code @ByteLength} alone would accept a null password; {@code @NotBlank}
     * alongside it is what makes presence required.
     *
     * <p><b>StandardCharsets.UTF_8, not the platform default.</b> The default charset
     * varies by machine - this project's JVM reports {@code Cp1252} - so measuring with it
     * would give a different answer on a different machine. The validator's result must
     * not depend on where it runs.
     */
    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;   // presence is @NotBlank's concern, not ours
        }
        return value.toString().getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
