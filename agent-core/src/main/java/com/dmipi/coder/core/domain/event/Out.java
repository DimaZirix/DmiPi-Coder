package com.dmipi.coder.core.domain.event;

/**
 * The out channel: one ordered stream of typed events from the core to the front-end.
 * Notification-only and never blocking — a front-end that ignores every event still produces a
 * correct, if silent, agent. Ordering is meaning: events arrive in the order they happened.
 */
public interface Out {

    void event(OutEvent event);
}
