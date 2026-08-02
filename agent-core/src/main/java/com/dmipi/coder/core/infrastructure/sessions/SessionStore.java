package com.dmipi.coder.core.infrastructure.sessions;

import com.dmipi.coder.core.domain.llm.ChatMessage;
import com.dmipi.coder.core.domain.llm.Role;
import com.dmipi.coder.core.domain.llm.ToolCall;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Saves and resumes conversations as JSON files under the sessions directory. Each file records
 * the system prompt and a fingerprint of what produced it ({@link SessionFingerprint}) alongside
 * the dialogue — so a resume can replay the prompt byte-for-byte when the world still matches,
 * reusing the LLM's prompt cache, and rebuild it otherwise. The system prompt is stored but never
 * re-sent from history: it becomes message 0 of the resumed conversation.
 */
public final class SessionStore {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path directory;

    public SessionStore(final Path directory) {
        this.directory = directory;
    }

    /** A saved session: the system prompt it ran under, the fingerprint of its inputs, and the dialogue. */
    public record SavedSession(String systemPrompt, String fingerprint, List<ChatMessage> messages) {
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

    public void save(final String name, final String systemPrompt, final String fingerprint, final List<ChatMessage> messages) {
        final ObjectNode root = MAPPER.createObjectNode();
        root.put("fingerprint", fingerprint);
        root.put("systemPrompt", systemPrompt);
        final ArrayNode dialogue = root.putArray("messages");
        messages.stream()
                .filter(message -> message.role() != Role.SYSTEM)
                .forEach(message -> dialogue.add(node(message)));
        try {
            Files.createDirectories(directory);
            // Write-then-move: a crash mid-write must never truncate the previous good save.
            final Path target = file(name);
            final Path staging = directory.resolve(name + ".json.tmp");
            Files.writeString(staging, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            move(staging, target);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not save the session '" + name + "': " + failure.getMessage(), failure);
        }
    }

    private static void move(final Path staging, final Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException unsupported) {
            // A filesystem without atomic moves still gets the write-then-move ordering.
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public SavedSession load(final String name) {
        final Path file = file(name);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No saved session named '" + name + "'. Saved: " + String.join(", ", list()) + ".");
        }
        final String json;
        try {
            json = Files.readString(file);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not read the session '" + name + "': " + failure.getMessage(), failure);
        }
        try {
            final JsonNode root = MAPPER.readTree(json);
            final List<ChatMessage> messages = new ArrayList<>();
            for (final JsonNode node : root.path("messages")) {
                messages.add(message(node));
            }
            return new SavedSession(root.path("systemPrompt").asString(""), root.path("fingerprint").asString(""), List.copyOf(messages));
        } catch (final RuntimeException corrupted) {
            throw new IllegalStateException("The session file " + file + " is corrupted and cannot be resumed: " + corrupted.getMessage(), corrupted);
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
