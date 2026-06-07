package com.dmipi.coder.core.infrastructure.json;

import com.dmipi.coder.core.domain.tool.ToolParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

final class JacksonToolParams implements ToolParams {

    private final JsonNode root;

    JacksonToolParams(final JsonNode root) {
        this.root = root;
    }

    @Override
    public Optional<String> string(final String key) {
        final JsonNode value = root.path(key);
        return value.isString() ? Optional.of(value.stringValue()) : Optional.empty();
    }

    @Override
    public Optional<Long> integer(final String key) {
        final JsonNode value = root.path(key);
        return value.isIntegralNumber() ? Optional.of(value.longValue()) : Optional.empty();
    }

    @Override
    public Optional<Boolean> bool(final String key) {
        final JsonNode value = root.path(key);
        return value.isBoolean() ? Optional.of(value.booleanValue()) : Optional.empty();
    }

    @Override
    public Optional<List<String>> stringList(final String key) {
        final JsonNode value = root.path(key);
        if (!value.isArray()) {
            return Optional.empty();
        }

        final List<String> strings = new ArrayList<>();
        for (int i = 0; i < value.size(); i++) {
            if (!value.path(i).isString()) {
                return Optional.empty();
            }
            strings.add(value.path(i).stringValue());
        }
        return Optional.of(List.copyOf(strings));
    }

    @Override
    public String rawJson() {
        return root.toString();
    }
}
