package com.dmipi.coder.core.plugins.planning;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;

/**
 * The planning tool: todo_write keeps a visible task list for multi-step work. Requires no
 * capability — the list travels to the front-end as the tool's display payload.
 */
public final class PlanningPlugin implements Plugin {

    private static final String INSTRUCTIONS = """
            ## Task planning

            When a request takes more than a couple of steps, keep a task list with the todo_write \
            tool: write the planned steps before starting, mark a task in_progress when you begin \
            it and completed as soon as it is done. Keep exactly one task in_progress at a time. \
            Each call replaces the whole list, so always send every task, not just the one that \
            changed. When a request is a single step, skip the list and just do the work.""";

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerTool(new TodoWriteTool());
        registrar.registerInstructionSection(INSTRUCTIONS);
    }
}
