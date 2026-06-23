package com.dmipi.coder.core.plugins.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.infrastructure.files.AnchoredFileSystem;
import com.dmipi.coder.core.infrastructure.json.JacksonToolParamsParser;
import com.dmipi.coder.core.plugin.FileSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ReadBeforeEditTest {

    @TempDir
    private Path project;

    private final JacksonToolParamsParser parser = new JacksonToolParamsParser(JsonMapper.builder().build());

    @Test
    @DisplayName("editing an existing file that was not read this session is refused with a correctable message")
    void should_refuse_editing_an_unread_file() throws IOException {
        // Given
        Files.writeString(project.resolve("f.txt"), "hello world");
        final ReadTracker tracker = new ReadTracker();
        final FileSystem files = new AnchoredFileSystem(project);
        final EditTool edit = new EditTool(files, tracker);

        // When: edit without reading first
        final ToolResult refused = edit.execute(params("{\"path\": \"f.txt\", \"old_string\": \"world\", \"new_string\": \"there\"}"), new CancelToken());

        // Then
        assertThat(refused).isInstanceOf(ToolResult.Failure.class);
        assertThat(refused.llmContent()).contains("Read f.txt").contains("read_file before editing");
        assertThat(project.resolve("f.txt")).hasContent("hello world");
    }

    @Test
    @DisplayName("after read_file records the read, the edit succeeds; a follow-up edit is also allowed")
    void should_allow_editing_after_a_read() throws IOException {
        // Given
        Files.writeString(project.resolve("f.txt"), "hello world");
        final ReadTracker tracker = new ReadTracker();
        final FileSystem files = new AnchoredFileSystem(project);
        new ReadFileTool(files, tracker).execute(params("{\"path\": \"f.txt\"}"), new CancelToken());
        final EditTool edit = new EditTool(files, tracker);

        // When
        final ToolResult first = edit.execute(params("{\"path\": \"f.txt\", \"old_string\": \"world\", \"new_string\": \"there\"}"), new CancelToken());
        final ToolResult second = edit.execute(params("{\"path\": \"f.txt\", \"old_string\": \"hello\", \"new_string\": \"hi\"}"), new CancelToken());

        // Then
        assertThat(first).isInstanceOf(ToolResult.Success.class);
        assertThat(second).isInstanceOf(ToolResult.Success.class);
        assertThat(project.resolve("f.txt")).hasContent("hi there");
    }

    @Test
    @DisplayName("creating a new file is never gated (nothing to have read)")
    void should_not_gate_creating_a_new_file() {
        // Given
        final ReadTracker tracker = new ReadTracker();
        final FileSystem files = new AnchoredFileSystem(project);

        // When: edit is not for creation, but write_file creating a new file is unaffected by the tracker
        final ToolResult created = new WriteFileTool(files).execute(params("{\"path\": \"new.txt\", \"content\": \"fresh\"}"), new CancelToken());

        // Then
        assertThat(created).isInstanceOf(ToolResult.Success.class);
        assertThat(project.resolve("new.txt")).hasContent("fresh");

        // And an edit to a not-yet-existing file falls through to the normal not-found path, not the gate
        final ToolResult missing = new EditTool(files, tracker).execute(params("{\"path\": \"absent.txt\", \"old_string\": \"a\", \"new_string\": \"b\"}"), new CancelToken());
        assertThat(missing).isInstanceOf(ToolResult.Failure.class);
        assertThat(missing.llmContent()).doesNotContain("read_file before editing");
    }

    @Test
    @DisplayName("without a shared tracker the gate is off — the default plugins do not block edits")
    void should_be_off_without_a_tracker() throws IOException {
        // Given
        Files.writeString(project.resolve("f.txt"), "hello world");

        // When: edit with no tracker (the no-arg default)
        final ToolResult result = new EditTool(new AnchoredFileSystem(project)).execute(
                params("{\"path\": \"f.txt\", \"old_string\": \"world\", \"new_string\": \"there\"}"), new CancelToken());

        // Then
        assertThat(result).isInstanceOf(ToolResult.Success.class);
    }

    private ToolParams params(final String json) {
        return parser.parse(json);
    }
}
