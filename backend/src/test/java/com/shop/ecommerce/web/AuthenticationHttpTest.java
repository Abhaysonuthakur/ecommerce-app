package com.shop.ecommerce.web;

import com.shop.ecommerce.dto.auth.LoginRequest;
import com.shop.ecommerce.dto.auth.RegisterRequest;
import com.shop.ecommerce.dto.common.ErrorCode;
import com.shop.ecommerce.entity.Role;
import com.shop.ecommerce.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authentication and authorization, proved through the real filter chain.
 *
 * <h2>Why this class is not merely "the same checks at HTTP level"</h2>
 *
 * <p>Authorization in this application is decided in two places that cannot see each other:
 * URL patterns in {@code SecurityConfig} and {@code @PreAuthorize} on service methods.
 * Neither is testable from the other's layer, and a bug in either is invisible at the
 * service-layer tests - {@code AdminServiceImpl} has no test that could notice its URL
 * prefix was left unguarded, and {@code SecurityConfig} has no test that could notice a
 * service method it was protecting had lost its annotation.
 *
 * <h2>The distinction this class exists to pin down</h2>
 *
 * <p><b>401 and 403 are different answers, and the difference is not cosmetic.</b>
 *
 * <ul>
 *   <li><b>401 Unauthorized</b> - "I do not know who you are." The client's correct response
 *       is to sign in, or refresh the token. This is the status for a missing header, a
 *       malformed one, an expired token, or a token for an account that has since been
 *       deleted or disabled.</li>
 *   <li><b>403 Forbidden</b> - "I know exactly who you are, and you may not do this."
 *       Signing in again changes nothing. A frontend that treats 403 as 401 sends the user
 *       through a login loop that cannot possibly succeed - which is a real bug worth
 *       preventing.</li>
 * </ul>
 *
 * <p>A third case is subtler still and gets its own group below: <b>404 instead of 403 for
 * another customer's resource.</b> The caller is authenticated and genuinely forbidden,
 * so 403 would be defensible - but 403 also confirms the row exists. Answering 404 means a
 * customer cannot probe for the existence of other people's orders, and since they can do
 * nothing with the knowledge either way, 404 costs a legitimate user nothing.
 */
@DisplayName("HTTP - authentication and authorization")
class AuthenticationHttpTest extends HttpIntegrationTestSupport {

    // =================================================================
    //  Public routes
    // =================================================================

    @Nested
    @DisplayName("public routes")
    class PublicRoutes {

