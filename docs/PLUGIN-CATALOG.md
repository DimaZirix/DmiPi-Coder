# Plugin Catalog

*Every built-in plugin: what it adds, what it needs, how it is configured, and what you lose without it. For people assembling an agent, whether in the console's startup wiring or in their own program.*

The agent can do exactly what its registered plugins offer. There are two kinds: **tool plugins** add tools the model can call (and sometimes a section of instructions); **provider plugins** add a mechanism the core selects by name (a model protocol, a sandbox technology) and contribute no tools. All live in `agent-core` under `com.dmipi.coder.core.plugins`.

Each plugin declares the capabilities it needs and receives only those. The capabilities are: **file system** (files inside the project), **configuration** (the two anchor directories; with file system it also unlocks files under the user directory), **shell** (run a command in the sandbox), **HTTP** (guarded web requests), **LLM** (call a model by tier), **HIL** (ask the user), **output** (write to the agent's output), **tools** (call another plugin's tool by name), **conversations** (run a subagent), **modes** (read and switch the approval mode).

## Tool plugins

| Plugin | Tools | Needs | What it does | Without it |
|---|---|---|---|---|
| **FilesReadPlugin** | `read_file`, `list_directory`, `glob`, `grep_search` | file system | Reads, lists and searches the project. Every result is capped and says when it was cut. None of the tools ask for permission. | The agent cannot read your files. |
| **FilesEditPlugin** | `edit`, `write_file` | file system | Changes files: exact-string replacement with a unique match, and whole-file writes. Both ask with a diff preview; both refuse to touch an existing file the agent has not read. | A read-only agent. |
| **ShellPlugin** | `run_shell_command` | shell | Runs a command in the sandbox, in the project directory, with a bounded timeout and capped output. Asks with the command line as preview. Optionally allows background commands. | No command ever runs. Also disables the installer, which needs the shell. |
| **WebPlugin** | `web_fetch` | HTTP, LLM | Fetches an http(s) page and returns a summary answering the agent's question, written by the fastest model in a separate context. Private and local addresses are refused. Asks with the URL as preview. | No web access. |
| **PlanningPlugin** | `todo_write`, `present_plan` | HIL, modes | Keeps the visible task list; in plan mode presents the plan for approval and switches the mode back on approval. Adds a task-planning section to the instructions. Both tools are main-conversation only. | No task list; plan mode can only be left with `/plan off`. |
| **MemoryPlugin** | `memory` | file system, configuration | Loads the user and project memory files into the instructions at start; reads and saves them on request. Saves ask with a diff; a user-scope save is never auto-approved by *allow edits*. | No memory file is ever opened. |
| **SkillsPlugin** | `skill` | file system, configuration | Finds `.coder/skills/<name>/SKILL.md` under both anchors and offers one tool listing them; loading returns the instructions. No skills, no tool. | Skills are not loaded. |
| **McpPlugin** | `mcp__<server>__<tool>` per remote tool | HTTP, file system, configuration | Connects to the HTTP MCP servers in `.mcp.json` (project) and `~/.coder/.mcp.json` (user) and proxies each remote tool. Read-only tools run silently; others ask. Unreachable servers are skipped. | No external tool servers, whatever the config files say. |
| **ClaudePluginInstallerPlugin** | `install_plugin`, `list_plugins`, `remove_plugin` | shell, file system, configuration, HIL | Installs Claude-format plugins (skills, MCP servers) from a git repository or a directory into the native layout, under the user or project scope, asking which when unspecified; keeps a manifest; lists and removes. | No plugin installation through the agent. |
| **SubagentsPlugin** | `task` | conversations | Delegates a task to a subagent type: `explore` (fast tier) or `review` (strong tier), or your own types. The subagent inherits the other plugins' tools and returns only a summary. Main-conversation only. | No delegation. |

## Provider plugins

