package com.shop.ecommerce.security;

import com.shop.ecommerce.entity.AuthProvider;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import com.shop.ecommerce.security.jwt.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtService} - the security core, tested against real signed tokens.
 *
 * <h2>Why these tests construct real tokens rather than mocking jjwt</h2>
 *
 * <p>A JWT's security properties live in bytes: the signature, the algorithm identifier in
 * the header, the base64url encoding. A mocked parser would assert that <em>this code calls
 * the parser</em>, which is true whether or not the signature check is actually configured.
 *
 * <p>Instead every test here builds a genuine token with a known key and asks the service
 * what it thinks. That is the only way to catch the failures that matter: a token signed
 * with a different key must be refused, an expired token must be refused, and a token with
 * no {@code exp} claim - which only somebody holding the key could construct - must not
 * produce a NullPointerException from inside the authentication filter.
 */
@DisplayName("JwtService")
class JwtServiceTest {

    /** Comfortably over 32 bytes; the service refuses anything shorter. */
    private static final String SECRET = "test-only-secret-key-that-is-long-enough-for-hs256";
    private static final String ISSUER = "https://test.ecommerce.local";
    private static final long ONE_HOUR_MS = 3_600_000L;

    private static JwtService service(String secret, long expirationMs) {
        return new JwtService(secret, expirationMs, ISSUER);
    }

