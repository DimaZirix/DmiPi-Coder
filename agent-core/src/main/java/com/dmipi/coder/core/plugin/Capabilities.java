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
    private final FileSystem userFileSystem;
    private final Http http;
    private final Shell shell;
    private final Conversations conversations;

    public Capabilities(final Hil hil, final Output output, final Llms llms, final Configuration configuration, final Tools tools, final FileSystem fileSystem, final FileSystem userFileSystem, final Http http, final Shell shell, final Conversations conversations) {
        this.hil = hil;
        this.output = output;
        this.llms = llms;
        this.configuration = configuration;
        this.tools = tools;
        this.fileSystem = fileSystem;
        this.userFileSystem = userFileSystem;
        this.http = http;
        this.shell = shell;
        this.conversations = conversations;
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
                declared.containsAll(Set.of(CapabilityType.FILE_SYSTEM, CapabilityType.CONFIGURATION)) ? userFileSystem : null,
                declared.contains(CapabilityType.HTTP) ? http : null,
                declared.contains(CapabilityType.SHELL) ? shell : null,
                declared.contains(CapabilityType.CONVERSATIONS) ? conversations : null);
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

    /**
     * File access anchored at the <em>user directory</em> instead of the project — the seam for
     * user-scope state such as user memory. Requires declaring both FILE_SYSTEM (file access)
     * and CONFIGURATION (knowing the anchors); either alone does not grant it.
     */
    public FileSystem userFileSystem() {
        if (userFileSystem == null) {
            throw new IllegalStateException("The user-scope file system requires declaring both FILE_SYSTEM and CONFIGURATION in Plugin.requires().");
        }
        return userFileSystem;
    }

    public Http http() {
        return present(http, CapabilityType.HTTP);
    }

    public Shell shell() {
        return present(shell, CapabilityType.SHELL);
    }

    public Conversations conversations() {
        return present(conversations, CapabilityType.CONVERSATIONS);
    }

    private static <T> T present(final T capability, final CapabilityType type) {
        if (capability == null) {
            throw new IllegalStateException("Capability " + type + " was not declared by this plugin (or is not granted). Declare it in Plugin.requires().");
        }
        return capability;
    }
}
