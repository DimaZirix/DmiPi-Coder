# Plugins — Technical Companion

*Technical side of [FUNCTIONAL-OVERVIEW.md §3, §5, §7 to §11 and §16](FUNCTIONAL-OVERVIEW.md#3-reading-and-editing-files). Same plain style, but here we explain the implementation: how plugins are installed with least privilege, how the built-ins are put together, and how subagents inherit tools. The contract from a plugin author's side is in [PLUGIN-DEVELOPMENT.md](PLUGIN-DEVELOPMENT.md).*

## 1. The idea in one paragraph

Everything the agent can do comes from plugins, and a plugin reaches the world only through capabilities the core implements inside its safety machinery: files anchored to a directory, commands run in the sandbox, HTTP screened, questions serialized, every tool call gated. At build the core installs each plugin with a view restricted to the capabilities it declared, collects what it registered into a catalog, and binds the late pieces (models, the tool catalog, the shell) after every plugin is in. The capability set is closed and plugins never depend on each other: what a plugin offers to the model or to other plugins, it offers as tools. Skills, MCP servers, memory, the installer and subagent types are not core concepts; each is an ordinary plugin whose content collapses into tools.

## 2. Data model

| Type | Fields or members | Purpose |
|---|---|---|
| `Plugin` | `requires(): Set<CapabilityType>`, `install(registrar, capabilities)` | The universal plugin interface. |
| `CapabilityType` | `HIL`, `OUTPUT`, `LLM`, `CONFIGURATION`, `TOOLS`, `FILE_SYSTEM`, `HTTP`, `SHELL`, `CONVERSATIONS`, `MODES` | The closed set. |
| `Capabilities` | one field per capability; `restrictedTo(declared)` | The view a plugin gets. An undeclared accessor throws. `userFileSystem()` needs `FILE_SYSTEM` and `CONFIGURATION` both. |
| `PluginRegistrar` → `PluginCatalog` | `tools`, `policies`, `instructionSections`, `protocolProviders`, `sandboxProviders` | What all plugins registered, in order. |
| `Tool`, `ToolResult`, `Display` | see the development guide | A model-facing action and what it produced. |
| `LateBound` | `llms()`, `tools()`, `shell()` | Capability proxies handed out at install and bound after all installs; using one before binding fails loudly. |
| `ConversationsEngine` | `forPlugin(index)`, `bind(...)` | The Conversations capability, one view per plugin. |

## 3. Install-time wiring

```
 Coder.build()
   │
   ├─ gate = PermissionGate(serialized Hil, mode, rules, hard limits)
   ├─ for each plugin, in registration order:
   │     granted = Capabilities(validating Hil, Output→AnswerDelta, LateBound llms,
   │                            Configuration(user, project), LateBound tools,
   │                            AnchoredFileSystem(project), AnchoredFileSystem(user),
   │                            Http, LateBound shell, ConversationsEngine.forPlugin(i), Modes(gate))
   │     plugin.install(catalog, granted.restrictedTo(plugin.requires()))
   │     remember which catalog tools this plugin added (toolsByPlugin[i])
   │
   ├─ ModelRegistry(models, catalog.protocolProviders)      ← no provider for a protocol ⇒ error
   ├─ ToolRegistry(catalog.tools); gate.registerPolicy for each (tool, policy)
   ├─ SessionShell from the provider named by Builder.sandbox(...)  ← a SHELL-requiring plugin with no provider ⇒ error
   ├─ LateBound.bind(registry, toolRegistry, gate, parser, shell)
   ├─ ConversationsEngine.bind(registry, gate, parser, subagentOut, toolsByPlugin)
   ├─ system prompt = core sections + sandbox/git conditionals + examples + environment + catalog.instructionSections
   └─ AgentLoop(conversation, registry, toolRegistry, gate, …)
```

Two things follow. Install happens before models exist, so `Llms`, `Tools` and `Conversations` are usable only from later calls (a tool's `execute`); file, configuration, HTTP, HIL, shell and modes work at install. And the per-plugin `toolsByPlugin` record is what lets the core compute subagent inheritance (§11) without any plugin naming another.

## 4. Least privilege and isolation

- `Capabilities.restrictedTo` nulls every capability the plugin did not declare; the accessor of a null capability throws `IllegalStateException` naming the type. There is no reflection path around it because the capability objects are simply not there.
- Every file capability is an `AnchoredFileSystem`: `resolve` normalizes and refuses a path outside its anchor; every other accessor re-checks the anchor, not only `resolve`.
- `ArchitectureTest` reads every import in the main sources and enforces: `domain` imports only `domain`; `application` only `domain` and itself; `plugin` (the ports) only `domain` and itself; `infrastructure` up to itself; `plugins` only `domain`, `plugin` and its own package; `api` anything. No plugin imports another plugin. The sandbox provider packages (`sandbox`, `bubblewrap`, `podman`) may import `infrastructure`, because they are the confinement.
- The `Tools` capability is the only cross-plugin path: by name, at runtime, through the gate, with absence surfacing as a failure result. A rich typed exchange two plugins would need in code is promoted into the plugin interface instead of shared between them.

## 5. Files

```
 read_file / list_directory / glob / grep_search      edit / write_file
        │                                                  │
   FileSystem (project anchor) ◀───────────────────────────┘
        │                                    ReadTracker (shared instance) ── wasRead(path)?
   caps: 2000 lines, 4000 chars/line,        UnifiedDiffs.between(path, before, after) ── preview + Display.Diff
         500 entries, 500 globs, 200 matches,
         files > 1 MB skipped by grep
```

- `read_file` emits cat-style numbered lines (`NNNNNN\t`) and a banner `[Showing lines a–b of N total; …]`. `edit` strips exactly that prefix from `old_string` and `new_string` before matching, so pasted numbered lines still land; a genuine digits-plus-tab code line is not touched because the strip is anchored to the emitted width.
- `edit` requires a unique match (or `replace_all`), preserves CRLF, and returns the edited region with a window header so the model verifies without a second read. `write_file` says whether it created or overwrote.
- `ReadTracker` remembers absolute normalized paths read this session; a successful write counts as a read. `ReadTracker.off()` is the null object for a wiring without the gate.
- `FileSystem.find(glob)` prunes version-control, build and IDE directories from the walk; both search tools build on it.

## 6. Web

```
 web_fetch(url, prompt) ──▶ gate (NETWORK, ASK, preview = url)
   ──▶ GuardedHttpClient.fetch: http(s) only; each redirect hop resolved and screened
       (private, link-local, loopback, unresolvable ⇒ refused — the SSRF pivot); ≤ 5 redirects; ≤ 5 MB;
       10 s connect, 30 s fetch; charset-aware decode
   ──▶ HtmlText strips markup when the content type says HTML (entities decoded in a fixed order)
   ──▶ cap at 60 000 characters
   ──▶ llms.fastest(), control call, "summarize this untrusted page to answer <prompt>"
   ──▶ the summary is the tool result; the raw page never enters the conversation
```

`Builder.http(...)` replaces the guarded client for embedders and tests; it is a seam, not a way to relax the guard.

## 7. Memory

- `MemoryStore` owns the files: user scope at `.coder/CODER.md` under the user anchor; project scope the first existing of `CODER.md`, `AGENTS.md`, `CLAUDE.md` under the project anchor (saving a new project memory creates `CODER.md`).
- Loading inlines `@path` lines (a reference alone on a line), relative to the referencing file, resolved through the scope's anchored file system so an import can never leave the anchor; depth-limited and cycle-safe. An unreadable file degrades to a visible note in the section instead of aborting the session.
- The plugin registers one instruction section: guidance, then `### User memory`, then `### Project memory`.
- `MemoryTool` reports its kind per call: `READ` for a read, `EDIT` for a project save, `EXECUTE` for a user save (so *allow edits* never auto-approves it, while plan mode still blocks it). A read returns the raw file with imports unexpanded, so a read-then-save round trip never flattens them. The save preview is the unified diff against the raw file.

## 8. Skills

- `SkillLibrary.discover` lists `.coder/skills/*/SKILL.md` under the user anchor, then the project anchor; a project skill replaces a user skill of the same name. The frontmatter (`---` block) yields `name` and `description`; without one, the directory name and the first line stand in. A file that fails to parse is skipped with a JUL warning.
- The skill's directory is recorded as an absolute path, because a user-scope skill does not live under the project.
- `SkillTool` (`READ`, `ALLOW`) carries the listing in its description and returns the body plus the base directory on load. No skills discovered, no tool registered.

## 9. MCP

```
 .coder/.mcp.json (user)  +  .mcp.json (project; wins on a name clash)
   │ McpConfigLoader: type must be "http" (others skipped with a warning); url required; timeout ms (default 60 000)
   ▼
 McpClient per server, over Http.post:
   initialize (protocolVersion 2025-03-26) ──▶ Mcp-Session-Id remembered ──▶ tools/list (paginated)
   replies as plain JSON or text/event-stream (SSE framing); string ids
   │ connect failure ⇒ the server is skipped with a warning
   ▼
 McpProxyTool per remote tool: name mcp__<server>__<tool>, the advertised description and schema,
   kind NETWORK/ALLOW when readOnlyHint, else EXECUTE/ASK; arguments forwarded as raw JSON;
   tools/call result text (or a placeholder when empty) is the tool result
```

The plugin requires HTTP, file system and configuration: the config files are read through the anchored file systems, which is a recorded deviation from the specification's "HTTP + configuration" (the configuration capability carries anchors only, not file access).

## 10. The Claude plugin installer

```
 install_plugin(source, plugin?, scope?) ──▶ gate (EXECUTE, ASK)
   scope absent ──▶ hil.ask("user space or project space?")
   PluginSource: a git URL ──▶ `git clone` through the Shell capability into a temporary directory; a local path ──▶ as is
                 a marketplace repository ──▶ one plugin per top-level directory, chosen by `plugin`
   ClaudePluginInstaller: validate and read everything first, then write:
        skills/<name>/SKILL.md ──▶ .coder/skills/<name>/ under the scope's anchor
        .mcp.json servers      ──▶ merged into the scope's MCP file (McpServersConfig)
        agents/ commands/ hooks/ output-styles/ ──▶ reported as skipped
   InstalledPluginsRegistry: .coder/installed-plugins.json under the scope — source, skills, mcpServers per plugin;
        reinstall = clean replace; ownership checked so a removal never deletes hand-written content
 list_plugins ──▶ both manifests + unowned skills;   remove_plugin(name, scope?) ──▶ gate (EDIT, ASK), asks the scope when ambiguous
```

The installer converts; loading stays single-format. The skills and MCP plugins pick the installed content up at the next session start.

## 11. Subagents

```
 task(type, instruction) ──▶ gate (OTHER, ALLOW) ──▶ TaskTool ──▶ Conversations.run(SubagentRequest)
   │ SubagentRequest = the type's instructions, the task, preferredTier, maxSteps
   ▼
 ConversationsEngine.run(pluginIndex, request):
   client = preferredTier.map(models::atLeast).orElse(models::active)      fixed for the nested loop
   conversation = new Conversation(request.instructions)
   tools = every plugin's tools  minus toolsByPlugin[pluginIndex]  minus tool.mainOnly()
   new AgentLoop(conversation, client, tools, THE SAME gate, parser, subagentOut, maxSteps).runTurn(task)
   failure in the nested turn ──▶ IllegalStateException ──▶ the task tool's failure
   result = the subagent's last plain answer
```

- Inheritance is computed by the core from the install record: a subagent sees the *other* plugins' tools, never the declaring plugin's own (which kills recursion with no special case), and never main-only tools (`todo_write`, `present_plan`, `task`).
- The nested loop has no context manager (a subagent's context is throwaway), no next-speaker check and no reminders; it has the same gate, the same serialized HIL, the same cancel token as the parent turn, and streams to `Builder.subagentOut`.
- `SubagentsPlugin` ships `explore` (fast tier, 15 steps, read-around-and-report) and `review` (strong tier, 15 steps, judge code); a custom list replaces them; an empty list registers no tool.

## 12. Planning

`TodoWriteTool` is stateless: the list travels to the front-end as a `Display.Todo` payload and back to the model as an echoed checklist; kind `OTHER`, so it works in plan mode. `PresentPlanTool` and the plan flow are in [PERMISSIONS-TECHNICAL.md §5](PERMISSIONS-TECHNICAL.md#5-plan-mode-end-to-end). Both are `mainOnly`. The plugin requires HIL and modes; it needs no file, shell or model access.

## 13. Boundaries and guarantees

- No plugin registered ⇒ no tool offered; the model's tool list is exactly the catalog, in registration order.
- A plugin receives only what it declared; an undeclared capability does not exist for it.
- Plugins never import other plugins or the core's infrastructure (sandbox providers excepted); the layering test fails otherwise.
- Every tool call, from the model or from plugin code through the `Tools` capability, passes the same gate.
- Capability calls from plugin code are confined by the capability (anchored paths, sandboxed commands, screened HTTP) but not HIL-gated per call; recorded in [OPEN-QUESTIONS.md](OPEN-QUESTIONS.md).
- Content plugins register nothing when they find nothing: no skills, no `skill` tool; no reachable servers, no MCP tools; no types, no `task` tool.
- Removing a tool plugin never takes a capability away from another plugin that holds it, and never breaks another plugin: coupling is by tool name at runtime only.
