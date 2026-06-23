package com.dmipi.coder.core.plugins.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdleStreamGuardTest {

    @Test
    @DisplayName("a stream that stays silent past the idle window is closed and the read fails")
    void should_fail_a_silent_stream() {
        // Given: a stream that blocks until closed — like a real socket the watchdog can close
        final InputStream stalled = new InputStream() {

            private volatile boolean closed;

            @Override
            public synchronized int read() throws IOException {
                while (!closed) {
                    try {
                        wait(200);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                throw new IOException("stream closed");
            }

            @Override
            public synchronized void close() {
                closed = true;
                notifyAll();
            }
        };

        // When / Then: with a 1s idle window, the read fails within a few seconds
        try (IdleStreamGuard guard = new IdleStreamGuard(stalled, Duration.ofSeconds(1))) {
            assertThatIOException().isThrownBy(guard::read);
        } catch (final IOException closing) {
            // closing the guard is fine
        }
    }

    @Test
    @DisplayName("a stream that keeps producing bytes never trips the guard, however long the whole read")
    void should_tolerate_a_slow_but_alive_stream() throws IOException {
        // Given: a stream that yields one byte per ~120ms for a while, exceeding the idle window in total
        final byte[] data = "hello".getBytes();
        final InputStream trickle = new InputStream() {

            private int index;

            @Override
            public int read() {
                if (index >= data.length) {
                    return -1;
                }
                try {
                    Thread.sleep(120);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return data[index++];
            }
        };

        // When: idle window 300ms — longer than each gap, shorter than the whole read
        final AtomicBoolean failed = new AtomicBoolean(false);
        final StringBuilder read = new StringBuilder();
        try (IdleStreamGuard guard = new IdleStreamGuard(trickle, Duration.ofMillis(300))) {
            int b;
            while ((b = guard.read()) != -1) {
                read.append((char) b);
            }
        } catch (final IOException tripped) {
            failed.set(true);
        }

        // Then
        assertThat(failed).isFalse();
        assertThat(read.toString()).isEqualTo("hello");
    }
}
