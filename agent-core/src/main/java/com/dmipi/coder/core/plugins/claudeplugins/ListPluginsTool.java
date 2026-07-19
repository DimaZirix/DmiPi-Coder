package com.dmipi.coder.core.plugins.claudeplugins;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.plugin.FileSystem;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/** Reads the per-anchor plugin manifests and reports what is installed where. */
final class ListPluginsTool implements Tool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {}
            }""";

    private final FileSystem projectFiles;
    private final FileSystem userFiles;

    ListPluginsTool(final FileSystem projectFiles, final FileSystem userFiles) {
        this.projectFiles = projectFiles;
        this.userFiles = userFiles;
    }

    @Override
    public String name() {
        return "list_plugins";
    }

    @Override
    public String description() {
        return "Lists the plugins installed by install_plugin — per scope (user and project), each with its source and the skills and MCP servers it brought.";
    }

    @Override
    public ToolKind kind() {
        return ToolKind.READ;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(SCHEMA);
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ALLOW;
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        try {
            final List<InstalledPlugin> user = InstalledPluginsRegistry.all(userFiles);
            final List<InstalledPlugin> project = InstalledPluginsRegistry.all(projectFiles);
            if (user.isEmpty() && project.isEmpty()) {
                return new ToolResult.Success("No plugins are installed.", new Display.Text("no plugins installed"));
            }
            final StringJoiner listing = new StringJoiner("\n");
            section(listing, InstallScope.USER, user);
            section(listing, InstallScope.PROJECT, project);
            final int count = user.size() + project.size();
            return new ToolResult.Success(listing.toString(), new Display.Text(count + " plugin" + (count == 1 ? "" : "s") + " installed"));
        } catch (final InstallFailure failure) {
            return new ToolResult.Failure(failure.getMessage());
        }
    }

    private static void section(final StringJoiner listing, final InstallScope scope, final List<InstalledPlugin> plugins) {
        if (plugins.isEmpty()) {
            return;
        }
        listing.add(scope.label() + " scope:");
        plugins.forEach(plugin -> listing.add(line(plugin)));
    }

    private static String line(final InstalledPlugin plugin) {
        final StringJoiner content = new StringJoiner("; ");
        if (!plugin.skills().isEmpty()) {
            content.add("skills: " + String.join(", ", plugin.skills()));
        }
        if (!plugin.mcpServers().isEmpty()) {
            content.add("MCP servers: " + String.join(", ", plugin.mcpServers()));
        }
        return "- " + plugin.name() + " (from " + plugin.source() + ") — " + content;
    }
}
