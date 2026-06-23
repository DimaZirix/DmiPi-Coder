package com.dmipi.coder.core.plugins.skills;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.ParameterSchema;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolKind;
import com.dmipi.coder.core.domain.tool.ToolParams;
import com.dmipi.coder.core.domain.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Progressive disclosure: the skill list rides in this tool's description (name + one line
 * each); the full instructions come back as the tool result when the model asks for one.
 */
final class SkillTool implements Tool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["name"],
              "properties": {
                "name": {"type": "string", "description": "The name of the skill to load."}
              }
            }""";

    private final Map<String, Skill> skills;

    SkillTool(final List<Skill> skills) {
        this.skills = skills.stream()
                .collect(Collectors.toMap(Skill::name, skill -> skill, (first, second) -> second, LinkedHashMap::new));
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Loads a skill — packaged instructions for a specific kind of task. When a task matches a listed skill, load it first and follow its instructions. Available skills:\n" + listing();
    }

    @Override
    public ToolKind kind() {
        return ToolKind.READ;
    }

    @Override
    public ParameterSchema parameterSchema() {
        return new ParameterSchema(SCHEMA);
    }

    @Override
    public Optional<String> validate(final ToolParams params) {
        if (params.string("name").filter(name -> !name.isBlank()).isEmpty()) {
            return Optional.of("Parameter 'name' is required — one of: " + String.join(", ", skills.keySet()) + ".");
        }
        return Optional.empty();
    }

    @Override
    public PermissionDecision defaultPermission(final ToolParams params) {
        return PermissionDecision.ALLOW;
    }

    @Override
    public String callSummary(final ToolParams params) {
        return params.string("name").orElse("");
    }

    @Override
    public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final String name = params.string("name").orElseThrow();
        final Skill skill = skills.get(name);
        if (skill == null) {
            return new ToolResult.Failure("No skill named '" + name + "'. Available: " + String.join(", ", skills.keySet()) + ".");
        }
        final String body = skill.instructions() + "\n\n(This skill's files are under: " + skill.directory() + ")";
        return new ToolResult.Success(body, new Display.Text("skill " + name));
    }

    private String listing() {
        return skills.values()
                .stream()
                .map(skill -> "- " + skill.name() + ": " + skill.description())
                .collect(Collectors.joining("\n"));
    }
}
