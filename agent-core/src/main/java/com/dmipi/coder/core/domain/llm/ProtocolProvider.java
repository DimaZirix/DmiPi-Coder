package com.dmipi.coder.core.domain.llm;

/**
 * A provider contribution: implements the LLM contract for one wire protocol. Contributed by
 * plugins; the core matches each declared model to the provider that speaks its protocol.
 */
public interface ProtocolProvider {

    /** The protocol name declarations refer to, e.g. {@code openai}. */
    String protocol();

    LlmClient connect(ModelDeclaration declaration);
}
