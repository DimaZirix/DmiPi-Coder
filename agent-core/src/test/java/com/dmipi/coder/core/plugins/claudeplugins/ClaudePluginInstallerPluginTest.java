package com.dmipi.coder.core.plugins.claudeplugins;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.plugins.sandbox.DirectSandboxPlugin;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ClaudePluginInstallerPluginTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SKILL = """
            ---
            name: prompt-engineer
            description: Apply prompt standards.
            ---
            State the action to take, not the action to avoid.""";

    @TempDir
    private Path marketplace;

    @TempDir
    private Path userDirectory;

    @TempDir
    private Path projectDirectory;

    private final RecordingOut out = new RecordingOut();

    @Test
    @DisplayName("a marketplace plugin installs into the project in the native format: skills copied, MCP servers merged")
    void should_install_a_marketplace_plugin_into_the_project() throws IOException {
        // Given: a marketplace plugin with a skill (plus a support file), an .mcp.json, and unsupported content
        write(marketplace.resolve("prompt-standards/skills/prompt-engineer/SKILL.md"), SKILL);
        write(marketplace.resolve("prompt-standards/skills/prompt-engineer/references/rules.md"), "Condition before action.");
        write(marketplace.resolve("prompt-standards/.mcp.json"),
                "{\"mcpServers\": {\"prompt-linter\": {\"type\": \"http\", \"url\": \"http://127.0.0.1:9/mcp\"}}}");
        write(marketplace.resolve("prompt-standards/agents/reviewer.md"), "unsupported");
        write(projectDirectory.resolve(".mcp.json"),
                "{\"mcpServers\": {\"existing\": {\"type\": \"http\", \"url\": \"http://127.0.0.1:8/mcp\"}}}");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", """
                        {"source": "%s", "plugin": "prompt-standards", "scope": "project"}""".formatted(marketplace)),
                ScriptedClient.textStep("done")));
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("allow-once")));

        // When
        runTurn(client, hil);

        // Then: an explicit scope asks nothing beyond permission; skills land under .coder/skills,
        // the server merges next to the existing one, and the report names both
        assertThat(hil.asked()).hasSize(1);
        assertThat(projectDirectory.resolve(".coder/skills/prompt-engineer/SKILL.md")).content().contains("State the action to take");
        assertThat(projectDirectory.resolve(".coder/skills/prompt-engineer/references/rules.md")).content().contains("Condition before action.");
        final JsonNode config = MAPPER.readTree(Files.readString(projectDirectory.resolve(".mcp.json")));
        assertThat(config.path("mcpServers").path("existing").path("url").stringValue()).isEqualTo("http://127.0.0.1:8/mcp");
        assertThat(config.path("mcpServers").path("prompt-linter").path("url").stringValue()).isEqualTo("http://127.0.0.1:9/mcp");
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content())
                        .contains("prompt-engineer")
                        .contains("prompt-linter")
                        .contains("Skipped unsupported Claude content: agents"));
    }

    @Test
    @DisplayName("without a scope the user is asked where to install, and the answer decides the anchor")
    void should_ask_where_to_install_when_no_scope_is_given() throws IOException {
        // Given: the source root is itself the plugin, and no scope in the call
        write(marketplace.resolve("skills/prompt-engineer/SKILL.md"), SKILL);
        write(marketplace.resolve(".mcp.json"),
                "{\"mcpServers\": {\"prompt-linter\": {\"type\": \"http\", \"url\": \"http://127.0.0.1:9/mcp\"}}}");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", "{\"source\": \"%s\"}".formatted(marketplace)),
                ScriptedClient.textStep("done")));
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("allow-once"), Answer.of("user")));

        // When
        runTurn(client, hil);

        // Then: the scope question offered both anchors, and answering 'user' installed there
        assertThat(hil.asked()).hasSize(2);
        assertThat(hil.asked().getLast()).satisfies(question -> {
            assertThat(question.question()).contains("Where should");
            assertThat(question.options()).extracting(option -> option.id()).containsExactly("user", "project");
        });
        assertThat(userDirectory.resolve(".coder/skills/prompt-engineer/SKILL.md")).content().contains("State the action to take");
        final JsonNode config = MAPPER.readTree(Files.readString(userDirectory.resolve(".coder/.mcp.json")));
        assertThat(config.path("mcpServers").path("prompt-linter").path("type").stringValue()).isEqualTo("http");
    }

    @Test
    @DisplayName("a .git source is cloned before installing")
    void should_clone_a_git_source() throws IOException, InterruptedException {
        // Given: the plugin committed to a bare repository
        final Path work = marketplace.resolve("work");
        write(work.resolve("skills/prompt-engineer/SKILL.md"), SKILL);
        git(work, "init", "--quiet");
        git(work, "add", ".");
        git(work, "-c", "user.name=test", "-c", "user.email=test@test", "commit", "--quiet", "-m", "plugin");
        git(marketplace, "clone", "--quiet", "--bare", work.toString(), marketplace.resolve("plugin.git").toString());
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", """
                        {"source": "%s", "scope": "project"}""".formatted(marketplace.resolve("plugin.git"))),
                ScriptedClient.textStep("done")));

        // When
        runTurn(client, new ScriptedHil(List.of(Answer.of("allow-once"))));

        // Then
        assertThat(projectDirectory.resolve(".coder/skills/prompt-engineer/SKILL.md")).content().contains("State the action to take");
    }

    @Test
    @DisplayName("a denied permission question installs nothing")
    void should_install_nothing_when_the_user_denies() throws IOException {
        // Given
        write(marketplace.resolve("skills/prompt-engineer/SKILL.md"), SKILL);
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", "{\"source\": \"%s\"}".formatted(marketplace)),
                ScriptedClient.textStep("ok")));
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("deny")));

        // When
        runTurn(client, hil);

        // Then: the question previewed the install, and no skill was written anywhere
        assertThat(hil.asked()).singleElement().satisfies(question -> assertThat(question.preview()).contains(marketplace.toString()));
        assertThat(userDirectory.resolve(".coder/skills")).doesNotExist();
        assertThat(projectDirectory.resolve(".coder/skills")).doesNotExist();
    }

    @Test
    @DisplayName("an unknown scope is refused with the valid values")
    void should_refuse_an_unknown_scope() throws IOException {
        // Given
        write(marketplace.resolve("skills/prompt-engineer/SKILL.md"), SKILL);
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", """
                        {"source": "%s", "scope": "global"}""".formatted(marketplace)),
                ScriptedClient.textStep("ok")));

        // When
        runTurn(client, new ScriptedHil(List.of(Answer.of("allow-once"))));

        // Then
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("Unknown scope 'global'").contains("user, project"));
    }

    @Test
    @DisplayName("pointing at a marketplace root without naming a plugin lists the plugins it holds")
    void should_list_marketplace_plugins_when_none_is_named() throws IOException {
        // Given: two plugins in the marketplace, none picked
        write(marketplace.resolve("prompt-standards/skills/prompt-engineer/SKILL.md"), SKILL);
        write(marketplace.resolve("java-standards/.mcp.json"), "{\"mcpServers\": {}}");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", "{\"source\": \"%s\"}".formatted(marketplace)),
                ScriptedClient.textStep("ok")));

        // When
        runTurn(client, new ScriptedHil(List.of(Answer.of("allow-once"), Answer.of("user"))));

        // Then
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("java-standards").contains("prompt-standards"));
    }

    @Test
    @DisplayName("a binary skill file fails the install loudly — nothing is written corrupted")
    void should_refuse_a_binary_skill_file() throws IOException {
        // Given: a skill carrying a binary asset the text copy would mangle
        write(marketplace.resolve("skills/prompt-engineer/SKILL.md"), SKILL);
        Files.write(marketplace.resolve("skills/prompt-engineer/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', (byte) 0xFF, (byte) 0xFE, 0x00, 0x1F});
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", """
                        {"source": "%s", "scope": "project"}""".formatted(marketplace)),
                ScriptedClient.textStep("ok")));

        // When
        runTurn(client, new ScriptedHil(List.of(Answer.of("allow-once"))));

        // Then: the failure names the file, and no partial content landed anywhere
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("logo.png").contains("not plain UTF-8 text"));
        assertThat(projectDirectory.resolve(".coder/skills")).doesNotExist();
        assertThat(projectDirectory.resolve(".coder/installed-plugins.json")).doesNotExist();
    }

    @Test
    @DisplayName("a malformed .mcp.json fails the install before any skill was copied")
    void should_validate_the_mcp_config_before_copying_anything() throws IOException {
        // Given: a valid skill next to a broken MCP config
        write(marketplace.resolve("skills/prompt-engineer/SKILL.md"), SKILL);
        write(marketplace.resolve(".mcp.json"), "{not json at all");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", """
                        {"source": "%s", "scope": "project"}""".formatted(marketplace)),
                ScriptedClient.textStep("ok")));

        // When
        runTurn(client, new ScriptedHil(List.of(Answer.of("allow-once"))));

        // Then: no orphaned skills invisible to remove_plugin, no manifest entry
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains(".mcp.json"));
        assertThat(projectDirectory.resolve(".coder/skills")).doesNotExist();
        assertThat(projectDirectory.resolve(".coder/installed-plugins.json")).doesNotExist();
    }

    @Test
    @DisplayName("list_plugins reports each installed plugin with its scope, skills, and servers")
    void should_list_installed_plugins() throws IOException {
        // Given: a plugin installed to the project, then listed
        write(marketplace.resolve("prompt-standards/skills/prompt-engineer/SKILL.md"), SKILL);
        write(marketplace.resolve("prompt-standards/.mcp.json"),
                "{\"mcpServers\": {\"prompt-linter\": {\"type\": \"http\", \"url\": \"http://127.0.0.1:9/mcp\"}}}");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", """
                        {"source": "%s", "plugin": "prompt-standards", "scope": "project"}""".formatted(marketplace)),
                ScriptedClient.toolCallStep("c2", "list_plugins", "{}"),
                ScriptedClient.textStep("done")));

        // When
        runTurn(client, new ScriptedHil(List.of(Answer.of("allow-once"))));

        // Then
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content())
                        .contains("project scope:")
                        .contains("prompt-standards")
                        .contains("skills: prompt-engineer")
                        .contains("MCP servers: prompt-linter"));
    }

    @Test
    @DisplayName("skills no plugin owns are listed too — 'no plugins' never hides existing skills")
    void should_report_skills_no_plugin_owns() throws IOException {
        // Given: a skill written by hand (or installed before the manifest existed), no plugins
        write(userDirectory.resolve(".coder/skills/senior-java-developer/SKILL.md"), "hand-written");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "list_plugins", "{}"),
                ScriptedClient.textStep("done")));

        // When
        runTurn(client, new ScriptedHil(List.of()));

        // Then
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content())
                        .contains("No plugins are installed.")
                        .contains("not installed by any plugin")
                        .contains("senior-java-developer"));
    }

    @Test
    @DisplayName("remove_plugin deletes exactly what the install recorded — foreign servers and hand-written skills stay")
    void should_remove_an_installed_plugin() throws IOException {
        // Given: an installed plugin next to a pre-existing server and a hand-written skill
        write(marketplace.resolve("prompt-standards/skills/prompt-engineer/SKILL.md"), SKILL);
        write(marketplace.resolve("prompt-standards/.mcp.json"),
                "{\"mcpServers\": {\"prompt-linter\": {\"type\": \"http\", \"url\": \"http://127.0.0.1:9/mcp\"}}}");
        write(projectDirectory.resolve(".mcp.json"),
                "{\"mcpServers\": {\"existing\": {\"type\": \"http\", \"url\": \"http://127.0.0.1:8/mcp\"}}}");
        write(projectDirectory.resolve(".coder/skills/hand-written/SKILL.md"), "keep me");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", """
                        {"source": "%s", "plugin": "prompt-standards", "scope": "project"}""".formatted(marketplace)),
                ScriptedClient.toolCallStep("c2", "remove_plugin", "{\"name\": \"prompt-standards\"}"),
                ScriptedClient.textStep("done")));

        // When
        runTurn(client, new ScriptedHil(List.of(Answer.of("allow-once"), Answer.of("allow-once"))));

        // Then: the plugin's skill, server, and manifest entry are gone; everything else survives
        assertThat(projectDirectory.resolve(".coder/skills/prompt-engineer")).doesNotExist();
        assertThat(projectDirectory.resolve(".coder/skills/hand-written/SKILL.md")).exists();
        final JsonNode config = MAPPER.readTree(Files.readString(projectDirectory.resolve(".mcp.json")));
        assertThat(config.path("mcpServers").path("prompt-linter").isMissingNode()).isTrue();
        assertThat(config.path("mcpServers").path("existing").path("url").stringValue()).isEqualTo("http://127.0.0.1:8/mcp");
        assertThat(projectDirectory.resolve(".coder/installed-plugins.json")).doesNotExist();
    }

    @Test
    @DisplayName("a reinstall clears the previous version's content — a dropped skill does not linger")
    void should_clear_stale_content_on_reinstall() throws IOException {
        // Given: v1 ships two skills; v2 of the same plugin ships only one
        write(marketplace.resolve("skills/old-skill/SKILL.md"), "---\nname: old-skill\ndescription: Old.\n---\nv1");
        write(marketplace.resolve("skills/prompt-engineer/SKILL.md"), SKILL);
        final ScriptedClient install = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", """
                        {"source": "%s", "scope": "project"}""".formatted(marketplace)),
                ScriptedClient.textStep("ok")));
        runTurn(install, new ScriptedHil(List.of(Answer.of("allow-once"))));
        Files.delete(marketplace.resolve("skills/old-skill/SKILL.md"));
        Files.delete(marketplace.resolve("skills/old-skill"));

        // When: the same source installs again
        final ScriptedClient reinstall = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c2", "install_plugin", """
                        {"source": "%s", "scope": "project"}""".formatted(marketplace)),
                ScriptedClient.textStep("ok")));
        runTurn(reinstall, new ScriptedHil(List.of(Answer.of("allow-once"))));

        // Then: the dropped skill is gone, the surviving one is present and owned
        assertThat(projectDirectory.resolve(".coder/skills/old-skill")).doesNotExist();
        assertThat(projectDirectory.resolve(".coder/skills/prompt-engineer/SKILL.md")).exists();
        assertThat(Files.readString(projectDirectory.resolve(".coder/installed-plugins.json")))
                .contains("prompt-engineer")
                .doesNotContain("old-skill");
    }

    @Test
    @DisplayName("installing content another plugin owns is refused, naming the owner")
    void should_refuse_to_overwrite_another_plugins_content() throws IOException {
        // Given: plugin A owns skill 'shared'; plugin B ships a skill of the same name
        write(marketplace.resolve("plugin-a/skills/shared/SKILL.md"), "---\nname: shared\ndescription: A's.\n---\nfrom A");
        write(marketplace.resolve("plugin-b/skills/shared/SKILL.md"), "---\nname: shared\ndescription: B's.\n---\nfrom B");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", """
                        {"source": "%s", "plugin": "plugin-a", "scope": "project"}""".formatted(marketplace)),
                ScriptedClient.toolCallStep("c2", "install_plugin", """
                        {"source": "%s", "plugin": "plugin-b", "scope": "project"}""".formatted(marketplace)),
                ScriptedClient.textStep("ok")));

        // When
        runTurn(client, new ScriptedHil(List.of(Answer.of("allow-once"), Answer.of("allow-once"))));

        // Then: B was refused naming A, and A's file is untouched
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("skill 'shared' belongs to plugin 'plugin-a'"));
        assertThat(projectDirectory.resolve(".coder/skills/shared/SKILL.md")).content().contains("from A");
    }

    @Test
    @DisplayName("a malformed manifest entry fails removal loudly — it never becomes a wildcard delete")
    void should_refuse_removal_on_a_malformed_manifest() throws IOException {
        // Given: a hand-broken manifest whose skill name is a number, next to an innocent skill
        write(projectDirectory.resolve(".coder/installed-plugins.json"),
                "{\"plugins\": {\"broken\": {\"source\": \"x\", \"skills\": [1], \"mcpServers\": []}}}");
        write(projectDirectory.resolve(".coder/skills/innocent/SKILL.md"), "keep me");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "remove_plugin", "{\"name\": \"broken\", \"scope\": \"project\"}"),
                ScriptedClient.textStep("ok")));

        // When
        runTurn(client, new ScriptedHil(List.of(Answer.of("allow-once"))));

        // Then: refused naming the manifest, and nothing was deleted
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("installed-plugins.json").contains("invalid name"));
        assertThat(projectDirectory.resolve(".coder/skills/innocent/SKILL.md")).exists();
    }

    @Test
    @DisplayName("removing a plugin that is not installed fails naming the installed ones")
    void should_refuse_to_remove_an_unknown_plugin() throws IOException {
        // Given: one installed plugin, and an attempt to remove another
        write(marketplace.resolve("prompt-standards/skills/prompt-engineer/SKILL.md"), SKILL);
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "install_plugin", """
                        {"source": "%s", "plugin": "prompt-standards", "scope": "project"}""".formatted(marketplace)),
                ScriptedClient.toolCallStep("c2", "remove_plugin", "{\"name\": \"java-standards\"}"),
                ScriptedClient.textStep("ok")));

        // When
        runTurn(client, new ScriptedHil(List.of(Answer.of("allow-once"), Answer.of("allow-once"))));

        // Then
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content())
                        .contains("No plugin named 'java-standards'")
                        .contains("Installed plugins: prompt-standards"));
    }

    private void runTurn(final ScriptedClient client, final ScriptedHil hil) {
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(hil)
                .model(MODEL)
                .userDirectory(userDirectory)
                .projectDirectory(projectDirectory)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new DirectSandboxPlugin())
                .registerPlugin(new ClaudePluginInstallerPlugin())
                .build()) {
            coder.runTurn("install it", new CancelToken());
        }
    }

    private static void write(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void git(final Path workingDirectory, final String... arguments) throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        final Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        final String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).withFailMessage("git %s failed: %s", List.of(arguments), output).isZero();
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
