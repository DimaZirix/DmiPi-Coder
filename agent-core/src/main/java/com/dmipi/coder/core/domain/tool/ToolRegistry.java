package com.dmipi.coder.core.domain.tool;

import com.dmipi.coder.core.domain.llm.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The tool catalog: registration order is the order the model sees; names are unique. */
public final class ToolRegistry {

    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public ToolRegistry(final List<Tool> tools) {
        for (final Tool tool : Objects.requireNonNull(tools, "tools")) {
            if (byName.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Tool name '" + tool.name() + "' is registered twice.");
            }
        }
    }

    public Optional<Tool> named(final String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<ToolSchema> schemas() {
        return byName.values()
                .stream()
                .map(tool -> new ToolSchema(tool.name(), tool.description(), tool.parameterSchema().json()))
                .toList();
    }
}
