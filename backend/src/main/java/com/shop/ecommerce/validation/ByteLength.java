package com.shop.ecommerce.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a value's <b>UTF-8 byte length</b> does not exceed a maximum.
 *
 * <h2>Why character length is the wrong unit here</h2>
 *
 * <p>BCrypt consumes only the first <b>72 bytes</b> of its input. Anything past that is
 * silently discarded - no warning, no error. So a project that validates
 * {@code @Size(max = 72)} believes it has bounded the password, while:
 *
 * <pre>
 *   Password A: 72 CJK characters  = 216 bytes
 *   Password B: the same 72 characters, then "totally different"
 *
 *   BCrypt sees bytes 0..71 for both -> identical hash -> both authenticate.
 * </pre>
 *
 * <p>The two passwords are different to a user and the same to the algorithm. That is a
 * real authentication weakening, and it is invisible to a character-based rule.
 *
 * <p>This constraint is used <em>alongside</em> {@code @Size}, not instead of it, because
 * they answer different questions:
 * <ul>
 *   <li>{@code @Size(min = 8)} - "is this password long enough?", in the unit the person
 *       typing it understands.</li>
 *   <li>{@code @ByteLength(max = 72)} - "will BCrypt consume all of it?", in the unit the
 *       algorithm measures in.</li>
 * </ul>
 *
 * <p><b>Why there is no {@code @ByteLength(min = ...)}:</b> a byte minimum would be wrong.
 * Three CJK characters are nine bytes and would pass a byte rule reading "minimum 8" -
 * while a user reading "at least 8 characters" would consider three characters an error.
 * The minimum belongs in characters; only the maximum belongs in bytes.
 *
 * @see ByteLengthValidator
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ByteLengthValidator.class)
public @interface ByteLength {

    /** The message reported when the value is too long. */
    String message() default "Value exceeds the maximum byte length";

    /** Groups this constraint belongs to. Required by the Bean Validation SPI. */
    Class<?>[] groups() default {};

    /** Severity level, for payload-carrying validation consumers. */
    Class<? extends Payload>[] payload() default {};

    /**
     * Maximum permitted size in <b>UTF-8 bytes</b>.
     *
     * <p>UTF-8 is the encoding used because it is what HTTP, Jackson and MySQL's
     * {@code utf8mb4} all agree on - so the number measured here is the number of bytes
     * the rest of the system will actually handle.
     */
    int max() default Integer.MAX_VALUE;
}
