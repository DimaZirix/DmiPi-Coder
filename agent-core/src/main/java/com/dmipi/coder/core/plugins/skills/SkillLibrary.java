package com.dmipi.coder.core.plugins.skills;

import com.dmipi.coder.core.plugin.FileSystem;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads skills in the native format — {@code .coder/skills/<name>/SKILL.md} with a YAML-ish
 * frontmatter carrying {@code name} and {@code description} — from the user and project anchors.
 * On a name clash, project wins. A file without frontmatter still loads: the directory name and
 * first body line stand in. The file layout is deliberately Claude-compatible, so an installer
 * can drop foreign skills in unchanged.
 */
final class SkillLibrary {

    private static final Logger LOGGER = Logger.getLogger(SkillLibrary.class.getName());
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
            final String location = SKILLS_LOCATION + "/" + directory;
            final Path skillFile = files.resolve(location + "/" + SKILL_FILE);
            if (files.exists(skillFile)) {
                try {
                    // The directory is recorded absolute: a user-scope skill's files do not live
                    // under the project, so a project-relative label would send the model astray.
                    final Skill skill = parse(directory, files.read(skillFile), files.resolve(location).toString());
                    byName.put(skill.name(), skill);
                } catch (final RuntimeException unreadable) {
                    LOGGER.warning("Skill '" + directory + "' could not be loaded; skipping it: " + unreadable.getMessage());
                }
            }
        }
    }

    private static Skill parse(final String directory, final String content, final String location) {
        final Matcher frontmatter = FRONTMATTER.matcher(content);
        if (!frontmatter.find()) {
            return new Skill(directory, firstLine(content), content.strip(), location);
        }
        final String header = frontmatter.group(1);
        final String body = content.substring(frontmatter.end()).strip();
        return new Skill(
                field(header, "name").orElse(directory),
                field(header, "description").orElseGet(() -> firstLine(body)),
                body,
                location);
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
