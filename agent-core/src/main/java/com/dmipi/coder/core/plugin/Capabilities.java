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

    public Capabilities(final Hil hil, final Output output, final Llms llms, final Configuration configuration, final Tools tools) {
        this.hil = hil;
        this.output = output;
        this.llms = llms;
        this.configuration = configuration;
        this.tools = tools;
    }

    /** The view a plugin sees: only what it declared, of what is granted. */
    public Capabilities restrictedTo(final Set<CapabilityType> declared) {
        return new Capabilities(
                declared.contains(CapabilityType.HIL) ? hil : null,
                declared.contains(CapabilityType.OUTPUT) ? output : null,
                declared.contains(CapabilityType.LLM) ? llms : null,
                declared.contains(CapabilityType.CONFIGURATION) ? configuration : null,
                declared.contains(CapabilityType.TOOLS) ? tools : null);
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

    private static <T> T present(final T capability, final CapabilityType type) {
        if (capability == null) {
            throw new IllegalStateException("Capability " + type + " was not declared by this plugin (or is not granted). Declare it in Plugin.requires().");
        }
        return capability;
    }
}
