package com.dmipi.coder.core.plugins.openai;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * Closes a wrapped stream when no byte has arrived for the idle window, so a dead or wedged
 * connection fails instead of hanging the turn forever. Idle-based (the clock resets on every
 * byte read), so a slow-but-alive model never trips it — only total silence does. A watchdog
 * virtual thread does the closing; a blocked read then throws {@link IOException}.
 */
final class IdleStreamGuard extends FilterInputStream {

    private static final long CHECK_INTERVAL_MILLIS = 1_000;

    private final long idleMillis;
    private final Thread watchdog;
    private volatile long lastActivityMillis;
    private volatile boolean stopped;

    IdleStreamGuard(final InputStream in, final Duration idle) {
        super(in);
        this.idleMillis = idle.toMillis();
        this.lastActivityMillis = System.currentTimeMillis();
        this.watchdog = Thread.ofVirtual().name("llm-idle-guard").start(this::watch);
    }

    private void watch() {
        while (!stopped) {
            try {
                Thread.sleep(Math.min(idleMillis, CHECK_INTERVAL_MILLIS));
            } catch (final InterruptedException interrupted) {
                return;
            }
            if (!stopped && System.currentTimeMillis() - lastActivityMillis > idleMillis) {
                try {
                    in.close();
                } catch (final IOException ignored) {
                    // The read side will surface the closed stream as its own failure.
                }
                return;
            }
        }
    }

    @Override
    public int read() throws IOException {
        final int b = super.read();
        lastActivityMillis = System.currentTimeMillis();
        return b;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        final int n = super.read(buffer, offset, length);
        lastActivityMillis = System.currentTimeMillis();
        return n;
    }

    @Override
    public void close() throws IOException {
        stopped = true;
        watchdog.interrupt();
        super.close();
    }
}
