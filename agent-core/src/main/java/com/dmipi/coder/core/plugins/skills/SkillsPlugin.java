package com.dmipi.coder.core.plugins.skills;

import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import java.util.List;
import java.util.Set;

/**
 * Scans {@code .coder/skills/<name>/SKILL.md} under the user and project anchors and
 * contributes exactly one tool: {@code skill}. No skills found → no tool registered.
 */
public final class SkillsPlugin implements Plugin {

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.FILE_SYSTEM, CapabilityType.CONFIGURATION);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        final List<Skill> skills = SkillLibrary.discover(capabilities.userFileSystem(), capabilities.fileSystem());
        if (!skills.isEmpty()) {
            registrar.registerTool(new SkillTool(skills));
        }
    }
}
