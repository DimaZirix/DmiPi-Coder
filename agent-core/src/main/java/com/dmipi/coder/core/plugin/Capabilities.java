package com.dmipi.coder.core.plugin;

import com.dmipi.coder.core.domain.hil.Hil;
import java.util.Set;

/**
 * What a plugin receives at install: exactly the capabilities it declared, nothing else —
 * least privilege by construction. Accessing an undeclared or ungranted capability fails loudly.
 */
public final class Capabilities {

    private final Hil hil;
    private final Output output;
    private final Llms llms;
    private final Configuration configuration;
    private final Tools tools;
    private final FileSystem fileSystem;
    private final Shell shell;

    public Capabilities(final Hil hil, final Output output, final Llms llms, final Configuration configuration, final Tools tools, final FileSystem fileSystem, final Shell shell) {
        this.hil = hil;
        this.output = output;
        this.llms = llms;
        this.configuration = configuration;
        this.tools = tools;
        this.fileSystem = fileSystem;
        this.shell = shell;
    }

    /** The view a plugin sees: only what it declared, of what is granted. */
    public Capabilities restrictedTo(final Set<CapabilityType> declared) {
        return new Capabilities(
                declared.contains(CapabilityType.HIL) ? hil : null,
                declared.contains(CapabilityType.OUTPUT) ? output : null,
                declared.contains(CapabilityType.LLM) ? llms : null,
                declared.contains(CapabilityType.CONFIGURATION) ? configuration : null,
                declared.contains(CapabilityType.TOOLS) ? tools : null,
                declared.contains(CapabilityType.FILE_SYSTEM) ? fileSystem : null,
                declared.contains(CapabilityType.SHELL) ? shell : null);
    }

    public Hil hil() {
        return present(hil, CapabilityType.HIL);
    }

    public Output output() {
        return present(output, CapabilityType.OUTPUT);
    }

    public Llms llms() {
        return present(llms, CapabilityType.LLM);
    }

    public Configuration configuration() {
        return present(configuration, CapabilityType.CONFIGURATION);
    }

    public Tools tools() {
        return present(tools, CapabilityType.TOOLS);
    }

    public FileSystem fileSystem() {
        return present(fileSystem, CapabilityType.FILE_SYSTEM);
    }

    public Shell shell() {
        return present(shell, CapabilityType.SHELL);
    }

    private static <T> T present(final T capability, final CapabilityType type) {
        if (capability == null) {
            throw new IllegalStateException("Capability " + type + " was not declared by this plugin (or is not granted). Declare it in Plugin.requires().");
        }
        return capability;
    }
}
