package com.dmipi.coder.core.plugins.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.hil.Answer;
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

class FilesPluginsIntegrationTest {

    @TempDir
    private Path project;

    private final RecordingOut out = new RecordingOut();

    @Test
    @DisplayName("registering only the read plugin yields a read-only tool catalog")
    void should_offer_only_read_tools_without_the_edit_plugin() {
        // Given
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hello")));

        // When
        coder(client, new ScriptedHil(List.of()), new FilesReadPlugin()).runTurn("hi", new CancelToken());

        // Then
        assertThat(client.requests().getFirst().tools())
                .extracting(ToolSchema::name)
                .containsExactly("read_file", "list_directory");
    }

    @Test
    @DisplayName("a full gated edit: the model edits a real file after HIL approval with the -/+ preview")
    void should_edit_a_real_file_through_the_full_stack() throws IOException {
        // Given
        Files.writeString(project.resolve("greeting.txt"), "hello world");
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "edit", "{\"path\": \"greeting.txt\", \"old_string\": \"world\", \"new_string\": \"dmipi\"}"),
                ScriptedClient.textStep("edited")));
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("allow-once")));

        // When
        coder(client, hil, new FilesReadPlugin(), new FilesEditPlugin()).runTurn("rename it", new CancelToken());

        // Then: the question previewed the real unified diff, and the file really changed
        assertThat(hil.asked()).singleElement().satisfies(question -> assertThat(question.preview()).contains("-hello world").contains("+hello dmipi"));
        assertThat(project.resolve("greeting.txt")).hasContent("hello dmipi");
        assertThat(out.answerText()).isEqualTo("edited");
    }

    private Coder coder(final ScriptedClient client, final ScriptedHil hil, final Plugin... filePlugins) {
        final Coder.Builder builder = Coder.builder()
                .out(out)
                .hil(hil)
                .model(new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000))
                .projectDirectory(project)
                .registerPlugin(providerPlugin(client));
        for (final Plugin plugin : filePlugins) {
            builder.registerPlugin(plugin);
        }
        return builder.build();
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
