package com.dmipi.coder.core.plugins.skills;

import com.dmipi.coder.core.plugin.FileSystem;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads Claude-format skills — {@code skills/<name>/SKILL.md} with a YAML-ish frontmatter
 * carrying {@code name} and {@code description} — from {@code .coder/skills} under the user and
 * project anchors. On a name clash, project wins. A file without frontmatter still loads: the
 * directory name and first body line stand in.
 */
final class SkillLibrary {

    private static final String SKILLS_LOCATION = ".coder/skills";
    private static final String SKILL_FILE = "SKILL.md";
    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\s*\\n(.*?)\\n---\\s*\\n?", Pattern.DOTALL);

    private SkillLibrary() {
    }

    /** Every discovered skill, user scope first so a project skill of the same name replaces it. */
    static List<Skill> discover(final FileSystem userFiles, final FileSystem projectFiles) {
        final Map<String, Skill> byName = new LinkedHashMap<>();
        collect(byName, userFiles);
        collect(byName, projectFiles);
        return List.copyOf(byName.values());
    }

    private static void collect(final Map<String, Skill> byName, final FileSystem files) {
        final Path root = files.resolve(SKILLS_LOCATION);
        if (!files.exists(root)) {
            return;
        }
        for (final String entry : files.list(root)) {
            if (!entry.endsWith("/")) {
                continue;
            }
            final String directory = entry.substring(0, entry.length() - 1);
            final Path skillFile = files.resolve(SKILLS_LOCATION + "/" + directory + "/" + SKILL_FILE);
            if (files.exists(skillFile)) {
                final Skill skill = parse(directory, files.read(skillFile));
                byName.put(skill.name(), skill);
            }
        }
    }

    private static Skill parse(final String directory, final String content) {
        final Matcher frontmatter = FRONTMATTER.matcher(content);
        if (!frontmatter.find()) {
            return new Skill(directory, firstLine(content), content.strip());
        }
        final String header = frontmatter.group(1);
        final String body = content.substring(frontmatter.end()).strip();
        return new Skill(
                field(header, "name").orElse(directory),
                field(header, "description").orElseGet(() -> firstLine(body)),
                body);
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
