package com.shop.ecommerce.exception;

import com.shop.ecommerce.dto.common.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Authentication failed, or was not supplied.
 *
 * <h2>Why the message is always the same</h2>
 *
 * <p>{@link #badCredentials()} says "Invalid email or password" for every failure - unknown
 * email, wrong password, Google-only account with no password set. It deliberately does not
 * say <em>which</em>.
 *
 * <p>Saying "no account with that email" would turn this endpoint into a free account
 * enumeration service: an attacker submits a list of addresses and learns which ones hold
 * accounts here. That is valuable on its own, and considerably more valuable when paired
 * with a breach elsewhere - it tells the attacker which of the leaked passwords are worth
 * trying.
 *
 * <p>The same reasoning is why the login DTO has no minimum password length: a 400 for a
 * short password would reveal that no valid password is that short.
 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, message);
    }

    public UnauthorizedException(String errorCode, String message) {
        super(HttpStatus.UNAUTHORIZED, errorCode, message);
    }

    /** The single response for every failed password login. See the class javadoc. */
    public static UnauthorizedException badCredentials() {
        return new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS,
                "Invalid email or password");
    }

    /** A Google account attempting a password login. Same message, for the same reason. */
    public static UnauthorizedException passwordLoginNotAvailable() {
        return new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS,
                "Invalid email or password");
    }

    public static UnauthorizedException accountDisabled() {
        return new UnauthorizedException(ErrorCode.ACCOUNT_DISABLED,
                "This account has been disabled");
    }

    /**
     * The caller's identity is no longer valid - most often because the account was
     * deleted while a still-unexpired token was in circulation.
     *
     * <p>Contrast with {@link #badCredentials()}: that one deliberately says nothing so the
     * login endpoint cannot be used to enumerate accounts. This one is reached only by a
     * caller who already holds a validly signed token, so telling them the truth leaks
     * nothing they did not already have - and the alternative, a generic "invalid
     * credentials", would send a signed-in user to a login form that would also fail.
     */
    public static UnauthorizedException invalidToken(String reason) {
        return new UnauthorizedException(ErrorCode.INVALID_TOKEN, reason);
    }
}
