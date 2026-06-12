package com.dmipi.coder.core.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PromptAssemblerTest {

    @Test
    @DisplayName("sections join in order with a blank line between them")
    void should_join_sections_in_order() {
        // When
        final String assembled = new PromptAssembler()
                .add("core")
                .addAll(List.of("memory section", "planning section"))
                .assemble();

        // Then
        assertThat(assembled).isEqualTo("core\n\nmemory section\n\nplanning section");
    }

    @Test
    @DisplayName("blank and null sections leave no trace")
    void should_drop_blank_sections() {
        // When
        final String assembled = new PromptAssembler()
                .add("")
                .add(null)
                .add("only this")
                .add("   ")
                .assemble();

        // Then
        assertThat(assembled).isEqualTo("only this");
    }

    @Test
    @DisplayName("reproduces the legacy instructions-then-sections composition byte for byte")
    void should_match_the_legacy_formula() {
        // Given: the exact cases the old inline formula handled
        assertThat(assemble("core", List.of("a", "b"))).isEqualTo(legacy("core", List.of("a", "b")));
        assertThat(assemble("", List.of("a", "b"))).isEqualTo(legacy("", List.of("a", "b")));
        assertThat(assemble("core", List.of())).isEqualTo(legacy("core", List.of()));
        assertThat(assemble("", List.of())).isEqualTo(legacy("", List.of()));
    }

    private static String assemble(final String instructions, final List<String> sections) {
        return new PromptAssembler().add(instructions).addAll(sections).assemble();
    }

    /** The pre-A0 inline composition, kept here as the oracle. */
    private static String legacy(final String instructions, final List<String> sectionList) {
        final String sections = String.join("\n\n", sectionList);
        if (sections.isBlank()) {
            return instructions;
        }
        return instructions.isBlank() ? sections : instructions + "\n\n" + sections;
    }
}
