package com.agentic.shortener.workflow.rules;

/** URL-shortener capabilities the workflow understands (research R5/R6). */
public enum Capability {
    CREATE_LINK,
    REDIRECT,
    ANALYTICS,
    IDEMPOTENCY,
    EXPIRATION
}
