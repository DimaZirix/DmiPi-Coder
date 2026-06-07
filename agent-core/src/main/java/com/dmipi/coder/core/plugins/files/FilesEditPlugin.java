package com.dmipi.coder.core.plugins.files;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.util.Set;

/** The mutating file tools: edit and write_file — the explicit opt-in to modification. */
public final class FilesEditPlugin implements Plugin {

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.FILE_SYSTEM);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerTool(new EditTool(capabilities.fileSystem()));
        registrar.registerTool(new WriteFileTool(capabilities.fileSystem()));
    }
}
