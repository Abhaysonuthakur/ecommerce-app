package com.shop.ecommerce.security.config;

import com.shop.ecommerce.dto.common.ApiErrorResponse;
import com.shop.ecommerce.dto.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes the application's standard error JSON from inside the servlet filter chain.
 *
 * <h2>Why this class has to exist</h2>
 *
 * <p>{@code @RestControllerAdvice} handles exceptions thrown by controllers. A servlet
 * filter runs <b>outside</b> the dispatcher, so nothing the security chain throws or decides
 * is shaped by that advice. Without this writer, a refused request would return an empty
 * body or Spring Security's default HTML - and the client would be parsing two different
 * error formats depending on where the request was rejected, which is precisely the thing
 * the single-error-contract design exists to prevent.
 *
 * <p>Sharing one writer between the {@code AuthenticationEntryPoint}, the
 * {@code AccessDeniedHandler} and any custom filter means a 401 from a missing token, a 401
 * from an expired token and a 403 from a role check are byte-identical in structure to a 400
 * from a controller.
 *
 * <h2>Why it writes rather than calling {@code sendError}</h2>
 *
 * <p>{@code HttpServletResponse.sendError(status)} is the obvious way to reject a request,
 * and it discards anything written to the body first - it triggers the container's error
 * page instead. So the carefully built JSON disappears and the client gets Tomcat's HTML.
 * Writing directly to the output stream is what makes the body survive.
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Writes an {@link ApiErrorResponse} and commits the response.
     *
     * <p>Note {@code StandardCharsets.UTF_8} being set <em>before</em> the writer is
     * obtained. Without it the container picks a charset - on this machine the JVM's default
     * is {@code Cp1252} - and any non-ASCII character in the message is mangled or throws.
     * Setting the encoding first is the only reliable order.
     */
    public void write(HttpServletRequest request,
                      HttpServletResponse response,
                      HttpStatus status,
                      String errorCode,
                      String message) throws IOException {

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ApiErrorResponse body = ApiErrorResponse.of(
                status.value(),
                errorCode,
                message,
                request.getRequestURI()
        );

        // writeValue (not writeValueAsString + write) so Jackson streams straight to the
        // response rather than building the whole string first.
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    /**
     * The message for a request with no credentials at all.
     *
     * <p>A constant rather than a literal at each call site, so the entry point and any
     * filter that rejects an unauthenticated request produce the same wording.
     */
    public static final String MSG_AUTHENTICATION_REQUIRED =
            "Authentication is required to access this resource.";

    /**
     * The message for an authenticated request that is not permitted.
     *
     * <p>Deliberately does not say <em>what</em> was required. "You need ROLE_ADMIN" tells a
     * probing client exactly which role to try to obtain, and no legitimate user needs that
     * information to act.
     */
    public static final String MSG_ACCESS_DENIED =
            "Your account is not permitted to access this resource.";

    /** Default code for a missing or unusable credential. */
    public static final String CODE_UNAUTHORIZED = ErrorCode.UNAUTHORIZED;

    /** Default code for a refusal. */
    public static final String CODE_ACCESS_DENIED = ErrorCode.ACCESS_DENIED;
}
