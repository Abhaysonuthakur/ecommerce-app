package com.shop.ecommerce.security.config;

import com.shop.ecommerce.dto.common.ErrorCode;
import com.shop.ecommerce.security.jwt.JwtAuthenticationFilter;
import com.shop.ecommerce.security.oauth.OAuth2LoginSuccessHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * The single place where authorization decisions are made.
 *
 * <h2>Read this file as a list of decisions, not as configuration</h2>
 *
 * <p>The rule that catches people out is {@code anyRequest().authenticated()} at the bottom.
 * It governs <b>every path this file does not mention</b> - including paths that do not
 * exist yet, and paths that are not part of the API at all. So the useful question to ask
 * about any new route is not "did I add a rule?" but "what does the fallback do to this?"
 *
 * <p>Two specific examples of routes that hide in that blind spot:
 * <ul>
 *   <li>The H2 console. It is not part of the API, so it is easy to overlook that this chain
 *       governs it. It is not public, so it falls to the fallback - and any signed-in
 *       customer would then reach a page that runs arbitrary SQL. It is not enabled in this
 *       project, and the matcher below is written defensively for the day somebody enables
 *       it for debugging.</li>
 *   <li>Actuator details. {@code /actuator/health} exposes no details (configured in
 *       {@code application.yml}), so it is safe to make public in its shallow form.</li>
 * </ul>
 *
 * <h2>Why method security as well as URL patterns</h2>
 *
 * <p>Two independent layers, deliberately:
 * <ul>
 *   <li><b>URL matching here</b> - refuses before a controller method is even selected. Cheap
 *       and central, and the failure mode is a denied route.</li>
 *   <li><b>{@code @PreAuthorize} on services</b> - refuses at the point of the operation. The
 *       failure mode here is only reachable if somebody adds a new route in a package this
 *       chain treats as public.</li>
 * </ul>
 *
 * <p>Neither alone is sufficient. URL patterns cannot express "an admin may not demote
 * themselves"; method security cannot stop a request that should never have been routed.
 */
@Configuration
@EnableMethodSecurity   // turns on @PreAuthorize / @PostAuthorize
public class SecurityConfig {

