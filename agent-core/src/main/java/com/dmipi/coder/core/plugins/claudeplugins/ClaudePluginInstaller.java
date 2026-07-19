package com.dmipi.coder.core.plugins.claudeplugins;

import com.dmipi.coder.core.plugin.FileSystem;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Converts one Claude-format plugin into the native layout: every file under
 * {@code skills/} is copied beneath {@code .coder/skills} of the destination anchor, and the
 * servers of the plugin's {@code .mcp.json} merge into the scope's native MCP config. Skills and
 * MCP servers are the only supported content — other Claude content is reported as skipped.
 */
final class ClaudePluginInstaller {

    private static final String SKILLS_DIRECTORY = "skills";
    private static final String SKILLS_LOCATION = ".coder/skills";
    private static final Pattern SKILL_FILE = Pattern.compile("[^/]+/SKILL\\.md");
    private static final String MCP_CONFIG = ".mcp.json";
    private static final List<String> UNSUPPORTED_CONTENT = List.of("agents", "commands", "hooks", "output-styles");

    private final PluginSource source;
    private final FileSystem destination;
    private final InstallScope scope;

    ClaudePluginInstaller(final PluginSource source, final FileSystem destination, final InstallScope scope) {
        this.source = source;
        this.destination = destination;
        this.scope = scope;
    }

    /**
     * Installs the plugin at {@code plugin} — the source root when absent — recording it in the
     * manifest as {@code name} coming from {@code origin}, and reports what landed where.
     */
    String install(final Optional<String> plugin, final String name, final String origin) {
        final String pluginRoot = plugin.orElse("");
        if (!plugin.map(source::directoryExists).orElse(true)) {
            throw new InstallFailure("The source has no plugin directory '" + pluginRoot + "'. " + availablePlugins());
        }
        final boolean hasSkills = source.directoryExists(join(pluginRoot, SKILLS_DIRECTORY));
        final boolean hasServers = source.fileExists(join(pluginRoot, MCP_CONFIG));
        if (!hasSkills && !hasServers) {
            throw new InstallFailure(plugin.isPresent()
                    ? "Plugin '" + pluginRoot + "' has neither a " + SKILLS_DIRECTORY + " directory nor an " + MCP_CONFIG + " — nothing supported to install."
                    : "The source root has no " + SKILLS_DIRECTORY + " directory and no " + MCP_CONFIG + " — pass 'plugin' to pick one from a marketplace. " + availablePlugins());
        }
        final List<String> skills = hasSkills ? installSkills(pluginRoot) : List.of();
        final List<String> servers = hasServers ? installServers(pluginRoot) : List.of();
        InstalledPluginsRegistry.record(destination, new InstalledPlugin(name, origin, skills, servers));
        return report(name, skills, servers, skippedContent(pluginRoot));
    }

    private List<String> installSkills(final String pluginRoot) {
        final String skillsRoot = join(pluginRoot, SKILLS_DIRECTORY);
        final List<String> files = source.filesUnder(skillsRoot);
        for (final String file : files) {
            destination.write(destination.resolve(SKILLS_LOCATION + "/" + file), source.read(skillsRoot + "/" + file));
        }
        return files.stream()
                .filter(file -> SKILL_FILE.matcher(file).matches())
                .map(file -> file.substring(0, file.indexOf('/')))
                .toList();
    }

    private List<String> installServers(final String pluginRoot) {
        return McpServersConfig.merge(source.read(join(pluginRoot, MCP_CONFIG)), destination, scope.mcpConfigLocation());
    }

    private List<String> skippedContent(final String pluginRoot) {
        return UNSUPPORTED_CONTENT.stream()
                .filter(directory -> source.directoryExists(join(pluginRoot, directory)))
                .toList();
    }

    private String availablePlugins() {
        final List<String> candidates = source.pluginDirectories();
        return candidates.isEmpty()
                ? "No plugin directories with installable content were found in it."
                : "Plugins found in it: " + String.join(", ", candidates) + ".";
    }

    private String report(final String name, final List<String> skills, final List<String> servers, final List<String> skipped) {
        final StringJoiner report = new StringJoiner("\n");
        report.add("Installed '" + name + "' to the " + scope.label() + " scope.");
        if (!skills.isEmpty()) {
            report.add("Skills (now under " + SKILLS_LOCATION + "): " + String.join(", ", skills));
        }
        if (!servers.isEmpty()) {
            report.add("MCP servers (merged into " + scope.mcpConfigLocation() + "): " + String.join(", ", servers));
        }
        if (!skipped.isEmpty()) {
            report.add("Skipped unsupported Claude content: " + String.join(", ", skipped));
        }
        report.add("The installed skills and MCP servers load at the next session start.");
        return report.toString();
    }

    private static String join(final String pluginRoot, final String entry) {
        return pluginRoot.isEmpty() ? entry : pluginRoot + "/" + entry;
    }
}
