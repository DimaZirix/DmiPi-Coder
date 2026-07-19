package com.dmipi.coder.core.plugins.claudeplugins;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.hil.Hil;
import com.dmipi.coder.core.domain.hil.Option;
import com.dmipi.coder.core.domain.hil.Question;
import com.dmipi.coder.core.domain.hil.QuestionKind;
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
import java.util.stream.Stream;

/**
 * Removes a plugin the manifest knows: its skills from {@code .coder/skills}, its servers from
 * the scope's native MCP config, and its manifest entry. Only manifest-recorded content is
 * touched — skills or servers added by hand stay. Installed in both scopes with no scope named,
 * the user is asked which one to remove from.
 */
final class RemovePluginTool implements Tool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["name"],
              "properties": {
                "name": {"type": "string", "description": "The installed plugin to remove, as list_plugins names it."},
                "scope": {"type": "string", "enum": ["user", "project"], "description": "The scope to remove from. Pass it only when the user already said which; omitted, an ambiguous plugin is asked about directly."}
              }
            }""";

    private static final String SKILLS_LOCATION = ".coder/skills";

    private final FileSystem projectFiles;
    private final FileSystem userFiles;
    private final Hil hil;

    RemovePluginTool(final FileSystem projectFiles, final FileSystem userFiles, final Hil hil) {
        this.projectFiles = projectFiles;
        this.userFiles = userFiles;
        this.hil = hil;
    }

    @Override
    public String name() {
        return "remove_plugin";
    }

    @Override
    public String description() {
        return "Removes a plugin installed by install_plugin: deletes its skills from .coder/skills, its servers from the native MCP config, "
                + "and its manifest entry. Only content the install recorded is touched. The removal takes effect at the next session start.";
    }

    @Override
    public ToolKind kind() {
        return ToolKind.EDIT;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(SCHEMA);
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        if (params.string("name").filter(name -> !name.isBlank()).isEmpty()) {
            return Optional.of("Parameter 'name' is required — an installed plugin as list_plugins names it.");
        }
        final Optional<String> scope = params.string("scope");
        if (scope.isPresent() && InstallScope.of(scope.orElseThrow()).isEmpty()) {
            return Optional.of("Unknown scope '" + scope.orElseThrow() + "'; valid values: " + InstallScope.validValues() + ".");
        }
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ASK;
    }

    @Override
    public String preview(final ToolParams params) {
        return "remove plugin " + callSummary(params) + params.string("scope")
                .map(scope -> " from the " + scope + " scope")
                .orElse("");
    }

    @Override
    public String callSummary(final ToolParams params) {
        return params.string("name").orElse("");
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final String name = params.string("name").orElseThrow();
        try {
            final InstallScope scope = scopeHolding(name, params.string("scope").flatMap(InstallScope::of));
            final FileSystem files = scope == InstallScope.USER ? userFiles : projectFiles;
            final InstalledPlugin plugin = InstalledPluginsRegistry.find(files, name)
                    .orElseThrow(() -> new InstallFailure("No plugin named '" + name + "' is installed in the " + scope.label() + " scope. " + installed()));
            remove(plugin, files, scope);
            return new ToolResult.Success(report(plugin, scope), new Display.Text("removed " + name + " (" + scope.label() + " scope)"));
        } catch (final InstallFailure failure) {
            return new ToolResult.Failure(failure.getMessage());
        }
    }

    /** The requested scope, the only scope holding the plugin, or — installed in both — the user's choice. */
    private InstallScope scopeHolding(final String name, final Optional<InstallScope> requested) {
        if (requested.isPresent()) {
            return requested.orElseThrow();
        }
        final boolean inUser = InstalledPluginsRegistry.find(userFiles, name).isPresent();
        final boolean inProject = InstalledPluginsRegistry.find(projectFiles, name).isPresent();
        if (!inUser && !inProject) {
            throw new InstallFailure("No plugin named '" + name + "' is installed. " + installed());
        }
        if (inUser && inProject) {
            return askScope(name);
        }
        return inUser ? InstallScope.USER : InstallScope.PROJECT;
    }

    private InstallScope askScope(final String name) {
        final Answer answer = hil.ask(new Question(
                "'" + name + "' is installed in both scopes — remove it from which?",
                "",
                QuestionKind.OPTION_LIST,
                List.of(
                        new Option(InstallScope.USER.label(), "User space", "available in every project"),
                        new Option(InstallScope.PROJECT.label(), "Project space", "this project only"))));
        return InstallScope.of(answer.selected().getFirst())
                .orElseThrow(() -> new InstallFailure("The scope answer '" + answer.selected().getFirst() + "' matches no scope; valid values: " + InstallScope.validValues() + "."));
    }

    private static void remove(final InstalledPlugin plugin, final FileSystem files, final InstallScope scope) {
        plugin.skills().forEach(skill -> files.delete(files.resolve(SKILLS_LOCATION + "/" + skill)));
        McpServersConfig.remove(plugin.mcpServers(), files, scope.mcpConfigLocation());
        InstalledPluginsRegistry.remove(files, plugin.name());
    }

    private String installed() {
        final List<String> names = Stream.concat(
                        InstalledPluginsRegistry.all(userFiles).stream(),
                        InstalledPluginsRegistry.all(projectFiles).stream())
                .map(InstalledPlugin::name)
                .distinct()
                .sorted()
                .toList();
        return names.isEmpty()
                ? "No plugins are installed."
                : "Installed plugins: " + String.join(", ", names) + ".";
    }

    private static String report(final InstalledPlugin plugin, final InstallScope scope) {
        final StringJoiner report = new StringJoiner("\n");
        report.add("Removed '" + plugin.name() + "' from the " + scope.label() + " scope.");
        if (!plugin.skills().isEmpty()) {
            report.add("Skills removed: " + String.join(", ", plugin.skills()));
        }
        if (!plugin.mcpServers().isEmpty()) {
            report.add("MCP servers removed: " + String.join(", ", plugin.mcpServers()));
        }
        report.add("The removal takes effect at the next session start.");
        return report.toString();
    }
}
