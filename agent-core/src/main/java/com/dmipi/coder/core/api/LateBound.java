package com.dmipi.coder.core.api;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelRegistry;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.llm.ToolSchema;
import com.dmipi.coder.core.domain.permissions.GateDecision;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolGate;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolParamsParser;
import com.dmipi.coder.core.domain.tool.ToolRegistry;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.domain.shell.ShellResult;
import com.dmipi.coder.core.plugin.Llms;
import com.dmipi.coder.core.plugin.Shell;
import com.dmipi.coder.core.plugin.Tools;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The LLM and Tools capabilities handed to plugins at install, before the model and tool
 * registries can exist (providers and tools are what installation collects). Plugins only
 * *hold* capabilities at install and call them later, so the views bind after assembly; a call
 * before binding is an assembly bug and fails loudly.
 *
 * <p>Thread-safety contract: {@code bind} runs once, on the building thread, before {@code
 * build()} returns — the individual fields are volatile for publication, not for concurrent
 * rebinding, so capability use must start only after construction completes.
 */
final class LateBound {

    private volatile ModelRegistry models;
    private volatile ToolRegistry toolRegistry;
    private volatile ToolGate gate;
    private volatile ToolParamsParser paramsParser;
    private volatile Shell shell;

    void bind(final ModelRegistry models, final ToolRegistry toolRegistry, final ToolGate gate, final ToolParamsParser paramsParser, final Shell shell) {
        this.models = models;
        this.toolRegistry = toolRegistry;
        this.gate = gate;
        this.paramsParser = paramsParser;
        this.shell = shell;
    }

    Shell shell() {
        return new Shell() {

            @Override
            public ShellResult run(final String command, final CancelToken cancel) {
                return boundShell().run(command, cancel);
            }

            @Override
            public ShellResult run(final String command, final Duration timeout, final CancelToken cancel) {
                return boundShell().run(command, timeout, cancel);
            }

            @Override
            public String runInBackground(final String command) {
                return boundShell().runInBackground(command);
            }
        };
    }

    private Shell boundShell() {
        if (shell == null) {
            throw new IllegalStateException("The Shell capability is not usable during install — hold it and call it later.");
        }
        return shell;
    }

    Llms llms() {
        return new Llms() {

            @Override
            public LlmClient active() {
                return bound().active().client();
            }

            @Override
            public LlmClient fastest() {
                return bound().fastest().client();
            }

            @Override
            public LlmClient strongest() {
                return bound().strongest().client();
            }

            @Override
            public LlmClient atLeast(final Tier bar) {
                return bound().atLeast(bar).client();
            }
        };
    }

    Tools tools() {
        return new Tools() {

            @Override
            public List<ToolSchema> available() {
                return boundTools().schemas();
            }

            @Override
            public ToolResult invoke(final String toolName, final String argumentsJson, final CancelToken cancel) {
                final Optional<Tool> tool = boundTools().named(toolName);
                if (tool.isEmpty()) {
                    return new ToolResult.Failure("Unknown tool '" + toolName + "'. It is not in the catalog — handle its absence.");
                }

                final ToolParams params;
                try {
                    params = paramsParser.parse(argumentsJson);
                } catch (final IllegalArgumentException invalid) {
                    return new ToolResult.Failure(invalid.getMessage());
                }

                final Optional<String> invalid = tool.orElseThrow().validate(params);
                if (invalid.isPresent()) {
                    return new ToolResult.Failure(invalid.orElseThrow());
                }
                if (gate.decide(tool.orElseThrow(), params) instanceof GateDecision.Denied(final String reason)) {
                    return new ToolResult.Failure("Permission denied: " + reason);
                }
                return tool.orElseThrow().execute(params, cancel);
            }
        };
    }

    private ModelRegistry bound() {
        if (models == null) {
            throw new IllegalStateException("The LLM capability is not usable during install — hold it and call it later.");
        }
        return models;
    }

    private ToolRegistry boundTools() {
        if (toolRegistry == null) {
            throw new IllegalStateException("The Tools capability is not usable during install — hold it and call it later.");
        }
        return toolRegistry;
    }
}
