package com.dmipi.coder.core.plugins.openai;

import com.dmipi.coder.core.domain.llm.ChatMessage;
import com.dmipi.coder.core.domain.llm.ChatRequest;
import com.dmipi.coder.core.domain.llm.LlmException;
import com.dmipi.coder.core.domain.llm.LlmStreamEvent;
import com.dmipi.coder.core.domain.llm.ToolCall;
import com.dmipi.coder.core.domain.llm.ToolSchema;
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The chat-completions wire format: request serialization and stream-chunk decoding. */
final class OpenAiJson {

    private OpenAiJson() {
    }

    static String writeRequest(final JsonMapper mapper, final String modelId, final ChatRequest request) {
        final ObjectNode root = mapper.createObjectNode();
        root.put("model", modelId);
        root.put("stream", true);

        final ArrayNode messages = root.putArray("messages");
        for (final ChatMessage message : request.messages()) {
            messages.add(messageNode(mapper, message));
        }
        if (!request.tools().isEmpty()) {
            final ArrayNode tools = root.putArray("tools");
            for (final ToolSchema schema : request.tools()) {
                tools.add(toolNode(mapper, schema));
            }
        }
        return mapper.writeValueAsString(root);
    }

    /** Decodes one stream chunk into events; true when it carried the finish reason. */
    static boolean emitChunk(final JsonNode chunk, final Consumer<LlmStreamEvent> events) {
        if (chunk.path("error").isObject()) {
            throw new LlmException("The server returned an error: " + chunk.path("error"));
        }

        final JsonNode choice = chunk.path("choices").path(0);
        final JsonNode delta = choice.path("delta");
        emitThinking(delta, events);
        if (delta.path("content").isString() && !delta.path("content").stringValue().isEmpty()) {
            events.accept(new LlmStreamEvent.TextDelta(delta.path("content").stringValue()));
        }
        for (final JsonNode call : delta.path("tool_calls")) {
            events.accept(toolCallDelta(call));
        }

        if (choice.path("finish_reason").isString()) {
            events.accept(new LlmStreamEvent.Finished(finishReason(choice.path("finish_reason").stringValue())));
            return true;
        }
        return false;
    }

    private static void emitThinking(final JsonNode delta, final Consumer<LlmStreamEvent> events) {
        final JsonNode reasoning = delta.path("reasoning_content").isString() ? delta.path("reasoning_content") : delta.path("reasoning");
        if (reasoning.isString() && !reasoning.stringValue().isEmpty()) {
            events.accept(new LlmStreamEvent.ThinkingDelta(reasoning.stringValue()));
        }
    }

    private static LlmStreamEvent.ToolCallDelta toolCallDelta(final JsonNode call) {
        final int index = call.path("index").isIntegralNumber() ? call.path("index").intValue() : 0;
        final String id = call.path("id").isString() ? call.path("id").stringValue() : "";
        final JsonNode function = call.path("function");
        final String name = function.path("name").isString() ? function.path("name").stringValue() : "";
        final String arguments = function.path("arguments").isString() ? function.path("arguments").stringValue() : "";
        return new LlmStreamEvent.ToolCallDelta(index, id, name, arguments);
    }

    private static LlmStreamEvent.FinishReason finishReason(final String wire) {
        return switch (wire) {
            case "stop" -> LlmStreamEvent.FinishReason.STOP;
            case "tool_calls" -> LlmStreamEvent.FinishReason.TOOL_CALLS;
            case "length" -> LlmStreamEvent.FinishReason.LENGTH;
            default -> LlmStreamEvent.FinishReason.OTHER;
        };
    }

    private static ObjectNode messageNode(final JsonMapper mapper, final ChatMessage message) {
        final ObjectNode node = mapper.createObjectNode();
        switch (message.role()) {
            case SYSTEM -> node.put("role", "system").put("content", message.content());
            case USER -> node.put("role", "user").put("content", message.content());
            case TOOL -> node.put("role", "tool").put("tool_call_id", message.toolCallId()).put("content", message.content());
            case ASSISTANT -> assistantNode(mapper, node, message);
        }
        return node;
    }

    private static void assistantNode(final JsonMapper mapper, final ObjectNode node, final ChatMessage message) {
        node.put("role", "assistant").put("content", message.content());
        if (message.toolCalls().isEmpty()) {
            return;
        }
        final ArrayNode calls = node.putArray("tool_calls");
        for (final ToolCall call : message.toolCalls()) {
            final ObjectNode callNode = calls.addObject();
            callNode.put("id", call.id());
            callNode.put("type", "function");
            callNode.putObject("function").put("name", call.name()).put("arguments", call.argumentsJson());
        }
    }

    private static ObjectNode toolNode(final JsonMapper mapper, final ToolSchema schema) {
        final ObjectNode node = mapper.createObjectNode();
        node.put("type", "function");
        final ObjectNode function = node.putObject("function");
        function.put("name", schema.name());
        function.put("description", schema.description());
        function.set("parameters", parametersTree(mapper, schema));
        return node;
    }

    private static JsonNode parametersTree(final JsonMapper mapper, final ToolSchema schema) {
        try {
            return mapper.readTree(schema.parametersJson());
        } catch (final JacksonException invalid) {
            throw new LlmException("Tool '" + schema.name() + "' has an invalid parameter schema: " + invalid.getMessage());
        }
    }
}
