package com.dmipi.coder.core.plugins.sandbox;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.Sandbox;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.shell.ShellResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Direct execution on the host: the system shell in the project directory, no confinement.
 * What it does bound: the timeout (the whole process tree is torn down when exceeded),
 * cancellation (polled while waiting), and captured output (capped per stream).
 */
final class DirectSandbox implements Sandbox {

    private static final int CAPTURE_CAP_BYTES = 1_000_000;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final SandboxSpec spec;

    DirectSandbox(final SandboxSpec spec) {
        this.spec = spec;
    }

    @Override
    public ShellResult run(final String command, final Duration timeout, final CancelToken cancel) {
        final Process process = start(command);
        final CappedCapture stdout = CappedCapture.of(process.getInputStream());
        final CappedCapture stderr = CappedCapture.of(process.getErrorStream());
        final boolean timedOut = awaitOrKill(process, timeout, cancel);
        return new ShellResult(timedOut ? -1 : process.exitValue(), stdout.text(), stderr.text(), timedOut);
    }

    private Process start(final String command) {
        try {
            return new ProcessBuilder(shellCommand(command))
                    .directory(spec.projectDirectory().toFile())
                    .start();
        } catch (final IOException failure) {
            throw new UncheckedIOException("The command could not be started: " + failure.getMessage(), failure);
        }
    }

    /** Waits for the process within the timeout, polling cancellation; true when it was killed for overrunning. */
    private static boolean awaitOrKill(final Process process, final Duration timeout, final CancelToken cancel) {
        final Instant deadline = Instant.now().plus(timeout);
        try {
            while (process.isAlive()) {
                if (cancel.isCancelled() || !Instant.now().isBefore(deadline)) {
                    killTree(process);
                    return !cancel.isCancelled();
                }
                process.waitFor(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            }
            return false;
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

    private static List<String> shellCommand(final String command) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return List.of("cmd.exe", "/c", command);
        }
        return List.of("/bin/sh", "-c", command);
    }

    @Override
    public String technology() {
        return DirectSandboxProvider.TECHNOLOGY;
    }

    @Override
    public boolean confines() {
        return false;
    }

    @Override
    public void close() {
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
