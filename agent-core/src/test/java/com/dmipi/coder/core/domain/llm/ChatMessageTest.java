package com.dmipi.coder.core.domain.llm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatMessageTest {

    private static final ToolCall CALL = new ToolCall("c1", "read_file", "{}");

    @Test
    @DisplayName("the documented shape is enforced: tool calls only on assistant, toolCallId only (and always) on tool results")
    void should_enforce_role_shape_invariants() {
        // When / Then: the factories build every legal shape
        assertThatCode(() -> ChatMessage.assistant("using a tool", List.of(CALL))).doesNotThrowAnyException();
        assertThatCode(() -> ChatMessage.toolResult("c1", "result")).doesNotThrowAnyException();

        // And the canonical constructor refuses each illegal one
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatMessage(Role.USER, "x", List.of(CALL), ""))
                .withMessageContaining("assistant");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatMessage(Role.USER, "x", List.of(), "c1"))
                .withMessageContaining("tool-result");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatMessage(Role.TOOL, "x", List.of(), ""))
                .withMessageContaining("toolCallId");
    }
}
