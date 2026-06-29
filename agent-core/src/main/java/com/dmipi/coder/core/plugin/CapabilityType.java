package com.dmipi.coder.core.plugin;

/**
 * The closed capability set: what a plugin may declare and receive. A plugin cannot offer a new
 * capability of its own — what it offers to others, it offers as tools.
 */
public enum CapabilityType {
    HIL,
    OUTPUT,
    LLM,
    CONFIGURATION,
    TOOLS,
    FILE_SYSTEM,
    HTTP,
    SHELL,
    CONVERSATIONS,
    MODES
}
