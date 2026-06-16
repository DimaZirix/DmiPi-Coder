package com.dmipi.coder.core.application.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads bundled prompt markdown from the classpath under {@code /prompt/}. A missing resource is a build error, not a runtime one. */
public final class PromptResources {

    private static final String ROOT = "/prompt/";

    private PromptResources() {
    }

    /** True when a bundled prompt resource with this name exists on the classpath. */
    public static boolean exists(final String name) {
        return PromptResources.class.getResource(ROOT + name) != null;
    }

    public static String load(final String name) {
        try (InputStream stream = PromptResources.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IllegalStateException("Bundled prompt resource is missing: " + ROOT + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not read the prompt resource " + ROOT + name + ": " + failure.getMessage(), failure);
        }
    }
}
