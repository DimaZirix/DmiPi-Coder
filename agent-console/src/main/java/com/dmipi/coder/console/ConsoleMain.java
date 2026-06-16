package com.dmipi.coder.console;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.application.prompt.CorePrompt;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.plugins.files.FilesEditPlugin;
import com.dmipi.coder.core.plugins.files.FilesReadPlugin;
import com.dmipi.coder.core.plugins.memory.MemoryPlugin;
import com.dmipi.coder.core.plugins.openai.OpenAiProviderPlugin;
import com.dmipi.coder.core.plugins.planning.PlanningPlugin;
import com.dmipi.coder.core.plugins.sandbox.DirectSandboxPlugin;
import com.dmipi.coder.core.plugins.shell.ShellPlugin;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * A runnable console wiring: a default model declared here in Java, then overlaid by the user and
 * project {@code .coder/settings.json} (a settings model of the same name replaces the default),
 * the common local plugins granted, and the loop driven over standard in/out.
 */
public final class ConsoleMain {

    /** The out-of-the-box model — edit here, or override in {@code .coder/settings.json} by declaring a model named "local". */
    private static final ModelDeclaration DEFAULT_MODEL =
            new ModelDeclaration("local", "openai", "http://localhost:8080/v1", Tier.BALANCED, 128_000);

    private ConsoleMain() {
    }

    public static void main(final String[] args) {
        final Path project = Path.of("").toAbsolutePath();
        final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        final PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        final ConsoleRenderer renderer = new ConsoleRenderer(output);

        final Coder coder;
        try {
            coder = Coder.builder()
                    .out(renderer)
                    .subagentOut(renderer.forSubagents())
                    .hil(new ConsoleHil(input, output))
                    .instructions(CorePrompt.standard())
                    .projectDirectory(project)
                    .gatherEnvironment()
                    .workedExamples()
                    .model(DEFAULT_MODEL)
                    .loadUserSettings()
                    .loadProjectSettings()
                    .enableSessions()
                    .nextSpeakerCheck()
                    .registerPlugin(new OpenAiProviderPlugin())
                    .registerPlugin(new FilesReadPlugin())
                    .registerPlugin(new FilesEditPlugin())
                    .registerPlugin(new PlanningPlugin())
                    .registerPlugin(new MemoryPlugin())
                    .registerPlugin(new DirectSandboxPlugin())
                    .registerPlugin(new ShellPlugin())
                    .build();
        } catch (final IllegalStateException misconfigured) {
            output.println("Cannot start: " + misconfigured.getMessage());
            output.println();
            output.println("A default model is built in; override it in .coder/settings.json (project or user), e.g.:");
            output.println(EXAMPLE_SETTINGS);
            output.flush();
            return;
        }

        try (coder) {
            new Console(coder, input, output, Console.autosaveNameFor(LocalDateTime.now())).run();
        }
    }

    private static final String EXAMPLE_SETTINGS = """
            {
              "models": [
                {
                  "name": "local",
                  "protocol": "openai",
                  "endpoint": "http://localhost:1234/v1",
                  "tier": "fast",
                  "contextWindow": 32000
                }
              ]
            }""";
}
