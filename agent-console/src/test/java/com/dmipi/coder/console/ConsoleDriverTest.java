package com.dmipi.coder.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.hil.Hil;
import com.dmipi.coder.core.domain.hil.Question;
import com.dmipi.coder.core.domain.llm.ChatRequest;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.LlmStreamEvent;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsoleDriverTest {

    private static final ModelDeclaration FAST = new ModelDeclaration("fast-local", "scripted", "", Tier.FAST, 8_000);
    private static final ModelDeclaration STRONG = new ModelDeclaration("strong-local", "scripted", "", Tier.STRONG, 8_000);

    @TempDir
    private Path projectDirectory;

    private final StringWriter out = new StringWriter();

    @Test
    @DisplayName("a prompt runs a turn and the answer is rendered; the turn is autosaved")
    void should_run_a_turn_and_autosave() {
        // Given
        final Coder coder = coder(reply("the answer is 42"));
        final Console console = new Console(coder, reader("what is the answer?\n/exit\n"), new PrintWriter(out), "auto");

        // When
        console.run();

        // Then
        assertThat(out.toString()).contains("the answer is 42");
        assertThat(coder.sessions()).contains("auto");
        coder.close();
    }

    @Test
    @DisplayName("/plan and /llm map onto interface functions; the core never sees the command as a prompt")
    void should_map_slash_commands_onto_interface_functions() {
        // Given
        final RecordingProvider provider = new RecordingProvider(reply("ok"));
        final Coder coder = coderWith(provider, FAST, STRONG);
        final Console console = new Console(coder, reader("/plan on\n/llm strong-local\n/exit\n"), new PrintWriter(out), null);

        // When
        console.run();

        // Then: mode switched, model switched, and no prompt ever reached the model
        assertThat(coder.mode()).isEqualTo(Mode.PLAN);
        assertThat(coder.activeModel().name()).isEqualTo("strong-local");
        assertThat(provider.sawAnyRequest()).isFalse();
        assertThat(out.toString()).contains("plan mode on").contains("active model: strong-local");
        coder.close();
    }

    @Test
    @DisplayName("/resume loads a saved session before the first turn")
    void should_resume_a_saved_session() {
        // Given: a first run saves under 'earlier'
        try (Coder first = coder(reply("remembered fact"))) {
            first.runTurn("note this", new CancelToken());
            first.saveSession("earlier");
        }

        // When: a new console resumes it
        final RecordingProvider provider = new RecordingProvider(reply("recalled"));
        final Coder coder = coderWith(provider, FAST);
        new Console(coder, reader("/resume earlier\ncontinue\n/exit\n"), new PrintWriter(out), null).run();

        // Then: the resumed model saw the earlier dialogue
        assertThat(provider.lastRequest().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("remembered fact"));
        assertThat(out.toString()).contains("resumed session 'earlier'");
        coder.close();
    }

    @Test
    @DisplayName("an interrupt during a turn cancels it; idle, the interrupt means exit")
    void should_cancel_the_running_turn_on_interrupt() throws InterruptedException {
        // Given: a model that streams only once cancelled, so the turn blocks until Ctrl+C
        final LlmClient blockingUntilCancelled = (request, cancel, events) -> {
            while (!cancel.isCancelled()) {
                try {
                    Thread.sleep(5);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            events.accept(new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.STOP));
        };
        final Coder coder = coder(blockingUntilCancelled);
        final Console console = new Console(coder, reader("block\n"), new PrintWriter(out), null);
        final Thread driver = new Thread(console::run);
        driver.start();
        awaitOutput("Ready");

        // When: the interrupt decision fires mid-turn (polled until the turn is actually running), then again when idle
        boolean duringTurn = false;
        final long deadline = System.currentTimeMillis() + 5_000;
        while (!duringTurn && System.currentTimeMillis() < deadline) {
            duringTurn = console.handleInterrupt();
            if (!duringTurn) {
                Thread.sleep(5);
            }
        }
        driver.join(5_000);
        final boolean whenIdle = console.handleInterrupt();

        // Then: mid-turn cancels (and the console survives to exit on EOF); idle means exit
        assertThat(duringTurn).isTrue();
        assertThat(whenIdle).isFalse();
        assertThat(driver.isAlive()).isFalse();
        assertThat(out.toString()).contains("(cancelling the current turn)");
        coder.close();
    }

    private void awaitOutput(final String marker) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (!out.toString().contains(marker) && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    private Coder coder(final LlmClient client) {
        return coderWith(new RecordingProvider(client), FAST);
    }

    private Coder coderWith(final RecordingProvider provider, final ModelDeclaration... declarations) {
        final Coder.Builder builder = Coder.builder()
                .out(new ConsoleRenderer(new PrintWriter(out)))
                .hil(denyAll())
                .projectDirectory(projectDirectory)
                .enableSessions()
                .registerPlugin(provider);
        for (final ModelDeclaration declaration : declarations) {
            builder.model(declaration);
        }
        return builder.build();
    }

    private static LlmClient reply(final String text) {
        return (request, cancel, events) -> {
            events.accept(new LlmStreamEvent.TextDelta(text));
            events.accept(new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.STOP));
        };
    }

    private static Hil denyAll() {
        return new Hil() {

            @Override
            public Answer ask(final Question question) {
                return Answer.of(question.options().getLast().id());
            }
        };
    }

    private static BufferedReader reader(final String typed) {
        return new BufferedReader(new StringReader(typed));
    }

    /** A provider that hands out one client and remembers what it was asked. */
    private static final class RecordingProvider implements Plugin {

        private final LlmClient delegate;
        private volatile ChatRequest lastRequest;

        private RecordingProvider(final LlmClient delegate) {
            this.delegate = delegate;
        }

        boolean sawAnyRequest() {
            return lastRequest != null;
        }

        ChatRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
            registrar.registerProtocolProvider(new ProtocolProvider() {

                @Override
                public String protocol() {
                    return "scripted";
                }

                @Override
                public LlmClient connect(final ModelDeclaration declaration) {
                    return (ChatRequest request, CancelToken cancel, Consumer<LlmStreamEvent> events) -> {
                        lastRequest = request;
                        delegate.stream(request, cancel, events);
                    };
                }
            });
        }
    }
}
