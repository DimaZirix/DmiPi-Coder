package com.dmipi.coder.core.domain.agent;

import java.util.Optional;

/**
 * The in channel: the front-end's source of user prompts, one per turn. The core asks for the
 * next prompt only between turns — there is no queue. An empty return ends the session.
 */
public interface In {

    /** The next user prompt, or empty when the input has ended. Blocks until one is available. */
    Optional<String> nextPrompt();
}
