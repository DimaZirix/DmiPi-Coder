package com.dmipi.coder.core.domain.hil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuestionTest {

    private static final List<Option> ALLOW_OPTIONS = List.of(new Option("once", "Allow once"), new Option("always", "Always allow this session"), new Option("deny", "Deny"));

    @Test
    @DisplayName("a question carries its text, preview, kind and options unchanged")
    void should_hold_the_question_as_given() {
        // When
        final Question question = new Question("Allow the edit?", "+ line", QuestionKind.OPTION_LIST, ALLOW_OPTIONS);

        // Then
        assertThat(question.question()).isEqualTo("Allow the edit?");
        assertThat(question.preview()).isEqualTo("+ line");
        assertThat(question.options()).hasSize(3);
    }

    @Test
    @DisplayName("a question requires a non-blank text")
    void should_reject_a_blank_question_text() {
        // When / Then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Question(" ", "", QuestionKind.OPTION_LIST, ALLOW_OPTIONS))
                .withMessageContaining("non-blank");
    }

    @Test
    @DisplayName("a question requires at least two options")
    void should_reject_fewer_than_two_options() {
        // When / Then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Question("Allow?", "", QuestionKind.OPTION_LIST, List.of(new Option("only", "Only choice"))))
                .withMessageContaining("at least 2");
    }

    @Test
    @DisplayName("option ids must be unique within a question")
    void should_reject_duplicate_option_ids() {
        // Given
        final List<Option> duplicated = List.of(new Option("same", "First"), new Option("same", "Second"));

        // When / Then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Question("Allow?", "", QuestionKind.OPTION_LIST, duplicated))
                .withMessageContaining("unique");
    }

    @Test
    @DisplayName("an option-list answer with exactly one known id is accepted")
    void should_accept_a_single_selection_on_an_option_list() {
        // Given
        final Question question = new Question("Allow?", "", QuestionKind.OPTION_LIST, ALLOW_OPTIONS);

        // When / Then
        assertThat(question.rejection(Answer.of("once"))).isEmpty();
    }

    @Test
    @DisplayName("an option-list answer with two selections is rejected")
    void should_reject_multiple_selections_on_an_option_list() {
        // Given
        final Question question = new Question("Allow?", "", QuestionKind.OPTION_LIST, ALLOW_OPTIONS);

        // When
        final var rejection = question.rejection(new Answer(List.of("once", "deny")));

        // Then
        assertThat(rejection).hasValueSatisfying(message -> assertThat(message).contains("exactly one"));
    }

    @Test
    @DisplayName("an answer referring to an id outside the offered set is rejected")
    void should_reject_an_unknown_option_id() {
        // Given
        final Question question = new Question("Allow?", "", QuestionKind.OPTION_LIST, ALLOW_OPTIONS);

        // When
        final var rejection = question.rejection(Answer.of("free-text"));

        // Then
        assertThat(rejection).hasValueSatisfying(message -> assertThat(message).contains("free-text"));
    }

    @Test
    @DisplayName("a checkbox-list answer may select several options")
    void should_accept_several_selections_on_a_checkbox_list() {
        // Given
        final Question question = new Question("Which environments?", "", QuestionKind.CHECKBOX_LIST, List.of(new Option("dev4", "dev4"), new Option("dev5", "dev5"), new Option("uat", "uat")));

        // When / Then
        assertThat(question.rejection(new Answer(List.of("dev4", "uat")))).isEmpty();
    }

    @Test
    @DisplayName("selecting the same option twice is rejected")
    void should_reject_a_duplicate_selection() {
        // Given
        final Question question = new Question("Which environments?", "", QuestionKind.CHECKBOX_LIST, List.of(new Option("dev4", "dev4"), new Option("dev5", "dev5")));

        // When
        final var rejection = question.rejection(new Answer(List.of("dev4", "dev4")));

        // Then
        assertThat(rejection).hasValueSatisfying(message -> assertThat(message).contains("more than once"));
    }
}
