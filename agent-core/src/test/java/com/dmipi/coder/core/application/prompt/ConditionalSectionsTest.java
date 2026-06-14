package com.dmipi.coder.core.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.plugins.sandbox.DirectSandboxPlugin;
import com.dmipi.coder.core.plugins.shell.ShellPlugin;
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

class ConditionalSectionsTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);

    @TempDir
    private Path projectDirectory;

    @Test
    @DisplayName("the direct (non-confining) sandbox yields the 'outside of sandbox' section, not 'inside'")
    void should_say_outside_for_the_direct_sandbox() {
        // Given: a shell agent on the direct provider
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        final String prompt = systemPrompt(client, true);

        // Then
        assertThat(prompt).contains("Outside of sandbox").doesNotContain("Inside sandbox");
    }

    @Test
    @DisplayName("with no shell at all, neither sandbox section appears")
    void should_omit_the_sandbox_section_without_a_shell() {
        // Given: no shell/sandbox plugin
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        final String prompt = systemPrompt(client, false);

        // Then
        assertThat(prompt).doesNotContain("sandbox");
    }

    @Test
    @DisplayName("the git section appears only inside a git repository")
    void should_add_the_git_section_only_in_a_repo() throws IOException {
        // Given: not a repo yet
        final ScriptedClient before = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));
        assertThat(systemPrompt(before, false)).doesNotContain("Git repository");

        // When: a .git directory exists
        Files.createDirectory(projectDirectory.resolve(".git"));
        final ScriptedClient after = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // Then
        assertThat(systemPrompt(after, false)).contains("Git repository");
    }

    private String systemPrompt(final ScriptedClient client, final boolean withShell) {
        final Coder.Builder builder = Coder.builder()
                .out(new RecordingOut())
                .hil(new ScriptedHil(List.of(Answer.of("deny"))))
                .model(MODEL)
                .instructions("core")
                .projectDirectory(projectDirectory)
                .registerPlugin(providerPlugin(client));
        if (withShell) {
            builder.registerPlugin(new DirectSandboxPlugin()).registerPlugin(new ShellPlugin());
        }
        try (Coder coder = builder.build()) {
            coder.runTurn("go", new CancelToken());
        }
        return client.requests().getFirst().messages().getFirst().content();
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
