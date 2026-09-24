package com.shop.ecommerce.security.jwt;

import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal stored in the {@code SecurityContext}.
 *
 * <h2>Why a record and not the {@link User} entity</h2>
 *
 * <p>The instinct is to put the entity in the context - it is right there, it has everything.
 * It also carries the BCrypt password hash, which means:
 *
 * <ul>
 *   <li>Every controller method that logs its principal writes a credential to disk. That
 *       is not hypothetical: {@code log.info("processing request for {}", principal)} is a
 *       line somebody adds while debugging, and with the entity it prints the hash.</li>
 *   <li>One {@code return currentUser;} from a controller publishes the hash as JSON. The
 *       DTO boundary is the defence against that, and putting an entity in the context
 *       means the boundary is one careless shortcut away from being bypassed.</li>
 * </ul>
 *
 * <p>A record with three fields has nothing to leak. Its {@code toString} prints an id, an
 * email and a role - all of which appear in the application's normal responses anyway.
 *
 * <h2>Why it holds no password and no provider</h2>
 *
 * <p>Neither is needed for authorization. Anything not needed for authorization has no
 * business being carried through the request lifecycle, because every field that is present
 * is a field that can end up somewhere unintended.
 *
 * @param id    the user's primary key
 * @param email their email, used in log correlation and order attribution
 * @param role  their role, as loaded from the database <b>on this request</b>
 */
public record AuthenticatedUser(
        Long id,
        String email,
        Role role
) {

    /**
     * Builds the principal from a freshly-loaded user row.
     *
     * <p>Note "freshly-loaded": {@link JwtService} is not the only source of a principal -
     * the OAuth2 path builds one too - but in both cases the role comes from the database
     * row, never from a token claim. That is what makes a role change take effect on the
     * user's <em>next request</em> rather than when their token expires.
     */
    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
    }

    /**
     * The authorities Spring Security uses for {@code hasRole(...)} and
     * {@code @PreAuthorize("hasRole('ADMIN')")}.
     *
     * <p>Built via {@link Role#authority()}, which produces {@code ROLE_ADMIN}. Declaring
     * the prefix inline here is how the {@code ROLE_ROLE_ADMIN} typo gets introduced - a
     * name that matches nothing, so the rule denies everyone while looking correct.
     */
    public Collection<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    /**
     * Whether this user is an administrator.
     *
     * <p>A convenience for conditional logic inside a service, where the question is
     * genuinely "should this person see everyone's data" rather than "is this route
     * allowed". Note that this is <b>not</b> how routes are protected - that is the filter
     * chain's job. Using this method as a gate in a controller would be re-implementing
     * authorization by hand, in a place with no test that enumerates the routes.
     */
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
