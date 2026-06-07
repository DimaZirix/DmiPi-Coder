package com.dmipi.coder.core.domain.permissions;

import com.dmipi.coder.core.domain.tool.ToolParams;

/**
 * A plugin-attached policy for its tool. The gate composes it with the tool's baseline so it
 * can only tighten — a policy can turn run into ask or deny, never the reverse.
 */
public interface PermissionPolicy {

    PermissionDecision decision(ToolParams params);
}
