package com.dmipi.coder.core.plugins.files;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.util.Set;

/**
 * The read-only file tools: read_file and list_directory. Registering only this plugin yields a
 * read-only agent — the explicit opt-in to mutation is {@link FilesEditPlugin}.
 */
public final class FilesReadPlugin implements Plugin {

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.FILE_SYSTEM);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerTool(new ReadFileTool(capabilities.fileSystem()));
        registrar.registerTool(new ListDirectoryTool(capabilities.fileSystem()));
        registrar.registerTool(new GlobTool(capabilities.fileSystem()));
        registrar.registerTool(new GrepTool(capabilities.fileSystem()));
    }
}
