package com.shop.ecommerce.exception;

import com.shop.ecommerce.dto.common.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Base class for every deliberate failure in this application.
 *
 * <h2>Why a hierarchy rather than throwing {@code ResponseStatusException}</h2>
 *
 * <p>Spring's {@code ResponseStatusException} is convenient and it is the wrong tool for an
 * API with a documented error contract. It carries a status and a message but no
 * <b>machine-readable code</b>, so the only thing a client can branch on is the message
 * text - which is the part most likely to change.
 *
 * <p>Every subclass here carries three things: an HTTP status, a stable {@code ErrorCode},
 * and a message for humans. That combination is exactly what
 * {@code ApiErrorResponse} needs, so the global handler becomes a one-line translation
 * rather than a chain of {@code instanceof} checks deciding what each exception means.
 *
 * <h2>Why the code and status live on the exception</h2>
 *
 * <p>The alternative is a big {@code switch} in the handler mapping exception types to
 * codes. That puts knowledge of every failure mode in one method far from the code that
 * raises it, so the two drift: somebody adds a new exception type and forgets the switch,
 * and it silently becomes a 500.
 *
 * <p>Keeping the mapping on the exception means adding a failure mode is a local change,
 * and the compiler ensures a new subclass declares its own status and code.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    /**
     * @param status    the HTTP status to return
     * @param errorCode the stable machine-readable code, from {@link ErrorCode}
     * @param message   a human-readable explanation. <b>Never include request values</b> -
     *                  a message containing the submitted password reaches the response
     *                  body, which is exactly what the validator's no-echo rule prevents.
     */
    protected ApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    /**
     * Constructor for wrapping a cause.
     *
     * <p>Used when a lower-level failure needs translating, e.g. a database error becoming
     * a 409. The original is kept as the cause so the stack trace in the log still points at
     * the real problem, while the client sees only the translated message.
     */
    protected ApiException(HttpStatus status, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
