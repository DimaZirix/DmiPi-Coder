package com.dmipi.coder.core.plugins.bubblewrap;

import java.util.Objects;

/**
 * Optional resource bounds for the bubblewrap sandbox, enforced by wrapping the command in
 * {@code systemd-run --user --scope} — bubblewrap itself has no resource controls. Values use
 * systemd's own syntax: {@code memoryMax} is a size with an optional suffix ("512M", "2G") and
 * becomes {@code MemoryMax=}; {@code tasksMax} caps threads + processes in the scope and becomes
 * {@code TasksMax=}. A blank {@code memoryMax} or zero {@code tasksMax} leaves that bound off.
 */
public record ResourceLimits(String memoryMax, int tasksMax) {

    public ResourceLimits {
        Objects.requireNonNull(memoryMax, "memoryMax");
        if (tasksMax < 0) {
            throw new IllegalArgumentException("tasksMax must be zero (no bound) or positive, got: " + tasksMax);
        }
    }

    /** No bounds: commands run under bubblewrap alone, without a systemd-run scope. */
    public static ResourceLimits none() {
        return new ResourceLimits("", 0);
    }

    public boolean limitsMemory() {
        return !memoryMax.isBlank();
    }

    public boolean limitsTasks() {
        return tasksMax > 0;
    }

    public boolean bounded() {
        return limitsMemory() || limitsTasks();
    }
}
