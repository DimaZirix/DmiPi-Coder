package com.dmipi.coder.core.plugins.claudeplugins;

import com.dmipi.coder.core.plugin.FileSystem;

/**
 * Removes exactly what a manifest entry recorded — the plugin's skill directories, its servers
 * from the scope's native MCP config, and the entry itself. Shared by {@code remove_plugin} and
 * by a reinstall clearing the previous version. Content the manifest never recorded is not
 * touched.
 */
final class InstalledContent {

    private static final String SKILLS_LOCATION = ".coder/skills";

    private InstalledContent() {
    }

    static void remove(final InstalledPlugin plugin, final FileSystem files, final InstallScope scope) {
        for (final String skill : plugin.skills()) {
            files.delete(files.resolve(SKILLS_LOCATION + "/" + skill));
        }
        McpServersConfig.remove(plugin.mcpServers(), files, scope.mcpConfigLocation());
        InstalledPluginsRegistry.remove(files, plugin.name());
    }
}
