package com.dmipi.coder.core.application.permissions;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "always allow this session" answers — remembered by the gate alone, keyed by the scope
 * the question offered (the tool, plus the exact command for an EXECUTE call).
 */
final class SessionApprovals {

    private final Set<String> approved = ConcurrentHashMap.newKeySet();

    void approve(final String scope) {
        approved.add(scope);
    }

    boolean isApproved(final String scope) {
        return approved.contains(scope);
    }
}
