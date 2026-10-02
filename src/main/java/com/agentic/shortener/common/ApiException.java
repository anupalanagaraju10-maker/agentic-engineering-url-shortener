package com.agentic.shortener.common;

import org.springframework.http.HttpStatus;

/** An expected, client-visible failure rendered as Problem Details with a category. */
public class ApiException extends RuntimeException {

    private final ErrorCategory category;
    private final HttpStatus status;

    public ApiException(ErrorCategory category, HttpStatus status, String detail) {
        super(detail);
        this.category = category;
        this.status = status;
    }

    public ErrorCategory getCategory() {
        return category;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
