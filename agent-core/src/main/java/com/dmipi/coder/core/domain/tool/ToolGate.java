package com.dmipi.coder.core.domain.tool;

import com.dmipi.coder.core.domain.permissions.GateDecision;

/** The permission layer as the loop sees it: every call passes here before executing. */
public interface ToolGate {

    GateDecision decide(Tool tool, ToolParams params);
}
