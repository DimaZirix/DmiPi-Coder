package com.dmipi.coder.core.infrastructure.json;

import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolParamsParser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Parses tool-call arguments with Jackson; an empty argument string counts as an empty object. */
public final class JacksonToolParamsParser implements ToolParamsParser {

    private final ObjectMapper mapper;

    public JacksonToolParamsParser(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ToolParams parse(final String argumentsJson) {
        final String json = argumentsJson.isBlank() ? "{}" : argumentsJson;
        final JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (final JacksonException e) {
            throw new IllegalArgumentException("The tool arguments are not valid JSON: " + e.getMessage());
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("The tool arguments must be a JSON object, got: " + json);
        }
        return new JacksonToolParams(root);
    }
}
