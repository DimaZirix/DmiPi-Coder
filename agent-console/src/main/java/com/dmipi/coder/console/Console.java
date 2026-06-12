package com.dmipi.coder.console;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.time.LocalDateTime;

/**
 * The console loop: read a line, run it as a command or a turn, autosave after each turn. A thin
 * driver over {@link Coder} — it owns no agent behaviour, only presentation and convenience.
 * Slash commands are sugar over interface functions; the core never sees them.
 */
public final class Console {

    private final Coder coder;
    private final BufferedReader input;
    private final PrintWriter output;
    private final String autosaveName;

    /** @param autosaveName the session name to autosave under after each turn, or null to disable autosave */
    public Console(final Coder coder, final BufferedReader input, final PrintWriter output, final String autosaveName) {
        this.coder = coder;
        this.input = input;
        this.output = output;
        this.autosaveName = autosaveName;
    }

    /** Reads and handles lines until {@code /exit} or end of input. */
    public void run() {
        output.println("Ready. Type a prompt, a /command (" + String.join(", ", SlashCommand.keywords()) + "), or /exit.");
        while (true) {
            output.print("\n> ");
            output.flush();
            final String line = readLine();
            if (line == null || SlashCommand.isExit(line)) {
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            if (SlashCommand.dispatch(line, coder, output)) {
                continue;
            }
            runTurn(line);
        }
    }

    private void runTurn(final String prompt) {
        coder.runTurn(prompt, new CancelToken());
        if (autosaveName != null) {
            try {
                coder.saveSession(autosaveName);
            } catch (final RuntimeException failure) {
                output.println("(autosave failed: " + failure.getMessage() + ")");
            }
        }
    }

    /** A stable autosave name for a session started now, from a timestamp the caller supplies. */
    public static String autosaveNameFor(final LocalDateTime startedAt) {
        return "session-" + startedAt.toString().replaceAll("[^0-9]", "-");
    }

    private String readLine() {
        try {
            return input.readLine();
        } catch (final java.io.IOException failure) {
            return null;
        }
    }
}
