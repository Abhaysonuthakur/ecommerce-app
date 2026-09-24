package com.shop.ecommerce.exception;

import com.shop.ecommerce.dto.common.ApiErrorResponse;
import com.shop.ecommerce.dto.common.ErrorCode;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns every exception into the same JSON error shape.
 *
 * <h2>Why one handler rather than a {@code try/catch} per controller</h2>
 *
 * <p>Without this class, the error a client receives is whatever the framework happened to
 * produce: a JSON body for one case, an HTML whitelabel page for another, and the default
 * Spring error structure for a third. A frontend cannot branch on that, so it ends up
 * parsing three formats badly.
 *
 * <p>The single most valuable line in this class is the handler for
 * {@link NoResourceFoundException}. Without it, an unmapped URL that gets past the security
 * filter chain throws, and the client receives a <b>500</b> for what is plainly a 404. That
 * is a genuinely confusing bug to receive and a hard one to guess from the response alone.
 *
 * <h2>The rule this class follows</h2>
 *
 * <p><b>Log the detail, return the summary.</b> A stack trace in a response is an
 * information leak - it names classes, packages, library versions and sometimes SQL in the
 * stack's {@code Caused by} chain. So every unexpected failure is logged in full, with a
 * correlation id, and the response carries only that id and a neutral message. A support
 * engineer can then find the trace from the id, and an attacker learns nothing.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // =================================================================
    //  Our own exceptions
    // =================================================================

    /**
     * The base handler for every deliberate failure in the application.
     *
     * <p>{@link ApiException} carries both the status and the error code, so there is
     * exactly one handler method for all seven subclasses. Adding a new exception type is a
     * new subclass and nothing else - no new branch here, and therefore no way for a new
     * exception to accidentally fall through to the 500 handler.
     *
     * <p>Logged at WARN rather than ERROR: these are expected outcomes. A customer trying
     * to buy a sold-out product is not an incident, and logging it at ERROR pollutes
     * alerting until real errors are invisible in the noise.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        log.warn("{} {} -> {} ({}): {}",
                request.getMethod(), request.getRequestURI(),
                ex.getStatus().value(), ex.getErrorCode(), ex.getMessage());

        return ResponseEntity.status(ex.getStatus())
                .body(ApiErrorResponse.of(
                        ex.getStatus().value(),
                        ex.getErrorCode(),
                        ex.getMessage(),
                        request.getRequestURI()));
    }

    // =================================================================
    //  Validation
    // =================================================================

    /**
     * A {@code @Valid} request body failed validation.
     *
     * <p>All violations are collected, not just the first. A form with three bad fields
     * should mark all three at once - returning one error means the user fixes it, submits,
     * and is told about the next one, which is a maddening loop when it was avoidable.
     *
     * <p>Note that {@link ApiErrorResponse.FieldError} deliberately does not carry the
     * rejected value. For most fields that would be helpful, but for {@code password} it
     * would echo the submitted password into the response body - where it lands in browser
     * devtools, in proxy logs, and in any error-tracking tool that captures response
     * bodies. Since one DTO in this application has a password field, the whole response
     * type omits values.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                            HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> fieldErrors = new ArrayList<>();

        /*
         * Field errors from the request body. getFieldError().getDefaultMessage() is the
         * message written in the DTO's annotation - so the wording the developer intended
         * is the wording the client sees, rather than a framework default like
         * "must not be null".
         */
        for (var error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new ApiErrorResponse.FieldError(
                    error.getField(),
                    error.getDefaultMessage()));
        }

        /*
         * Global (object-level) errors - from a class-level constraint rather than a field
         * one. Collected into the same list with a null field name so the client has one
         * array to render instead of two.
         */
        for (var error : ex.getBindingResult().getGlobalErrors()) {
            fieldErrors.add(new ApiErrorResponse.FieldError(
                    null,
                    error.getDefaultMessage()));
        }

        log.warn("{} {} -> 400 validation failed on {} field(s)",
                request.getMethod(), request.getRequestURI(), fieldErrors.size());

        return ResponseEntity.badRequest().body(ApiErrorResponse.validation(
                "Validation failed for one or more fields.",
                request.getRequestURI(),
                fieldErrors));
    }

    /**
     * A constraint on a method parameter (not a body) was violated.
     *
     * <p>Raised when a controller method is annotated with
     * {@code @Validated} and a {@code @Min}/{@code @Max} on a {@code @RequestParam} fails,
     * or by {@code @Valid} on a query-object record bound from parameters. Distinct from
     * {@code MethodArgumentNotValidException}, which is bodies only - and confusingly
     * similar in name, which is exactly why both handlers are written out rather than one
     * being assumed to cover the other.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                     HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> fieldErrors = new ArrayList<>();

        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            /*
             * The property path of a parameter constraint is something like
             * "list.size" or "createProduct.arg0.name". Only the last segment is useful to
             * a client - it is the parameter name it sent.
             */
            String path = violation.getPropertyPath().toString();
            int lastDot = path.lastIndexOf('.');
            String field = lastDot >= 0 ? path.substring(lastDot + 1) : path;

            fieldErrors.add(new ApiErrorResponse.FieldError(field, violation.getMessage()));
        }

        log.warn("{} {} -> 400 constraint violation(s): {}",
                request.getMethod(), request.getRequestURI(), fieldErrors);

        return ResponseEntity.badRequest().body(ApiErrorResponse.validation(
                "Validation failed for one or more parameters.",
                request.getRequestURI(),
                fieldErrors));
    }

    // =================================================================
    //  Malformed requests
    // =================================================================

    /**
     * The body could not be parsed at all: invalid JSON, or a value that cannot be
     * converted - most often {@code "price": "abc"} for a {@code BigDecimal}, or
     * {@code "status": "REFUNDED"} for an enum that has no such constant.
     *
     * <p>400 rather than 500: the client sent something the server cannot read, and no
     * amount of retrying with the same body will help.
     *
     * <h3>Why an unknown enum value gets a different message from malformed JSON</h3>
     *
     * <p>Both arrive as {@link HttpMessageNotReadableException}, so they are handled here
     * together - but they are very different mistakes and the generic "could not be parsed
     * as JSON" is actively misleading for the second. The body <em>is</em> valid JSON; one
     * string simply is not a member of the enum. Jackson wraps that in an
     * {@code InvalidFormatException} whose target type is the enum, which is enough to
     * detect the case and name the valid values instead.
     *
     * <p>That distinction matters because the frontend sends a status from a dropdown, so a
     * mismatch means the client and server disagree about the enum's members - a bug that is
     * invisible under "could not be parsed as JSON" and obvious under "allowed values are
     * PENDING, CONFIRMED, ...".
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                HttpServletRequest request) {
        // Walk the whole cause chain rather than calling getMostSpecificCause(): Jackson
        // signals an unreadable enum in one of two ways, and only one of them keeps the
        // InvalidFormatException as the *most specific* cause. A top-level mismatched value
        // ({"role":"SUPERUSER"}) does. A nested one -
        // ({"items":[{"quantity":"three"}]}) - is wrapped in a MismatchedInputException,
        // which then becomes the most specific cause and hides the InvalidFormatException
        // underneath it. Scanning the chain handles both with one code path.
        InvalidFormatException formatException = findInvalidFormat(ex);

        if (formatException != null
                && formatException.getTargetType() != null
                && formatException.getTargetType().isEnum()) {
            String fieldName = formatException.getPath().isEmpty()
                    ? "value"
                    : formatException.getPath().get(formatException.getPath().size() - 1).getFieldName();

            List<String> allowed = java.util.Arrays.stream(formatException.getTargetType().getEnumConstants())
                    .map(Object::toString)
                    .toList();

            log.warn("{} {} -> 400 invalid enum value for '{}'; allowed: {}",
                    request.getMethod(), request.getRequestURI(), fieldName, allowed);

            return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                    HttpStatus.BAD_REQUEST.value(),
                    ErrorCode.BAD_REQUEST,
                    "Invalid value for '%s'. Allowed values: %s.".formatted(fieldName, String.join(", ", allowed)),
                    request.getRequestURI()));
        }

        log.warn("{} {} -> 400 unreadable body: {}",
                request.getMethod(), request.getRequestURI(), ex.getMostSpecificCause().getMessage());

        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCode.BAD_REQUEST,
                "The request body is missing or could not be parsed as JSON.",
                request.getRequestURI()));
    }

    /**
     * Finds the first {@link InvalidFormatException} anywhere in the cause chain, or
     * {@code null} if there is none.
     *
     * <p>Kept as a separate method because it is a pure, easily-testable traversal - and
     * because the {@code while} guard (<em>cause != cause.getCause()</em>) matters: an
     * exception whose cause is itself would otherwise loop forever.
     */
    private static InvalidFormatException findInvalidFormat(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidFormatException formatException) {
                return formatException;
            }
        }
        return null;
    }

    /**
     * A query parameter could not be converted to its declared type - {@code ?page=abc} for
     * an {@code int}, or an unknown enum constant for {@code ?status=SHIPPED_SOON}.
     *
     * <p>Without this handler it is a 500, because Spring's conversion failure is a
     * {@code MethodArgumentTypeMismatchException}, which is not a {@code SpringException}…
     * it is a {@code RuntimeException} like any other.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                              HttpServletRequest request) {
        String name = ex.getName();
        String required = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "the expected type";

        String message = required.equals("OrderStatus")
                ? "Invalid value for '%s'. Allowed values: %s.".formatted(name, allowedEnumValues())
                : "Invalid value for '%s'. Expected a value of type %s.".formatted(name, required);

        log.warn("{} {} -> 400 type mismatch on '{}': {}",
                request.getMethod(), request.getRequestURI(), name, ex.getMessage());

        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCode.BAD_REQUEST,
                message,
                request.getRequestURI()));
    }

    /** A required query parameter was absent. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex,
                                                                  HttpServletRequest request) {
        log.warn("{} {} -> 400 missing parameter '{}'",
                request.getMethod(), request.getRequestURI(), ex.getParameterName());

        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCode.BAD_REQUEST,
                "Required parameter '%s' is missing.".formatted(ex.getParameterName()),
                request.getRequestURI()));
    }

    // =================================================================
    //  Routing and content negotiation
    // =================================================================

    /**
     * A URL that matches no handler.
     *
     * <h2>Why this handler is required, not optional</h2>
     *
     * <p>Spring Boot 3.2 introduced {@link NoResourceFoundException} for the static-resource
     * path. It is <em>not</em> an {@code ApiException}, so without this method an unmapped
     * URL produces a <b>500</b>. A client that gets a 500 for a typo'd URL will report a
     * server outage; a client that gets a 404 knows what happened.
     *
     * <p>A subtlety worth knowing: an unmapped path is a 404 only for an
     * <em>authenticated</em> caller. An anonymous request to an unmapped path is a
     * <b>401</b>, because {@code anyRequest().authenticated()} is evaluated before routing -
     * the request never reaches the dispatcher to find out whether a handler exists. That
     * is correct and deliberate: a 404 for anonymous traffic would turn the API into a path
     * scanner that reveals which routes exist.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex,
                                                            HttpServletRequest request) {
        log.warn("{} {} -> 404 no handler", request.getMethod(), request.getRequestURI());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                ErrorCode.RESOURCE_NOT_FOUND,
                "No endpoint exists for %s %s.".formatted(request.getMethod(), request.getRequestURI()),
                request.getRequestURI()));
    }

    /** Wrong HTTP method for an existing path - e.g. {@code DELETE} on a GET-only route. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                    HttpServletRequest request) {
        log.warn("{} {} -> 405 method not supported",
                request.getMethod(), request.getRequestURI());

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                ErrorCode.METHOD_NOT_ALLOWED,
                "%s is not supported for this endpoint.".formatted(request.getMethod()),
                request.getRequestURI()));
    }

    /** A body was sent with a Content-Type the endpoint does not consume. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                                      HttpServletRequest request) {
        log.warn("{} {} -> 415 unsupported media type '{}'",
                request.getMethod(), request.getRequestURI(), ex.getContentType());

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiErrorResponse.of(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type must be application/json.",
                request.getRequestURI()));
    }

    // =================================================================
    //  Security - the safety net behind the filter chain
    // =================================================================

    /**
     * {@code @PreAuthorize} refused the call.
     *
     * <p>Most role checks are refused by the filter chain and never reach a controller, so
     * this handler is the path taken by <em>method-level</em> authorization only. Both must
     * exist: the filter chain protects URL patterns, {@code @PreAuthorize} protects methods,
     * and a method check that fires needs a JSON body just as much as a URL check does.
     *
     * <p>403, not 401. The caller is authenticated - they simply may not do this, and
     * signing in again will not change that.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                              HttpServletRequest request) {
        log.warn("{} {} -> 403 access denied", request.getMethod(), request.getRequestURI());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                ErrorCode.ACCESS_DENIED,
                "You do not have permission to access this resource.",
                request.getRequestURI()));
    }

    /**
     * Authentication failed.
     *
     * <p>Largely unreachable: the JWT filter deliberately does not reject a request, and
     * form/login are disabled, so authentication failures are handled by
     * {@code ApiErrorWriter} inside the filter chain. This handler exists so that if some
     * future mechanism does throw one, it produces the same JSON shape rather than falling
     * through to a framework default.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex,
                                                                HttpServletRequest request) {
        log.warn("{} {} -> 401 authentication failed: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                ErrorCode.UNAUTHORIZED,
                "Authentication is required to access this resource.",
                request.getRequestURI()));
    }

    // =================================================================
    //  Database
    // =================================================================

    /**
     * A database constraint was violated that no service-level check anticipated.
     *
     * <p>Every constraint in this schema has a pre-check: a duplicate email is caught by
     * {@code existsByEmailIgnoreCase}, a deleted-in-use product by {@code isProductOrdered},
     * a negative stock by Bean Validation. This handler is therefore the <em>safety net</em>
     * for the case where a pre-check was right at the time and the state changed before the
     * write - the unavoidable race between a check and an insert.
     *
     * <p>The message returned is deliberately generic. A
     * {@code DataIntegrityViolationException} message contains the constraint name -
     * {@code uk_users_email} - and often the offending value from the SQL. Both describe
     * the schema to a caller who should not have it, and the value could be a password
     * hash's email column. The developer-facing detail is logged; the client gets a 409
     * saying the request conflicts with existing data, which is both true and actionable.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                               HttpServletRequest request) {
        /*
         * The exception's own message is logged, not returned. This is the one place a
         * constraint name appears, and it appears in the log where the developer who wrote
         * the check can find it.
         */
        log.error("{} {} -> 409 data integrity violation: {}",
                request.getMethod(), request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                ErrorCode.DATA_INTEGRITY_VIOLATION,
                "The request conflicts with existing data. It may have been modified by another request.",
                request.getRequestURI()));
    }

    /** Two requests modified the same row; the caller should re-read and retry. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                                HttpServletRequest request) {
        log.warn("{} {} -> 409 optimistic lock conflict",
                request.getMethod(), request.getRequestURI());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                ErrorCode.CONCURRENT_MODIFICATION,
                "This item was modified by another request. Please refresh and try again.",
                request.getRequestURI()));
    }

    // =================================================================
    //  The catch-all
    // =================================================================

    /**
     * Anything not handled above.
     *
     * <p><b>This is the only place a stack trace is significant, and the only place it is
     * logged at ERROR.</b> Everything else in this class is an expected outcome.
     *
     * <p>A correlation id is generated and both logged and returned. Without it, a user
     * reporting "it just said something went wrong" gives a developer nothing to search
     * for; with it, the log line and the report are one grep apart. The id is a random
     * UUID rather than a timestamp or a sequence, so it leaks nothing about traffic volume
     * and cannot be guessed to read somebody else's entry.
     *
     * <p>The response says "an unexpected error occurred" and nothing more. Exception
     * messages routinely contain file paths, class names, and fragments of SQL.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = UUID.randomUUID().toString();

        log.error("Unhandled exception [{}] on {} {}",
                correlationId, request.getMethod(), request.getRequestURI(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Reference: " + correlationId,
                request.getRequestURI()));
    }

    // =================================================================
    //  Helpers
    // =================================================================

    /** Every {@code OrderStatus} name, for a type-mismatch message on {@code ?status=}. */
    private static String allowedEnumValues() {
        StringBuilder sb = new StringBuilder();
        for (com.shop.ecommerce.entity.OrderStatus status : com.shop.ecommerce.entity.OrderStatus.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(status.name());
        }
        return sb.toString();
    }
}
