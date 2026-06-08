package com.dmipi.coder.core.domain.tool;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import java.util.Optional;

/** A model-facing action contributed by a plugin: schema, validation, permission baseline, execution. */
public interface Tool {

    String name();

    String description();

    ToolKind kind();

    /** The kind of this specific call, for a tool whose actions differ in effect; defaults to {@link #kind()}. */
    default ToolKind kind(final ToolParams params) {
        return kind();
    }

    ParameterSchema parameterSchema();

    /** Cheap synchronous validation; empty means OK, else the error the model can correct from. */
    Optional<String> validate(ToolParams params);

    /** The tool's own permission baseline for this call; the gate composes it with policies and the mode. */
    PermissionDecision defaultPermission(ToolParams params);

    /** The verbatim preview a permission question shows — a diff, a command line; empty when there is nothing to show. */
    default String preview(final ToolParams params) {
        return "";
    }

    /** One line of what this call targets (a path, a pattern, a command…) for activity display. */
    default String callSummary(final ToolParams params) {
        return "";
    }

    ToolResult execute(ToolParams params, CancelToken cancel);
}
