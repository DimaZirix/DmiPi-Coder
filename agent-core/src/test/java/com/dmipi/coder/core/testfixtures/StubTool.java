package com.dmipi.coder.core.testfixtures;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import java.util.Optional;
import java.util.function.Function;

/** A configurable tool for tests. */
public final class StubTool implements Tool {

    private final String name;
    private final ToolKind kind;
    private final PermissionDecision baseline;
    private final Function<ToolParams, ToolResult> execution;

    public StubTool(final String name, final ToolKind kind, final PermissionDecision baseline, final Function<ToolParams, ToolResult> execution) {
        this.name = name;
        this.kind = kind;
        this.baseline = baseline;
        this.execution = execution;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "Stub tool " + name + ".";
    }

    @Override
    public ToolKind kind() {
        return kind;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema("{\"type\": \"object\"}");
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return baseline;
    }

    @Override
    public String preview(final ToolParams params) {
        return "stub preview";
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        return execution.apply(params);
    }
}
