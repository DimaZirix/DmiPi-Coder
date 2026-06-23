package com.dmipi.coder.core.domain.llm;

/** Whether a model is asked for schema-constrained output when a caller wants it. */
public enum StructuredOutput {

    /** Send a json-schema response_format when asked, falling back to text parsing if the server rejects it. */
    AUTO,

    /** Never send response_format; callers always parse free text. */
    OFF
}
