package com.shop.ecommerce.controller;

import com.shop.ecommerce.dto.auth.AuthResponse;
import com.shop.ecommerce.dto.auth.LoginRequest;
import com.shop.ecommerce.dto.auth.RegisterRequest;
import com.shop.ecommerce.dto.user.UserResponse;
import com.shop.ecommerce.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration and login - the two endpoints that issue a token.
 *
 * <h2>Why both routes are fully public, and why that is safe</h2>
 *
 * <p>These are the only two non-public-shaped endpoints in the API, and they are exempt
 * from authentication for the obvious reason: you cannot present a token before you have
 * one. What makes that safe is that neither endpoint does anything on behalf of a user -
 * {@code /register} creates a new identity, {@code /login} verifies one, and the token they
 * return is the only thing that grants access to anything else.
 *
 * <p>Both are listed <b>exactly</b> in {@code SecurityConfig.PUBLIC_ROUTES} rather than
 * matched with {@code /api/auth/**}. That distinction matters: a wildcard would also make
 * any future {@code /api/auth/something-sensitive} public by default, and the failure mode
 * of that mistake is silence. An exact list means a new auth route is protected until
 * somebody deliberately adds it.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Register, log in, and inspect the current session")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Creates a customer account.
     *
     * <h3>Two things this endpoint cannot do</h3>
     *
     * <p><b>It cannot create an administrator.</b> {@link RegisterRequest} has no
     * {@code role} component, and the service assigns {@code Role.CUSTOMER} as a literal.
     * Accepting a role here - or ignoring one that was sent - is the single most common
     * privilege-escalation bug in a Spring Security application, and it is structurally
     * impossible in this one because there is no field to accept.
     *
     * <p><b>It cannot set any server-owned field.</b> No {@code id}, no {@code enabled}, no
     * {@code provider}, no timestamps. Those are assigned by the service or by JPA auditing.
     *
     * <h3>Why 201 and not 200</h3>
     *
     * <p>A resource was created, and the response carries both that resource's public
     * representation and a usable token. 201 is the honest status, and a client that
     * branches on it can tell "signed up" from "signed in" without inspecting the body.
     */
    @PostMapping("/register")
    @SecurityRequirements  // excludes this operation from the global bearer-auth requirement in Swagger UI
    @Operation(summary = "Register a new customer account",
            description = "Creates an account with the CUSTOMER role. Returns a JWT, so the client is signed in immediately. "
                    + "The role cannot be chosen by the caller.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created and signed in",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed (bad email, short password, password over 72 bytes)"),
            @ApiResponse(responseCode = "409", description = "That email already has an account")
    })
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Exchanges credentials for a token.
     *
     * <h3>Why the password has no minimum length here</h3>
     *
     * <p>{@link LoginRequest} validates that the password is present, but not its length.
     * That is deliberate. A length rule on login would reject {@code "abc"} with a
     * <b>400</b>, while a wrong password of acceptable length gets a <b>401</b> - and the
     * difference between those two responses tells an attacker whether a password is
     * structurally plausible before they have guessed anything. Login should answer exactly
     * one question: are these credentials correct?
     *
     * <h3>Why a wrong email and a wrong password give the same response</h3>
     *
     * <p>{@code UnauthorizedException.badCredentials()} is raised for both, with the same
     * message and the same code. Distinguishing them would turn this endpoint into an
     * account-enumeration oracle: submit a list of emails, and the ones that say "wrong
     * password" instead of "no such user" are the ones worth attacking.
     *
     * <p>The one deliberate exception is a disabled account, which returns the error code
     * {@code ACCOUNT_DISABLED}. It is a small concession - it does confirm the account
     * exists - and it is justified because the alternative is a user whose correct password
     * produces a generic failure message and no way to understand why.
     *
     * <p>Note that it is still a <b>401</b>, not a 403: the request failed at
     * authentication. The code is what distinguishes the two failures, which is why a client
     * should branch on {@code error} rather than on the status alone. (This javadoc and the
     * {@code @ApiResponse} above both said 403 until an HTTP-level test compared them with
     * the running application; {@code UnauthorizedException} has always produced a 401.)
     */
    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Log in with email and password",
            description = "Returns a JWT valid for the configured lifetime. Identical response for an unknown email and a wrong password.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "Missing email or password"),
            @ApiResponse(responseCode = "401",
                    description = "Email/password combination is wrong, or the account is disabled "
                            + "(distinguish by the `error` field: INVALID_CREDENTIALS vs ACCOUNT_DISABLED)")
    })
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * The currently authenticated user.
     *
     * <p>Exists so a frontend can validate a stored token on startup: on reload, the app
     * has a token in memory (or session storage) but does not know whether it is still
     * valid, or what role it carries. Calling this returns both answers in one request, and
     * a 401 is the unambiguous signal to clear the token and show the login page.
     *
     * <p>Note there is no {@code /api/auth/logout}. Logging out with a stateless JWT is a
     * client-side action: discard the token. There is nothing on the server to invalidate
     * unless a denylist is introduced - which is explicitly out of scope, and which would
     * cost a lookup on every request in exchange for shortening a token's life. The
     * configured expiry is the control instead.
     */
    @PostMapping("/me")
    @Operation(summary = "Get the authenticated user",
            description = "Used by the frontend on startup to validate a stored token and read the current role.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The current user",
                    content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "401", description = "No token, or an invalid or expired one")
    })
    public ResponseEntity<UserResponse> me() {
        return ResponseEntity.ok(authService.currentUser());
    }
}
