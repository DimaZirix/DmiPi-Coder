package com.dmipi.coder.core.domain.hil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerTest {

    @Test
    @DisplayName("an answer requires at least one selected id — 'none' is an explicit option, not an empty answer")
    void should_reject_an_empty_selection() {
        // When / Then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Answer(List.of()))
                .withMessageContaining("at least one");
    }

    @Test
    @DisplayName("a blank id is rejected")
    void should_reject_a_blank_id() {
        // When / Then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Answer(List.of(" ")))
                .withMessageContaining("blank");
    }

    @Test
    @DisplayName("the selection is an immutable copy — later mutation of the source does not leak in")
    void should_copy_the_selection_defensively() {
        // Given
        final List<String> source = new ArrayList<>(List.of("once"));

        // When
        final Answer answer = new Answer(source);
        source.add("deny");

        // Then
        assertThat(answer.selected()).containsExactly("once");
    }
}
