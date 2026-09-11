# dmipi-coder

[![Build](https://github.com/DimaZirix/DmiPi-Coder/actions/workflows/build.yml/badge.svg)](https://github.com/DimaZirix/DmiPi-Coder/actions/workflows/build.yml)

An agent engine for local LLMs, shipped as a coding agent. Plain Java, no framework, two runtime libraries.

Point it at an OpenAI-compatible endpoint (llama.cpp, LM Studio, vLLM, Ollama, or a hosted API) and, with the built-in plugins, it reads your project, edits files, runs commands, fetches pages, keeps notes, plans, delegates to subagents, and loads skills and MCP servers, all behind a permission gate that asks you before anything risky, with shell commands confined to a sandbox.

The coding abilities are plugins; the engine underneath is not about code. It knows a conversation loop, a permission gate, a sandbox contract and a plugin interface. Register other plugins and the same core is another kind of agent.

The engine and the user interface are separate modules. `agent-core` is a library with no user interface of its own: add it to your project and drive it from your code over three channels, prompts in, events out, questions to the user. `agent-console` is one front-end built on that library, the terminal one; a web app, an IDE plugin or a CI job would be others. A ten-line example is [below](#use-the-core-from-your-code).

## Three ideas behind it

**Small enough to audit.** Fewer dependencies means less code you have to trust and fewer places for something to slip in. Almost everything comes from the JDK: HTTP through `java.net.http`, processes through `ProcessBuilder`, no framework, no dependency-injection container. On top of the JDK the engine needs exactly two libraries:

| Library | What it is used for |
|---|---|
| **Jackson** (`jackson-databind` 3.1.4, which brings its own `jackson-core` and `jackson-annotations`) | Reading and writing JSON: the model's wire format, the settings files, tool arguments, saved sessions, MCP messages. |
| **java-diff-utils** (4.17) | The unified diffs shown as previews before you approve an edit. |

Both belong to `agent-core`, the engine with all built-in plugins. The console, `agent-console`, adds nothing of its own: it depends on `agent-core` and the JDK's console. JUnit 5 and AssertJ are used by the tests only and are not part of what ships.

Adding a library is a project decision: every new dependency is approved by the project owner before it goes in.

**Made of parts you can leave out.** An agent that cannot do something cannot be talked into doing it. So no ability is built into the engine: each one is a plugin, the agent can do exactly what the plugins you register offer, and nothing is on by default.

Three rules make that safe to rely on:

- A plugin declares what it needs, such as files, the shell, the network, a model, or a question to you, and receives only that. A plugin that never asked for file access cannot reach a file, whatever else is registered.
- Plugins never depend on each other. Removing one never breaks another; the model simply loses those tools.
- The permission gate and the sandbox are not plugins. They sit in the engine, under every plugin, and no plugin can remove or relax them.

Removing an ability is removing one line from the wiring:

| Leave out | And the agent can no longer |
|---|---|
| the file-editing plugin (`FilesEditPlugin`) | change a file. Reading still works: a read-only agent. |
| both file plugins | see your files at all. |
| the shell plugin | run any command. |
| the web and MCP plugins | reach the network. |
| the subagents plugin | delegate. |
| the memory plugin | open a memory file. |

The model protocol and the sandbox technology are plugins too, chosen by name in the settings file.

**Built as a coding agent, but not only a coding agent.** The built-in plugins are coding abilities: files, shell, web, skills, MCP servers, subagents. The engine underneath knows nothing about code. It runs a conversation loop, a permission gate, a sandbox contract and a plugin interface, and that is all. Register a different set of plugins and the same core is a support agent, an operations agent or a research assistant, with the same three channels, the same gate and the same questions to the user. Coding is the first use, not the boundary.

## Plugins

Every plugin is one class you register or leave out; the console registers all of them. *Needs* is the capability the plugin declares, and it receives nothing else. *Asks you before* is the plugin's own default; your rules and the approval mode can change it.

### Abilities

| Plugin | Adds | Needs | Asks you before | What it does |
|---|---|---|---|---|
| **Working on the project** | | | | |
| File reading (`FilesReadPlugin`) | `read_file`, `list_directory`, `glob`, `grep_search` | files | never | Reads, lists and searches the project. Every result is capped and says when it was cut. |
| File editing (`FilesEditPlugin`) | `edit`, `write_file` | files | changing a file, with the diff as preview | Exact-string edits and whole-file writes. Refuses to change a file the agent has not read. |
| Shell (`ShellPlugin`) | `run_shell_command` | shell | each command, with the command line as preview | Runs a command in the sandbox with a bounded timeout and capped output. Background commands optional. |
| **Reaching outside** | | | | |
| Web (`WebPlugin`) | `web_fetch` | network, a model | each fetch | Fetches a page and returns a summary written by the fastest model in a separate context. Private addresses are refused. |
| MCP (`McpPlugin`) | one `mcp__<server>__<tool>` per remote tool | network, files, configuration | each call, unless the server marks the tool read-only | Connects to the HTTP MCP servers declared in `.mcp.json` and offers their tools. |
| **Standing knowledge** | | | | |
| Memory (`MemoryPlugin`) | `memory` | files, configuration | saving, with the diff as preview | Loads `~/.coder/CODER.md` and the project's `CODER.md`, `AGENTS.md` or `CLAUDE.md` into the instructions; saves on request. |
| Skills (`SkillsPlugin`) | `skill` | files, configuration | never | Offers the skills found in `.coder/skills/<name>/SKILL.md`, in the Claude Code layout, from both the user and the project. |
| Claude plugin installer (`ClaudePluginInstallerPlugin`) | `install_plugin`, `list_plugins`, `remove_plugin` | shell, files, configuration, a question to you | installing or removing | Brings the skills and MCP servers of a Claude-format plugin into the native layout, and keeps a manifest of what it installed. |
| **Working method** | | | | |
| Planning (`PlanningPlugin`) | `todo_write`, `present_plan` | a question to you, the mode | never; plan approval is its own question | A visible task list, and presenting a plan to leave plan mode. |
| Subagents (`SubagentsPlugin`) | `task` | nested conversations | never; the subagent's own actions ask as usual | Delegates to an `explore` (fast model) or `review` (strong model) subagent that returns only a summary. Custom types from code. |

### Providers

A provider adds a mechanism rather than an ability. It contributes no tools and is chosen by name in the settings file: a model's `protocol`, or `sandbox.technology`.

| Plugin | Provides | Needs on the host | What it does |
|---|---|---|---|
| OpenAI-compatible (`OpenAiProviderPlugin`) | protocol `openai` | nothing | The chat-completions API with streaming, an idle guard, a thinking switch and an optional bearer key. |
| Direct (`DirectSandboxPlugin`) | technology `direct` | nothing | No confinement, and the model is told so. The default. |
| Bubblewrap (`BubblewrapSandboxPlugin`) | technology `bubblewrap` | `bwrap`; `systemd-run` for limits | Read-only host, writable project, private temp directory, optional memory and process limits. |
| Podman (`PodmanSandboxPlugin`) | technology `podman` | `podman`; `pasta` or `slirp4netns` for controlled egress | One fresh container per command with the project mounted, optional limits. The image must carry your toolchain. |

Details, options and what each removal costs: [docs/PLUGIN-CATALOG.md](docs/PLUGIN-CATALOG.md).

## Layout

| Module | What it is |
|---|---|
| `agent-core` | The embeddable engine: the conversation loop, the permission gate, the sandbox contract, the plugin interface, and every built-in plugin. A front-end talks to it over three channels: prompts in, typed events out, questions to the user. |
| `agent-console` | The terminal front-end. It renders the three channels and adds four slash commands (`/plan`, `/llm`, `/resume`, `/exit`). No agent logic of its own. |

## Get it

Needs Java 25. Three ways in, from the least to the most involved:

**The console, ready to run.** Every [release](https://github.com/DimaZirix/DmiPi-Coder/releases) carries one jar with everything inside. Start it in the project you want to work on:

```bash
cd ~/work/my-project
java -jar agent-console-<version>-all.jar
```

**The core, as a library.** `agent-core` is published to this repository's GitHub Packages on every release (and as a snapshot from every push to master). GitHub Packages needs a token even for public packages; the [User Manual](docs/USER-MANUAL.md#13-embedding-the-core) has the two-step setup.

```xml
<dependency>
    <groupId>com.dmipi</groupId>
    <artifactId>agent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

**From source.** Needs Maven as well.

```bash
mvn -q install
```

This builds both modules, runs the tests, and leaves the runnable console at `agent-console/target/agent-console-<version>-all.jar`. Or run `com.dmipi.coder.console.ConsoleMain` from your IDE with the working directory set to the project you want to work on.

## Use the core from your code

The smallest agent: one model, one read-only plugin, answers printed as they stream. A Java 25 compact source file, so this is the whole program.

```java
import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.plugins.files.FilesReadPlugin;
import com.dmipi.coder.core.plugins.openai.OpenAiProviderPlugin;

void main() {
    try (Coder coder = Coder.builder()
            // Out channel: the agent's answer, printed as it streams. Every other event is ignored.
            .out(event -> { if (event instanceof OutEvent.AnswerDelta(String text)) System.out.print(text); })
            // HIL channel: where the core asks a person. A real front-end shows the options and reads
            // a choice; this one prints the question and picks the last option. For a permission
            // question the last option is "Deny": the tool call is refused and the agent is told so.
            // This program never asks, because reading files needs no permission.
            .hil(question -> {
                System.out.println("Question: " + question.question());
                return Answer.of(question.options().getLast().id());
            })
            // The bundled system prompt.
            .standardInstructions()
            // One model on a local OpenAI-compatible server, and the provider plugin that speaks to it.
            .model(new ModelDeclaration("local", "openai", "http://localhost:8080/v1", Tier.BALANCED, 32_000))
            .registerPlugin(new OpenAiProviderPlugin())
            // The only ability: read, list and search files under the current directory.
            .registerPlugin(new FilesReadPlugin())
            .build()) {
        coder.runTurn("Say hello, then tell me in two sentences what this project is about.", new CancelToken());
    }
}
```

Everything not registered does not exist for this agent: it cannot edit, run commands or reach the network. Add `FilesEditPlugin` and the HIL channel starts receiving permission questions, one per edit, with the diff as the preview.

Run it from a project directory with a model server on port 8080. The console jar contains `agent-core` and its libraries, so it doubles as the classpath:

```bash
java -cp agent-console-<version>-all.jar Hello.java
```

Every builder call, the CI recipe and the network options are in the manual's [embedding section](docs/USER-MANUAL.md#13-embedding-the-core).

## Configure a model

`.coder/settings.json` in your home directory or in the project:

```json
{
  "models": [
    { "name": "local", "protocol": "openai", "endpoint": "http://localhost:1234/v1", "tier": "fast", "contextWindow": 32000 }
  ],
  "mode": "default",
  "sandbox": { "technology": "bubblewrap" }
}
```

Every key, the approval modes, permission rules, memory, skills, MCP servers and embedding the core in your own program: [docs/USER-MANUAL.md](docs/USER-MANUAL.md).

## Documentation

| Read | For |
|---|---|
| [docs/README.md](docs/README.md) | The index of the documentation set. |
| [docs/FUNCTIONAL-OVERVIEW.md](docs/FUNCTIONAL-OVERVIEW.md) | What the agent does, feature by feature, in plain language. |
| [docs/USER-MANUAL.md](docs/USER-MANUAL.md) | Running, configuring, embedding. |
| [docs/PLUGIN-CATALOG.md](docs/PLUGIN-CATALOG.md) | Every built-in plugin. |
| [docs/PLUGIN-DEVELOPMENT.md](docs/PLUGIN-DEVELOPMENT.md) | Writing your own plugin or provider. |

## License

[MIT](LICENSE).
