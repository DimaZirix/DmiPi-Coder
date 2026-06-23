package com.dmipi.coder.core.plugins.skills;

import java.util.Objects;

/** One loaded skill: its name, one-line description, the full instruction body, and the project-relative directory its files live in. */
record Skill(String name, String description, String instructions, String directory) {

    Skill {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(directory, "directory");
    }
}
