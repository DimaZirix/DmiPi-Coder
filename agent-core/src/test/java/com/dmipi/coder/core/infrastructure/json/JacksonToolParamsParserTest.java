package com.dmipi.coder.core.infrastructure.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.dmipi.coder.core.domain.tool.ToolParams;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JacksonToolParamsParserTest {

    private final JacksonToolParamsParser parser = new JacksonToolParamsParser(JsonMapper.builder().build());

    @Test
    @DisplayName("typed accessors read matching values and stay empty on wrong types")
    void should_read_typed_values() {
        // Given
        final ToolParams params = parser.parse("{\"path\": \"a.txt\", \"lines\": 42, \"force\": true, \"names\": [\"x\", \"y\"]}");

        // Then
        assertThat(params.string("path")).contains("a.txt");
        assertThat(params.integer("lines")).contains(42L);
        assertThat(params.bool("force")).contains(true);
        assertThat(params.stringList("names")).contains(List.of("x", "y"));
        assertThat(params.string("lines")).isEmpty();
        assertThat(params.string("missing")).isEmpty();
    }

    @Test
    @DisplayName("the raw JSON of the whole argument object is available for verbatim forwarding")
    void should_expose_the_raw_json() {
        // Given
        final ToolParams params = parser.parse("{\"env\": \"dev4\"}");

        // Then
        assertThat(params.rawJson()).contains("\"env\"").contains("\"dev4\"");
    }

    @Test
    @DisplayName("blank arguments count as an empty object")
    void should_treat_blank_arguments_as_empty() {
        // When / Then
        assertThat(parser.parse("").string("anything")).isEmpty();
    }

    @Test
    @DisplayName("invalid JSON is rejected with a message the model can correct from")
    void should_reject_invalid_json() {
        // When / Then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse("{not json"))
                .withMessageContaining("not valid JSON");
    }

    @Test
    @DisplayName("a non-object root is rejected")
    void should_reject_a_non_object_root() {
        // When / Then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse("[1, 2]"))
                .withMessageContaining("JSON object");
    }
}
