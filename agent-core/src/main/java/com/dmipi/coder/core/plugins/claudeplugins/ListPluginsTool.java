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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Reads the per-anchor plugin manifests and reports what is installed where. Skills present in
 * {@code .coder/skills} that no manifest entry owns — hand-written, or installed before the
 * manifest existed — are reported separately, so "no plugins" never hides existing skills.
 */
final class ListPluginsTool implements Tool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {}
            }""";

    private static final String SKILLS_LOCATION = ".coder/skills";

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
        return "Lists the plugins installed by install_plugin — per scope (user and project), each with its source and the skills and MCP servers it brought. "
                + "Skills present in .coder/skills that no plugin owns (hand-written or pre-manifest) are reported separately.";
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
            final StringJoiner listing = new StringJoiner("\n");
            if (user.isEmpty() && project.isEmpty()) {
                listing.add("No plugins are installed.");
            }
            section(listing, InstallScope.USER, user);
            section(listing, InstallScope.PROJECT, project);
            unmanaged(listing, InstallScope.USER, userFiles, user);
            unmanaged(listing, InstallScope.PROJECT, projectFiles, project);
            final int count = user.size() + project.size();
            return new ToolResult.Success(listing.toString(), new Display.Text(count + " plugin" + (count == 1 ? "" : "s") + " installed"));
        } catch (final InstallFailure failure) {
            return new ToolResult.Failure(failure.getMessage());
        }
    }

    /** Reports the skills of this scope that no manifest entry owns; remove_plugin cannot touch them. */
    private static void unmanaged(final StringJoiner listing, final InstallScope scope, final FileSystem files, final List<InstalledPlugin> plugins) {
        final Path root = files.resolve(SKILLS_LOCATION);
        if (!files.exists(root)) {
            return;
        }
        final Set<String> managed = plugins.stream()
                .flatMap(plugin -> plugin.skills().stream())
                .collect(Collectors.toSet());
        final List<String> loose = files.list(root).stream()
                .filter(entry -> entry.endsWith("/"))
                .map(entry -> entry.substring(0, entry.length() - 1))
                .filter(skill -> !managed.contains(skill))
                .toList();
        if (!loose.isEmpty()) {
            listing.add("Skills in the " + scope.label() + " scope not installed by any plugin (hand-written or pre-manifest): " + String.join(", ", loose));
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
