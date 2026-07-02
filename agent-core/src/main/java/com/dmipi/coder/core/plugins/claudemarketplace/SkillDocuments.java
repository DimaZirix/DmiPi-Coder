package com.dmipi.coder.core.plugins.claudemarketplace;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses one Claude-format {@code SKILL.md} document — an optional YAML-ish frontmatter carrying
 * {@code name} and {@code description}, then the instruction body — into a {@link Skill} rooted at
 * the given directory. A document without frontmatter still parses: the directory name and the
 * first non-blank line stand in for the missing name and description.
 */
final class SkillDocuments {

    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\s*\\n(.*?)\\n---\\s*\\n?", Pattern.DOTALL);

    private SkillDocuments() {
    }

    static Skill parse(final String directoryName, final String content, final String directory) {
        final Matcher frontmatter = FRONTMATTER.matcher(content);
        if (!frontmatter.find()) {
            return new Skill(directoryName, firstLine(content), content.strip(), directory);
        }
        final String header = frontmatter.group(1);
        final String body = content.substring(frontmatter.end()).strip();
        return new Skill(
                field(header, "name").orElse(directoryName),
                field(header, "description").orElseGet(() -> firstLine(body)),
                body,
                directory);
    }

    private static Optional<String> field(final String header, final String key) {
        return header.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(key + ":"))
                .map(line -> line.substring(key.length() + 1).strip())
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private static String firstLine(final String text) {
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("");
    }
}
