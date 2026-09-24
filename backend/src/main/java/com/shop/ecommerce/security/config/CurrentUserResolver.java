package com.shop.ecommerce.security.config;

import com.shop.ecommerce.exception.UnauthorizedException;
import com.shop.ecommerce.security.jwt.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Reads the current authenticated user out of the security context.
 *
 * <h2>Why this exists rather than each controller reading the context</h2>
 *
 * <p>Three reasons, and the third is the one that matters:
 *
 * <ol>
 *   <li><b>The cast is in one place.</b> {@code (AuthenticatedUser) authentication.getPrincipal()}
 *       repeated in twenty controllers is twenty places for a {@code ClassCastException} when
 *       something other than our filter sets the authentication.</li>
 *   <li><b>Null handling is decided once.</b> "No authentication" means the filter chain let
 *       the request through without one - which should be impossible on a protected route, and
 *       therefore a 401 rather than a {@code NullPointerException}.</li>
 *   <li><b>Ownership becomes impossible to take from a parameter.</b> This is the important
 *       one. Every service method that needs to know <em>whose</em> data to touch calls this.
 *       There is no {@code userId} request parameter anywhere in the application, so no
 *       controller can accidentally delegate "whose cart is this?" to the client. An
 *       ownership check that comes from a request parameter is a check the caller controls.</li>
 * </ol>
 */
@Component
public class CurrentUserResolver {

    /**
     * The authenticated user, or a 401 if there is none.
     *
     * <p>A 401 rather than a 403: the request had no valid identity at all, so the correct
     * instruction to the client is "authenticate", not "you may not do this".
     */
    public AuthenticatedUser requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("Authentication is required to access this resource.");
        }

        Object principal = authentication.getPrincipal();

        /*
         * The principal is not our own type in exactly one situation worth handling:
         * Spring Security represents an anonymous request with the String
         * "anonymousUser" as the principal. Checking for the type rather than for
         * "not anonymous" covers that and any future Spring-assigned principal in one
         * condition.
         */
        if (!(principal instanceof AuthenticatedUser user)) {
            throw new UnauthorizedException("Authentication is required to access this resource.");
        }

        return user;
    }

    /** The current user's id - the value every ownership query is scoped by. */
    public Long requireCurrentUserId() {
        return requireCurrentUser().id();
    }

    /**
     * The current user, or null when the request is anonymous.
     *
     * <p>For endpoints that are genuinely public but behave differently for a signed-in
     * visitor. Used sparingly: a public endpoint whose behaviour branches on the caller is
     * one step away from an endpoint whose authorization does, and that is the shape of a
     * security bug. Where it is used, the branch is about <em>presentation</em> only.
     */
    public AuthenticatedUser currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return null;
        }
        return user;
    }

    /**
     * Whether the current request is authenticated as an administrator.
     *
     * <p>Note this is <b>not</b> used for authorization. Routes are protected by the filter
     * chain and by {@code @PreAuthorize}. This is for the handful of service-level decisions
     * where the question is genuinely about scope - such as an admin being allowed to see
     * every order while a customer sees only their own.
     */
    public boolean isCurrentUserAdmin() {
        AuthenticatedUser user = currentUserOrNull();
        return user != null && user.isAdmin();
    }
}
