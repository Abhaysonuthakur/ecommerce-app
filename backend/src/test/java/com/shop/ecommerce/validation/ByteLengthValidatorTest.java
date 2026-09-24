package com.shop.ecommerce.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @ByteLength} measures UTF-8 <b>bytes</b>, not characters - and this test is the
 * proof, because the two units only disagree on non-ASCII input.
 *
 * <h2>The bug this guards against</h2>
 *
 * <p>BCrypt reads the first 72 <em>bytes</em> of its input and silently ignores the rest.
 * A project that writes {@code @Size(max = 72)} believes it has bounded the password. It
 * has not:
 *
 * <pre>
 *   password A = 24 Hindi characters         (3 bytes each = 72 bytes)
 *   password B = those same 24 characters, then the word "and-also-this"
 *
 *   BCrypt sees bytes 0..71 for both.
 *   -> identical hash -> either string authenticates the account.
 * </pre>
 *
 * <p>Two different passwords, one account. That is a genuine authentication weakening, and
 * a character-counting rule cannot see it. The tests below therefore use Devanagari text
 * rather than ASCII, since ASCII is the one case where a byte rule and a character rule
 * look identical - and a test written in ASCII would pass against the broken implementation.
 */
@DisplayName("ByteLength - validating in the unit BCrypt actually uses")
class ByteLengthValidatorTest {

    /**
     * One validator, built once. {@code Validation.buildDefaultValidatorFactory()} is
     * expensive (it scans the classpath for providers), so a per-test build would dominate
     * the suite's runtime for no benefit.
     */
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        // Not strictly required for a JVM that is about to exit, but a test that leaks a
        // factory is the reason "the suite hangs at the end" in larger projects.
        validatorFactory.close();
    }

    // -----------------------------------------------------------------
    //  A local record with the constraint applied, so tests exercise the
    //  real annotation through the real Bean Validation pipeline rather
    //  than calling the validator directly. Calling isValid() by hand
    //  would not prove the annotation is wired up.
    // -----------------------------------------------------------------

    private record PasswordHolder(@ByteLength(max = 72) String password) {
    }

    private static boolean isValid(String password) {
        return validator.validate(new PasswordHolder(password)).isEmpty();
    }

    private static Set<ConstraintViolation<PasswordHolder>> violationsOf(String password) {
        return validator.validate(new PasswordHolder(password));
    }

    @Test
    @DisplayName("72 ASCII characters = 72 bytes: accepted")
    void exactlySeventyTwoAsciiBytesIsAccepted() {
        String password = "a".repeat(72);
        assertThat(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        assertThat(isValid(password)).isTrue();
    }

    @Test
    @DisplayName("73 ASCII characters = 73 bytes: rejected")
    void seventyThreeAsciiBytesIsRejected() {
        assertThat(isValid("a".repeat(73))).isFalse();
    }

    @Test
    @DisplayName("72 CJK characters = 216 bytes: rejected, though @Size(max=72) would accept it")
    void multiByteCharactersAreMeasuredInBytesNotCharacters() {
        /*
         * The centrepiece. In Devanagari the letter 'क' is 3 UTF-8 bytes, so this string is
         * 72 Characters but 216 bytes.
         *
         * A character-based rule says "fine". BCrypt says "I will use the first 24 of your
         * 72 characters and discard the rest". The constraint under test must side with
         * BCrypt.
         */
        String devanagari = "क".repeat(72);

        assertThat(devanagari).hasSize(72);   // 72 characters...
        assertThat(devanagari.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(216);

        assertThat(isValid(devanagari))
                .as("72 three-byte characters are 216 bytes and must be refused")
                .isFalse();
    }

    @Test
    @DisplayName("24 CJK characters = exactly 72 bytes: accepted")
    void boundaryIsInclusiveOnTheByteCount() {
        String twentyFourDevanagari = "क".repeat(24);
        assertThat(twentyFourDevanagari.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        assertThat(isValid(twentyFourDevanagari)).isTrue();
    }

    @Test
    @DisplayName("25 CJK characters = 75 bytes: rejected")
    void oneCharacterOverTheByteBoundaryIsRejected() {
        assertThat(isValid("क".repeat(25))).isFalse();
    }

    @Test
    @DisplayName("an emoji counts as four bytes, not one")
    void supplementaryCharactersAreCountedCorrectly() {
        /*
         * A character-counting rule is not even correct for Java's own char type: an emoji
         * outside the BMP is a surrogate pair, so String.length() reports 2 while the
         * encoded size is 4 bytes. This test pins the byte interpretation for that case too.
         */
        String emoji = "😀".repeat(18);   // 18 x 4 bytes = 72 bytes, but String.length() == 36
        assertThat(emoji).hasSize(36);
        assertThat(emoji.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        assertThat(isValid(emoji)).isTrue();

        assertThat(isValid("😀".repeat(19))).isFalse();   // 76 bytes
    }

    @Test
    @DisplayName("null is valid - presence is @NotBlank's job, not this constraint's")
    void nullIsAccepted() {
        /*
         * The Bean Validation contract requires validators to accept null, and the reason is
         * composability: if this constraint also rejected null, an empty password would
         * produce two messages ("Password is required" and "Password must not exceed 72
         * bytes") where the user needs one. The practical consequence is that @ByteLength
         * must always be declared alongside @NotBlank - which RegisterRequest does, and
         * DomainBoundaryTest asserts.
         */
        assertThat(isValid(null)).isTrue();
    }

    @Test
    @DisplayName("an empty string is valid - it is 0 bytes")
    void emptyStringIsAccepted() {
        assertThat(isValid("")).isTrue();
    }

    @Test
    @DisplayName("the violation message is the one supplied at the annotation site")
    void messageIsTakenFromTheAnnotationSite() {
        /*
         * The annotation declares a generic default ("Value exceeds the maximum byte
         * length"); RegisterRequest overrides it with one that names the unit. Asserting
         * the override propagates is what proves the message is not stuck on the default.
         */
        record Holder(@ByteLength(max = 10, message = "Password must not exceed 72 bytes") String value) {
        }

        Set<ConstraintViolation<Holder>> violations = validator.validate(new Holder("x".repeat(11)));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Password must not exceed 72 bytes");
    }

    @Test
    @DisplayName("no violation is reported for a value inside the limit")
    void noViolationInsideTheLimit() {
        assertThat(violationsOf("short-but-fine")).isEmpty();
    }
}
