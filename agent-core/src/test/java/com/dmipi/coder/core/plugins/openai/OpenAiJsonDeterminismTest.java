package com.dmipi.coder.core.plugins.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.llm.ChatMessage;
import com.dmipi.coder.core.domain.llm.ChatRequest;
import com.dmipi.coder.core.domain.llm.ToolCall;
import com.dmipi.coder.core.domain.llm.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenAiJsonDeterminismTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("the same request serializes to identical bytes every time")
    void should_serialize_identically_on_repeat() {
        // Given
        final ChatRequest request = request();

        // When
        final String first = OpenAiJson.writeRequest(mapper, "coder-local", request);
        final String second = OpenAiJson.writeRequest(mapper, "coder-local", request);

        // Then
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("a request rebuilt from the same structured data serializes to the same bytes")
    void should_be_a_pure_function_of_the_structure() {
        // Given: two independently constructed but structurally identical requests
        final String a = OpenAiJson.writeRequest(mapper, "coder-local", request());
        final String b = OpenAiJson.writeRequest(mapper, "coder-local", request());

        // Then
        assertThat(a).isEqualTo(b);
    }

    private static ChatRequest request() {
        return new ChatRequest(
                List.of(
                        ChatMessage.system("You are coder."),
                        ChatMessage.user("read the file"),
                        ChatMessage.assistant("on it", List.of(new ToolCall("c1", "read_file", "{\"path\": \"a.txt\"}"))),
                        ChatMessage.toolResult("c1", "one\ntwo")),
                List.of(
                        new ToolSchema("read_file", "Reads a file.", "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}"),
                        new ToolSchema("edit", "Edits a file.", "{\"type\": \"object\"}")));
    }
}
