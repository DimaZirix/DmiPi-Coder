package com.dmipi.coder.core.plugins.web;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Reduces fetched HTML to readable plain text: drops scripts/styles/tags and decodes a few entities. */
final class HtmlText {

    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    /** Decode order matters: {@code &amp;} must go last, or {@code &amp;amp;lt;} would double-decode into {@code <}. */
    private static final List<Map.Entry<String, String>> ENTITIES = List.of(
            Map.entry("&lt;", "<"),
            Map.entry("&gt;", ">"),
            Map.entry("&quot;", "\""),
            Map.entry("&#39;", "'"),
            Map.entry("&nbsp;", " "),
            Map.entry("&amp;", "&"));

    private HtmlText() {
    }

    static String extract(final String html) {
        final String withoutScripts = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
        final String withoutTags = TAG.matcher(withoutScripts).replaceAll(" ");
        final String decoded = decodeEntities(withoutTags);
        final String collapsed = WHITESPACE.matcher(decoded).replaceAll(" ");
        return BLANK_LINES.matcher(collapsed.replace(" \n", "\n")).replaceAll("\n\n").strip();
    }

    private static String decodeEntities(final String text) {
        String decoded = text;
        for (final Map.Entry<String, String> entity : ENTITIES) {
            decoded = decoded.replace(entity.getKey(), entity.getValue());
        }
        return decoded;
    }
}
