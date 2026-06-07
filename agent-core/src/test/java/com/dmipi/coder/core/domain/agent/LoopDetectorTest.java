package com.dmipi.coder.core.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.llm.ToolCall;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoopDetectorTest {

    @Test
    @DisplayName("the same call with the same arguments three times in a row is a loop")
    void should_detect_three_identical_calls() {
        // Given
        final LoopDetector detector = new LoopDetector();
        final ToolCall call = new ToolCall("1", "grep", "{\"pattern\": \"x\"}");

        // When / Then
        assertThat(detector.repetitionDetected(List.of(call))).isFalse();
        assertThat(detector.repetitionDetected(List.of(call))).isFalse();
        assertThat(detector.repetitionDetected(List.of(call))).isTrue();
    }

    @Test
    @DisplayName("the same tool with different arguments is not a loop")
    void should_not_flag_different_arguments() {
        // Given
        final LoopDetector detector = new LoopDetector();

        // When / Then
        assertThat(detector.repetitionDetected(List.of(new ToolCall("1", "grep", "{\"pattern\": \"a\"}")))).isFalse();
        assertThat(detector.repetitionDetected(List.of(new ToolCall("2", "grep", "{\"pattern\": \"b\"}")))).isFalse();
        assertThat(detector.repetitionDetected(List.of(new ToolCall("3", "grep", "{\"pattern\": \"c\"}")))).isFalse();
    }

    @Test
    @DisplayName("alternating calls are not a loop")
    void should_not_flag_alternating_calls() {
        // Given
        final LoopDetector detector = new LoopDetector();
        final ToolCall first = new ToolCall("1", "read", "{}");
        final ToolCall second = new ToolCall("2", "grep", "{}");

        // When / Then
        assertThat(detector.repetitionDetected(List.of(first, second))).isFalse();
        assertThat(detector.repetitionDetected(List.of(first, second))).isFalse();
        assertThat(detector.repetitionDetected(List.of(first, second))).isFalse();
    }
}
