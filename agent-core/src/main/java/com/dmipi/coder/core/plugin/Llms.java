package com.dmipi.coder.core.plugin;

import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.Tier;

/** The LLM capability: call a model, selected by tier from the configured set. */
public interface Llms {

    /** The conversation's active model. */
    LlmClient active();

    /** The cheapest model of the set. */
    LlmClient fastest();

    /** The most capable model of the set. */
    LlmClient strongest();

    /** The cheapest model meeting the bar; the strongest when none does. */
    LlmClient atLeast(Tier bar);
}
