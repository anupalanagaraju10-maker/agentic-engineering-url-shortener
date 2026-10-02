package com.agentic.shortener.workflow.engine;

/** Thrown at a fault point; the engine turns it into a failed attempt labelled as injected. */
public class InjectedFault extends RuntimeException {

    private final FailureClass failureClass;
    private final String code;

    public InjectedFault(FailureClass failureClass, String code) {
        super("injected fault " + code);
        this.failureClass = failureClass;
        this.code = code;
    }

    public FailureClass failureClass() {
        return failureClass;
    }

    public String code() {
        return code;
    }
}
