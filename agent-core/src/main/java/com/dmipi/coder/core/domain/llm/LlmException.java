package com.dmipi.coder.core.domain.llm;

/** A model call failed at the transport or protocol level. */
public class LlmException extends RuntimeException {

    public LlmException(final String message) {
        super(message);
    }

    public LlmException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
