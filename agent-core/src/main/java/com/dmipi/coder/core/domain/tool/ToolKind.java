package com.dmipi.coder.core.domain.tool;

public enum ToolKind {
    READ,
    EDIT,
    SEARCH,
    EXECUTE,
    NETWORK,
    OTHER;

    /** True for the kinds that change state: edits and execution. */
    public boolean mutates() {
        return this == EDIT || this == EXECUTE;
    }
}
