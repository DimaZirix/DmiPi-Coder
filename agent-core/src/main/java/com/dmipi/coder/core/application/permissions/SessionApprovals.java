package com.dmipi.coder.core.application.permissions;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The "always allow this session" answers — remembered by the gate alone, per tool name. */
final class SessionApprovals {

    private final Set<String> approved = ConcurrentHashMap.newKeySet();

    void approve(final String toolName) {
        approved.add(toolName);
    }

    boolean isApproved(final String toolName) {
        return approved.contains(toolName);
    }
}
