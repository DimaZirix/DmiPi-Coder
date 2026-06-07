package com.dmipi.coder.core.domain.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelRegistryTest {

    private static final LlmClient CLIENT = (request, cancel, events) -> {
    };
    private static final ProtocolProvider SCRIPTED = provider("scripted");

    private static final ModelDeclaration STRONG = new ModelDeclaration("big", "scripted", "http://x", Tier.STRONG, 32_000);
    private static final ModelDeclaration FAST = new ModelDeclaration("small", "scripted", "http://x", Tier.FAST, 8_000);
    private static final ModelDeclaration BALANCED = new ModelDeclaration("mid", "scripted", "http://x", Tier.BALANCED, 16_000);

    @Test
    @DisplayName("a declared model whose protocol no provider speaks is a startup error naming both")
    void should_fail_startup_for_an_unspoken_protocol() {
        // When / Then
        assertThatIllegalStateException()
                .isThrownBy(() -> new ModelRegistry(List.of(new ModelDeclaration("m", "anthropic", "http://x", Tier.FAST, 8_000)), List.of(SCRIPTED)))
                .withMessageContaining("m")
                .withMessageContaining("anthropic");
    }

    @Test
    @DisplayName("declaring the same model name twice is rejected")
    void should_reject_a_duplicate_model_name() {
        // When / Then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelRegistry(List.of(FAST, FAST), List.of(SCRIPTED)))
                .withMessageContaining("small");
    }

    @Test
    @DisplayName("selection: fastest picks the cheapest tier, strongest the most capable")
    void should_select_by_tier() {
        // Given
        final ModelRegistry registry = new ModelRegistry(List.of(STRONG, FAST, BALANCED), List.of(SCRIPTED));

        // Then
        assertThat(registry.fastest().declaration().name()).isEqualTo("small");
        assertThat(registry.strongest().declaration().name()).isEqualTo("big");
    }

    @Test
    @DisplayName("at-least picks the cheapest model meeting the bar")
    void should_select_the_cheapest_model_meeting_the_bar() {
        // Given
        final ModelRegistry registry = new ModelRegistry(List.of(STRONG, FAST, BALANCED), List.of(SCRIPTED));

        // Then
        assertThat(registry.atLeast(Tier.BALANCED).declaration().name()).isEqualTo("mid");
        assertThat(registry.atLeast(Tier.STRONG).declaration().name()).isEqualTo("big");
    }

    @Test
    @DisplayName("when no model meets the bar, the strongest available is used")
    void should_fall_back_to_the_strongest_when_none_meets_the_bar() {
        // Given: only fast models
        final ModelRegistry registry = new ModelRegistry(List.of(FAST), List.of(SCRIPTED));

        // Then
        assertThat(registry.atLeast(Tier.STRONG).declaration().name()).isEqualTo("small");
    }

    @Test
    @DisplayName("the active model starts as the first declared and is switchable; an unknown name is rejected")
    void should_manage_the_active_model() {
        // Given
        final ModelRegistry registry = new ModelRegistry(List.of(STRONG, FAST), List.of(SCRIPTED));

        // Then
        assertThat(registry.active().declaration().name()).isEqualTo("big");

        // When
        registry.activate("small");

        // Then
        assertThat(registry.active().declaration().name()).isEqualTo("small");
        assertThatIllegalArgumentException().isThrownBy(() -> registry.activate("nope")).withMessageContaining("nope");
    }

    private static ProtocolProvider provider(final String protocol) {
        return new ProtocolProvider() {

            @Override
            public String protocol() {
                return protocol;
            }

            @Override
            public LlmClient connect(final ModelDeclaration declaration) {
                return CLIENT;
            }
        };
    }
}
