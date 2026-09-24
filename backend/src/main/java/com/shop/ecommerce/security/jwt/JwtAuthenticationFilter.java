package com.shop.ecommerce.security.jwt;

import com.shop.ecommerce.entity.User;
import com.shop.ecommerce.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads a bearer token, verifies it, and installs an authentication into the security
 * context.
 *
 * <h2>The rule this filter must never break</h2>
 *
 * <p><b>It authenticates; it never rejects.</b>
 *
 * <p>If a token is missing, malformed or expired, this filter does nothing and calls
 * {@code chain.doFilter(...)} anyway. The security chain then decides, based on whether the
 * target route needs authentication. The obvious alternative - write a 401 from here - breaks
 * every endpoint whose job is to <em>recover</em> from a bad token, most visibly the login
 * endpoint itself, which has no token and would become unreachable.
 *
 * <p>The symptom of getting this wrong is "everyone is signed out after an hour" with
 * nothing in the authentication code to explain it, because the request never reaches it.
 *
 * <h2>Why the authority comes from the database, not from the token</h2>
 *
 * <p>The token carries a {@code role} claim. This filter ignores it and reloads the user row
 * instead, because the claim is a snapshot from issue time. Trusting it would mean:
 *
 * <ul>
 *   <li>An admin demoted to customer keeps full admin access until their token expires.</li>
 *   <li>A disabled account keeps working for the same window.</li>
 * </ul>
 *
 * <p>Both are silent - the endpoint still works, so nothing looks wrong. The cost of reading
 * the row is one indexed primary-key lookup per authenticated request, which is the price of
 * correctness here. The alternative would be a token so short-lived that the role could not
 * meaningfully change within its lifetime - and that is a worse trade for the user.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    /**
     * {@code OncePerRequestFilter} guarantees one execution per request even when the request
     * is dispatched internally - for example to {@code /error} when a container-level failure
     * occurs. Without that guarantee the filter would run twice and perform the user lookup
     * twice.
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String token = extractBearerToken(request);

            /*
             * The guard that stops us overwriting an existing authentication.
             *
             * If the context already holds an authority, something earlier in the chain
             * established it - this filter must not clobber it. The practical case is a
             * request that carries both a token and, in tests, a @WithMockUser.
             */
            if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                authenticate(token, request);
            }
        } catch (Exception ex) {
            /*
             * Deliberately swallowed, and this is the important part.
             *
             * A filter that throws inside the security chain produces a 500 with a stack
             * trace, turning "your token expired" into "the server is broken" - and worse,
             * it leaks internals. Logging at debug and continuing means the request falls
             * through unauthenticated, and the chain answers with a clean 401.
             *
             * Debug rather than warn: an expired or malformed token on a public endpoint is
             * completely normal (a stale tab reloading the homepage), and warning on every
             * occurrence would drown the log.
             */
            log.debug("Could not authenticate request to {} {}: {}",
                    request.getMethod(), request.getRequestURI(), ex.getClass().getSimpleName());
            SecurityContextHolder.clearContext();
        }

        /*
         * ALWAYS. On every path, including the ones above that failed. This single line is
         * what makes the filter "authenticate, never reject".
         */
        filterChain.doFilter(request, response);
    }

    /**
     * Verifies the token and installs the authentication.
     *
     * <p>Three things must all hold before an authentication is created, and any one of them
     * failing means the request continues unauthenticated:
     * <ol>
     *   <li>The token's signature and standard claims verify.</li>
     *   <li>The user id in {@code sub} still corresponds to a row.</li>
     *   <li>That account is still enabled.</li>
     * </ol>
     */
    private void authenticate(String token, HttpServletRequest request) {
        if (!jwtService.isTokenValid(token)) {
            return;
        }

        Long userId = jwtService.extractUserId(token);
        if (userId == null) {
            return;
        }

        User user = userRepository.findById(userId)
                .filter(User::isEnabled)   // a disabled account authenticates nowhere
                .orElse(null);

        if (user == null) {
            // The token is validly signed but the account is gone or disabled. Logged at
            // info because this is worth knowing about - it is either a stale account or
            // somebody using a token after being disabled.
            log.info("Refusing a valid token for user id {}: account missing or disabled", userId);
            return;
        }

        AuthenticatedUser principal = AuthenticatedUser.from(user);

        /*
         * The THREE-argument constructor is what marks the token authenticated.
         *
         * The two-argument one (principal, credentials) leaves `authenticated = false`, and
         * Spring Security then treats the request as anonymous - so every protected route
         * returns 401 while the code looks entirely correct. This is a genuinely common
         * mistake and produces no warning.
         */
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,                    // no credentials to hold; the token was already verified
                        principal.authorities()  // ROLE_CUSTOMER or ROLE_ADMIN, from the DB row
                );

        // Records the remote address and session id for auditing. Not used by any logic in
        // this application, but it makes the details available to anything that needs them.
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * Pulls the token out of the {@code Authorization} header.
     *
     * <p>Returns null rather than throwing for anything unexpected. "No token" and "a header
     * I do not recognise" are the same situation from this filter's point of view: carry on
     * and let the chain decide.
     *
     * <p>The case-insensitive {@code Bearer} check matters because HTTP header values are
     * compared case-insensitively by convention, and clients differ - some send
     * {@code bearer}. Rejecting {@code bearer} would be a bug that only shows up with one
     * particular HTTP library.
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || header.isBlank()) {
            return null;
        }
        if (header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
