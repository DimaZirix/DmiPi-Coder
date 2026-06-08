package com.dmipi.coder.core.plugins.memory;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.util.Set;

/**
 * Standing memory: markdown files the user owns, loaded into the instructions at session start
 * and saved through the {@code memory} tool under the normal permission gate. Registering this
 * plugin is the grant — without it, no memory file is ever read.
 */
public final class MemoryPlugin implements Plugin {

    private static final String GUIDANCE = """
            ## Memory

            Standing knowledge from the user's memory files, loaded at session start; where entries \
            contradict, the most specific (later) section wins. When the user asks you to remember \
            something, save it with the memory tool: a fact about this project goes to scope \
            'project', a personal preference to scope 'user'. Keep memory short — rules and \
            pointers, not prose.""";

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.FILE_SYSTEM, CapabilityType.CONFIGURATION);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        final MemoryStore store = new MemoryStore(capabilities.userFileSystem(), capabilities.fileSystem());
        registrar.registerTool(new MemoryTool(store));
        registrar.registerInstructionSection(section(store));
    }

    private static String section(final MemoryStore store) {
        final StringBuilder section = new StringBuilder(GUIDANCE);
        store.load(MemoryScope.USER)
                .ifPresent(memory -> section.append("\n\n### User memory\n\n").append(memory));
        store.load(MemoryScope.PROJECT)
                .ifPresent(memory -> section.append("\n\n### Project memory\n\n").append(memory));
        return section.toString();
    }
}
