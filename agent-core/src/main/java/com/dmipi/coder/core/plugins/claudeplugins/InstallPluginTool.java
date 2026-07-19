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
import com.dmipi.coder.core.plugin.Shell;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The model-facing entry: fetch a Claude-format plugin and install its supported content in the
 * native format under the chosen scope. Installation runs commands and writes files, so it is an
 * EXECUTE tool asked about by default.
 */
final class InstallPluginTool implements Tool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["source"],
              "properties": {
                "source": {"type": "string", "description": "Git URL of a Claude-format plugin or marketplace repository, or a local directory path."},
                "plugin": {"type": "string", "description": "Plugin directory inside a marketplace repository (e.g. 'prompt-standards'). Omit when the repository root is itself the plugin."},
                "scope": {"type": "string", "enum": ["user", "project"], "description": "Where to install: 'user' (default) makes the content available in every project; 'project' installs into this project only."}
              }
            }""";

    private static final Pattern PLUGIN_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final Shell shell;
    private final FileSystem projectFiles;
    private final FileSystem userFiles;

    InstallPluginTool(final Shell shell, final FileSystem projectFiles, final FileSystem userFiles) {
        this.shell = shell;
        this.projectFiles = projectFiles;
        this.userFiles = userFiles;
    }

    @Override
    public String name() {
        return "install_plugin";
    }

    @Override
    public String description() {
        return "Installs a Claude-format plugin from a marketplace git repository or a local directory, converting it to the native layout: "
                + "every skills/<name>/SKILL.md lands under .coder/skills, and the servers of the plugin's .mcp.json merge into the native MCP config. "
                + "Only skills and MCP servers are supported — other Claude plugin content (agents, commands, hooks) is skipped. "
                + "Installed content loads at the next session start.";
    }

    @Override
    public ToolKind kind() {
        return ToolKind.EXECUTE;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(SCHEMA);
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        if (params.string("source").filter(source -> !source.isBlank()).isEmpty()) {
            return Optional.of("Parameter 'source' is required — a git URL or a local directory of a Claude-format plugin or marketplace.");
        }
        final Optional<String> plugin = params.string("plugin");
        if (plugin.isPresent() && !PLUGIN_NAME.matcher(plugin.orElseThrow()).matches()) {
            return Optional.of("Parameter 'plugin' must be a plain directory name, got: '" + plugin.orElseThrow() + "'.");
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
        return "install " + callSummary(params) + " into the " + scopeOf(params).label() + " scope";
    }

    @Override
    public String callSummary(final ToolParams params) {
        final String source = params.string("source").orElse("");
        return params.string("plugin")
                .map(plugin -> source + " (plugin " + plugin + ")")
                .orElse(source);
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final String sourceLocation = params.string("source").orElseThrow();
        final InstallScope scope = scopeOf(params);
        final FileSystem destination = scope == InstallScope.USER ? userFiles : projectFiles;
        try (PluginSource source = PluginSource.open(shell, sourceLocation, cancel)) {
            final String report = new ClaudePluginInstaller(source, destination, scope).install(params.string("plugin"));
            return new ToolResult.Success(report, new Display.Text("installed " + callSummary(params) + " (" + scope.label() + " scope)"));
        } catch (final InstallFailure failure) {
            return new ToolResult.Failure(failure.getMessage());
        }
    }

    private static InstallScope scopeOf(final ToolParams params) {
        return params.string("scope")
                .flatMap(InstallScope::of)
                .orElse(InstallScope.USER);
    }
}
