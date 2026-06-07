package com.dmipi.coder.core.domain.tool;

import java.util.Objects;

/** A tool's parameter schema as raw JSON — passed through to the model, never interpreted by the core. */
public record ParameterSchema(String json) {

    public ParameterSchema {
        Objects.requireNonNull(json, "json");
    }
}
