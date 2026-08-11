package com.dmipi.coder.core.plugins.files;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.util.Set;

/** The mutating file tools: edit and write_file — the explicit opt-in to modification. */
public final class FilesEditPlugin implements Plugin {

    private final ReadTracker readTracker;

    public FilesEditPlugin() {
        this(ReadTracker.off());
    }

    /** Shares a read-tracker with {@link FilesReadPlugin} to enable the read-before-edit gate. */
    public FilesEditPlugin(final ReadTracker readTracker) {
        this.readTracker = java.util.Objects.requireNonNull(readTracker, "readTracker — use ReadTracker.off() for a wiring without the gate");
    }

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.FILE_SYSTEM);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerTool(new EditTool(capabilities.fileSystem(), readTracker));
        registrar.registerTool(new WriteFileTool(capabilities.fileSystem(), readTracker));
    }
}
