package com.dmipi.coder.core.plugins.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import com.dmipi.coder.core.infrastructure.files.AnchoredFileSystem;
import com.dmipi.coder.core.infrastructure.json.JacksonToolParamsParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SearchToolsTest {

    @TempDir
    private Path project;

    private final JacksonToolParamsParser parser = new JacksonToolParamsParser(JsonMapper.builder().build());

    @BeforeEach
    void tree() throws IOException {
        Files.createDirectories(project.resolve("src/main"));
        Files.writeString(project.resolve("src/main/App.java"), "class App {\n  void run() {}\n}");
        Files.writeString(project.resolve("src/main/Util.java"), "class Util {\n  static int add(int a, int b) { return a + b; }\n}");
        Files.writeString(project.resolve("README.md"), "# Title\nApp docs");
    }

    @Test
    @DisplayName("glob finds files by pattern, sorted, and reports no matches cleanly")
    void should_find_files_by_glob() {
        // Given
        final GlobTool tool = new GlobTool(new AnchoredFileSystem(project));

        // When
        final ToolResult java = tool.execute(params("{\"pattern\": \"**/*.java\"}"), new CancelToken());

        // Then: self-explaining header naming the count and pattern
        assertThat(java.llmContent())
                .startsWith("Found 2 file(s) matching \"**/*.java\":")
                .contains("App.java").contains("Util.java").doesNotContain("README");

        // When / Then: no matches
        assertThat(tool.execute(params("{\"pattern\": \"**/*.py\"}"), new CancelToken()).llmContent()).contains("No files match");
    }

    @Test
    @DisplayName("grep reports matching lines as path:line: text")
    void should_grep_matching_lines() {
        // Given
        final GrepTool tool = new GrepTool(new AnchoredFileSystem(project));

        // When
        final ToolResult result = tool.execute(params("{\"pattern\": \"class \\\\w+\"}"), new CancelToken());

        // Then: a header naming the count and pattern, then the path:line: text hits
        assertThat(result.llmContent())
                .startsWith("Found 2 matching line(s) for")
                .contains("App.java:1: class App").contains("Util.java:1: class Util");
    }

    @Test
    @DisplayName("grep is case-insensitive by default and case-sensitive on request")
    void should_honor_case_sensitivity() {
        // Given
        final GrepTool tool = new GrepTool(new AnchoredFileSystem(project));

        // When / Then: default is insensitive
        assertThat(tool.execute(params("{\"pattern\": \"CLASS app\"}"), new CancelToken()).llmContent()).contains("App.java:1");
        // And case_sensitive:true respects case
        assertThat(tool.execute(params("{\"pattern\": \"CLASS app\", \"case_sensitive\": true}"), new CancelToken()).llmContent())
                .isEqualTo("No matches for \"CLASS app\".");
    }

    @Test
    @DisplayName("grep caps at the requested limit and says so")
    void should_respect_the_limit() {
        // Given a pattern that matches many lines
        final GrepTool tool = new GrepTool(new AnchoredFileSystem(project));

        // When
        final ToolResult limited = tool.execute(params("{\"pattern\": \"class \\\\w+\", \"limit\": 1}"), new CancelToken());

        // Then
        assertThat(limited.llmContent()).contains("Found 1+ matching").contains("stopped at 1 matches");
    }

    @Test
    @DisplayName("grep narrows by glob and finds nothing outside it")
    void should_grep_within_a_glob() {
        // Given
        final GrepTool tool = new GrepTool(new AnchoredFileSystem(project));

        // When: search only markdown
        final ToolResult result = tool.execute(params("{\"pattern\": \"App\", \"glob\": \"**/*.md\"}"), new CancelToken());

        // Then
        assertThat(result.llmContent()).contains("README.md:2: App docs").doesNotContain(".java");
    }

    @Test
    @DisplayName("grep reports no matches cleanly and rejects an invalid regex at validation")
    void should_handle_no_matches_and_bad_regex() {
        // Given
        final GrepTool tool = new GrepTool(new AnchoredFileSystem(project));

        // Then: no matches
        assertThat(tool.execute(params("{\"pattern\": \"zzzznotfound\"}"), new CancelToken()).llmContent()).contains("No matches");

        // Then: invalid regex caught before execution
        assertThat(tool.validate(params("{\"pattern\": \"[unclosed\"}")))
                .hasValueSatisfying(message -> assertThat(message).contains("valid regular expression"));
    }

    @Test
    @DisplayName("search never enters VCS internals or build output")
    void should_prune_noise_directories() throws IOException {
        // Given: matching text hidden in .git and target
        Files.createDirectories(project.resolve(".git"));
        Files.writeString(project.resolve(".git/config"), "class App in git config");
        Files.createDirectories(project.resolve("target/generated"));
        Files.writeString(project.resolve("target/generated/App.java"), "class App generated");

        // When
        final ToolResult grep = new GrepTool(new AnchoredFileSystem(project)).execute(params("{\"pattern\": \"class App\"}"), new CancelToken());
        final ToolResult glob = new GlobTool(new AnchoredFileSystem(project)).execute(params("{\"pattern\": \"**/*.java\"}"), new CancelToken());

        // Then
        assertThat(grep.llmContent()).doesNotContain(".git").doesNotContain("target");
        assertThat(glob.llmContent()).doesNotContain("target");
    }

    @Test
    @DisplayName("grep skips a file over the size cap instead of loading it into memory")
    void should_skip_an_oversized_file() throws IOException {
        // Given: a >1 MB file whose content would match
        Files.writeString(project.resolve("huge.log"), "class App match\n" + "x".repeat(1_100_000));

        // When
        final ToolResult result = new GrepTool(new AnchoredFileSystem(project)).execute(params("{\"pattern\": \"class App\", \"glob\": \"**/*.log\"}"), new CancelToken());

        // Then
        assertThat(result.llmContent()).contains("No matches");
    }

    private ToolParams params(final String json) {
        return parser.parse(json);
    }
}
