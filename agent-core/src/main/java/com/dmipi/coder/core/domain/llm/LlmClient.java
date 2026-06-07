package com.dmipi.coder.core.domain.llm;

import com.dmipi.coder.core.domain.agent.CancelToken;
import java.util.function.Consumer;

/**
 * The LLM contract every consumer speaks — the conversation loop, the advisors, plugins.
 * Implemented by protocol providers; the core never speaks a wire protocol itself.
 */
public interface LlmClient {

    /**
     * Sends the request and delivers stream events in order until {@code Finished}; returns
     * when the stream has ended. Cancellation is polled cooperatively. Transport or protocol
     * failure raises {@link LlmException}.
     */
    void stream(ChatRequest request, CancelToken cancel, Consumer<LlmStreamEvent> events);
}
