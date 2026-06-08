package com.dmipi.coder.core.plugins.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.llm.ToolSchema;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.infrastructure.files.AnchoredFileSystem;
import com.dmipi.coder.core.infrastructure.json.JacksonToolParamsParser;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MemoryPluginTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);

    @TempDir
    private Path userDirectory;

    @TempDir
    private Path projectDirectory;

    private final RecordingOut out = new RecordingOut();
    private final JacksonToolParamsParser parser = new JacksonToolParamsParser(JsonMapper.builder().build());

    @Test
    @DisplayName("loaded memory rides in the instructions: guidance, then user memory, then project memory")
    void should_load_both_scopes_into_the_instructions() throws IOException {
        // Given
        Files.createDirectories(userDirectory.resolve(".coder"));
        Files.writeString(userDirectory.resolve(".coder/CODER.md"), "Prefer short answers.");
        Files.writeString(projectDirectory.resolve("CODER.md"), "Build with mvn test.");
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        runTurn(client, new ScriptedHil(List.of()), Mode.DEFAULT);

        // Then: general first, specific last
        final String instructions = client.requests().getFirst().messages().getFirst().content();
        assertThat(instructions).contains("Prefer short answers.").contains("Build with mvn test.");
        assertThat(instructions.indexOf("Prefer short answers.")).isLessThan(instructions.indexOf("Build with mvn test."));
    }

    @Test
    @DisplayName("an ecosystem memory file (AGENTS.md) is recognized when the conventional name is absent")
    void should_recognize_ecosystem_file_names() throws IOException {
        // Given
        Files.writeString(projectDirectory.resolve("AGENTS.md"), "Repo-wide rule.");
        final MemoryStore store = store();

        // When / Then
        assertThat(store.load(MemoryScope.PROJECT)).hasValue("Repo-wide rule.");
        assertThat(store.targetLabel(MemoryScope.PROJECT)).isEqualTo("AGENTS.md");
    }

    @Test
    @DisplayName("an @path line is inlined at load; a cyclic or escaping reference stays verbatim")
    void should_inline_imports_depth_limited_and_cycle_safe() throws IOException {
        // Given: memory imports conventions, which imports memory back (a cycle) and reaches outside
        Files.writeString(projectDirectory.resolve("CODER.md"), "Rules:\n@docs/conventions.md");
        Files.createDirectories(projectDirectory.resolve("docs"));
        Files.writeString(projectDirectory.resolve("docs/conventions.md"), "Be terse.\n@../CODER.md\n@../../outside.md");

        // When
        final String loaded = store().load(MemoryScope.PROJECT).orElseThrow();

        // Then
        assertThat(loaded)
                .contains("Rules:")
                .contains("Be terse.")
                .contains("@../CODER.md")
                .contains("@../../outside.md");
    }

    @Test
    @DisplayName("a user-scope save asks even in allow-edits mode, with the diff as preview")
    void should_ask_for_a_user_scope_save_in_allow_edits() {
        // Given: the model saves a user preference; the user approves
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "memory", "{\"action\": \"save\", \"scope\": \"user\", \"content\": \"Never push without asking.\"}"),
                ScriptedClient.textStep("remembered")));
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("allow-once")));

        // When
        runTurn(client, hil, Mode.ALLOW_EDITS);

        // Then: it asked despite allow-edits, previewed a diff, and wrote the conventional user file
        assertThat(hil.asked()).singleElement().satisfies(question ->
                assertThat(question.preview()).contains("+Never push without asking."));
        assertThat(userDirectory.resolve(".coder/CODER.md")).hasContent("Never push without asking.");
    }

    @Test
    @DisplayName("a project-scope save is an ordinary edit: auto-approved in allow-edits")
    void should_auto_approve_a_project_scope_save_in_allow_edits() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "memory", "{\"action\": \"save\", \"scope\": \"project\", \"content\": \"Build with mvn test.\"}"),
                ScriptedClient.textStep("done")));
        final ScriptedHil hil = new ScriptedHil(List.of());

        // When
        runTurn(client, hil, Mode.ALLOW_EDITS);

        // Then
        assertThat(hil.asked()).isEmpty();
        assertThat(projectDirectory.resolve("CODER.md")).hasContent("Build with mvn test.");
    }

    @Test
    @DisplayName("plan mode blocks a save but lets a read through")
    void should_block_saves_but_not_reads_in_plan_mode() {
        // Given: the model tries a save, is blocked, then reads instead
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "memory", "{\"action\": \"save\", \"scope\": \"project\", \"content\": \"x\"}"),
                ScriptedClient.toolCallStep("c2", "memory", "{\"action\": \"read\", \"scope\": \"project\"}"),
                ScriptedClient.textStep("ok")));

        // When
        runTurn(client, new ScriptedHil(List.of()), Mode.PLAN);

        // Then: the save never landed, the read completed
        assertThat(projectDirectory.resolve("CODER.md")).doesNotExist();
        assertThat(out.events())
                .filteredOn(OutEvent.ActivityFinished.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((OutEvent.ActivityFinished) event).display())
                        .isEqualTo(new Display.Text("read project memory")));
    }

    @Test
    @DisplayName("the kinds per call: read is READ, a project save an EDIT, a user save an EXECUTE")
    void should_report_the_kind_per_call() {
        // Given
        final MemoryTool tool = new MemoryTool(store());

        // When / Then
        assertThat(tool.kind(params("{\"action\": \"read\", \"scope\": \"user\"}"))).isEqualTo(ToolKind.READ);
        assertThat(tool.kind(params("{\"action\": \"save\", \"scope\": \"project\", \"content\": \"x\"}"))).isEqualTo(ToolKind.EDIT);
        assertThat(tool.kind(params("{\"action\": \"save\", \"scope\": \"user\", \"content\": \"x\"}"))).isEqualTo(ToolKind.EXECUTE);
        assertThat(tool.defaultPermission(params("{\"action\": \"read\", \"scope\": \"user\"}"))).isEqualTo(PermissionDecision.ALLOW);
        assertThat(tool.defaultPermission(params("{\"action\": \"save\", \"scope\": \"user\", \"content\": \"x\"}"))).isEqualTo(PermissionDecision.ASK);
    }

    @Test
    @DisplayName("malformed calls fail validation with messages the model can correct from")
    void should_reject_malformed_calls() {
        // Given
        final MemoryTool tool = new MemoryTool(store());

        // When / Then
        assertThat(tool.validate(params("{}"))).hasValueSatisfying(error ->
                assertThat(error).contains("'action'"));
        assertThat(tool.validate(params("{\"action\": \"save\", \"scope\": \"everywhere\"}"))).hasValueSatisfying(error ->
                assertThat(error).contains("'scope'"));
        assertThat(tool.validate(params("{\"action\": \"save\", \"scope\": \"user\"}"))).hasValueSatisfying(error ->
                assertThat(error).contains("'content'"));
        assertThat(tool.validate(params("{\"action\": \"read\", \"scope\": \"project\"}"))).isEmpty();
    }

    @Test
    @DisplayName("without memory files the plugin still registers the tool and its guidance")
    void should_work_from_a_clean_slate() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        runTurn(client, new ScriptedHil(List.of()), Mode.DEFAULT);

        // Then
        assertThat(client.requests().getFirst().tools()).extracting(ToolSchema::name).contains("memory");
        assertThat(client.requests().getFirst().messages().getFirst().content()).contains("## Memory");
    }

    private void runTurn(final ScriptedClient client, final ScriptedHil hil, final Mode mode) {
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(hil)
                .model(MODEL)
                .mode(mode)
                .userDirectory(userDirectory)
                .projectDirectory(projectDirectory)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new MemoryPlugin())
                .build()) {
            coder.runTurn("go", new CancelToken());
        }
    }

    private MemoryStore store() {
        return new MemoryStore(new AnchoredFileSystem(userDirectory), new AnchoredFileSystem(projectDirectory));
    }

    private ToolParams params(final String json) {
        return parser.parse(json);
    }

    private static Plugin providerPlugin(final ScriptedClient client) {
        return new Plugin() {

            @Override
            public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
                registrar.registerProtocolProvider(new ProtocolProvider() {

                    @Override
                    public String protocol() {
                        return "scripted";
                    }

                    @Override
                    public LlmClient connect(final ModelDeclaration declaration) {
                        return client;
                    }
                });
            }
        };
    }
}
