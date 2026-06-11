package com.dmipi.coder.core.plugins.skills;

import java.util.Objects;

/** One loaded skill: its name, one-line description, and the full instruction body. */
record Skill(String name, String description, String instructions) {

    Skill {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(instructions, "instructions");
    }
}
