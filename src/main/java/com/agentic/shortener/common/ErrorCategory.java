package com.agentic.shortener.common;

/** Error categories carried by every Problem Details response (contracts/openapi.yaml). */
public enum ErrorCategory {
    VALIDATION,
    NOT_FOUND,
    EXPIRED,
    CONFLICT,
    INVALID_STATE,
    STALE_PLAN_VERSION,
    EVIDENCE_SCOPE_MISMATCH,
    CHANGE_CONTROL_REQUIRED,
    FAULT_INJECTION_DISABLED,
    REPLAN_FAILED,
    STORAGE_UNAVAILABLE,
    CODE_SPACE_EXHAUSTED,
    INTERNAL
}
