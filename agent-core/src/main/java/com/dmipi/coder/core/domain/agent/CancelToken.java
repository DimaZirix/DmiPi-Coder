package com.dmipi.coder.core.domain.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation of one turn: the loop and streaming clients poll it between steps. */
public final class CancelToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
