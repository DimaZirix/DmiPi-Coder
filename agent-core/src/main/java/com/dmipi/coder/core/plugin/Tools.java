package com.dmipi.coder.core.plugin;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.llm.ToolSchema;
import com.dmipi.coder.core.domain.tool.ToolResult;
import java.util.List;

/**
 * The tools capability: the catalog, reachable from plugin code. A tool is invoked by name —
 * the core brokers the call and it passes the same permission gate as a model call; an absent
 * tool surfaces as a failure the caller must handle, never as a dependency.
 */
public interface Tools {

    List<ToolSchema> available();

    ToolResult invoke(String toolName, String argumentsJson, CancelToken cancel);
}
