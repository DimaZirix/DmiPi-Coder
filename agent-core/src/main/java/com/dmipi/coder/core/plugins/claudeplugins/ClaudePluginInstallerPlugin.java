package com.dmipi.coder.core.plugins.claudeplugins;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.util.Set;

/**
 * Contributes {@code install_plugin}: fetches a Claude-format plugin (a git repository or local
 * directory; a marketplace repository carries one plugin per top-level directory) and installs
 * its supported content converted to the native format — skills into {@code .coder/skills}, MCP
 * servers merged into the scope's native MCP config — under the user or project anchor. Runtime
 * loading stays single-format: the native skills and MCP plugins pick the installed content up
 * at the next session start. When the call does not say where to install, the user is asked
 * directly through HIL — user space or project space. Every install is recorded in a per-anchor
 * manifest ({@code .coder/installed-plugins.json}), which backs the two companion tools:
 * {@code list_plugins} and {@code remove_plugin}.
 */
public final class ClaudePluginInstallerPlugin implements Plugin {

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.SHELL, CapabilityType.FILE_SYSTEM, CapabilityType.CONFIGURATION, CapabilityType.HIL);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerTool(new InstallPluginTool(capabilities.shell(), capabilities.fileSystem(), capabilities.userFileSystem(), capabilities.hil()));
        registrar.registerTool(new ListPluginsTool(capabilities.fileSystem(), capabilities.userFileSystem()));
        registrar.registerTool(new RemovePluginTool(capabilities.fileSystem(), capabilities.userFileSystem(), capabilities.hil()));
    }
}
