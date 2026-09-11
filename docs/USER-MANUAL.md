# dmipi-coder — User Manual

*How to build, run and configure the agent, and how to shape what it may do. Written for people who run the console or embed the core in their own program. Read the [Functional Overview](FUNCTIONAL-OVERVIEW.md) first for what the agent does; this page is the reference for how to set it up.*

The manual covers the console and the settings files first, then the content you can add (memory, skills, MCP servers, Claude plugins), then embedding the core in your own Java program. Writing your own plugin is a separate guide: [PLUGIN-DEVELOPMENT.md](PLUGIN-DEVELOPMENT.md). What each built-in plugin does is in the [Plugin Catalog](PLUGIN-CATALOG.md).

## Contents

1. [Requirements and build](#1-requirements-and-build)
2. [Running the console](#2-running-the-console)
3. [Configuring models](#3-configuring-models)
4. [Approval modes and slash commands](#4-approval-modes-and-slash-commands)
5. [Permission rules](#5-permission-rules)
6. [The sandbox](#6-the-sandbox)
7. [Memory files](#7-memory-files)
8. [Skills](#8-skills)
9. [MCP servers](#9-mcp-servers)
10. [Installing Claude plugins](#10-installing-claude-plugins)
11. [Sessions](#11-sessions)
12. [Choosing what the agent may do](#12-choosing-what-the-agent-may-do)
13. [Embedding the core](#13-embedding-the-core)
14. [Troubleshooting](#14-troubleshooting)

---

## 1. Requirements and build

| Need | Version |
|---|---|
| Java | 25 |
| Maven | 3.9 or later |
| A model server | Anything speaking the OpenAI chat-completions API with streaming: llama.cpp server, LM Studio, vLLM, Ollama, LocalAI, or a hosted endpoint. |
| Optional, for confinement | `bwrap` (bubblewrap) on the host, or `podman` with `pasta` or `slirp4netns`. |
| Optional, for the installer | `git` on the host. |

You do not have to build anything to run the console: every [GitHub release](https://github.com/DimaZirix/DmiPi-Coder/releases) carries `agent-console-<version>-all.jar`, one jar with the engine and its libraries inside (§2). To embed the core, take `agent-core` from GitHub Packages (§13). To build from source, from the repository root:

```bash
mvn -q install
```

This runs the tests and leaves the runnable console at `agent-console/target/agent-console-<version>-all.jar`. Pushes to `master` and tags run the same build on GitHub Actions; a tag `vX.Y.Z` also publishes `agent-core` X.Y.Z to GitHub Packages and creates the release with the jar.

The runtime dependencies are deliberately few. Everything else is the JDK.

| Module | Runtime dependencies |
|---|---|
| `agent-core` | `tools.jackson.core:jackson-databind` 3.1.4 (JSON; brings its own `jackson-core` and `jackson-annotations`), `io.github.java-diff-utils:java-diff-utils` 4.17 (unified diffs). |
| `agent-console` | `agent-core` only. |
| Tests, both modules | JUnit Jupiter 5.12.2, AssertJ 3.27.3 (test scope). |

Adding a dependency is a project decision: each one is approved by the project owner before it is added.

## 2. Running the console

The console works on the project it is started in: the current working directory is the project directory. The user directory is your home.

**From the release jar:** download `agent-console-<version>-all.jar` from the releases page, go to the project, and start it.

```bash
cd ~/work/my-project
java -jar agent-console-<version>-all.jar
```

**From a source build:** the same jar is at `agent-console/target/` after `mvn -q install`. Or run `com.dmipi.coder.console.ConsoleMain` from your IDE with the working directory set to the project you want to work on.

**What you see at start:**

```
Ready. Type a prompt, a /command (/plan, /resume, /llm, /exit), or /exit.
Saved sessions: session-2026-09-10-14-02-11-... — continue one with /resume <name>.

>
```

Type a prompt and press Enter. Ctrl+C cancels a running turn; when the console is idle it exits.

**What the console has switched on.** The console registers every built-in plugin and every built-in sandbox technology, reads both settings files, autosaves sessions, adds the environment facts and worked examples to the model's instructions, sends periodic reminders, and runs the stalled-step check. The exact list is the startup wiring in `ConsoleMain`; §12 explains how to change it.

**If the console cannot start** it prints the reason and an example settings file. The usual causes: no model declared with a protocol a provider speaks, a settings file with a typo, an API key variable that is not set.

## 3. Configuring models

Models are declared in `.coder/settings.json`, in your home directory (applies everywhere) or in the project (this project only). A project declaration with the same name as a user one replaces it. The console also has a built-in model named `local` at `http://localhost:8080/v1`, tier balanced, 128 000 tokens; declaring a model named `local` replaces it, declaring other names adds to it.

The smallest working file:

```json
{
  "models": [
    {
      "name": "local",
      "protocol": "openai",
      "endpoint": "http://localhost:1234/v1",
      "tier": "fast",
      "contextWindow": 32000
    }
  ]
}
```

Every key of a model declaration:

| Key | Required | Values | Meaning |
|---|---|---|---|
| `name` | yes | text | How the model is referred to (`/llm <name>`). Unique. |
| `protocol` | yes | `openai` | The provider that speaks to it. `openai` is the built-in OpenAI-compatible provider; requests go to `<endpoint>/chat/completions`. |
| `endpoint` | yes | URL | The server's base URL, typically ending in `/v1`. A trailing slash is fine. |
| `tier` | yes | `fast`, `balanced`, `strong` | Your ranking of the model within your set. Used for "fastest", "strongest", "at least balanced" selection. |
| `contextWindow` | yes | positive integer | The window size in tokens. Compaction triggers at 70% of it. |
| `promptStyle` | no | `general` (default), `native`, `qwen_coder`, `qwen_vl` | Which worked tool-call examples the model is shown. Today every style resolves to the general examples; the value is accepted for forward compatibility. |
| `idleTimeoutSeconds` | no | positive integer, default 900 | How long a stream may stay completely silent before the turn fails. Slow generation never trips it. |
| `apiKeyEnv` | no | environment variable name | Sends `Authorization: Bearer <value>`. The variable must be set at startup, or startup fails with a message naming it. Never put the key itself in the file. |
| `thinking` | no | `true` (default), `false` | Whether the model's thinking is on for the conversation (the llama.cpp and Qwen `enable_thinking` switch). Internal control calls always force it off. |
| `structuredOutput` | no | `auto` (default), `off` | Whether the agent may ask the server for schema-constrained replies. Under `auto` a server that rejects them falls back to text parsing. |

Several models, different tiers:

```json
{
  "models": [
    { "name": "qwen-7b", "protocol": "openai", "endpoint": "http://localhost:8080/v1", "tier": "fast", "contextWindow": 32000 },
    { "name": "qwen-32b", "protocol": "openai", "endpoint": "http://localhost:8081/v1", "tier": "strong", "contextWindow": 128000, "thinking": false },
    { "name": "hosted", "protocol": "openai", "endpoint": "https://api.example.com/v1", "tier": "strong", "contextWindow": 200000, "apiKeyEnv": "EXAMPLE_API_KEY" }
  ]
}
```

The first declared model is active at start. Which model does what:

| Work | Model used |
|---|---|
| The conversation, subagents without a tier preference, compaction summaries | The active model. |
| The stalled-step check, the web page summary | The fastest. |
| Subagent types with a preference (`explore` wants fast, `review` wants strong) | The cheapest model at or above that tier; the strongest when none reaches it. |

## 4. Approval modes and slash commands

The starting mode comes from settings:

```json
{ "mode": "default" }
```

| Value | Mode |
|---|---|
| `default` | Anything sensitive asks. |
| `plan` | Read-only until a plan is approved. |
| `allow_edits` | File edits run without asking; commands, web fetches and installs still ask. |
| `allow_all` | Nothing asks. The sandbox is what remains between the agent and your machine. |
| `dont_ask` | Nothing asks; anything that would ask is blocked. For scripts and CI. |

Whatever the mode, a deny rule holds and the hard limits hold.

Console commands:

| Command | Effect |
|---|---|
| `/plan` | Plan mode on. `/plan off`: back to default. |
| `/resume` | List saved sessions. `/resume <name>`: continue one, before the first prompt. |
| `/llm` | List models, the active one marked with `*`. `/llm <name>`: switch. |
| `/exit` | Quit. Trailing text is ignored. |

Everything else you type is a prompt.

## 5. Permission rules

Rules are decided before the mode is. Put them in `.coder/settings.json`:

```json
{
  "permissions": [
    { "tool": "run_shell_command", "argument": "git status*", "decision": "allow" },
    { "tool": "run_shell_command", "argument": "mvn -q test", "decision": "allow" },
    { "tool": "run_shell_command", "argument": "git push*", "decision": "deny" },
    { "tool": "edit", "argument": "src/generated/*", "decision": "deny" },
    { "tool": "web_fetch", "decision": "deny" },
    { "tool": "*", "argument": "*secrets*", "decision": "deny" }
  ]
}
```

| Key | Default | Meaning |
|---|---|---|
| `tool` | `*` | The tool name, or `*` for any tool. |
| `argument` | any | A pattern with `*` wildcards, matched against the **whole** of what the call targets: the full command line for a shell command, the path for a file tool, the URL for a fetch, `mcp` arguments as JSON. `ls*` matches `ls -la` and not `false`. Omitted: every call to the tool matches. |
| `decision` | required | `allow`, `ask` or `deny`. |

How rules combine:

- When several rules match one call, the strictest wins: deny over ask over allow.
- **deny** blocks the call in every mode, *allow all* included, and no answer of yours can override it.
- **allow** turns a call that would have asked into a run. It never overrides a deny, a plugin's own tightening, or a hard limit.
- **ask** makes an otherwise silent call ask.
- Hard limits are checked before any rule and cannot be allowed away.

Rules from the user file and the project file are combined; both apply.

## 6. The sandbox

Every shell command runs inside the configured technology. Configure it in settings:

```json
{
  "sandbox": {
    "technology": "bubblewrap",
    "additionalWritableDirectories": ["/home/me/.m2", "/home/me/.cache/pip"]
  },
  "shell": {
    "defaultTimeoutSeconds": 120,
    "maxTimeoutSeconds": 600
  }
}
```

| Key | Default | Meaning |
|---|---|---|
| `sandbox.technology` | `direct` | `direct`, `bubblewrap` or `podman`. A name no registered provider matches stops startup. |
| `sandbox.additionalWritableDirectories` | none | Directories outside the project a command may write: package caches, a build output directory. Absolute paths. |
| `shell.defaultTimeoutSeconds` | 120 | The timeout of a command that does not ask for one. |
| `shell.maxTimeoutSeconds` | 600 | The most a command may ask for; larger requests are clamped. |

| Technology | Requirements | What you get |
|---|---|---|
| `direct` | nothing | No confinement. Honest: the agent is told it runs on your system directly. Fine with a trusted model and the default mode, where every command is shown to you first. |
| `bubblewrap` | `bwrap` installed | The whole machine visible read-only; writable: the project, the additional directories, a private `/tmp` that is discarded after each command. Your toolchain is available as is. At startup a probe checks that a write outside the allowed paths really fails; a sandbox that lies is refused. |
| `podman` | `podman` installed; for controlled egress also `pasta` or `slirp4netns` | Each command in a fresh, removed-afterwards container of an image, running as your user id, no privilege escalation. The project and the additional directories are mounted writable. The default image is Alpine; it does not carry your toolchain, so pick an image that does. A liveness probe runs at startup. |

**Resource limits and the podman image** are set in code today, not in the settings file: the console registers bubblewrap without limits and podman with the default Alpine image. An embedding program passes both to the plugin constructors (§13). Making them settings keys is an open point ([OPEN-QUESTIONS.md](OPEN-QUESTIONS.md)).

**Network.** Commands see your network unrestricted unless an embedding program asks otherwise (§13): the network can be cut entirely, or routed through a control point that lets listed hosts through and asks you about any other host at connect time. Both need `bubblewrap` or `podman`; `direct` cannot enforce them and refuses at build time. The console has no settings key for this yet.

**Background commands.** The console enables them: the agent may start a server or a watcher in the background, and the session stops it at exit. With podman, a background container is not stopped at session end (a known limit; foreground commands are bounded inside the container).

## 7. Memory files

| Scope | File | Loaded |
|---|---|---|
| User | `~/.coder/CODER.md` | First, in every project. |
| Project | The first that exists of `CODER.md`, `AGENTS.md`, `CLAUDE.md` in the project directory. | Last, so it wins where the two disagree. |

Write them as short markdown: rules, conventions, pointers. Every line costs tokens on every request.

A line consisting only of `@` and a path inlines that file, relative to the memory file, inside the same scope:

```markdown
# Project memory
- Build with `mvn -q install`; never run `mvn deploy`.
- Tests are Given/When/Then with `@DisplayName`.
@docs/conventions.md
```

Imports nest a few levels, ignore cycles, and never leave the scope's directory. When the agent saves memory it reads the file first and writes the complete new content; the `@` lines are kept as they are, never flattened.

Saving is asked about with the diff as preview: a project save like any edit, a user save always (except in *allow all*). Reads never ask. Without the memory plugin registered no memory file is ever opened.

## 8. Skills

```
~/.coder/skills/<name>/SKILL.md        available in every project
<project>/.coder/skills/<name>/SKILL.md   this project; wins on a name clash
```

A `SKILL.md`:

```markdown
---
name: deploy
description: Deploy the app safely, step by step.
---

# Deploy

1. Run the tests.
2. ...
```

The header (`name`, `description`) is what the agent sees in the `skill` tool's description; the body is what it receives when it loads the skill, together with the skill's directory so that relative references resolve. A file without a header still loads: the folder name and the first line stand in. A file that cannot be read is skipped with a warning.

The layout is the Claude Code skill layout, so a Claude skill folder can be dropped in unchanged, or installed with the installer (§10).

## 9. MCP servers

```
<project>/.mcp.json        this project; wins on a name clash
~/.coder/.mcp.json         every project
```

```json
{
  "mcpServers": {
    "log-reader": {
      "type": "http",
      "url": "http://log-reader.internal/mcp",
      "timeout": 60000
    }
  }
}
```

| Key | Required | Meaning |
|---|---|---|
| `type` | yes | Must be `http` (streamable HTTP). Any other value is skipped with a warning, so a config shared with other clients still loads. |
| `url` | yes | The server's MCP endpoint. Missing or blank stops startup. |
| `timeout` | no | Per-request timeout in milliseconds, default 60 000. |

Each remote tool appears as `mcp__<server>__<tool>`. A tool the server marks read-only runs without asking; every other asks, is blocked in plan mode, and is not softened by *allow edits*. A rule can name an MCP tool like any other (`"tool": "mcp__log-reader__getLogs"`). A server that cannot be reached at startup is skipped with a warning.

The `url` is your own configuration, so it may point at a private or local address; the fetch tool's private-address screening does not apply here.

## 10. Installing Claude plugins

Ask the agent, in plain words:

> Install the java-standards plugin from https://git.example.com/team/claude-marketplace.git into my user space.

The agent calls `install_plugin` with the source, the plugin folder (for a marketplace repository, one plugin per top-level folder) and the scope. Had you not said where, it would ask you: user space or project space. The install asks for permission, because it runs `git` and writes files. Then:

- `skills/<name>/SKILL.md` folders are copied under `.coder/skills` of the chosen scope;
- the plugin's `.mcp.json` servers are merged into the scope's MCP file (`.mcp.json` in the project, `~/.coder/.mcp.json` for the user);
- agents, commands, hooks and output styles are reported as skipped;
- the install is recorded in `.coder/installed-plugins.json` of that scope.

Reinstalling the same plugin cleanly replaces what it installed before. `list_plugins` shows what is installed where, with the skills and servers each brought; hand-written skills that no plugin owns are reported separately. `remove_plugin` deletes exactly what the manifest recorded, after asking, and asks which scope when the plugin is installed in both. Changes apply at the next session start.

## 11. Sessions

The console autosaves after every turn to `.coder/sessions/session-<date-time>.json` in the project, one file per console run. At start it lists what is there. `/resume <name>` continues a session; it must come before your first prompt, or it is refused.

A resume prints one of two notes:

| Note | Meaning |
|---|---|
| `prompt reused, cache warm` | The tools, plugins, model and environment match the save; the model's cached prompt prefix can be reused by a server that kept it (llama.cpp with a slot cache, vLLM prefix caching). |
| `environment changed, prompt rebuilt` | Something changed since the save; the instructions were rebuilt, the dialogue kept. |

Session names are restricted to letters, digits, `_` and `-`. Thinking is never saved. Compacted history stays compacted. Add `.coder/` to your `.gitignore` if you do not want sessions committed.

## 12. Choosing what the agent may do

The agent can do exactly what its registered plugins offer. The console registers all of them in its startup wiring (`ConsoleMain`); to remove an ability, remove its `registerPlugin(...)` line and rebuild.

| Want | Register | Leave out |
|---|---|---|
| A read-only agent that can still run tests | file-read, shell, planning | file-edit |
| An agent that never touches files | shell, web, MCP, planning | both file plugins |
| No commands, ever | file-read, file-edit, web, planning | shell, installer (needs the shell) |
| No network of any kind | file plugins, shell, planning, memory, skills | web, MCP, installer |
| No delegation | everything else | subagents |
| No standing knowledge | everything else | memory, skills |

Two more things are chosen by name rather than registered on or off:

- the **sandbox technology**, from the registered providers (`direct`, `bubblewrap`, `podman`) via `sandbox.technology`;
- the **model protocol**, from the registered protocol providers (`openai`) via each model's `protocol`.

Each plugin's tools, required capabilities and constructor options are in the [Plugin Catalog](PLUGIN-CATALOG.md).

## 13. Embedding the core

**Getting the library.** `agent-core` is published to the repository's GitHub Packages: every tag `vX.Y.Z` publishes version X.Y.Z, and every push to `master` republishes `1.0-SNAPSHOT`. GitHub Packages requires authentication even to read public packages, so two steps are needed.

Add the repository and the dependency to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/DimaZirix/DmiPi-Coder</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.dmipi</groupId>
        <artifactId>agent-core</artifactId>
        <version>1.0.0</version>
    </dependency>
</dependencies>
```

And a server entry with a GitHub personal access token that has the `read:packages` scope in `~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>YOUR_GITHUB_USERNAME</username>
            <password>YOUR_TOKEN</password>
        </server>
    </servers>
</settings>
```

The `id` must be `github` in both files. Without a token Maven reports a 401 from `maven.pkg.github.com`. A source build (`mvn -q install`) puts the same artifact into your local repository, which needs no token.

**Wiring it.** The core is a library. A program that embeds it supplies three things, an out channel, a HIL channel and at least one model, and registers the plugins it wants. Nothing is on by default: every file read, environment fact, session file and plugin is an explicit builder call.

**A console-like agent:**

```java
ReadTracker readTracker = new ReadTracker();   // shared so edits require a prior read
try (Coder coder = Coder.builder()
        .out(renderer)                          // your Out implementation
        .subagentOut(renderer.forSubagents())   // optional: where subagent events go (dropped otherwise)
        .hil(hil)                               // your Hil implementation
        .standardInstructions()                 // the bundled system prompt
        .projectDirectory(Path.of("/work/app"))
        .userDirectory(Path.of("/home/me"))     // default: user.home
        .gatherEnvironment()                    // cwd, OS, model, git yes/no into the prompt
        .workedExamples()                       // tool-call examples for the model's style
        .reminders()                            // date, plan-mode notice, rules refresher every 10 steps
        .nextSpeakerCheck()                     // one fast-tier call when a step ends in plain text
        .model(new ModelDeclaration("local", "openai", "http://localhost:8080/v1", Tier.BALANCED, 128_000))
        .loadUserSettings()                     // ~/.coder/settings.json, applied onto the builder
        .loadProjectSettings()                  // <project>/.coder/settings.json, wins over user
        .enableSessions()                       // .coder/sessions in the project
        .registerPlugin(new OpenAiProviderPlugin())
        .registerPlugin(new DirectSandboxPlugin())
        .registerPlugin(new BubblewrapSandboxPlugin(new ResourceLimits("2G", 256)))
        .registerPlugin(new PodmanSandboxPlugin("docker.io/library/maven:3-eclipse-temurin-25", ResourceLimits.none()))
        .registerPlugin(new FilesReadPlugin(readTracker))
        .registerPlugin(new FilesEditPlugin(readTracker))
        .registerPlugin(new ShellPlugin(true))   // true: background commands allowed
        .registerPlugin(new PlanningPlugin())
        .registerPlugin(new MemoryPlugin())
        .registerPlugin(new WebPlugin())
        .registerPlugin(new SkillsPlugin())
        .registerPlugin(new McpPlugin())
        .registerPlugin(new ClaudePluginInstallerPlugin())
        .registerPlugin(new SubagentsPlugin())
        .build()) {
    coder.runTurn("add a --dry-run flag to the deploy script", new CancelToken());
}
```

The order of calls matters in one place: `loadUserSettings()` and `loadProjectSettings()` apply the file onto the builder when called, so set the anchors first, and put explicit overrides after them if they must win.

**A read-only agent in a CI job** (never asks, never writes):

```java
Hil denyAll = question -> new Answer(List.of(question.options().getLast().id()));  // a policy answer; here: the last option
try (Coder coder = Coder.builder()
        .out(event -> log(event))
        .hil(denyAll)
        .mode(Mode.DONT_ASK)                    // anything that would ask is blocked instead
        .standardInstructions()
        .projectDirectory(workspace)
        .environment(new EnvironmentFacts(workspace.toString(), "Linux", "local", true))  // facts you choose; nothing gathered
        .model(model)
        .registerPlugin(new OpenAiProviderPlugin())
        .registerPlugin(new FilesReadPlugin())
        .registerPlugin(new SubagentsPlugin())
        .build()) {
    coder.runTurn("review the diff in CHANGES.md and list the risks", new CancelToken());
}
```

No edit plugin, no shell plugin, no shell provider: the agent cannot change anything, by construction. In *don't ask* mode the HIL is never reached by the gate; a policy answer is still required because tools may ask non-permission questions.

**Confining the network** (needs a confining technology):

```java
.sandbox("bubblewrap")
.egressControl(List.of("repo.maven.apache.org", "*.npmjs.org"))   // listed hosts pass; others ask via HIL
// or
.isolateNetwork()                                                   // no network at all
```

**Other builder calls:**

| Call | Effect |
|---|---|
| `instructions(text)` | Your own system prompt instead of the bundled one. |
| `mode(Mode)` | Starting mode. |
| `maxStepsPerTurn(n)` | Step limit, default 40. |
| `permissionRule(rule)` | A rule in code, same semantics as the settings file. |
| `sandbox("name")` | Technology, default `direct`. |
| `shellTimeouts(default, max)` | Timeouts in code. |
| `compactionThreshold(0.7)` | The fraction of the window that triggers compaction. |
| `reminderInterval(n)` | How often the rules refresher fires, default every 10 steps. |
| `http(Http)` | Replace the guarded HTTP client; a test seam, not a way to relax the guards. |
| `in(In)` then `coder.run()` | Let the core pull prompts from an `In` channel until it ends, instead of calling `runTurn` yourself. |

At runtime: `models()`, `activeModel()`, `activateModel(name)`, `mode()`, `switchMode(mode)`, `sessions()`, `saveSession(name)`, `resumeSession(name)` (returns whether the prompt was reused), `cancelCurrentTurn()`, and `close()` to tear the sandbox down. A builder builds once; use a fresh builder per session, because plugin instances hold session state.

The contract a front-end implements, the three channels, is in [INTERFACE-TECHNICAL.md](INTERFACE-TECHNICAL.md).

## 14. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Cannot start: At least one model must be declared.` | No model in either settings file and the built-in default was removed. Add a `models` entry (§3). |
| `Model 'x' declares protocol 'y', but no registered provider speaks it.` | The protocol name is not `openai`, or the provider plugin is not registered. |
| `Model 'x' declares apiKeyEnv 'Y', but that environment variable is not set.` | Export the variable before starting. |
| `The settings file ... is not valid JSON` / `names an unknown mode 'X'` | Fix the file and key it names. Valid modes are in §4, tiers in §3. |
| `A plugin requires the shell capability, but no sandbox provider for technology 'x' is registered.` | The technology name in settings has no matching provider; use `direct`, `bubblewrap` or `podman`, or register the provider. |
| `The 'direct' sandbox does not confine, so it cannot control the network.` | Network isolation or egress control was requested with `direct`. Choose `bubblewrap` or `podman`, or leave the network open. |
| The turn fails after a long silence. | The idle guard tripped: the server stopped sending anything for `idleTimeoutSeconds` (15 minutes by default). Check the server; raise the value for very slow hardware. |
| `[Step limit reached after 40 steps. Send another prompt to continue.]` | The turn used its step budget. Say "continue", or raise the limit when embedding. |
| `[The turn was stopped: the model kept repeating the same tool call.]` | Loop detection. Rephrase the task, or switch to a stronger model with `/llm`. |
| `edit` refused with "read the file first". | The read-before-edit gate. The agent will read and retry; nothing to do. |
| An MCP server's tools are missing. | The server was unreachable at startup, or its `type` is not `http`. The warning is in the JVM log. Restart the console after the server is up. |
| `Plan mode is active: mutating calls are blocked` in an activity line. | Expected in plan mode; wait for the plan, or `/plan off`. |
| A podman background command keeps running after exit. | Known limit; stop it with `podman ps` and `podman stop`. |
