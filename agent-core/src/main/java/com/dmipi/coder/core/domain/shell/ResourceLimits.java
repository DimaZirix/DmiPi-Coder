package com.dmipi.coder.core.domain.shell;

import java.util.Objects;

/**
 * Optional resource bounds a confining provider enforces on sandboxed commands. {@code memoryMax}
 * is a size with an uppercase suffix ("512M", "2G") — the spelling both mechanisms accept;
 * {@code tasksMax} caps threads + processes. Bubblewrap enforces them by wrapping the command in
 * {@code systemd-run --user --scope} ({@code MemoryMax=}/{@code TasksMax=}); podman natively via
 * cgroups ({@code --memory}/{@code --pids-limit}). A blank {@code memoryMax} or zero
 * {@code tasksMax} leaves that bound off.
 */
public record ResourceLimits(String memoryMax, int tasksMax) {

    public ResourceLimits {
        Objects.requireNonNull(memoryMax, "memoryMax");
        if (tasksMax < 0) {
            throw new IllegalArgumentException("tasksMax must be zero (no bound) or positive, got: " + tasksMax);
        }
    }

    /** No bounds: the provider adds no resource flags at all. */
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
