package com.dmipi.coder.core.domain.llm;

import java.util.Locale;

/**
 * The tool-call style a model speaks, selecting the worked examples it is shown. {@code GENERAL}
 * and {@code NATIVE} models call tools natively and get format-agnostic workflow examples;
 * {@code QWEN_CODER}/{@code QWEN_VL} emit calls as text and would get format-specific examples —
 * those await the text-embedded tool-call parser, so today every style resolves to the general
 * workflow examples.
 */
public enum PromptStyle {
    GENERAL,
    NATIVE,
    QWEN_CODER,
    QWEN_VL;

    /** The resource-name suffix, e.g. {@code qwen_coder} → {@code examples-qwen_coder.md}. */
    public String resourceSuffix() {
        return name().toLowerCase(Locale.ROOT);
    }
}