        @Test
        @DisplayName("the catalogue is readable without a token")
        void catalogueIsPublic() throws Exception {
            /*
             * The storefront must render for a visitor who has not signed in - that is what
             * makes "browse, then sign up to buy" work. This asserts the whole anonymous
             * read path at once: GET /api/products and GET /api/categories both reach their
             * controllers with no Authorization header.
             */
            mockMvc.perform(get("/api/products")).andExpect(status().isOk());
            mockMvc.perform(get("/api/categories")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("health is public, so a load balancer needs no credentials")
        void healthIsPublic() throws Exception {
            mockMvc.perform(get("/api/health")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("the OpenAPI document is public, so the API is discoverable")
        void apiDocsArePublic() throws Exception {
            /*
             * springdoc's /v3/api-docs is enabled in the test profile (the test yml turns it
             * off, so this asserts the route is permitted even when no document is served -
             * which is the authorization question). A 200 or a 404 both prove the point;
             * what must not happen is a 401.
             */
            int statusCode = perform(get("/v3/api-docs"), null).getResponse().getStatus();

            assertThat(statusCode)
                    .as("the docs route must be permitted, not authenticated")
                    .isNotEqualTo(401);
        }

        @Test
        @DisplayName("a write to the catalogue is NOT public, even though reads are")
        void catalogueWritesAreNotPublic() throws Exception {
            /*
             * The point of splitting GET from POST in SecurityConfig. If the rule had been
             * written as a blanket permitAll on "/api/products/**", this request would reach
             * the controller - and creating a product would be open to the internet.
             *
             * The expected status is 401 rather than 403 because no credential was
             * presented: the chain cannot tell a customer from an admin from nobody, so the
             * honest answer is "I do not know who you are".
             */
            mockMvc.perform(post("/api/products")
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(ErrorCode.UNAUTHORIZED));
        }
    }

    // =================================================================
    //  401 - no usable credential
    // =================================================================

    @Nested
    @DisplayName("401 - no usable credential")
    class Unauthenticated {

        @Test
        @DisplayName("a protected route with no Authorization header is refused")
        void missingHeaderIsRefused() throws Exception {
            mockMvc.perform(get("/api/users/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(ErrorCode.UNAUTHORIZED))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.path").value("/api/users/me"));
        }

        @Test
        @DisplayName("the refusal body is the application's JSON, not Spring Security's HTML")
        void refusalIsJsonNotHtml() throws Exception {
            /*
             * The single most valuable assertion in this class.
             *
             * A filter-chain refusal never reaches @RestControllerAdvice - a filter runs
             * outside the dispatcher. So this JSON can only be produced by ApiErrorWriter,
             * and if that wiring were absent the response would be an empty body or an HTML
             * error page while every service-level test continued to pass.
             *
             * Asserting the content type as well as the shape is deliberate: a client
             * branching on "does this response have a JSON error code?" needs the header to
             * be right before it can parse the body.
             */
            var response = perform(get("/api/users/me"), null).getResponse();

            assertThat(response.getContentType()).contains("application/json");
            assertThat(response.getContentAsString())
                    .contains("\"error\":\"" + ErrorCode.UNAUTHORIZED + "\"")
                    .doesNotContain("<html");
        }

        @Test
        @DisplayName("a malformed token is refused rather than throwing a 500")
        void malformedTokenIsRefused() throws Exception {
            /*
             * The filter's contract: authenticate, never reject. A token it cannot parse
             * must leave the request unauthenticated so the chain answers 401 - not throw,
             * which would produce a 500 from inside the security chain and turn "your token
             * is stale" into "the server is broken".
             */
            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer not-a-real-jwt"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(ErrorCode.UNAUTHORIZED));
        }

        @Test
        @DisplayName("a token signed with the wrong key is refused")
        void tokenWithForeignSignatureIsRefused() throws Exception {
            /*
             * A well-formed JWT that anyone can produce - it verifies structurally and fails
             * only on the signature. This is the forgery case, and if the signature check
             * were accidentally omitted (a plausible refactor: `parseClaims` instead of
             * `parseSignedClaims`), the API would accept tokens minted by anybody.
             *
             * Built by hand rather than through JwtService because the point is a token that
             * JwtService would not mint.
             */
            String foreign = io.jsonwebtoken.Jwts.builder()
                    .subject("1")
                    .claim("email", "attacker@example.com")
                    .claim("role", "ADMIN")
                    .issuer("https://test.ecommerce.local")
                    .issuedAt(new java.util.Date())
                    .expiration(new java.util.Date(System.currentTimeMillis() + 3_600_000))
                    .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                            "a-completely-different-secret-key-long-enough-for-hs256"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            io.jsonwebtoken.Jwts.SIG.HS256)
                    .compact();

            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + foreign))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(ErrorCode.UNAUTHORIZED));
        }

