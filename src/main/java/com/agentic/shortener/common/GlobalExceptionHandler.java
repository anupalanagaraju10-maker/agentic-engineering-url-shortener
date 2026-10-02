package com.agentic.shortener.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as RFC 9457 Problem Details with an added {@code category} and never exposes
 * stack traces or internal exception messages (FR-URL-015, NFR-001).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("category", ex.getCategory().name());
        return problem;
    }

    /**
     * Storage unavailable ⇒ 503, never 404/410 (FR-URL-013). Only resource failures are mapped; integrity and
     * concurrency errors remain internal errors.
     */
    @ExceptionHandler({ DataAccessResourceFailureException.class, TransientDataAccessResourceException.class,
            NonTransientDataAccessResourceException.class, CannotCreateTransactionException.class })
    public ProblemDetail handleStorageUnavailable(Exception ex) {
        log.error("Storage unavailable: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Storage is unavailable");
        problem.setProperty("category", ErrorCategory.STORAGE_UNAVAILABLE.name());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
        problem.setProperty("category", ErrorCategory.INTERNAL.name());
        return problem;
    }

    /** Adds the category to Spring MVC's own Problem Details (validation, 404, 405, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("category", categoryFor(statusCode).name());
        }
        return response;
    }

    static ErrorCategory categoryFor(HttpStatusCode status) {
        int code = status.value();
        if (code == 404) {
            return ErrorCategory.NOT_FOUND;
        }
        if (code == 409) {
            return ErrorCategory.CONFLICT;
        }
        if (code == 503) {
            return ErrorCategory.STORAGE_UNAVAILABLE;
        }
        if (status.is4xxClientError()) {
            return ErrorCategory.VALIDATION;
        }
        return ErrorCategory.INTERNAL;
    }
}
