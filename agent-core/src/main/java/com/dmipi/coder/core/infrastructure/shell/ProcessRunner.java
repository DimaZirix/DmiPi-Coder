package com.dmipi.coder.core.infrastructure.shell;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.ShellResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bounded execution of one external process, shared by sandbox providers: the timeout kills the
 * whole process tree, cancellation is polled while waiting, and each output stream is captured
 * up to a cap. What confines the process is the caller's argv; this only runs it honestly.
 */
public final class ProcessRunner {

    private static final int CAPTURE_CAP_BYTES = 1_000_000;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private ProcessRunner() {
    }

    /** The host's command shell wrapped around one command line. */
    public static List<String> systemShell(final String command) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return List.of("cmd.exe", "/c", command);
        }
        return List.of("/bin/sh", "-c", command);
    }

    public static ShellResult run(final List<String> argv, final Path directory, final Duration timeout, final CancelToken cancel) {
        final Process process = start(argv, directory);
        final CappedCapture stdout = CappedCapture.of(process.getInputStream());
        final CappedCapture stderr = CappedCapture.of(process.getErrorStream());
        final Outcome outcome = awaitOrKill(process, timeout, cancel);
        // exitValue is only safe after a natural exit — a killed process may survive the bounded kill wait.
        final int exitCode = outcome == Outcome.COMPLETED ? process.exitValue() : -1;
        return new ShellResult(exitCode, stdout.text(), stderr.text(), outcome == Outcome.TIMED_OUT, outcome == Outcome.CANCELLED);
    }

    /** Starts a process without waiting — used for background commands the session tracks and tears down later. */
    public static Process start(final List<String> argv, final Path directory) {
        try {
            return new ProcessBuilder(argv)
                    .directory(directory.toFile())
                    .start();
        } catch (final IOException failure) {
            throw new UncheckedIOException("The command could not be started: " + failure.getMessage(), failure);
        }
    }

    /** Kills a process and its whole descendant tree. */
    public static void killProcessTree(final Process process) {
        killTree(process);
    }

    /** How the wait ended: the process exited by itself, or was killed for a timeout or a cancel. */
    private enum Outcome {
        COMPLETED,
        TIMED_OUT,
        CANCELLED
    }

    /** Waits for the process within the timeout, polling cancellation; kills the tree on either limit. */
    private static Outcome awaitOrKill(final Process process, final Duration timeout, final CancelToken cancel) {
        final Instant deadline = Instant.now().plus(timeout);
        try {
            while (process.isAlive()) {
                if (cancel.isCancelled()) {
                    killTree(process);
                    return Outcome.CANCELLED;
                }
                if (!Instant.now().isBefore(deadline)) {
                    killTree(process);
                    return Outcome.TIMED_OUT;
                }
                process.waitFor(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            }
            return Outcome.COMPLETED;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            killTree(process);
            throw new IllegalStateException("The wait for the command was interrupted.", interrupted);
        }
    }

    /** Kills the process and every descendant — a timed-out build must not leave workers behind. */
    private static void killTree(final Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Drains one process stream on its own thread, keeping at most the cap and marking truncation. */
    private static final class CappedCapture {

        private final Thread reader;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean truncated;

        private CappedCapture(final InputStream stream) {
            reader = Thread.ofVirtual().start(() -> drain(stream));
        }

        static CappedCapture of(final InputStream stream) {
            return new CappedCapture(stream);
        }

        private void drain(final InputStream stream) {
            try (stream) {
                final byte[] chunk = new byte[8_192];
                int read;
                while ((read = stream.read(chunk)) >= 0) {
                    append(chunk, read);
                }
            } catch (final IOException closedByKill) {
                // The stream ends abruptly when the process is torn down; what was read stands.
            }
        }

        private void append(final byte[] chunk, final int length) {
            synchronized (bytes) {
                if (bytes.size() >= CAPTURE_CAP_BYTES) {
                    truncated = true;
                    return;
                }
                bytes.write(chunk, 0, length);
            }
        }

        /** The captured text so far; joins the reader briefly so a finished process reports complete output. */
        String text() {
            try {
                reader.join(Duration.ofSeconds(2).toMillis());
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            synchronized (bytes) {
                final String text = bytes.toString(StandardCharsets.UTF_8);
                return truncated ? text + "\n[…output truncated]" : text;
            }
        }
    }
}