| Plugin | Provides | Selected by | Notes |
|---|---|---|---|
| **OpenAiProviderPlugin** | Protocol `openai` | A model's `protocol` | The OpenAI chat-completions API with streaming, at `<endpoint>/chat/completions`. Optional bearer key from an environment variable, idle-stream guard, thinking switch, schema-constrained replies with fallback. |
| **DirectSandboxPlugin** | Technology `direct` | `sandbox.technology` | No confinement, honestly reported. The default technology. Refuses network isolation or egress control at build time. |
| **BubblewrapSandboxPlugin** | Technology `bubblewrap` | `sandbox.technology` | Unprivileged namespaces: read-only host, writable project and additional directories, private `/tmp`. Optional memory and task limits via `systemd-run`. Probed at startup. |
| **PodmanSandboxPlugin** | Technology `podman` | `sandbox.technology` | One ephemeral container per command, your user id, no new privileges, project and additional directories mounted. Optional memory and pids limits. Proxied egress through `pasta` or `slirp4netns`. |

Sandbox providers are trusted computing base: a faulty one silently un-sandboxes every command. Register only the ones you have reviewed, and select one by name; they are never auto-discovered.

## Constructor options

| Plugin | Constructor | Meaning |
|---|---|---|
| `FilesReadPlugin`, `FilesEditPlugin` | `()` | The read-before-edit gate off. |
| | `(ReadTracker tracker)` | Share one `ReadTracker` between the two to turn the gate on: an edit to an existing file the agent has not read this session is refused. |
| `ShellPlugin` | `()` | Foreground commands only. |
| | `(true)` | Adds the `is_background` parameter; background processes are stopped at session close. |
| `SubagentsPlugin` | `()` | The built-in `explore` and `review` types. |
| | `(List<SubagentType> types)` | Your own types: name, description, instructions, preferred tier, step budget. An empty list registers no tool. |
| `BubblewrapSandboxPlugin` | `()` | Filesystem confinement only. |
| | `(ResourceLimits limits)` | Memory and task bounds, e.g. `new ResourceLimits("2G", 256)`, enforced through `systemd-run --user --scope`. |
| `PodmanSandboxPlugin` | `()` | The default image `docker.io/library/alpine:latest`, no limits. |
| | `(String image)` | An image carrying the project's toolchain. |
| | `(String image, ResourceLimits limits)` | Plus memory and pids bounds. |
| the others | `()` | No options. |

The console constructs: `FilesReadPlugin` and `FilesEditPlugin` sharing one tracker, `ShellPlugin(true)`, `BubblewrapSandboxPlugin()` without limits, `PodmanSandboxPlugin()` with the default image, everything else with its no-argument constructor.

## Tools at a glance

What the model sees, in one table. *Kind* is what the permission gate reasons about; *default* is the tool's own baseline before rules, mode and your "always" answers.

| Tool | Plugin | Kind | Default | Parameters |
|---|---|---|---|---|
| `read_file` | files (read) | read | run | `path`, `offset`, `limit` |
| `list_directory` | files (read) | read | run | `path` |
| `glob` | files (read) | search | run | `pattern` |
| `grep_search` | files (read) | search | run | `pattern`, `glob`, `path`, `case_sensitive`, `limit` |
| `edit` | files (edit) | edit | ask, diff preview | `path`, `old_string`, `new_string`, `replace_all` |
| `write_file` | files (edit) | edit | ask, diff preview | `path`, `content` |
| `run_shell_command` | shell | execute | ask, command preview; "always" per exact command | `command`, `timeout_seconds`, `is_background` (when enabled) |
| `web_fetch` | web | network | ask, URL preview | `url`, `prompt` |
| `todo_write` | planning | other | run | `todos[]` of `content`, `status` (`pending`, `in_progress`, `completed`) |
| `present_plan` | planning | other | run; raises its own approve/revise question | `plan` |
| `memory` | memory | read on read; edit on project save; execute on user save | run on read; ask on save, diff preview | `action` (`read`, `save`), `scope` (`user`, `project`), `content` |
| `skill` | skills | read | run | `name` |
| `mcp__<server>__<tool>` | MCP | network if the server marks it read-only, else execute | run if read-only, else ask | as advertised by the server |
| `install_plugin` | installer | execute | ask | `source`, `plugin`, `scope` (`user`, `project`) |
| `list_plugins` | installer | read | run | none |
| `remove_plugin` | installer | edit | ask | `name`, `scope` |
| `task` | subagents | other | run | `type`, `instruction` |

The kinds matter in three places: plan mode blocks every *edit* and *execute* call; *allow edits* auto-approves *edit* calls only; the hard limits and the per-command "always" scope apply to *execute* calls. A user-scope memory save deliberately reports *execute* so that *allow edits* never auto-approves it.
