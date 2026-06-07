package com.dmipi.coder.core.plugins.openai;

import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;

final class OpenAiProtocolProvider implements ProtocolProvider {

    static final String PROTOCOL = "openai";

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public LlmClient connect(final ModelDeclaration declaration) {
        return new OpenAiClient(declaration);
    }
}
