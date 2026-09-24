package com.shop.ecommerce.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health and capability endpoints.
 *
 * <h2>Why this exists rather than only {@code /actuator/health}</h2>
 *
 * <p>Actuator answers "is the process alive", which is a question for a load balancer. It
 * cannot answer the question a <em>frontend</em> has: "should I render a Sign in with Google
 * button?" That depends on whether the backend was started with Google credentials, and the
 * answer changes the UI.
 *
 * <p>Without this endpoint the frontend has two bad options: always show the button and let
 * it fail for developers without Google credentials, or hardcode the answer at build time -
 * which means the same bundle cannot be promoted between environments.
 *
 * <p>Disclosing whether Google sign-in is enabled is not a leak. It is not a secret, an
 * anonymous visitor could discover it by following the authorization route and seeing a 404,
 * and the alternative - the frontend guessing wrong - is a visible bug.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Health", description = "Liveness and capability discovery")
public class HealthController {

    /**
     * Optional. Empty when Google sign-in is not configured - which the application treats
     * as "the feature is off" rather than as a configuration error.
     */
    private final String googleClientId;

    public HealthController(
            @Value("${spring.security.oauth2.client.registration.google.client-id:}") String googleClientId) {
        this.googleClientId = googleClientId;
    }

    /**
     * Liveness plus the one capability the frontend needs.
     *
     * <p>Deliberately does <b>not</b> touch the database. A health check that queries the
     * database turns a slow database into a failing health check, and an orchestrator that
     * restarts the application in response makes the slow database slower. Readiness and
     * liveness are different questions; {@code /actuator/health} handles the deeper one.
     */
    @GetMapping("/health")
    @SecurityRequirements
    @Operation(summary = "Liveness and features",
            description = "Public. Tells a frontend whether to render the Google sign-in button.")
    @ApiResponse(responseCode = "200", description = "The application is up")
    public ResponseEntity<HealthResponse> health() {
        boolean googleEnabled = googleClientId != null && !googleClientId.isBlank();
        return ResponseEntity.ok(new HealthResponse("UP", googleEnabled));
    }

    /**
     * @param status        always {@code "UP"} - the endpoint would not be reached otherwise
     * @param googleEnabled whether {@code /oauth2/authorization/google} is registered. When
     *                      false, the frontend must hide the Google button; the route does
     *                      not exist and following it would 404 rather than start a flow.
     */
    @Schema(description = "Application status and enabled features")
    public record HealthResponse(
            @Schema(example = "UP") String status,
            @Schema(example = "false", description = "Whether Google sign-in is configured on this server")
            boolean googleEnabled
    ) {
    }
}
