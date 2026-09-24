package com.shop.ecommerce.security.oauth;

import com.shop.ecommerce.entity.AuthProvider;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import com.shop.ecommerce.repository.UserRepository;
import com.shop.ecommerce.security.jwt.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Turns a successful Google sign-in into an application JWT.
 *
 * <h2>OAuth 2.0 versus OpenID Connect - the distinction that matters here</h2>
 *
 * <pre>
 *   OAuth 2.0          = an AUTHORIZATION framework.
 *                        It answers "may this app do X on your behalf?"
 *                        It issues an ACCESS TOKEN, which is a capability - a key.
 *                        It says nothing about WHO the user is.
 *
 *   OpenID Connect     = an AUTHENTICATION layer built ON TOP of OAuth 2.0.
 *                        It answers "who is this person?" by adding a standard
 *                        ID TOKEN - a JWT with `sub`, `email`, `iss`, `aud` - and a
 *                        UserInfo endpoint that returns the same claims.
 * </pre>
 *
 * <p>Using OAuth 2.0 alone to authenticate is the classic mistake and it has a name: it
 * assumes that holding a Google access token proves identity. It does not. That token might
 * have been issued to a different application, or have a narrow scope. OpenID Connect fixes
 * this by defining exactly what an identity assertion looks like - and the {@code sub} claim
 * is the part that is the actual identity.
 *
 * <h2>Why the handler ends in a redirect rather than JSON</h2>
 *
 * <p>This is the piece people find surprising. The email/password login is a
 * {@code @RestController} returning {@code AuthResponse} as JSON. The Google flow cannot be:
 * it is driven by <b>Spring's own OAuth2 filter</b>, which ends by issuing an HTTP redirect.
 * There is no controller method to return from.
 *
 * <p>So this handler's job is:
 * <ol>
 *   <li>Read the identity Google returned.</li>
 *   <li>Find or create the local account - <b>always as CUSTOMER</b>.</li>
 *   <li>Mint the <em>same</em> application JWT the password path mints.</li>
 *   <li>Redirect the browser to the frontend with the token.</li>
 * </ol>
 *
 * <p>From step 3 onwards the client is holding an ordinary application token, and nothing
 * downstream - no filter, no controller, no authorization rule - knows or cares which road
 * it came down.
 */
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final String frontendRedirectUri;

    public OAuth2LoginSuccessHandler(UserRepository userRepository,
                                     JwtService jwtService,
                                     @Value("${app.oauth2.redirect-uri}") String frontendRedirectUri) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.frontendRedirectUri = frontendRedirectUri;
    }

    /**
     * Runs after Google has authenticated the user.
     *
     * <h3>Why it needs its own transaction</h3>
     *
     * <p>This executes outside any service method, so there is no surrounding transaction to
     * join. Without {@code @Transactional}, the {@code save} of a brand-new user would still
     * work (JPA repositories are transactional by default) but the subsequent read-back and
     * the id used for the token would come from a detached instance - which in practice means
     * a null id and a token with {@code sub="null"}.
     *
     * <h3>Why a failed Google login must not create anything</h3>
     *
     * <p>Note the order: the account is resolved <em>first</em>, and any failure throws before
     * the token is minted. A half-completed sign-in that leaves a user row behind but sends no
     * token would be worse than an outright failure - the next attempt would find the account
     * and take a different path.
     */
    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        String email = oAuth2User.getAttribute("email");
        /*
         * `sub` is the stable Google identity. NOT the email.
         *
         * Email addresses get changed and, more importantly, get REASSIGNED. Keying an
         * account on an email means that whoever controls that address later signs into
         * this account. `sub` is Google's immutable identifier for the account, and it is
         * what an identity should be keyed on.
         *
         * (This project stores the account against the email because there is no separate
         *  identity table - see the README's "What is not built". The email comparison is
         *  gated on email_verified below, which is the mitigation available without that
         *  table. The full design is described in the README.)
         */
        String googleSubject = oAuth2User.getAttribute("sub");
        String name = oAuth2User.getAttribute("name");

        if (email == null || email.isBlank()) {
            /*
             * Fail closed. A Google account without a verified email cannot be matched to a
             * local account, and creating one with a null email would violate the NOT NULL
             * constraint - producing a 500 instead of a clear failure.
             */
            log.warn("Google sign-in refused: no email claim for subject {}", googleSubject);
            redirectWithError(response, "google_email_missing");
            return;
        }

        /*
         * The claim that makes email matching safe at all.
         *
         * `email_verified` is what tells us Google has confirmed the user controls this
         * address. Without checking it, an attacker could sign up for a Google account
         * claiming to own victim@example.com (if Google allowed unverified addresses to
         * reach this endpoint) and be handed the local account.
         *
         * Note the `Boolean.FALSE.equals` form rather than `!verified`:
         *   - if the claim is ABSENT, `(Boolean) null` would cause an NPE with `!verified`
         *   - if the claim arrives as the STRING "false" (which some providers do),
         *     `if (claim)` treats the non-empty string as truthy and lets it through
         * Both are authentication bypasses. Using Boolean.FALSE.equals plus an explicit
         * null check fails closed on both.
         */
        Object verifiedClaim = oAuth2User.getAttribute("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(verifiedClaim)
                || "true".equalsIgnoreCase(String.valueOf(verifiedClaim));

        if (!emailVerified) {
            log.warn("Google sign-in refused for {}: email is not verified", email);
            redirectWithError(response, "google_email_not_verified");
            return;
        }

        User user = findOrCreateUser(email, name);

        if (!user.isEnabled()) {
            log.warn("Google sign-in refused for {}: account is disabled", email);
            redirectWithError(response, "account_disabled");
            return;
        }

        String token = jwtService.generateToken(user);

        log.info("Google sign-in succeeded for userId={} (new account: {})",
                user.getId(), user.getCreatedAt() == null);

        /*
         * The token goes in the FRAGMENT (#), not the query string.
         *
         * This is a real decision, not a style choice. A query string is sent to the server
         * in the Referer header of every subsequent request on the destination page, and it
         * is written into server access logs and browser history. A fragment is never sent to
         * a server at all - it is for the browser only - so the token stays out of logs.
         *
         * The trade-off, stated honestly: a fragment is readable by any JavaScript on the
         * destination page. Since that page is our own frontend, which is about to store the
         * token anyway, this is a smaller exposure than a log.
         */
        String redirectUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                .fragment("token=" + URLEncoder.encode(token, StandardCharsets.UTF_8))
                .build(true)
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }

    /**
     * Finds the local account for a Google email, or creates one.
     *
     * <h2>The role is a literal here, and that is the security control</h2>
     *
     * <p>{@code Role.CUSTOMER} is written out in full. There is no role parameter, no
     * configuration flag, and no claim read from Google. Google cannot tell us whether
     * somebody should be an administrator of <em>this</em> shop - it has no idea the role
     * exists - and a Google-sourced role claim would be a claim an attacker who could
     * influence their own Google profile might control.
     *
     * <p>Promotion to ADMIN happens in exactly one place
     * ({@code AdminUserController.updateRole}), reachable only by an existing admin. This
     * method cannot produce an admin, and a test asserts that.
     *
     * <h2>The account-linking decision, and its refusal case</h2>
     *
     * <p>When the email matches an existing account, that account is reused rather than
     * duplicated. The dangerous case is a pre-registration attack: an attacker registers
     * {@code victim@example.com} with a password they choose, then waits for the real owner
     * to sign in with Google. If the sign-in silently linked the two, the attacker would
     * retain password access to the victim's account.
     *
     * <p>The mitigation present here: linking only happens when the account's provider is
     * already {@code GOOGLE}, or when the password account is being claimed by a
     * <em>verified</em> Google email. A production system would go further and require an
     * authenticated link - the app's own token proving control of the local account, plus
     * the verified Google token proving control of the external one, with no email
     * round-trip. That is described in the README as the upgrade path.
     */
    private User findOrCreateUser(String email, String name) {
        Optional<User> existing = userRepository.findByEmailIgnoreCase(email);

        if (existing.isPresent()) {
            User user = existing.get();
            /*
             * An account created through the password path, now being reached through
             * Google. This is the pre-registration attack surface described above, so it is
             * refused rather than silently linked.
             */
            if (user.getProvider() == AuthProvider.LOCAL && user.canUsePasswordLogin()) {
                log.warn("Google sign-in refused for {}: a password account with this email already exists. "
                        + "Sign in with your password, or use an authenticated account link.", email);
                throw new AccountLinkRefusedException(email);
            }
            return user;
        }

        User newUser = new User();
        newUser.setEmail(email);
        newUser.setName(name != null && !name.isBlank() ? name : deriveNameFromEmail(email));
        /*
         * No password. A Google user has none, which is why the column is nullable - and
         * why `User.canUsePasswordLogin()` is false for them. Inventing a password hash here
         * would create a credential that should not exist.
         */
        newUser.setPassword(null);
        newUser.setProvider(AuthProvider.GOOGLE);
        // The literal. See the method javadoc.
        newUser.setRole(Role.CUSTOMER);
        newUser.setEnabled(true);

        User saved = userRepository.save(newUser);
        log.info("Created a new CUSTOMER account from Google sign-in: userId={}", saved.getId());
        return saved;
    }

    /**
     * Falls back to the local part of the email when Google provides no display name.
     *
     * <p>Google usually does provide one, but not always - and a user row with a null name
     * would violate the NOT NULL constraint and turn a successful authentication into a 500.
     * "ada.lovelace@example.com" becomes "ada". Someone's initial is not a good name, but it
     * is a better outcome than a failed sign-in, and they can change it in their profile.
     */
    private String deriveNameFromEmail(String email) {
        int at = email.indexOf('@');
        String localPart = at > 0 ? email.substring(0, at) : email;
        return localPart.length() > 100 ? localPart.substring(0, 100) : localPart;
    }

    /**
     * Sends the browser back to the frontend with an error code in the query string.
     *
     * <p>An error <em>code</em> rather than a message: the frontend owns the wording, so it
     * can be translated and styled consistently. Passing a backend message through to a user
     * would also risk leaking implementation detail.
     *
     * <p>Query string rather than fragment here, because an error code is not a secret and
     * the frontend reads it before deciding what to do. Using the fragment for the token and
     * the query for errors keeps the two channels distinct.
     */
    private void redirectWithError(HttpServletResponse response, String errorCode) throws IOException {
        String url = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                .queryParam("error", errorCode)
                .build(true)
                .toUriString();
        response.sendRedirect(url);
    }

    /**
     * Exposed so {@code SecurityConfig}'s failure handler can build its redirect from the
     * same configured value. Two components reading one config property is fine; two
     * components each holding their own copy of the URL is how they drift apart.
     */
    public String getFrontendRedirectUri() {
        return frontendRedirectUri;
    }

    /**
     * Raised when linking a Google identity to an existing password account is refused.
     *
     * <p>A nested type because it has exactly one raiser and is not part of the API's error
     * contract - it never reaches a client as-is (the flow is a redirect, so the user sees
     * the {@code google_auth_failed} page). Keeping it here means the refusal and its reason
     * are readable in one place, next to the decision that raises it.
     */
    public static class AccountLinkRefusedException extends RuntimeException {
        public AccountLinkRefusedException(String email) {
            super("Refusing to link the Google identity to the existing password account for " + email);
        }
    }
}
