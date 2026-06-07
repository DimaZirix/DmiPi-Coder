package com.dmipi.coder.core.domain.tool;

import java.util.List;
import java.util.Optional;

/** Read view of a tool call's parsed arguments; {@link #rawJson()} forwards them verbatim. */
public interface ToolParams {

    Optional<String> string(String key);

    Optional<Long> integer(String key);

    Optional<Boolean> bool(String key);

    Optional<List<String>> stringList(String key);

    /** The whole argument object as the raw JSON it was parsed from. */
    String rawJson();
}
