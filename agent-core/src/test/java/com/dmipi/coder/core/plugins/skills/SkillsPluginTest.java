package com.dmipi.coder.core.plugins.skills;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.llm.ToolSchema;
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

class SkillsPluginTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);

    @TempDir
    private Path userDirectory;

    @TempDir
    private Path projectDirectory;

    private final RecordingOut out = new RecordingOut();

    @Test
    @DisplayName("the skill list rides in the tool description; loading a skill returns its full instructions")
    void should_list_skills_and_return_instructions() throws IOException {
        // Given: a user skill and a project skill, and the model loading one
        writeSkill(userDirectory, "deploy", """
                ---
                name: deploy
                description: Deploy the app safely.
                ---
                Step 1: run the tests. Step 2: ship it.""");
        writeSkill(projectDirectory, "review", """
                ---
                name: review
                description: Review a change.
                ---
                Check correctness first.""");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "skill", "{\"name\": \"deploy\"}"),
                ScriptedClient.textStep("loaded")));

        // When
        runTurn(client);

        // Then: one tool, both skills listed, the body returned on load
        final ToolSchema tool = client.requests().getFirst().tools().stream()
                .filter(schema -> schema.name().equals("skill"))
                .findFirst()
                .orElseThrow();
        assertThat(tool.description()).contains("deploy: Deploy the app safely.").contains("review: Review a change.");
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("Step 1: run the tests."));
    }

    @Test
    @DisplayName("a project skill replaces a user skill of the same name")
    void should_prefer_the_project_skill_on_a_name_clash() throws IOException {
        // Given
        writeSkill(userDirectory, "deploy", "---\nname: deploy\ndescription: User way.\n---\nuser body");
        writeSkill(projectDirectory, "deploy", "---\nname: deploy\ndescription: Project way.\n---\nproject body");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "skill", "{\"name\": \"deploy\"}"),
                ScriptedClient.textStep("ok")));

        // When
        runTurn(client);

        // Then
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("project body"));
    }

    @Test
    @DisplayName("a skill file without frontmatter still loads, named by its directory")
    void should_tolerate_a_missing_frontmatter() throws IOException {
        // Given
        writeSkill(projectDirectory, "plain", "Just do the thing carefully.");
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        runTurn(client);

        // Then
        final ToolSchema tool = client.requests().getFirst().tools().stream()
                .filter(schema -> schema.name().equals("skill"))
                .findFirst()
                .orElseThrow();
        assertThat(tool.description()).contains("plain: Just do the thing carefully.");
    }

    @Test
    @DisplayName("one unreadable SKILL.md is skipped with a warning — the session still starts and other skills load")
    void should_survive_an_unreadable_skill_file() throws IOException {
        // Given: a healthy skill and one whose file cannot be read
        writeSkill(projectDirectory, "healthy", "---\nname: healthy\ndescription: Works.\n---\nbody");
        writeSkill(projectDirectory, "broken", "---\nname: broken\ndescription: Broken.\n---\nbody");
        final java.nio.file.Path unreadable = projectDirectory.resolve(".coder/skills/broken/SKILL.md");
        java.nio.file.Files.setPosixFilePermissions(unreadable, java.util.Set.of());
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        try {
            // When
            runTurn(client);
        } finally {
            java.nio.file.Files.setPosixFilePermissions(unreadable, java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        }

        // Then: the healthy skill is listed, the broken one absent, and startup survived
        final ToolSchema tool = client.requests().getFirst().tools().stream()
                .filter(schema -> schema.name().equals("skill"))
                .findFirst()
                .orElseThrow();
        assertThat(tool.description()).contains("healthy").doesNotContain("broken:");
    }

    @Test
    @DisplayName("with no skills anywhere, no skill tool is registered")
    void should_register_nothing_without_skills() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        runTurn(client);

        // Then
        assertThat(client.requests().getFirst().tools()).extracting(ToolSchema::name).doesNotContain("skill");
    }

    private void runTurn(final ScriptedClient client) {
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .userDirectory(userDirectory)
                .projectDirectory(projectDirectory)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new SkillsPlugin())
                .build()) {
            coder.runTurn("go", new CancelToken());
        }
    }

    private static void writeSkill(final Path anchor, final String name, final String content) throws IOException {
        final Path directory = anchor.resolve(".coder/skills/" + name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), content);
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
