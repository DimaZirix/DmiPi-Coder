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

    private final ReadTracker readTracker;

    public FilesReadPlugin() {
        this(null);
    }

    /** Shares a read-tracker with {@link FilesEditPlugin} to enable the read-before-edit gate. */
    public FilesReadPlugin(final ReadTracker readTracker) {
        this.readTracker = readTracker;
    }

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.FILE_SYSTEM);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerTool(new ReadFileTool(capabilities.fileSystem(), readTracker));
        registrar.registerTool(new ListDirectoryTool(capabilities.fileSystem()));
        registrar.registerTool(new GlobTool(capabilities.fileSystem()));
        registrar.registerTool(new GrepTool(capabilities.fileSystem()));
    }
}
