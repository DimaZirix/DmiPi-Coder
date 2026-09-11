# Open Questions

*Cross-cutting decisions the documentation could not settle from the code or the specification. Each entry names where it bites, what is already decided, and what is still open. When one is decided, fold the answer into the relevant document and delete it here.*

## 1. Should capability calls made by plugin code be gated per call?

**Where:** [PERMISSIONS-TECHNICAL.md §8](PERMISSIONS-TECHNICAL.md#8-boundaries-and-guarantees), [PLUGINS-TECHNICAL.md §13](PLUGINS-TECHNICAL.md#13-boundaries-and-guarantees).
**Decided:** every *tool call* passes the gate, from the model, a subagent, or plugin code through the `Tools` capability. Capability objects themselves are confined (anchored paths, sandboxed commands, screened HTTP).
**Open:** the specification says every capability *use* passes the permission layer. Today a plugin's own `fileSystem().write(...)` is anchored but raises no question. Options: leave as is (the built-in plugins only write through gated tools, and the trust boundary is "review the plugin"); or interpose a gate on the mutating capability methods with the plugin named as the asker. Proposed default: leave as is, document the trust boundary, revisit when a third-party plugin ecosystem exists.

## 2. Network and sandbox knobs in the settings file

**Where:** [USER-MANUAL.md §6](USER-MANUAL.md#6-the-sandbox), [FUNCTIONAL-OVERVIEW.md §4](FUNCTIONAL-OVERVIEW.md#4-running-commands), [tasks/T08](tasks/T08-network-and-sandbox-settings.md).
**Decided:** network isolation, controlled egress with an allowlist, resource limits and the podman image exist on the builder and the plugin constructors.
**Open:** none of them has a settings key, so the console always runs with an open network, bubblewrap without limits and podman on Alpine. Proposed default: add `sandbox.network` (`open` | `isolated` | `controlled`), `sandbox.allowedHosts[]`, `sandbox.limits.{memoryMax, tasksMax}` and `sandbox.image`, applied by the builder onto the registered providers.

## 3. A settings-driven plugin list for the console

**Where:** [FUNCTIONAL-OVERVIEW.md §16](FUNCTIONAL-OVERVIEW.md#16-choosing-what-the-agent-may-do), [USER-MANUAL.md §12](USER-MANUAL.md#12-choosing-what-the-agent-may-do).
**Decided:** an embedder chooses plugins in code; the console's set is fixed in its startup wiring and changed by editing it.
**Open:** whether the console should read a `plugins[]` list from settings so a user can drop file access without rebuilding. The tension: the sandbox providers are trusted computing base and must stay explicit, and a settings-driven list is one step from auto-discovery. Proposed default: allow *disabling* built-ins by name in settings (`disabledPlugins: ["files-edit", "shell"]`), never enabling anything not compiled in.

## 4. Text-embedded tool calls for models without native tool calling

**Where:** [LLM-TECHNICAL.md §6](LLM-TECHNICAL.md#6-boundaries-and-guarantees).
**Decided:** only native `tool_calls` are decoded; the fallback parser of the predecessor project (`<tool_call>` tags, JSON in prose, a streaming extractor holding back partial calls, synthetic indices from 1000) is recorded as reference, not planned.
**Open:** whether to build it. Proposed default: only when a concretely needed target model cannot emit native tool calls; until then prefer models that can, since the worked examples and self-explaining results already raise their success rate. If built, the examples and the extractor must teach and parse the same format.

## 5. How hard should egress enforcement be?

**Where:** [SANDBOX-TECHNICAL.md §7](SANDBOX-TECHNICAL.md#7-honest-limits).
**Decided:** cooperative enforcement (proxy environment plus DNS blackhole); the threat model is accidents, not adversaries; the documentation says so.
**Open:** whether a controlled network should also be packet-level isolated (a network namespace with only the proxy reachable), which would make a hostile binary's raw-IP dialling fail. Proposed default: keep cooperative for v1; treat hard isolation as a later tightening behind the same contract, so no user-facing behaviour changes.

## 6. Project memory beyond the project directory

**Where:** [FUNCTIONAL-OVERVIEW.md §7](FUNCTIONAL-OVERVIEW.md#7-memory), [tasks/T07](tasks/T07-memory-repo-root-walk.md).
**Decided:** project memory loads from the project directory only; the specification wants a walk up to the repository root for monorepos.
**Open:** the walk reaches outside the project boundary the file capability enforces, and needs repository-root detection (an environment fact under its own grant). Proposed default: a builder grant `memoryFromRepositoryRoot()` that anchors a third file system at the detected root, read-only, loading outer files first.

## 7. Resume after the first turn

**Where:** [FUNCTIONAL-OVERVIEW.md §13](FUNCTIONAL-OVERVIEW.md#13-sessions).
**Decided:** `/resume` is refused once the conversation has history, so the saved dialogue is never grafted into a live one.
**Open:** whether "start a new conversation from a saved session" should be a facade function, so a user need not restart the console. Proposed default: a `Coder.startFromSession(name)` that resets the conversation first; the console maps `/resume` onto it when history exists, after confirming.

## 8. The next-speaker check: opt-in or always on?

**Where:** [CONVERSATION-TECHNICAL.md §4](CONVERSATION-TECHNICAL.md#4-the-guards).
**Decided:** opt-in on the builder for cost; the console turns it on.
**Open:** the specification wires it unconditionally. Proposed default: keep opt-in; a documented deviation is better than a hidden cost.

## 9. The MCP plugin's file-system requirement

**Where:** [PLUGINS-TECHNICAL.md §9](PLUGINS-TECHNICAL.md#9-mcp).
**Decided:** the plugin requires HTTP, file system and configuration, because the configuration capability carries anchors only and the config files are read through the anchored file systems.
**Open:** whether the configuration capability should offer a read-only "conventional file" reader so that content plugins (skills, MCP, memory) stop needing the full file-system capability. Proposed default: leave as is; the file systems are anchored and read-only use is easy to review.

## 10. Naming the asker on questions from parallel work

**Where:** [INTERFACE-TECHNICAL.md §6](INTERFACE-TECHNICAL.md#6-boundaries-and-guarantees), [tasks/T01](tasks/T01-parallel-subagents.md).
**Decided:** questions are serialized; there is no parallel work yet, so no question needs to name its asker.
**Open:** with parallel subagents, a question must say who asks. Proposed default: an optional `asker` field on `Question`, rendered as a prefix by the console, set by the core for subagent-originated questions.
