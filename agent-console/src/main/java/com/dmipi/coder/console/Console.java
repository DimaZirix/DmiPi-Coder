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

    private static final int EXIT_CODE_INTERRUPTED = 130;

    private final Coder coder;
    private final BufferedReader input;
    private final PrintWriter output;
    private final String autosaveName;
    private volatile boolean turnRunning;

    /** @param autosaveName the session name to autosave under after each turn, or null to disable autosave */
    public Console(final Coder coder, final BufferedReader input, final PrintWriter output, final String autosaveName) {
        this.coder = coder;
        this.input = input;
        this.output = output;
        this.autosaveName = autosaveName;
    }

    /** Reads and handles lines until {@code /exit} or end of input. Ctrl+C cancels the running turn; idle, it exits. */
    public void run() {
        installCancelHandler();
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
        turnRunning = true;
        try {
            coder.runTurn(prompt, new CancelToken());
        } finally {
            turnRunning = false;
        }
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

    /**
     * The Ctrl+C decision: a running turn is cancelled (true); idle, the caller should exit
     * (false). Package-visible so the behavior is testable without raising real signals.
     */
    boolean handleInterrupt() {
        if (!turnRunning) {
            return false;
        }
        coder.cancelCurrentTurn();
        output.println();
        output.println("(cancelling the current turn)");
        output.flush();
        return true;
    }

    private void installCancelHandler() {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("INT"), signal -> {
                if (!handleInterrupt()) {
                    Runtime.getRuntime().exit(EXIT_CODE_INTERRUPTED);
                }
            });
        } catch (final RuntimeException unsupported) {
            // Signal handling is a JVM extra (-Xrs disables it); without it Ctrl+C keeps its default meaning.
        }
    }

    private String readLine() {
        try {
            return input.readLine();
        } catch (final java.io.IOException failure) {
            output.println("(standard input failed: " + failure.getMessage() + " — exiting)");
            return null;
        }
    }
}
