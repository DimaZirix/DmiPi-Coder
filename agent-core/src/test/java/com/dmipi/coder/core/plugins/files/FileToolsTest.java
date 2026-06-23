package com.dmipi.coder.core.plugins.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.infrastructure.files.AnchoredFileSystem;
import com.dmipi.coder.core.infrastructure.json.JacksonToolParamsParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class FileToolsTest {

    @TempDir
    private Path project;

    private final JacksonToolParamsParser parser = new JacksonToolParamsParser(JsonMapper.builder().build());

    @Test
    @DisplayName("read_file returns the content, and a window of a larger file with a continuation note")
    void should_read_whole_and_windowed() throws IOException {
        // Given
        Files.writeString(project.resolve("f.txt"), "one\ntwo\nthree\nfour");
        final ReadFileTool tool = new ReadFileTool(files());

        // When / Then: whole file, numbered cat-style with a banner
        assertThat(tool.execute(params("{\"path\": \"f.txt\"}"), new CancelToken()).llmContent())
                .isEqualTo("[Showing lines 1-4 of 4 total lines. Use 'offset' and 'limit' to read more.]\n"
                        + "     1\tone\n     2\ttwo\n     3\tthree\n     4\tfour");

        // When / Then: a window carries the banner and the right line numbers
        final ToolResult window = tool.execute(params("{\"path\": \"f.txt\", \"offset\": 2, \"limit\": 2}"), new CancelToken());
        assertThat(window.llmContent()).contains("Showing lines 2-3 of 4").contains("     2\ttwo").contains("     3\tthree");
    }

    @Test
    @DisplayName("read_file fails cleanly on a missing file and on an escaping path")
    void should_fail_reading_missing_or_escaping() {
        // Given
        final ReadFileTool tool = new ReadFileTool(files());

        // Then
        assertThat(tool.execute(params("{\"path\": \"missing.txt\"}"), new CancelToken())).isInstanceOf(ToolResult.Failure.class);
        assertThat(tool.execute(params("{\"path\": \"../outside\"}"), new CancelToken()).llmContent()).contains("escapes");
    }

    @Test
    @DisplayName("list_directory lists sorted entries with directory markers")
    void should_list_a_directory() throws IOException {
        // Given
        Files.createDirectory(project.resolve("src"));
        Files.writeString(project.resolve("pom.xml"), "");

        // When
        final ToolResult result = new ListDirectoryTool(files()).execute(params("{}"), new CancelToken());

        // Then
        assertThat(result.llmContent()).isEqualTo("Directory listing for .:\npom.xml\nsrc/");
    }

    @Test
    @DisplayName("write_file asks by default, previews the content, and writes it")
    void should_write_a_file() {
        // Given
        final WriteFileTool tool = new WriteFileTool(files());
        final ToolParams params = params("{\"path\": \"new/hello.txt\", \"content\": \"hi there\"}");

        // Then: baseline, and the preview is an all-additions unified diff for a new file
        assertThat(tool.defaultPermission(params)).isEqualTo(PermissionDecision.ASK);
        assertThat(tool.preview(params)).contains("+hi there");

        // When
        final ToolResult result = tool.execute(params, new CancelToken());

        // Then
        assertThat(result).isInstanceOf(ToolResult.Success.class);
        assertThat(project.resolve("new/hello.txt")).hasContent("hi there");
    }

    @Test
    @DisplayName("edit replaces a unique match and previews the change as a unified diff")
    void should_edit_a_unique_match() throws IOException {
        // Given
        Files.writeString(project.resolve("f.txt"), "hello world");
        final EditTool tool = new EditTool(files());
        final ToolParams params = params("{\"path\": \"f.txt\", \"old_string\": \"world\", \"new_string\": \"there\"}");

        // Then: the preview is the real unified diff of what would change
        assertThat(tool.preview(params)).contains("-hello world").contains("+hello there");

        // When
        final ToolResult result = tool.execute(params, new CancelToken());

        // Then: the result echoes the edited region so the model can self-verify
        assertThat(result).isInstanceOf(ToolResult.Success.class);
        assertThat(result.llmContent()).contains("has been updated").contains("     1\thello there");
        assertThat(project.resolve("f.txt")).hasContent("hello there");
    }

    @Test
    @DisplayName("edit tolerates cat-style line numbers pasted from read_file, and leaves short digit+tab lines alone")
    void should_strip_pasted_line_numbers() throws IOException {
        // Given: a multi-line file, and old_string pasted WITH read_file's numbering
        Files.writeString(project.resolve("f.txt"), "alpha\nbeta\ngamma");
        final EditTool tool = new EditTool(files());
        final ToolParams numbered = params("{\"path\": \"f.txt\", \"old_string\": \"     2\\tbeta\", \"new_string\": \"     2\\tBETA\"}");

        // When
        final ToolResult result = tool.execute(numbered, new CancelToken());

        // Then: the line-number prefix is stripped from both strings, so the edit lands cleanly
        assertThat(result).isInstanceOf(ToolResult.Success.class);
        assertThat(project.resolve("f.txt")).hasContent("alpha\nBETA\ngamma");

        // And a genuine short "digits+tab" line (field under 6 wide) is NOT mis-stripped
        Files.writeString(project.resolve("g.txt"), "42\tvalue");
        final ToolResult tsv = new EditTool(files()).execute(
                params("{\"path\": \"g.txt\", \"old_string\": \"42\\tvalue\", \"new_string\": \"42\\tchanged\"}"), new CancelToken());
        assertThat(tsv).isInstanceOf(ToolResult.Success.class);
        assertThat(project.resolve("g.txt")).hasContent("42\tchanged");
    }

    @Test
    @DisplayName("edit refuses a missing match and an ambiguous match, with counts")
    void should_refuse_missing_and_ambiguous_matches() throws IOException {
        // Given
        Files.writeString(project.resolve("f.txt"), "a b a");
        final EditTool tool = new EditTool(files());

        // Then: not found
        assertThat(tool.execute(params("{\"path\": \"f.txt\", \"old_string\": \"zz\", \"new_string\": \"y\"}"), new CancelToken()).llmContent()).contains("not found");

        // Then: ambiguous
        assertThat(tool.execute(params("{\"path\": \"f.txt\", \"old_string\": \"a\", \"new_string\": \"y\"}"), new CancelToken()).llmContent()).contains("2 times");
    }

    @Test
    @DisplayName("edit with replace_all replaces every occurrence")
    void should_replace_all_occurrences() throws IOException {
        // Given
        Files.writeString(project.resolve("f.txt"), "a b a");

        // When
        final ToolResult result = new EditTool(files()).execute(params("{\"path\": \"f.txt\", \"old_string\": \"a\", \"new_string\": \"c\", \"replace_all\": true}"), new CancelToken());

        // Then
        assertThat(result).isInstanceOf(ToolResult.Success.class);
        assertThat(project.resolve("f.txt")).hasContent("c b c");
    }

    @Test
    @DisplayName("edit validation rejects identical old and new strings")
    void should_reject_identical_strings() {
        // When / Then
        assertThat(new EditTool(files()).validate(params("{\"path\": \"f\", \"old_string\": \"same\", \"new_string\": \"same\"}")))
                .hasValueSatisfying(message -> assertThat(message).contains("identical"));
    }

    @Test
    @DisplayName("edit matches and preserves CRLF files even though the model composes strings with \\n")
    void should_edit_a_crlf_file() throws IOException {
        // Given: a CRLF file, and an old_string spanning a line break as the model would write it
        Files.writeString(project.resolve("f.txt"), "hello\r\nworld\r\n");

        // When
        final ToolResult result = new EditTool(files()).execute(params("{\"path\": \"f.txt\", \"old_string\": \"hello\\nworld\", \"new_string\": \"bye\\nworld\"}"), new CancelToken());

        // Then: the edit applied and the file kept its CRLF endings
        assertThat(result).isInstanceOf(ToolResult.Success.class);
        assertThat(Files.readString(project.resolve("f.txt"))).isEqualTo("bye\r\nworld\r\n");
    }

    @Test
    @DisplayName("an absurdly large offset fails cleanly instead of wrapping into a crash")
    void should_reject_a_huge_offset_cleanly() throws IOException {
        // Given
        Files.writeString(project.resolve("f.txt"), "one\ntwo");

        // When
        final ToolResult result = new ReadFileTool(files()).execute(params("{\"path\": \"f.txt\", \"offset\": 6000000000}"), new CancelToken());

        // Then
        assertThat(result).isInstanceOf(ToolResult.Failure.class);
        assertThat(result.llmContent()).contains("past the end");
    }

    @Test
    @DisplayName("the preview of an edit that would fail says so instead of showing an unapplicable diff")
    void should_preview_a_failing_edit_honestly() throws IOException {
        // Given
        Files.writeString(project.resolve("f.txt"), "a b a");
        final EditTool tool = new EditTool(files());

        // Then: ambiguous without replace_all, and not-found, both announce the failure
        assertThat(tool.preview(params("{\"path\": \"f.txt\", \"old_string\": \"a\", \"new_string\": \"c\"}"))).contains("will fail without replace_all");
        assertThat(tool.preview(params("{\"path\": \"f.txt\", \"old_string\": \"zz\", \"new_string\": \"c\"}"))).contains("was not found");
    }

    private AnchoredFileSystem files() {
        return new AnchoredFileSystem(project);
    }

    private ToolParams params(final String json) {
        return parser.parse(json);
    }
}