    private static User user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setName("Ada Lovelace");
        user.setEmail("ada@example.com");
        user.setRole(role);
        user.setProvider(AuthProvider.LOCAL);
        user.setEnabled(true);
        return user;
    }

    /** The same byte-level key construction the service uses, so tokens are genuinely valid. */
    private static SecretKey keyFor(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // =================================================================
    //  Startup validation
    // =================================================================

    @Nested
    @DisplayName("constructor")
    class Startup {

        @Test
        @DisplayName("a blank secret stops the application starting")
        void blankSecretIsRefused() {
            /*
             * The most important behaviour in this class, and the reason it is in the
             * constructor rather than in a @PostConstruct or a check at first use.
             *
             * An application that boots with no signing key looks completely healthy and
             * mints tokens anybody can forge - because HS256's entire security rests on the
             * key being unguessable. A startup failure is discoverable in seconds; the
             * alternative is discovered by a penetration test in month six.
             */
            assertThatThrownBy(() -> service("", ONE_HOUR_MS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET is not set");

            assertThatThrownBy(() -> service("   ", ONE_HOUR_MS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET is not set");

            assertThatThrownBy(() -> service(null, ONE_HOUR_MS))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a secret under 32 bytes stops the application starting, and says how short")
        void shortSecretIsRefused() {
            /*
             * HS256 needs a 256-bit key. The message reports the actual byte count, because
             * "must be at least 32 bytes" is unhelpful advice to somebody who set a
             * 32-character string and does not know why it failed.
             */
            String twentyBytes = "a".repeat(20);

            assertThatThrownBy(() -> service(twentyBytes, ONE_HOUR_MS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("20 bytes")
                    .hasMessageContaining("32");
        }

        @Test
        @DisplayName("the check is on bytes, not characters - multibyte input is measured correctly")
        void theLimitIsMeasuredInBytes() {
            /*
             * The trap the class documents: "a 32-character secret" and "a 32-byte secret"
             * are different requirements. Devanagari 'क' is 3 UTF-8 bytes, so 11 of them are
             * 33 bytes and pass - while 10 are 30 bytes and fail.
             *
             * A character-counting check would accept the 10-character string and build a key
             * that HS256 considers weak.
             */
            String tenDevanagari = "क".repeat(10);          // 30 bytes
            String elevenDevanagari = "क".repeat(11);       // 33 bytes

            assertThat(tenDevanagari).hasSize(10);
            assertThat(tenDevanagari.getBytes(StandardCharsets.UTF_8)).hasSize(30);
            assertThat(elevenDevanagari.getBytes(StandardCharsets.UTF_8)).hasSize(33);

            assertThatThrownBy(() -> service(tenDevanagari, ONE_HOUR_MS))
                    .as("30 bytes is too short even though it is 10 characters")
                    .isInstanceOf(IllegalStateException.class);

            assertThatCode(() -> service(elevenDevanagari, ONE_HOUR_MS))
                    .as("33 bytes is enough even though it is only 11 characters")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("exactly 32 bytes is accepted - the boundary is inclusive")
        void exactlyThirtyTwoBytesIsAccepted() {
            assertThatCode(() -> service("a".repeat(32), ONE_HOUR_MS)).doesNotThrowAnyException();

            assertThatThrownBy(() -> service("a".repeat(31), ONE_HOUR_MS))
                    .as("one byte under the limit must fail")
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // =================================================================
    //  Minting
    // =================================================================

    @Nested
    @DisplayName("generateToken")
    class Minting {

        private final JwtService service = service(SECRET, ONE_HOUR_MS);

        @Test
        @DisplayName("produces a three-segment token with an HS256 header")
        void producesAWellFormedHs256Token() {
            /*
             * The header is asserted from the actual bytes rather than trusted. This is the
             * test that would catch a future switch to a longer secret silently changing the
             * algorithm - signWith(key) infers HS384 for a 48-byte key and HS512 for 64.
             */
            String token = service.generateToken(user(7L, Role.CUSTOMER));

            assertThat(token.split("\\.")).hasSize(3);

            String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                    StandardCharsets.UTF_8);
            assertThat(header).contains("\"alg\":\"HS256\"");
        }

        @Test
        @DisplayName("carries sub, email, name and role")
        void carriesTheExpectedClaims() {
            String token = service.generateToken(user(7L, Role.CUSTOMER));

            Claims claims = Jwts.parser().verifyWith(keyFor(SECRET)).build()
                    .parseSignedClaims(token).getPayload();

            assertThat(claims.getSubject()).isEqualTo("7");
            assertThat(claims.get(JwtService.CLAIM_EMAIL, String.class)).isEqualTo("ada@example.com");
            assertThat(claims.get(JwtService.CLAIM_NAME, String.class)).isEqualTo("Ada Lovelace");
            assertThat(claims.get(JwtService.CLAIM_ROLE, String.class)).isEqualTo("CUSTOMER");
            assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        }

        @Test
        @DisplayName("sets exp exactly one lifetime after iat, from a single clock reading")
        void issuedAtAndExpirationComeFromOneInstant() {
            /*
             * The subtlety documented in the method: two separate Instant.now() calls can
             * straddle a millisecond boundary, so exp - iat would not equal the configured
             * lifetime and the encoder's `exp > iat` assertion could fire. One instant,
             * two uses - and this assertion is what keeps it that way.
             */
            String token = service.generateToken(user(7L, Role.CUSTOMER));

            Claims claims = Jwts.parser().verifyWith(keyFor(SECRET)).build()
                    .parseSignedClaims(token).getPayload();

            long delta = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
            assertThat(delta)
                    .as("exp - iat must be exactly the configured lifetime")
                    .isEqualTo(ONE_HOUR_MS);
        }

        @Test
        @DisplayName("every token gets a unique jti, even for the same user in the same second")
        void eachTokenHasAUniqueId() {
            /*
             * Without a jti, two tokens minted for the same user within the same second are
             * byte-identical - so a log cannot tell you which one a request presented. This
             * project does not revoke tokens by jti (there is no refresh-token table), but
             * the claim costs one line and makes a token traceable.
             */
            User sameUser = user(7L, Role.CUSTOMER);

            String first = service.generateToken(sameUser);
            String second = service.generateToken(sameUser);

            assertThat(first).isNotEqualTo(second);

            String firstJti = Jwts.parser().verifyWith(keyFor(SECRET)).build()
                    .parseSignedClaims(first).getPayload().getId();
            String secondJti = Jwts.parser().verifyWith(keyFor(SECRET)).build()
                    .parseSignedClaims(second).getPayload().getId();

            assertThat(firstJti).isNotBlank().isNotEqualTo(secondJti);
        }

        @Test
        @DisplayName("the role claim tracks the user's role at issue time")
        void roleClaimIsASnapshot() {
            /*
             * Stated as a test because it is also a warning: this claim is a snapshot, and
             * the application must never authorize on it. If it did, demoting an admin would
             * have no effect until their existing token expired.
             *
             * JwtAuthenticationFilterTest asserts the other half - that the filter reloads
             * the row and ignores this claim.
             */
            assertThat(roleOf(service.generateToken(user(5L, Role.ADMIN)), service)).isEqualTo("ADMIN");
            assertThat(roleOf(service.generateToken(user(5L, Role.CUSTOMER)), service)).isEqualTo("CUSTOMER");
        }
    }

    // =================================================================
    //  Verification
    // =================================================================

    @Nested
    @DisplayName("isTokenValid and extractUsername")
    class Verification {

        private final JwtService service = service(SECRET, ONE_HOUR_MS);

        @Test
        @DisplayName("a freshly minted token is valid and reports its subject")
        void freshTokenIsValid() {
            String token = service.generateToken(user(7L, Role.CUSTOMER));

            assertThat(service.isTokenValid(token)).isTrue();
            assertThat(service.extractUsername(token)).isEqualTo("7");
            assertThat(service.extractUserId(token)).isEqualTo(7L);
        }

        @Test
        @DisplayName("a token signed with a different key is refused")
        void tokenSignedWithAnotherKeyIsRefused() {
            /*
             * The central security property. Something signed by an attacker who guessed a
             * different secret must not authenticate - and the failure must be a clean
             * `false`, not an exception escaping into the servlet container.
             */
            String forged = service("an-entirely-different-secret-that-is-also-long-enough", ONE_HOUR_MS)
                    .generateToken(user(7L, Role.ADMIN));

            assertThat(service.isTokenValid(forged))
                    .as("a token signed with another key must never authenticate")
                    .isFalse();
        }

        @Test
        @DisplayName("an expired token is refused")
        void expiredTokenIsRefused() {
            /*
             * A negative lifetime produces a token that was valid in the past. The service
             * must return false rather than throwing, because the filter calls this on every
             * request and an exception would surface as a 500 instead of a 401.
             */
            String expired = service(SECRET, -60_000L).generateToken(user(7L, Role.CUSTOMER));

            assertThat(service.isTokenValid(expired)).isFalse();
            assertThatThrownBy(() -> service.extractUsername(expired))
                    .isInstanceOf(ExpiredJwtException.class);
        }

        @Test
        @DisplayName("a token minted for another issuer is refused")
        void wrongIssuerIsRefused() {
            /*
             * Issuer checking is what stops a token from a sibling service (or a staging
             * deployment sharing a secret) being accepted here. Without requireIssuer, a
             * token is valid anywhere the same key is held.
             */
            String otherIssuer = new JwtService(SECRET, ONE_HOUR_MS, "https://somewhere.else")
                    .generateToken(user(7L, Role.ADMIN));

            assertThat(service.isTokenValid(otherIssuer)).isFalse();
        }

        @Test
        @DisplayName("a tampered payload is refused, because the signature covers it")
        void tamperedPayloadIsRefused() {
            /*
             * The practical forgery attempt: take a valid token, base64-decode the payload,
             * change the role to ADMIN, re-encode with the same length, and present it.
             *
             * The classic hand-edit is to change the *length*, which makes the base64 padding
             * wrong and fails earlier; this test changes the payload in place so the failure
             * is genuinely the signature check and not an encoding accident.
             *
             * The payload is NOT encrypted, which is exactly why this must fail.
             */
            String token = service.generateToken(user(7L, Role.CUSTOMER));
            String[] parts = token.split("\\.");

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            // Same length either way, so the base64 is still well-formed.
            String tampered = payloadJson.replace("\"CUSTOMER\"", "\"ADMIN___\"");

            String forged = parts[0] + "."
                    + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(tampered.getBytes(StandardCharsets.UTF_8))
                    + "." + parts[2];

            assertThat(service.isTokenValid(forged))
                    .as("editing the payload must invalidate the signature")
                    .isFalse();
        }

        @Test
        @DisplayName("the alg=none attack is refused")
        void unsignedTokenIsRefused() {
            /*
             * The best-known JWT vulnerability: present a token whose header says
             * `{"alg":"none"}` with an empty signature, and a naive parser treats it as valid.
             * A library that honours the header's own algorithm choice is trivially bypassed.
             *
             * jjwt 0.12 does not do this - `verifyWith(key)` requires the signature and
             * rejects `none` outright - but the attack is famous enough that asserting the
             * defence is worthwhile: it is the one that would silently reappear if the parser
             * were ever switched to an unsafe mode.
             */
            String header = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

            // Written as an escaped literal rather than a text block: a text block needs a
            // newline after the opening delimiter, and this is a single-line JSON document.
            String payloadJson = "{\"sub\":\"7\",\"email\":\"ada@example.com\",\"role\":\"ADMIN\"}";
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            String unsigned = header + "." + payload + ".";

            assertThat(service.isTokenValid(unsigned)).isFalse();
            assertThatThrownBy(() -> service.extractUsername(unsigned)).isInstanceOf(JwtException.class);
        }

        @Test
        @DisplayName("garbage, an empty string and a null all fail cleanly")
        void malformedInputFailsCleanly() {
            /*
             * The filter runs this on every request, including ones where the Authorization
             * header is nonsense. Every one of these must be a clean `false` and a
             * JwtException - never an NPE or an ArrayIndexOutOfBounds escaping into Spring.
             */
            assertThat(service.isTokenValid("not-a-token")).isFalse();
            assertThat(service.isTokenValid("")).isFalse();
            assertThat(service.isTokenValid(null)).isFalse();
            assertThat(service.isTokenValid("a.b.c")).isFalse();
            assertThat(service.isTokenValid("aaaa.bbbb.cccc")).isFalse();

            assertThatThrownBy(() -> service.extractUsername("not-a-token"))
                    .isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> service.extractUsername(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =================================================================
    //  Defending against a signed token that omits required claims
    // =================================================================

    @Nested
    @DisplayName("missing claims on a correctly signed token")
    class MissingClaims {

        private final JwtService service = service(SECRET, ONE_HOUR_MS);

        /**
         * Mints a token with this class's real key but a chosen set of claims.
         *
         * <p>This simulates the only attacker who can reach these code paths: somebody
         * holding the signing key. They cannot be stopped by the signature check, so the
         * claims have to be validated individually - and the failure mode if they are not is
         * a NullPointerException inside an authentication filter, surfacing as a 500.
         */
        private String tokenWith(String subject, Date issuedAt, Date expiration, boolean includeEmail,
                                boolean includeRole) {
            var builder = Jwts.builder()
                    .issuer(ISSUER)
                    .signWith(keyFor(SECRET), Jwts.SIG.HS256);

            if (subject != null) {
                builder.subject(subject);
            }
            if (issuedAt != null) {
                builder.issuedAt(issuedAt);
            }
            if (expiration != null) {
                builder.expiration(expiration);
            }
            if (includeEmail) {
                builder.claim(JwtService.CLAIM_EMAIL, "ada@example.com");
            }
            if (includeRole) {
                builder.claim(JwtService.CLAIM_ROLE, "CUSTOMER");
            }
            return builder.compact();
        }

        private Date inAnHour() {
            return new Date(System.currentTimeMillis() + ONE_HOUR_MS);
        }

        private Date justNow() {
            return new Date();
        }

        @Test
        @DisplayName("a token with no exp claim is refused, not an NPE")
        void missingExpirationIsRefused() {
            /*
             * RFC 7519 makes every registered claim OPTIONAL, and jjwt enforces exp only when
             * it is present. So this token is correctly signed, has no expiry, and would parse
             * cleanly - then `claims.getExpiration().toInstant()` throws an NPE that surfaces
             * as a 500 from inside the filter.
             *
             * requireInstant() is what turns that into a JwtException the filter already
             * handles. Without it, this test errors rather than passing.
             */
            String noExpiry = tokenWith("7", justNow(), null, true, true);

            assertThat(service.isTokenValid(noExpiry)).isFalse();
            assertThatThrownBy(() -> service.extractUsername(noExpiry))
                    .isInstanceOf(MalformedJwtException.class)
                    .hasMessageContaining("'exp'");
        }

        @Test
        @DisplayName("a token with no iat claim is refused")
        void missingIssuedAtIsRefused() {
            String noIssuedAt = tokenWith("7", null, inAnHour(), true, true);

            assertThat(service.isTokenValid(noIssuedAt)).isFalse();
            assertThatThrownBy(() -> service.extractUsername(noIssuedAt))
                    .isInstanceOf(MalformedJwtException.class)
                    .hasMessageContaining("'iat'");
        }

        @Test
        @DisplayName("a token with no sub claim is refused")
        void missingSubjectIsRefused() {
            String noSubject = tokenWith(null, justNow(), inAnHour(), true, true);

            assertThat(service.isTokenValid(noSubject)).isFalse();
            assertThatThrownBy(() -> service.extractUsername(noSubject))
                    .isInstanceOf(MalformedJwtException.class)
                    .hasMessageContaining("'sub'");
        }

        @Test
        @DisplayName("a token with no email claim is refused")
        void missingEmailIsRefused() {
            String noEmail = tokenWith("7", justNow(), inAnHour(), false, true);

            assertThat(service.isTokenValid(noEmail)).isFalse();
            assertThatThrownBy(() -> service.extractUsername(noEmail))
                    .isInstanceOf(MalformedJwtException.class)
                    .hasMessageContaining("'email'");
        }

        @Test
        @DisplayName("a token with no role claim is refused")
        void missingRoleIsRefused() {
            String noRole = tokenWith("7", justNow(), inAnHour(), true, false);

            assertThat(service.isTokenValid(noRole)).isFalse();
            assertThatThrownBy(() -> service.extractUsername(noRole))
                    .isInstanceOf(MalformedJwtException.class)
                    .hasMessageContaining("'role'");
        }

        @Test
        @DisplayName("a non-numeric sub is refused rather than throwing NumberFormatException")
        void nonNumericSubjectIsRefused() {
            /*
             * extractUserId parses the subject. A token whose sub is "admin" would throw
             * NumberFormatException - a RuntimeException from a non-JWT package, which a
             * caller catching JwtException would miss. It is wrapped for that reason.
             */
            String nonNumeric = tokenWith("not-a-number", justNow(), inAnHour(), true, true);

            assertThat(service.extractUsername(nonNumeric)).isEqualTo("not-a-number");
            assertThatThrownBy(() -> service.extractUserId(nonNumeric))
                    .isInstanceOf(MalformedJwtException.class)
                    .hasMessageContaining("not a valid user id");
        }
    }

    @Nested
    @DisplayName("getExpirationSeconds")
    class ExpirationReporting {

        @Test
        @DisplayName("reports the lifetime in whole seconds, for the client's expiresIn")
        void reportsSecondsNotMilliseconds() {
            /*
             * The field this feeds is documented as seconds, and the frontend uses it to
             * decide when to refresh. Returning milliseconds here would make a client refresh
             * 1000x too late - a bug that looks like "logged out unexpectedly" and is
             * nowhere near this line of code.
             */
            assertThat(service(SECRET, ONE_HOUR_MS).getExpirationSeconds()).isEqualTo(3600);
            assertThat(service(SECRET, 900_000L).getExpirationSeconds()).isEqualTo(900);
        }
    }

    // -----------------------------------------------------------------
    //  Helper
    // -----------------------------------------------------------------

    /** Reads the role claim out of a token without going through the service under test. */
    private static String roleOf(String token, JwtService ignored) {
        return Jwts.parser().verifyWith(keyFor(SECRET)).build()
                .parseSignedClaims(token).getPayload()
                .get(JwtService.CLAIM_ROLE, String.class);
    }
}
