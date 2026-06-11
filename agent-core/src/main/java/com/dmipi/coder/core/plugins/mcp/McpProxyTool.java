package com.dmipi.coder.core.plugins.mcp;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import java.util.Optional;

/**
 * One remote MCP tool, proxied as {@code mcp__<server>__<tool>} with the schema the server
 * advertised. A remote tool can do anything on its side, so it asks by default and counts as
 * EXECUTE (plan mode blocks it) — unless the server marked it read-only, which softens it to a
 * NETWORK read.
 */
final class McpProxyTool implements Tool {

    private final McpClient client;
    private final String serverName;
    private final McpRemoteTool remote;

    McpProxyTool(final McpClient client, final String serverName, final McpRemoteTool remote) {
        this.client = client;
        this.serverName = serverName;
        this.remote = remote;
    }

    @Override
    public String name() {
        return "mcp__" + serverName + "__" + remote.name();
    }

    @Override
    public String description() {
        return remote.description().isBlank()
                ? "Tool '" + remote.name() + "' on the MCP server '" + serverName + "'."
                : remote.description();
    }

    @Override
    public ToolKind kind() {
        return remote.readOnly() ? ToolKind.NETWORK : ToolKind.EXECUTE;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(remote.inputSchemaJson());
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return remote.readOnly() ? PermissionDecision.ALLOW : PermissionDecision.ASK;
    }

    @Override
    public String preview(final ToolParams params) {
        return params.rawJson();
    }

    @Override
    public String callSummary(final ToolParams params) {
        return remote.name() + " @ " + serverName;
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final McpClient.CallResult result;
        try {
            result = client.callTool(remote.name(), params.rawJson());
        } catch (final RuntimeException failure) {
            return new ToolResult.Failure("The MCP call failed: " + failure.getMessage());
        }
        if (result.error()) {
            return new ToolResult.Failure(result.text().isBlank() ? "The MCP server reported an error." : result.text());
        }
        return new ToolResult.Success(result.text(), new Display.Text(callSummary(params)));
    }
}
