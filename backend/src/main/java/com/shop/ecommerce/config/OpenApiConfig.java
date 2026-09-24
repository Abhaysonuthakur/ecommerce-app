package com.shop.ecommerce.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger / OpenAPI configuration.
 *
 * <h2>Why the bearer scheme has to be declared, not inferred</h2>
 *
 * <p>springdoc scans controllers and can see that a route is protected - but it has no way
 * to know <em>how</em>. JWT authentication here happens in a servlet filter, which is
 * invisible to the annotation scan. Without the declaration below, Swagger UI renders every
 * protected endpoint with no way to supply a token, so "Try it out" returns 401 forever and
 * the documentation is unusable for exactly the endpoints that most need testing.
 *
 * <p>Two pieces are required, and both are easy to forget:
 * <ol>
 *   <li>A {@link SecurityScheme} of type {@code HTTP} with scheme {@code bearer} and
 *       format {@code JWT} - this is what makes the Authorize button appear.</li>
 *   <li>A global {@link SecurityRequirement} referencing it by name - this is what makes
 *       Swagger UI <em>attach</em> the token to every request rather than merely store it.</li>
 * </ol>
 *
 * <p>The name in the {@link SecurityRequirement} must match the key in
 * {@code components.securitySchemes} exactly. A mismatch is silent: the Authorize button
 * appears, accepts a token, and no request ever carries it.
 *
 * <h2>Public endpoints and the Authorize button</h2>
 *
 * <p>Marking a route public is done on the operation with
 * {@code @SecurityRequirements} (an empty one), not here. A global requirement plus a
 * per-operation opt-out is the only combination springdoc honours - setting the global
 * requirement to empty and adding it per-operation is the other way to do it, but it means
 * annotating every one of the twenty-odd protected routes instead of the three public ones.
 */
@Configuration
public class OpenApiConfig {

    /**
     * The name that links the scheme to the global requirement.
     *
     * <p>A constant rather than a literal in two places, because the two must match and the
     * failure mode of a mismatch is invisible - see the class javadoc.
     */
    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    /**
     * The base URL Swagger UI sends "Try it out" requests to.
     *
     * <p>Injected as a property so it can be overridden per environment, but its default is
     * written as <em>the application's own port</em> rather than a hardcoded 8080. The port
     * is set once in {@code application.yml} ({@code server.port}) and is itself
     * overridable by {@code SERVER_PORT}, so deriving this from it removes a second place
     * that has to be kept in step. Getting it wrong is a silent failure: the docs render,
     * the Authorize button accepts a token, and every "Try it out" call hits a dead port.
     */
    @Value("${app.openapi.server-url:http://localhost:${server.port:8080}}")
    private String serverUrl;

    @Bean
    public OpenAPI ecommerceOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(List.of(new Server()
                        .url(serverUrl)
                        .description("This application")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, bearerScheme()))
                /*
                 * Applied globally, so every operation inherits it. Public routes opt out
                 * individually with an empty @SecurityRequirements - see the class javadoc
                 * for why that direction is the cheaper one.
                 */
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }

    private Info apiInfo() {
        return new Info()
                .title("E-Commerce API")
                .version("1.0.0")
                .description("""
                        A modular-monolith e-commerce backend: product catalogue, cart, transactional order
                        placement, JWT authentication and Google sign-in.

                        ## Getting a token

                        1. `POST /api/auth/register` with an email and password (minimum 8 characters, maximum
                           72 **bytes** - BCrypt silently ignores anything past byte 72, so the limit is enforced
                           as bytes rather than characters).
                        2. Or `POST /api/auth/login` with an existing account.
                        3. Copy the `token` from the response, click **Authorize** above, paste it, and every
                           subsequent request will carry `Authorization: Bearer <token>`.

                        The token is returned as `tokenType: "Bearer"`; paste **only the token value**, not the
                        word "Bearer" - Swagger adds the prefix itself.

                        ## Roles

                        Registration and Google sign-in both create **CUSTOMER** accounts. `ADMIN` can only be
                        granted by an existing admin via `PATCH /api/admin/users/{id}/role`. A customer who calls
                        an admin route gets **403**, not 404.

                        ## Authorisation is not the frontend's job

                        Every admin route is protected by the filter chain and, independently, by a method-level
                        check. The frontend hides admin buttons, but hiding a button is presentation - the server
                        refuses the request regardless.

                        ## Two conventions worth knowing

                        - **Money** is `BigDecimal` over `DECIMAL(19,2)`. A price with more than two decimal
                          places is rejected with a 400, because MySQL would otherwise round it silently.
                        - **Ownership** is never a request parameter. "Whose cart is this?" is answered from the
                          JWT, so there is no parameter a caller could tamper with. Asking for another
                          customer's order returns **404**, not 403, so order ids cannot be enumerated.
                        """)
                .contact(new Contact()
                        .name("API support")
                        .email("support@example.com"))
                .license(new License()
                        .name("MIT"));
    }

    /**
     * The HTTP bearer scheme.
     *
     * <p>{@code type(HTTP)} with {@code scheme("bearer")} is the standard way to describe a
     * JWT in OpenAPI 3 - the alternative, {@code type(APIKEY)} with
     * {@code in(HEADER)}, describes the same header but tells tooling it is an API key
     * rather than a token with a defined lifetime, and generated clients then produce a
     * method signature that does not distinguish the two.
     *
     * <p>{@code bearerFormat("JWT")} is documentation only - it changes nothing about how a
     * request is sent - but it is what tells a reader of the spec that this is a JWT rather
     * than an opaque token, which matters because the difference determines whether they can
     * decode it to inspect the expiry.
     */
    private SecurityScheme bearerScheme() {
        return new SecurityScheme()
                .name(BEARER_SCHEME_NAME)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("""
                        Paste the raw JWT from `POST /api/auth/login` or `POST /api/auth/register`.
                        Do not include the word "Bearer" - Swagger prepends it.
                        """);
    }
}
