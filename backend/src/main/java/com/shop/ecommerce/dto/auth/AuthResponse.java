package com.shop.ecommerce.dto.auth;

import com.shop.ecommerce.dto.user.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a successful sign-in returns.
 *
 * <p>Both authentication paths - email/password and Google - produce this same record,
 * which is the point: a client that has signed in has a token and a user, and does not
 * need to know which road it came down. Everything differentiating happens on the server.
 *
 * <p>Two fields rather than a bare token string, so the frontend can render a navbar
 * immediately after login without a second round trip to {@code GET /api/users/me}. That
 * is one fewer request on the most latency-sensitive path in the application.
 *
 * @param token     the signed JWT. Sent on every subsequent request as
 *                  {@code Authorization: Bearer <token>}.
 * @param tokenType always {@code "Bearer"} - included because OAuth 2.0's token response
 *                  format specifies it, and because it tells a client that does not know
 *                  this API how to use the token.
 * @param expiresIn the token's lifetime in <b>seconds</b>, matching the OAuth 2.0
 *                  convention. The frontend uses it to schedule a proactive sign-out
 *                  rather than discovering expiry from a failed request.
 * @param user      the authenticated user's public profile. Never contains a password.
 */
@Schema(description = "Successful authentication response")
public record AuthResponse(

        @Schema(description = "Signed JWT access token")
        String token,

        @Schema(example = "Bearer")
        String tokenType,

        @Schema(example = "3600", description = "Token lifetime in seconds")
        long expiresIn,

        @Schema(description = "The authenticated user")
        UserResponse user

) {

    /**
     * Factory so the two call sites - password login and OAuth2 success - cannot drift
     * in how they build the response. {@code tokenType} in particular is easy to typo in
     * one place, and a client branching on it would then behave differently depending on
     * how the user signed in.
     */
    public static AuthResponse of(String token, long expiresInSeconds, UserResponse user) {
        return new AuthResponse(token, "Bearer", expiresInSeconds, user);
    }
}
