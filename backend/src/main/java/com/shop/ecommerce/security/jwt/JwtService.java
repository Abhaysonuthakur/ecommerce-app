package com.shop.ecommerce.security.jwt;

import com.shop.ecommerce.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints and verifies access tokens.
 *
 * <h2>What a JWT is, and why this shape</h2>
 *
 * <p>A JWT is three base64url segments separated by dots: {@code header.payload.signature}.
 * The header names the algorithm, the payload is a set of claims, and the signature is an
 * HMAC over the first two. <b>The payload is not encrypted</b> - anyone can read it with a
 * base64 decoder. The signature only proves it has not been altered.
 *
 * <p>That fact drives every decision below. Nothing secret goes in the claims. In
 * particular the token carries a {@code role} claim for convenience and <b>the application
 * never authorizes on it</b> - it is a snapshot from issue time, so trusting it would mean a
 * demoted admin keeps their access until the token expires.
 *
 * <h2>Why HS256 and not RS256</h2>
 *
 * <p>HS256 is a shared secret: whoever can verify a token can also mint one. RS256 uses a
 * key pair, so a verifier holds only the public half and cannot forge. RS256 is the right
 * choice when the issuer and the verifier are different parties - which is exactly the
 * Google case, and why Google publishes a JWKS endpoint.
 *
 * <p>Here the issuer and verifier are the same application, so RS256 would add key
 * management for no security benefit. HS256 is correct for a single-service token, and the
 * secret is the thing that must be protected.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /*
     * Claim names as constants rather than inline strings.
     *
     * A typo in a string literal is not a compile error: writing "emial" when reading would
     * silently produce a null and the failure would appear somewhere else entirely. A
     * constant used symmetrically at both ends makes the typo impossible.
     */
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_NAME = "name";

    private final SecretKey signingKey;
    private final long expirationMs;
    private final String issuer;

    /**
     * Builds the signing key at startup, and refuses to start if the secret is unusable.
     *
     * <h3>Why the validation is in the constructor</h3>
     * An application that boots with a blank or too-short secret looks completely healthy
     * while minting tokens that anyone can forge - because HS256's security rests entirely
     * on the key being unguessable. Failing here means a misconfiguration is a startup
     * error with a clear message, discoverable in seconds, rather than a silent
     * vulnerability that a penetration test finds in month six.
     *
     * <h3>Why 32 bytes</h3>
     * HS256 needs a 256-bit key. The check is on the <b>decoded bytes</b>, not the string
     * length: a 40-character base64 string is 30 bytes. That distinction is the common
     * mistake - someone sets a 32-character secret believing it satisfies a 32-byte
     * requirement, and it may or may not.
     */
    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs,
            @Value("${app.jwt.issuer}") String issuer) {

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("""
                    JWT_SECRET is not set.

                    Generate one with:  openssl rand -base64 48
                    or:                 head -c 48 /dev/urandom | base64

                    The application refuses to start without it: a blank signing key means
                    every token it issues is forgeable by anyone who reads the source.
                    """);
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least 32 bytes for HS256, but it is %d bytes (%d characters). "
                            .formatted(keyBytes.length, secret.length())
                            + "Note that the requirement is on bytes, not characters - generate one with "
                            + "`openssl rand -base64 48`.");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
        this.issuer = issuer;

        log.info("JwtService initialised: algorithm=HS256, expiry={}s, issuer={}",
                expirationMs / 1000, issuer);
    }

    /**
     * Mints a token for a user.
     *
     * <h3>Why {@code .issuer(...)} is set explicitly</h3>
     * The {@code iss} claim identifies who minted the token, and verification checks it. It
     * must be a URL-shaped string: {@code JwtClaimAccessor.getIssuer()} is URL-typed and
     * throws on a bare name, while the validator compares it as a string - so a plain
     * "ecommerce-backend" appears to work until something asks for it as a URL. The default
     * in {@code application.yml} is therefore a proper URI.
     *
     * <h3>Why {@code .id(UUID)} - the {@code jti} claim</h3>
     * A unique token id. Nothing in this application revokes tokens by it (there is no
     * refresh-token table - see the non-goals), but it costs one line and it makes a token
     * traceable in a log. Without it, two tokens issued to the same user in the same second
     * are byte-identical, and there is no way to tell which one a request presented.
     *
     * <h3>Why {@code iat} and {@code exp} both come from the same {@code Instant}</h3>
     * Computing {@code Instant.now()} twice can straddle a second boundary, so
     * {@code exp - iat} would not be exactly {@code expirationMs}. It also trips the encoder's
     * assertion that {@code exp > iat} in edge cases. One instant, two uses.
     */
    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(expirationMs);

        return Jwts.builder()
                // `sub` is the user id, and it is what the filter reads back.
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_NAME, user.getName())
                /*
                 * The role claim is included for convenience - a client can read it to
                 * decide which navigation to show without decoding anything else.
                 *
                 * IT IS NOT AN AUTHORIZATION INPUT. The filter reloads the user row and
                 * takes the authority from there. Treating this claim as authoritative
                 * would be a privilege-escalation bug the moment somebody's role changes.
                 */
                .claim(CLAIM_ROLE, user.getRole().name())
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                /*
                 * `signWith(key, algorithm)` - the ALGORITHM IS EXPLICIT.
                 *
                 * The one-argument `signWith(key)` infers the algorithm from the key's
                 * length: 32 bytes gives HS256, 48 gives HS384, 64 gives HS512. So rotating
                 * to a longer secret would silently start minting HS512 tokens and
                 * invalidate every existing session, with nothing in the diff to explain
                 * why. Naming it here makes that impossible.
                 */
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * The user id from a token's {@code sub} claim, without verifying anything else.
     *
     * <p><b>Only call this after {@link #extractUsername} has succeeded on the same token.</b>
     * It exists as a separate step because verification parses the token once and this reads
     * the result of that parse; calling it standalone on an unverified token would be
     * reading attacker-controlled data.
     */
    public Long extractUserId(String token) {
        Claims claims = parseClaims(token);
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new MalformedJwtException("The access token is missing the 'sub' claim.");
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ex) {
            // Wrapping rather than letting NumberFormatException escape: this class's
            // contract is that every failure it raises is a JwtException, so callers can
            // catch one family instead of enumerating exceptions from three packages.
            throw new MalformedJwtException("The access token's 'sub' claim is not a valid user id.");
        }
    }

    /**
     * Verifies a token's signature, expiry and issuer, returning the subject.
     *
     * <h3>Why every required claim is checked explicitly</h3>
     * RFC 7519 makes <b>every registered claim optional</b>, and jjwt enforces {@code exp}
     * only when it is present. So a correctly signed token with no {@code exp} parses
     * cleanly - and code that then does {@code claims.getExpiration().toInstant()} throws a
     * {@code NullPointerException}, which surfaces as a <b>500 from inside an authentication
     * filter</b>. Only somebody holding the signing key can construct such a token, which is
     * why it is never found by fuzzing.
     *
     * <p>The fix is to require every claim this class reads. {@link #requireClaim} names the
     * missing claim in the error and never includes its value.
     */
    public String extractUsername(String token) {
        Claims claims = parseClaims(token);

        requireClaim(claims, CLAIM_EMAIL);
        requireClaim(claims, CLAIM_ROLE);

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new MalformedJwtException("The access token is missing the 'sub' claim.");
        }

        /*
         * Reading exp/iat forces jjwt to have validated them, and turns a would-be
         * NullPointerException into a JwtException the filter already handles.
         */
        requireInstant(claims.getExpiration(), "exp");
        requireInstant(claims.getIssuedAt(), "iat");

        return subject;
    }

    /**
     * Verifies a token and reports whether it is acceptable.
     *
     * <p>Used by the filter, which must not throw on a bad token - it has to fall through and
     * let the security chain decide. Catching inside the service keeps that contract in one
     * place instead of a try/catch in the filter.
     *
     * <p>Logging is deliberately limited to the exception <b>class</b>. Never the token, and
     * never a claim value: a log line containing a token is a credential in a log aggregator,
     * readable by everyone with dashboard access and retained for months.
     */
    public boolean isTokenValid(String token) {
        try {
            extractUsername(token);
            return true;
        } catch (ExpiredJwtException ex) {
            // Not a problem worth a warning: an expired token is the normal lifecycle,
            // and the client is expected to handle it by signing in again.
            log.debug("Token expired at {}", ex.getClaims().getExpiration());
            return false;
        } catch (SignatureException ex) {
            // This one is worth attention: a valid-looking token whose signature fails
            // means either a secret mismatch between instances or a forgery attempt.
            log.warn("Rejected a token with an invalid signature");
            return false;
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Rejected a malformed or unverifiable token: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    /** The configured token lifetime, in seconds - used to populate {@code AuthResponse.expiresIn}. */
    public long getExpirationSeconds() {
        return expirationMs / 1000;
    }

    // =================================================================
    //  Internals
    // =================================================================

    /**
     * Verifies and parses in one step.
     *
     * <p>{@code verifyWith(PublicKey)} is what performs the signature and expiry checks -
     * everything else in this class is about what to do with the result. jjwt 0.12 renamed
     * this whole area: the old {@code parseClaimsJws} is gone, replaced by
     * {@code parseSignedClaims} returning a {@code Jws<Claims>}.
     *
     * <p>One parse per call rather than parsing in {@code extractUsername} and again in
     * {@code extractUserId}: HMAC verification is the expensive part of handling a token,
     * and doing it twice per request would double that cost for no benefit. The cost is a
     * slightly awkward contract on {@code extractUserId}, documented there.
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Reads a required string claim, or fails naming the claim and never its value. */
    private static String requireClaim(Claims claims, String name) {
        String value = claims.get(name, String.class);
        if (value == null || value.isBlank()) {
            throw new MalformedJwtException("The access token is missing the '" + name + "' claim.");
        }
        return value;
    }

    /** Reads a required temporal claim, so a null becomes a JwtException rather than an NPE. */
    private static Date requireInstant(Date value, String name) {
        if (value == null) {
            throw new MalformedJwtException("The access token is missing the '" + name + "' claim.");
        }
        return value;
    }
}
