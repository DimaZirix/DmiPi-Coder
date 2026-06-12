package com.dmipi.coder.console;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.plugins.files.FilesEditPlugin;
import com.dmipi.coder.core.plugins.files.FilesReadPlugin;
import com.dmipi.coder.core.plugins.memory.MemoryPlugin;
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
 * A runnable console wiring: reads settings from the project, grants the common local plugins,
 * and drives the loop over standard in/out. Models come from {@code .coder/settings.json} — with
 * none declared, the build fails clearly, which is the honest signal to configure one.
 */
public final class ConsoleMain {

    private ConsoleMain() {
    }

    public static void main(final String[] args) {
        final Path project = Path.of("").toAbsolutePath();
        final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        final PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        final ConsoleRenderer renderer = new ConsoleRenderer(output);

        try (Coder coder = Coder.builder()
                .out(renderer)
                .subagentOut(renderer.forSubagents())
                .hil(new ConsoleHil(input, output))
                .projectDirectory(project)
                .loadUserSettings()
                .loadProjectSettings()
                .enableSessions()
                .nextSpeakerCheck()
                .registerPlugin(new FilesReadPlugin())
                .registerPlugin(new FilesEditPlugin())
                .registerPlugin(new PlanningPlugin())
                .registerPlugin(new MemoryPlugin())
                .registerPlugin(new DirectSandboxPlugin())
                .registerPlugin(new ShellPlugin())
                .build()) {
            new Console(coder, input, output, Console.autosaveNameFor(LocalDateTime.now())).run();
        }
    }
}