        @Test
        @DisplayName("an Authorization header that is not a bearer token is ignored")
        void nonBearerAuthorizationIsIgnored() throws Exception {
            /*
             * A Basic credential is not a token. The filter's extractBearerToken returns
             * null and the request continues unauthenticated - so this is a 401, not a 500
             * and not an attempt to parse "Basic ..." as a JWT.
             */
            mockMvc.perform(get("/api/users/me").header("Authorization", "Basic YWRtaW46YWRtaW4="))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a token for a deleted account is refused, even though the signature verifies")
        void tokenForDeletedAccountIsRefused() throws Exception {
            /*
             * The gap between "the token is valid" and "the caller is authenticated".
             *
             * The signature checks out - the token was genuinely issued by this application.
             * But the filter reloads the user row and finds nothing, so it installs no
             * authentication and the chain answers 401.
             *
             * This is why the filter reads the database instead of trusting the claims. A
             * filter that authenticated purely from the token would let a deleted account
             * keep using its token until expiry, with no log line and no way to revoke.
             */
            User customer = persistCustomer();
            String token = bearerFor(customer);

            // Sanity: the token works while the account exists.
            mockMvc.perform(get("/api/users/me").header("Authorization", token))
                    .andExpect(status().isOk());

            userRepository.deleteById(customer.getId());
            userRepository.flush();

            mockMvc.perform(get("/api/users/me").header("Authorization", token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(ErrorCode.UNAUTHORIZED));
        }

        @Test
        @DisplayName("a token for a disabled account is refused")
        void tokenForDisabledAccountIsRefused() throws Exception {
            /*
             * The same reload, a different condition: `filter(User::isEnabled)`. Disabling an
             * account is the closest thing this API has to revocation, so it must end the
             * session immediately rather than at token expiry.
             */
            User customer = persistCustomer();
            String token = bearerFor(customer);

            customer.setEnabled(false);
            userRepository.saveAndFlush(customer);

            mockMvc.perform(get("/api/users/me").header("Authorization", token))
                    .andExpect(status().isUnauthorized());
        }
    }

    // =================================================================
    //  403 - authenticated but not permitted
    // =================================================================

    @Nested
    @DisplayName("403 - authenticated but not permitted")
    class Forbidden {

        @Test
        @DisplayName("a customer may not read the dashboard")
        void customerCannotReadDashboard() throws Exception {
            String bearer = bearerFor(persistCustomer());

            mockMvc.perform(get("/api/admin/dashboard/stats").header("Authorization", bearer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ErrorCode.ACCESS_DENIED))
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("a customer may not list every user")
        void customerCannotListUsers() throws Exception {
            String bearer = bearerFor(persistCustomer());

            mockMvc.perform(get("/api/admin/users").header("Authorization", bearer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ErrorCode.ACCESS_DENIED));
        }

        @Test
        @DisplayName("a customer may not list every order")
        void customerCannotListAllOrders() throws Exception {
            /*
             * The route that would leak the most if the URL rule were missing: an unscoped
             * order list is every customer's purchase history, names and addresses in one
             * response. It is protected by the /api/admin/** prefix match rather than by
             * anything in OrderController - which is exactly why it needs a test here.
             */
            String bearer = bearerFor(persistCustomer());

            mockMvc.perform(get("/api/admin/orders").header("Authorization", bearer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ErrorCode.ACCESS_DENIED));
        }

        @Test
        @DisplayName("a customer may not create a product")
        void customerCannotCreateProduct() throws Exception {
            String bearer = bearerFor(persistCustomer());

            mockMvc.perform(post("/api/admin/products")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a customer may not promote themselves")
        void customerCannotPromoteThemselves() throws Exception {
            /*
             * The privilege-escalation attempt, and the reason /api/admin/users/{id}/role
             * exists at all. A customer who could call this would be an admin in one request;
             * the URL prefix and the @PreAuthorize on UserServiceImpl both have to refuse.
             *
             * Note the target id is the customer's own - so the test is not relying on any
             * ownership check to reach the refusal.
             */
            User customer = persistCustomer();
            String bearer = bearerFor(customer);

            mockMvc.perform(patch("/api/admin/users/" + customer.getId() + "/role")
                            .header("Authorization", bearer)
                            .contentType("application/json")
                            .content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ErrorCode.ACCESS_DENIED));

            // And the role is unchanged - the refusal was not merely a status code.
            assertThat(userRepository.findById(customer.getId()).orElseThrow().getRole())
                    .isEqualTo(Role.CUSTOMER);
        }

        @Test
        @DisplayName("an admin CAN reach the same routes, so the rule denies the role and not everyone")
        void adminIsPermitted() throws Exception {
            /*
             * The control case, and it is not optional.
             *
             * Every test above would also pass if the routes were simply broken - a
             * `hasRole("ROLE_ADMIN")` typo (the doubled prefix, so Spring looks for
             * ROLE_ROLE_ADMIN) denies everyone including admins, and six passing 403
             * assertions would report a completely broken API as correct.
             *
             * One positive assertion per group is what distinguishes "the rule works" from
             * "the rule denies everything".
             */
            String bearer = bearerFor(persistAdmin());

            mockMvc.perform(get("/api/admin/dashboard/stats").header("Authorization", bearer))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/admin/users").header("Authorization", bearer))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/admin/orders").header("Authorization", bearer))
                    .andExpect(status().isOk());
        }
    }

    // =================================================================
    //  404 - the existence-hiding answer
    // =================================================================

    @Nested
    @DisplayName("404 rather than 403 for another customer's resource")
    class OwnershipIsNotDisclosed {

        @Test
        @DisplayName("another customer's order is not found, not forbidden")
        void anotherCustomersOrderIsNotFound() throws Exception {
            /*
             * Ada places an order; Grace asks for it by id.
             *
             * Grace is authenticated and genuinely not permitted, so 403 would be defensible.
             * The API answers 404 instead, and the reason is that 403 confirms the row exists:
             * a customer could walk /api/orders/1, /2, /3 and map which ids are real, and
             * how many orders the shop has. With 404 the scan yields nothing.
             *
             * The cost is paid only by a user who guessed a plausible id and did not own it,
             * for whom "not found" and "not yours" are equally unhelpful.
             *
             * The error code matters as much as the status: ORDER_NOT_FOUND rather than
             * ACCESS_DENIED, so a client can distinguish this from a real refusal. Asserting
             * only the status would let a 404 with the wrong body through.
             */
            User ada = persistCustomer();
            User grace = persistCustomer();

            var category = persistCategory("Ownership");
            var product = persistProduct(category, "Owned Item", "100.00", 10);

            long adasOrderId = placeOrderFor(ada, product, 1);

            mockMvc.perform(get("/api/orders/" + adasOrderId)
                            .header("Authorization", bearerFor(grace)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value(ErrorCode.ORDER_NOT_FOUND));

            // And Ada can still read her own order - the scoping hides it from Grace only.
            mockMvc.perform(get("/api/orders/" + adasOrderId)
                            .header("Authorization", bearerFor(ada)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(adasOrderId));
        }

        @Test
        @DisplayName("a cart line in someone else's cart cannot be edited")
        void anotherCustomersCartLineIsNotFound() throws Exception {
            /*
             * The same rule on the cart. The id of a CartItem is a sequential primary key,
             * so from Ada's cart line it is a single increment to Grace's - which makes an
             * unscoped updateItem(id) endpoint a trivial way to edit strangers' carts.
             */
            User ada = persistCustomer();
            User grace = persistCustomer();

            var category = persistCategory("Carts");
            var product = persistProduct(category, "Cart Item", "50.00", 10);

            String adasBearer = bearerFor(ada);
            mockMvc.perform(post("/api/cart/items")
                            .header("Authorization", adasBearer)
                            .contentType("application/json")
                            .content(json(new ProductQuantity(product.getId(), 1))))
                    .andExpect(status().isOk());

            long adasItemId = cartLineId(ada, product.getId());

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/cart/items/" + adasItemId)
                            .header("Authorization", bearerFor(grace))
                            .contentType("application/json")
                            .content("{\"quantity\":5}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value(ErrorCode.CART_ITEM_NOT_FOUND));
        }
    }

    // =================================================================
    //  The login loop the token-issuing endpoints must avoid
    // =================================================================

    @Nested
    @DisplayName("registration and login")
    class IssuingTokens {

        @Test
        @DisplayName("registration returns 201 and a token that works immediately")
        void registrationReturnsAWorkingToken() throws Exception {
            /*
             * Proves the loop closes: a token minted by AuthServiceImpl at sign-up is a token
             * JwtAuthenticationFilter accepts on the *next* request. The two code paths are
             * joined only by the signing key, and testing each against itself (as the unit
             * tests do) cannot catch a mismatch between them.
             *
             * Also asserts the frontend's first requirement after sign-up: the response
             * carries the user, so the navbar can render without a follow-up request.
             */
            var request = new RegisterRequest("Ada Lovelace", uniqueEmail("ada"), TEST_PASSWORD, "+91 9876543210");

            var result = postJson("/api/auth/register", request, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(201);

            String token = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("token").asText();

            assertThat(token).isNotBlank();

            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(request.email()))
                    .andExpect(jsonPath("$.role").value("CUSTOMER"));
        }

        @Test
        @DisplayName("a duplicate email is a 409, not a 500 from the unique constraint")
        void duplicateEmailIsConflict() throws Exception {
            /*
             * The database would reject this row anyway - there is a unique index on email.
             * The question is what the client sees: a 500 with "the server is broken", or a
             * 409 saying the request conflicts with existing data.
             *
             * The service pre-checks with existsByEmailIgnoreCase, so this 409 comes from
             * AuthServiceImpl rather than from the DataIntegrityViolationException handler.
             * Both produce the same status, which is the point of having both.
             */
            String email = uniqueEmail("duplicate");
            var request = new RegisterRequest("Ada Lovelace", email, TEST_PASSWORD, null);

            // First registration succeeds.
            assertThat(postJson("/api/auth/register", request, null).getResponse().getStatus())
                    .isEqualTo(201);

            var second = postJson("/api/auth/register", request, null).getResponse();

            assertThat(second.getStatus()).isEqualTo(409);
            assertThat(second.getContentAsString())
                    .contains(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("an unknown email and a wrong password are indistinguishable")
        void badCredentialsDoNotRevealWhichWasWrong() throws Exception {
            /*
             * Account enumeration: if "no such user" and "wrong password" produced different
             * responses, an attacker could submit a list of email addresses and learn which
             * ones have accounts - the first step of a targeted attack.
             *
             * Both are 401, and crucially both carry the SAME error code and the same
             * message. Asserting only the status would let a differing message through, so
             * this compares the two bodies' error fields directly.
             */
            User customer = persistCustomer();

            var unknownEmail = perform(post("/api/auth/login")
                    .contentType("application/json")
                    .content(json(new LoginRequest("nobody@example.com", TEST_PASSWORD))), null).getResponse();

            var wrongPassword = perform(post("/api/auth/login")
                    .contentType("application/json")
                    .content(json(new LoginRequest(customer.getEmail(), "definitely-not-it"))), null).getResponse();

            assertThat(unknownEmail.getStatus()).isEqualTo(401);
            assertThat(wrongPassword.getStatus()).isEqualTo(401);

            String unknownError = objectMapper.readTree(unknownEmail.getContentAsString()).get("error").asText();
            String wrongError = objectMapper.readTree(wrongPassword.getContentAsString()).get("error").asText();

            assertThat(unknownError)
                    .as("both failures must carry the same code, or the API enumerates accounts")
                    .isEqualTo(wrongError)
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("a disabled account at login is named explicitly, and is still a 401")
        void disabledAccountIsNamedExplicitly() throws Exception {
            /*
             * The deliberate exception to the rule above, pinned so it stays deliberate.
             *
             * Telling the user "this account is disabled" does confirm the account exists -
             * a small disclosure. It is accepted because the alternative is a customer with
             * a correct password receiving a generic "invalid credentials" and no way to
             * understand why, which for a disabled account is a support ticket every time.
             *
             * ------------------------------------------------------------------
             *  THE STATUS IS 401, AND IT WAS 403 UNTIL THIS TEST WAS WRITTEN.
             * ------------------------------------------------------------------
             * This assertion originally expected 403, reading AuthController's
             * @ApiResponse(responseCode = "403", description = "Account disabled") as the
             * contract. The running application returned 401, and the application is right:
             *
             *   UnauthorizedException extends ApiException(HttpStatus.UNAUTHORIZED, ...)
             *
             * so every factory method on it produces a 401 - including accountDisabled().
             * The 403 in the OpenAPI annotation was simply wrong, and nothing had ever
             * checked. That is exactly the class of bug an HTTP-level test exists to find:
             * it is invisible from the service layer, where the exception type is perfectly
             * correct, and it would have been published in the generated docs as a promise
             * the API does not keep.
             *
             * 401 is also the defensible answer on the merits. The request failed at
             * authentication: the caller presented credentials and they were not accepted.
             * 403 asserts "I know who you are and you may not do this", which is not what
             * happened here.
             *
             * The annotation has been corrected to match. The important part of this test is
             * therefore not the status but the ERROR CODE: ACCOUNT_DISABLED is what
             * distinguishes this from every other 401, and it is what lets a frontend show
             * "your account is disabled, contact support" instead of a generic failure.
             */
            User customer = persistCustomer();
            customer.setEnabled(false);
            userRepository.saveAndFlush(customer);

            mockMvc.perform(post("/api/auth/login")
                            .contentType("application/json")
                            .content(json(new LoginRequest(customer.getEmail(), TEST_PASSWORD))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(ErrorCode.ACCOUNT_DISABLED))
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("registration cannot create an administrator, even when asked")
        void registrationCannotCreateAnAdmin() throws Exception {
            /*
             * The classic privilege-escalation attempt: add "role":"ADMIN" to the sign-up
             * body. RegisterRequest has no role component, so there is nothing to bind -
             * and this asserts the outcome rather than the mechanism, which is what makes it
             * survive a future refactor of the DTO.
             *
             * Note the request is likely rejected outright (the test profile configures
             * Jackson to fail on unknown properties), and that is a perfectly good answer:
             * either way, no admin account exists afterwards. The assertion that matters is
             * the last one - that the created account's role is CUSTOMER.
             */
            String email = uniqueEmail("escalate");

            perform(post("/api/auth/register")
                    .contentType("application/json")
                    .content("""
                            {"name":"Attacker","email":"%s","password":"%s","role":"ADMIN"}
                            """.formatted(email, TEST_PASSWORD)), null);

            userRepository.findAll().stream()
                    .filter(u -> u.getEmail().equals(email))
                    .forEach(u -> assertThat(u.getRole())
                            .as("a request body must never be able to choose a role")
                            .isEqualTo(Role.CUSTOMER));

            assertThat(userRepository.findAll())
                    .as("no administrator may exist that a registration created")
                    .noneMatch(u -> u.getRole() == Role.ADMIN);
        }
    }
}
