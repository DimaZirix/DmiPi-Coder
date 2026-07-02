package com.dmipi.coder.core.plugins.claudemarketplace;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Loads Claude-format plugins from one or more marketplace directories on disk. Every
 * {@code <plugin>/skills/<name>/SKILL.md} becomes a skill behind the single {@code skill} tool,
 * and every {@code <plugin>/.mcp.json} contributes its http MCP servers as
 * {@code mcp__<server>__<tool>}. The directories are operator-supplied (registered in Java), so
 * they are read directly rather than through the sandboxed file system.
 *
 * <p>This plugin is self-contained — it carries its own SKILL.md / .mcp.json parsing, MCP client,
 * and skill tool, so choosing the Claude layout is a compile-time decision made by registering it.
 * Only one plugin may own the {@code skill} tool, so do not register the native
 * {@code SkillsPlugin} alongside it.
 */
public final class ClaudeMarketplacePlugin implements Plugin {

    private static final Logger LOGGER = Logger.getLogger(ClaudeMarketplacePlugin.class.getName());
    private static final String SKILL_FILE = "SKILL.md";
    private static final String MCP_CONFIG_FILE = ".mcp.json";

    private final List<Path> roots;

    public ClaudeMarketplacePlugin(final List<Path> roots) {
        this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
    }

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.HTTP);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        installSkills(registrar);
        installServers(registrar, capabilities);
    }

    private void installSkills(final PluginRegistrar registrar) {
        final List<Skill> skills = new ArrayList<>();
        for (final Path skillFile : ClaudeMarketplaceTree.filesNamed(roots, SKILL_FILE)) {
            final Path directory = skillFile.getParent();
            ClaudeMarketplaceTree.read(skillFile)
                    .map(content -> SkillDocuments.parse(directory.getFileName().toString(), content, directory.toString()))
                    .ifPresent(skills::add);
        }
        if (!skills.isEmpty()) {
            registrar.registerTool(new SkillTool(skills));
        }
    }

    private void installServers(final PluginRegistrar registrar, final Capabilities capabilities) {
        for (final Path configFile : ClaudeMarketplaceTree.filesNamed(roots, MCP_CONFIG_FILE)) {
            ClaudeMarketplaceTree.read(configFile).ifPresent(content -> {
                for (final McpServerConfig server : McpServers.from(content, configFile.toString())) {
                    installServer(registrar, capabilities, server);
                }
            });
        }
    }

    private static void installServer(final PluginRegistrar registrar, final Capabilities capabilities, final McpServerConfig server) {
        final McpClient client = new McpClient(capabilities.http(), server);
        try {
            for (final McpRemoteTool remote : client.connect()) {
                registrar.registerTool(new McpProxyTool(client, server.name(), remote));
            }
        } catch (final RuntimeException unreachable) {
            LOGGER.warning("MCP server '" + server.name() + "' at " + server.url() + " could not be connected; skipping it: " + unreachable.getMessage());
        }
    }
}