    /**
     * Public routes, named one at a time.
     *
     * <h3>Why this is an exact list and not {@code "/api/auth/**"}</h3>
     *
     * <p>The wildcard is shorter and it makes every route added to that package public by
     * default - including the ones that should not be. Consider
     * {@code GET /api/auth/me}: it is unanswerable without a token, so it obviously should
     * not be public, and a wildcard would silently make it so. A security default should be
     * the restrictive one; the exceptions should be things somebody had to type.
     *
     * <h3>Why {@code /error} is on the list</h3>
     *
     * <p>It looks out of place among the API routes. It is there because when Tomcat rejects
     * a request before Spring MVC sees it - a malformed header, a bad URL - the container
     * dispatches internally to {@code /error}, and that dispatch passes through this chain.
     * Without the entry, a request Tomcat rejected is answered 401 instead of its real
     * status, which is both wrong and deeply confusing to debug.
     *
     * <h3>Why the product and category GETs are split</h3>
     *
     * <p>{@code /api/products/**} cannot be blanket-public: {@code POST /api/products}
     * creates a product and must be admin-only. Spring Security matches
     * {@code HttpMethod.GET} separately for exactly this reason, and the write methods fall
     * through to the fallback - which requires authentication - and are then narrowed
     * further by the admin rules below.
     */
    private static final String[] PUBLIC_ROUTES = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/google/status",
            "/api/health",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/error",
    };

    /**
     * The OAuth2 callback path.
     *
     * <p>{@code /oauth2/authorization/**} starts the flow and {@code /login/oauth2/code/**}
     * is where Google redirects back. Both must be reachable without a token - the whole
     * point is that the user does not have one yet.
     */
    private static final String[] OAUTH2_ROUTES = {
            "/oauth2/authorization/**",
            "/login/oauth2/code/**",
    };

    /**
     * Documentation paths.
     *
     * <p>Public so that a developer can read the API without an account. In a production
     * deployment with a private API these would be removed - and this is the single line
     * that would change, which is why the decision is written down here rather than left
     * implicit.
     */
    private static final String[] DOCS_ROUTES = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final ApiErrorWriter apiErrorWriter;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    /**
     * Whether Google sign-in is configured.
     *
     * <h2>Why this flag has to exist</h2>
     *
     * <p>The obvious way to make Google login optional is to give the client id and secret
     * empty defaults - {@code ${GOOGLE_CLIENT_ID:}} - and assume the application starts
     * either way. <b>It does not.</b> Spring Boot validates the registration during context
     * refresh and refuses to start with:
     *
     * <pre>
     *   Client id of registration 'google' must not be empty.
     * </pre>
     *
     * <p>"Unset" and "present but empty" are different things, and empty fails validation
     * rather than being skipped. Since a developer who only wants email/password locally
     * should not have to obtain Google credentials, and since forcing them to is a worse
     * experience than the alternative, the fix is to not register the client at all unless
     * a client id was actually supplied.
     *
     * <p>This is the reason the {@code application.yml} block is written with
     * {@code ${GOOGLE_CLIENT_ID:}} rather than a placeholder value: the empty string is the
     * signal, and this flag is what reads it.
     */
    @Value("${spring.security.oauth2.client.registration.google.client-id:}")
    private String googleClientId;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
                          ApiErrorWriter apiErrorWriter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.apiErrorWriter = apiErrorWriter;
    }

    /** True when a Google client id was actually configured. */
    private boolean googleLoginEnabled() {
        return googleClientId != null && !googleClientId.isBlank();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                /*
                 * ============================================================
                 *  CSRF - disabled, and this is conditional on something
                 *
                 *  CSRF protection exists because a browser attaches cookies to
                 *  cross-site requests automatically. An attacker's page can therefore
                 *  make the victim's browser send an authenticated request without the
                 *  victim knowing.
                 *
                 *  This API has no ambient credential: the token is sent explicitly in
                 *  the Authorization header by JavaScript, and a cross-site form cannot
                 *  set that header. So CSRF does not apply here.
                 *
                 *  THE EXPIRY CONDITION, written down because it is load-bearing:
                 *  the moment the token is moved into an httpOnly cookie - the usual
                 *  next step, to protect it from XSS - CSRF becomes real, and this line
                 *  must change to CookieCsrfTokenRepository.withHttpOnlyFalse() IN THE
                 *  SAME COMMIT. A follow-up commit is the one that gets forgotten.
                 * ============================================================
                 */
                .csrf(AbstractHttpConfigurer::disable)

                // ------------------------------------------------------------
                //  CORS - the allowed origin is configurable, never "*"
                // ------------------------------------------------------------
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .sessionManagement(session ->
                        /*
                         * STATELESS is a correctness choice, not a performance one.
                         *
                         * Under the default policy Spring Security creates an HttpSession
                         * and stores the SecurityContext in it. The server then holds state
                         * that the token cannot invalidate - so signing out leaves the
                         * session cookie working, and "log out everywhere" is impossible.
                         *
                         * STATELESS means every request is authenticated from its own
                         * token. Nothing to leak between requests, nothing to invalidate.
                         */
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                /*
                 * ------------------------------------------------------------
                 *  Disable the defaults that are wrong for a token API
                 * ------------------------------------------------------------
                 *  formLogin   - would add an HTML login page at /login
                 *  httpBasic   - would add a browser popup and a credential path we
                 *                do not want
                 *  logout      - registers a /logout route that ends a session we do
                 *                not have, competing with our own endpoint
                 *  requestCache- saves the request to replay after login, which is a
                 *                redirect-based concept with no meaning for a token API
                 *                (and it can be used to replay a state-changing request)
                 */
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);

        /*
         * ------------------------------------------------------------
         *  OAuth2 login - registered ONLY when Google is configured
         * ------------------------------------------------------------
         *  Spring's own filter handles the authorization-code exchange with Google. It
         *  ends in a REDIRECT rather than JSON, which is why successHandler is needed: it
         *  runs after Google has authenticated the user and it is responsible for minting
         *  our JWT and sending the browser back to the frontend.
         *
         *  This is applied conditionally for a concrete reason, and it is not stylistic.
         *  Without a client id, Spring Boot's OAuth2ClientProperties validation refuses to
         *  start the application at all:
         *
         *      Client id of registration 'google' must not be empty.
         *
         *  An empty ${GOOGLE_CLIENT_ID:} is "present" and therefore validated - it is not
         *  the same as the property being absent. A developer who only wants
         *  email/password locally should not have to obtain Google credentials, so when no
         *  client id is configured this configurer is never applied and Spring Security
         *  registers no OAuth2 filters.
         *
         *  The consequence, which the frontend is told about via Google status: the route
         *  /oauth2/authorization/google does not exist, and its absence is intentional
         *  rather than a misconfiguration.
         * ------------------------------------------------------------
         */
        if (googleLoginEnabled()) {
            http.oauth2Login(oauth2 -> oauth2
                    .successHandler(oAuth2LoginSuccessHandler)
                    // On failure, send the user to the frontend with an error rather than
                    // showing Spring's default error page. The frontend can then display it
                    // in the application's own styling.
                    .failureHandler((request, response, exception) -> {
                        String redirect = "%s?error=google_auth_failed".formatted(
                                oAuth2LoginSuccessHandler.getFrontendRedirectUri());
                        response.sendRedirect(redirect);
                    }));
        }

        http
                /*
                 * ------------------------------------------------------------
                 *  401 and 403 from the chain use the shared writer
                 * ------------------------------------------------------------
                 *  Ordinary refusals inside the filter chain never reach
                 *  @RestControllerAdvice, because a filter runs outside the dispatcher.
                 *  Both handlers write through ApiErrorWriter, so a refusal from the
                 *  chain is byte-identical in shape to one from a controller.
                 */
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                apiErrorWriter.write(request, response,
                                        HttpStatus.UNAUTHORIZED,
                                        ErrorCode.UNAUTHORIZED,
                                        ApiErrorWriter.MSG_AUTHENTICATION_REQUIRED))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                apiErrorWriter.write(request, response,
                                        HttpStatus.FORBIDDEN,
                                        ErrorCode.ACCESS_DENIED,
                                        ApiErrorWriter.MSG_ACCESS_DENIED)))

                /*
                 * ============================================================
                 *  AUTHORIZATION RULES - order matters, first match wins
                 * ============================================================
                 */
                .authorizeHttpRequests(auth -> auth

                        // ---- Public: no token needed ----
                        .requestMatchers(PUBLIC_ROUTES).permitAll()
                        .requestMatchers(OAUTH2_ROUTES).permitAll()
                        .requestMatchers(DOCS_ROUTES).permitAll()

                        /*
                         * ---- Public reads of the catalogue ----
                         *
                         * GET only. A POST to /api/products falls through to the
                         * fallback below, which requires authentication, and is then
                         * narrowed to ROLE_ADMIN by the rule after next.
                         */
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categories", "/api/categories/**").permitAll()

                        /*
                         * ---- Admin-only routes ----
                         *
                         * hasRole("ADMIN"), NEVER hasRole("ROLE_ADMIN").
                         *
                         * hasRole prepends the ROLE_ prefix itself, so the doubled form
                         * looks for ROLE_ROLE_ADMIN - which no account has. The rule then
                         * denies everyone INCLUDING administrators, while reading exactly
                         * like the correct thing.
                         *
                         * The symptom is "admins cannot reach the admin page", which
                         * points at sign-in, at the token, at the role in the database -
                         * everywhere except the one line that is wrong.
                         */
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        /*
                         * Write operations on the catalogue. These are listed explicitly
                         * rather than relying on /api/admin/** alone, because the product
                         * and category controllers use REST-style paths
                         * (/api/products/{id}) rather than an admin prefix.
                         *
                         * Belt and braces: @PreAuthorize on the service methods enforces
                         * the same thing. If this line were deleted and nothing else
                         * changed, the endpoints would still be protected - and the
                         * reverse is also true.
                         */
                        .requestMatchers(HttpMethod.POST, "/api/products", "/api/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/categories", "/api/categories/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/categories/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/categories/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/categories/**").hasRole("ADMIN")

                        /*
                         * ---- Defence in depth: the H2 console ----
                         *
                         * Not enabled in this project, so this matcher is inert. It is
                         * here because the console is the classic route that falls to
                         * anyRequest().authenticated() and is then reachable by any
                         * signed-in customer - and its own login accepts user "SA" with
                         * NO PASSWORD, giving them arbitrary SQL.
                         *
                         * "Authenticated as a customer" is not a strong enough answer for
                         * a page that runs arbitrary SQL.
                         */
                        .requestMatchers("/h2-console/**").hasRole("ADMIN")

                        /*
                         * ---- Everything else needs a valid token ----
                         *
                         * This is the line that governs every path not named above,
                         * including routes added in future. Read it as a question, not
                         * as a default.
                         */
                        .anyRequest().authenticated())

                /*
                 * ------------------------------------------------------------
                 *  Where the JWT filter goes
                 * ------------------------------------------------------------
                 *  addFilterBefore with a CLASS, not a number.
                 *
                 *  UsernamePasswordAuthenticationFilter is disabled by this very config,
                 *  so the class is being used purely as a NAMED POSITION in Spring
                 *  Security's canonical filter ordering. A hand-chosen number would
                 *  silently mean something different after a framework upgrade; a named
                 *  position cannot.
                 */
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt, with the strength left at its default of 10.
     *
     * <h3>Why BCrypt rather than SHA-256</h3>
     *
     * <p>A general-purpose hash is designed to be <b>fast</b>, and that is exactly the wrong
     * property for a password. A GPU can compute billions of SHA-256 hashes per second, so a
     * leaked table of fast hashes is largely reversed by brute force.
     *
     * <p>BCrypt is deliberately slow and tunable. The cost factor 10 means 2^10 = 1024
     * iterations of an expensive key setup, so a single verification takes around 100 ms -
     * unnoticeable to a user signing in, and a hundred-million-fold increase in the cost of
     * guessing.
     *
     * <h3>Why the salt is not configured</h3>
     *
     * <p>BCrypt generates a random salt per password and stores it <em>inside</em> the 60-
     * character output. So two users with the identical password get different hashes, which
     * defeats precomputed rainbow tables - and there is nothing to configure.
     *
     * <h3>The 72-byte limit</h3>
     *
     * <p>BCrypt reads only the first 72 bytes and silently ignores the rest. That is why
     * {@code RegisterRequest} carries both {@code @Size(min = 8)} and
     * {@code @ByteLength(max = 72)} - the second one measures bytes, because 72 CJK
     * characters is 216 bytes and two passwords sharing their first 72 bytes would
     * authenticate identically.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS configuration, with the origin read from configuration rather than hardcoded.
     *
     * <h3>Why the wildcard is refused</h3>
     *
     * <p>{@code allowedOrigins("*")} tells the browser that <b>any</b> website may read this
     * API's responses. Combined with a token in local storage - which is what this frontend
     * uses - a malicious page could make requests and read the results.
     *
     * <p>The value comes from {@code FRONTEND_URL}, so the same build runs in development,
     * staging and production with only an environment variable changing. That is the point:
     * an origin baked into the code is an artefact that cannot be promoted between
     * environments.
     *
     * <h3>Why credentials are allowed</h3>
     *
     * <p>Needed for the OAuth2 redirect flow, which involves cookies on the backend's own
     * domain during the authorization-code exchange. Note that {@code allowCredentials(true)}
     * is <b>incompatible with a wildcard origin</b> - Spring rejects the combination outright,
     * which is another reason the wildcard is not an option here.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        configuration.setAllowedOrigins(origins);

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        /*
         * The headers the browser is allowed to SEND. Authorization must be here or every
         * authenticated cross-origin request fails the preflight.
         */
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"));

        /*
         * The headers JavaScript is allowed to READ. Authorization is exposed so a client
         * can inspect the token it sent - useful for debugging - and Content-Disposition
         * would be needed if downloads were added later.
         */
        configuration.setExposedHeaders(List.of("Authorization", "Content-Disposition"));

        /*
         * How long the browser may cache the preflight result. Without this, every
         * non-simple request - which is every request with an Authorization header - sends
         * an extra OPTIONS request first, doubling the request count.
         */
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
