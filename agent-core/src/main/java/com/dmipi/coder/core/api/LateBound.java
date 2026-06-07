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
import com.dmipi.coder.core.plugin.Llms;
import com.dmipi.coder.core.plugin.Tools;
import java.util.List;
import java.util.Optional;

/**
 * The LLM and Tools capabilities handed to plugins at install, before the model and tool
 * registries can exist (providers and tools are what installation collects). Plugins only
 * *hold* capabilities at install and call them later, so the views bind after assembly; a call
 * before binding is an assembly bug and fails loudly.
 */
final class LateBound {

    private volatile ModelRegistry models;
    private volatile ToolRegistry toolRegistry;
    private volatile ToolGate gate;
    private volatile ToolParamsParser paramsParser;

    void bind(final ModelRegistry models, final ToolRegistry toolRegistry, final ToolGate gate, final ToolParamsParser paramsParser) {
        this.models = models;
        this.toolRegistry = toolRegistry;
        this.gate = gate;
        this.paramsParser = paramsParser;
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
