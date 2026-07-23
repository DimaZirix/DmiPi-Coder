package com.dmipi.coder.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.hil.Option;
import com.dmipi.coder.core.domain.hil.Question;
import com.dmipi.coder.core.domain.hil.QuestionKind;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConsoleHilTest {

    private final StringWriter out = new StringWriter();

    @Test
    @DisplayName("an option-list question maps the chosen number back to the asker's id")
    void should_pick_one_option_by_number() {
        // Given
        final Question question = new Question("Allow?", "rm -rf build", QuestionKind.OPTION_LIST,
                List.of(new Option("allow-once", "Allow"), new Option("deny", "Deny")));

        // When: the user types "2"
        final Answer answer = hil("2\n").ask(question);

        // Then
        assertThat(answer.selected()).containsExactly("deny");
        assertThat(out.toString()).contains("rm -rf build").contains("1) Allow").contains("2) Deny");
    }

    @Test
    @DisplayName("a bad selection is re-prompted until it fits the shape")
    void should_reprompt_until_valid() {
        // Given
        final Question question = new Question("Pick one", "", QuestionKind.OPTION_LIST,
                List.of(new Option("a", "A"), new Option("b", "B")));

        // When: junk, then two-when-one-allowed, then a valid single pick
        final Answer answer = hil("nope\n1,2\n1\n").ask(question);

        // Then
        assertThat(answer.selected()).containsExactly("a");
        assertThat(out.toString()).contains("single valid number");
    }

    @Test
    @DisplayName("input ending while a question is open fails loudly instead of looping forever")
    void should_fail_loudly_when_input_ends_mid_question() {
        // Given: a question and a stdin that is already at end-of-file
        final Question question = new Question("Allow the edit?", "", QuestionKind.OPTION_LIST,
                List.of(new Option("allow-once", "Allow"), new Option("deny", "Deny")));

        // When / Then: no spin, no silent answer — an error naming the open question
        assertThatIllegalStateException()
                .isThrownBy(() -> hil("").ask(question))
                .withMessageContaining("Allow the edit?");
    }

    @Test
    @DisplayName("input ending after invalid attempts still fails loudly, not with an infinite re-prompt")
    void should_fail_loudly_when_input_ends_after_bad_attempts() {
        // Given
        final Question question = new Question("Pick", "", QuestionKind.OPTION_LIST,
                List.of(new Option("a", "A"), new Option("b", "B")));

        // When / Then: one junk line, then EOF
        assertThatIllegalStateException()
                .isThrownBy(() -> hil("junk\n").ask(question))
                .withMessageContaining("Pick");
    }

    @Test
    @DisplayName("a checkbox question accepts several comma-separated numbers")
    void should_pick_several_for_a_checkbox_question() {
        // Given
        final Question question = new Question("Which?", "", QuestionKind.CHECKBOX_LIST,
                List.of(new Option("x", "X"), new Option("y", "Y"), new Option("z", "Z")));

        // When
        final Answer answer = hil("1, 3\n").ask(question);

        // Then
        assertThat(answer.selected()).containsExactly("x", "z");
        assertThat(question.rejection(answer)).isEmpty();
    }

    private ConsoleHil hil(final String typed) {
        return new ConsoleHil(new BufferedReader(new StringReader(typed)), new PrintWriter(out));
    }
}
