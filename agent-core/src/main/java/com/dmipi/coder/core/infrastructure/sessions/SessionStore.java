package com.dmipi.coder.core.infrastructure.sessions;

import com.dmipi.coder.core.domain.llm.ChatMessage;
import com.dmipi.coder.core.domain.llm.Role;
import com.dmipi.coder.core.domain.llm.ToolCall;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Saves and resumes conversations as JSON files under the sessions directory. The system
 * message is never stored — instructions are rebuilt fresh each session; only the dialogue
 * (prompts, answers, tool calls and results) persists.
 */
public final class SessionStore {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path directory;

    public SessionStore(final Path directory) {
        this.directory = directory;
    }

    /** The saved session names, sorted; an absent directory holds none. */
    public List<String> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not list the sessions in " + directory, failure);
        }
    }

    public void save(final String name, final List<ChatMessage> messages) {
        final ArrayNode root = MAPPER.createArrayNode();
        messages.stream()
                .filter(message -> message.role() != Role.SYSTEM)
                .forEach(message -> root.add(node(message)));
        try {
            Files.createDirectories(directory);
            Files.writeString(file(name), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not save the session '" + name + "': " + failure.getMessage(), failure);
        }
    }

    public List<ChatMessage> load(final String name) {
        final Path file = file(name);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No saved session named '" + name + "'. Saved: " + String.join(", ", list()) + ".");
        }
        try {
            final JsonNode root = MAPPER.readTree(Files.readString(file));
            final List<ChatMessage> messages = new ArrayList<>();
            for (final JsonNode node : root) {
                messages.add(message(node));
            }
            return List.copyOf(messages);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not read the session '" + name + "': " + failure.getMessage(), failure);
        }
    }

    private Path file(final String name) {
        if (name.isBlank() || !name.matches("[\\w-]+")) {
            throw new IllegalArgumentException("A session name uses letters, digits, '-' and '_' only, got '" + name + "'.");
        }
        return directory.resolve(name + ".json");
    }

    private static ObjectNode node(final ChatMessage message) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("role", message.role().name());
        node.put("content", message.content());
        node.put("toolCallId", message.toolCallId());
        final ArrayNode calls = node.putArray("toolCalls");
        for (final ToolCall call : message.toolCalls()) {
            final ObjectNode callNode = calls.addObject();
            callNode.put("id", call.id());
            callNode.put("name", call.name());
            callNode.put("argumentsJson", call.argumentsJson());
        }
        return node;
    }

    private static ChatMessage message(final JsonNode node) {
        final List<ToolCall> calls = new ArrayList<>();
        for (final JsonNode call : node.path("toolCalls")) {
            calls.add(new ToolCall(call.path("id").stringValue(), call.path("name").stringValue(), call.path("argumentsJson").stringValue()));
        }
        return new ChatMessage(
                Role.valueOf(node.path("role").stringValue()),
                node.path("content").stringValue(),
                calls,
                node.path("toolCallId").stringValue());
    }
}
