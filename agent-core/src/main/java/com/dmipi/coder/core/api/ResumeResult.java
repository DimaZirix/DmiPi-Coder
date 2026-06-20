package com.dmipi.coder.core.api;

/** The outcome of resuming a session: whether the saved prompt was replayed verbatim (cache-friendly) or rebuilt because the world changed. */
public enum ResumeResult {

    /** The fingerprint matched: the saved system prompt was replayed byte-for-byte, so the server's prompt cache can be reused. */
    PROMPT_REUSED,

    /** The fingerprint differed (tools, plugins, environment, or model changed): the prompt was rebuilt, and the cache is cold. */
    PROMPT_REBUILT
}
