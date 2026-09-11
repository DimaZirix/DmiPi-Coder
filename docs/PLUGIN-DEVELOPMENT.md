# Plugin Development Guide

*How to write a plugin for dmipi-coder: the contract a plugin implements, the capabilities it can ask for, how a tool is judged and shown, and how to test it. For Java developers extending the agent. The internals behind the contract are in [PLUGINS-TECHNICAL.md](PLUGINS-TECHNICAL.md).*

## 1. The contract in one paragraph

A plugin is a class implementing `Plugin` from `com.dmipi.coder.core.plugin`. It declares the capabilities it requires, and at install it receives exactly those and registers what it contributes: tools the model can call, sections of the system instructions, or providers (a model protocol, a sandbox technology). It has no other way in or out. It never imports another plugin and never reaches the core's infrastructure; the world reaches it only through the capability objects it was handed, all of which the core guards (paths anchored to the project, commands run in the sandbox, questions serialized, every tool call gated).

## 2. Ground rules

- **One package per plugin**, under `com.dmipi.coder.core.plugins.<name>` for a built-in, or anywhere on the classpath for your own.
- **Import only `domain` and `plugin` types** from the core (plus your own package). Importing `infrastructure`, `application`, `api` or another plugin's package fails the layering test (`ArchitectureTest`). Sandbox providers are the one exemption, because they *are* the confinement.
- **No new dependencies without approval.** The core has two runtime libraries on purpose; a plugin in this repository adds none unless the project owner agreed first. Your own plugin outside the repository may do as it likes, but it then carries that trust.
- **A plugin instance is session state.** The builder builds once; make a fresh plugin instance per `Coder`.
- **Tests are behaviour tests** through the public `Coder` API, Given/When/Then, `@DisplayName` sentences, `should_...` method names, AssertJ (see §11).

## 3. The `Plugin` interface

```java
public interface Plugin {
    default Set<CapabilityType> requires() { return Set.of(); }
    void install(PluginRegistrar registrar, Capabilities capabilities);
}
```

`requires()` is read once, at build. `install` is called once, in registration order, with a `Capabilities` view restricted to what was declared. Registration order is the order the model sees tools.

`install` runs before models and tools are bound, so two capabilities cannot be *used* during install: **LLM** and **tools**. Hold them and call them later, from a tool's `execute`. **Conversations** behaves the same way. Everything else (file system, configuration, HTTP, HIL, output, shell, modes) is usable at install; the skills, memory and MCP plugins read files and connect to servers there.

## 4. Capabilities

| `CapabilityType` | Accessor | You get | Notes |
|---|---|---|---|
| `FILE_SYSTEM` | `capabilities.fileSystem()` | `FileSystem`: `resolve`, `read`, `write`, `delete`, `list`, `exists`, `size`, `find(glob)` | Anchored to the project directory. `resolve` throws `IllegalArgumentException` for a path that escapes it; I/O failures are `UncheckedIOException`. |
| `FILE_SYSTEM` + `CONFIGURATION` | `capabilities.userFileSystem()` | A second `FileSystem` anchored to the user directory | Only when **both** are declared. The seam for user-scope state (user memory, user skills, the user MCP file). |
| `CONFIGURATION` | `capabilities.configuration()` | `Configuration(userDirectory, projectDirectory)` | The two anchors, read-only. Derive conventional locations from them; do not take paths from elsewhere. |
| `SHELL` | `capabilities.shell()` | `Shell`: `run(command, cancel)`, `run(command, timeout, cancel)`, `runInBackground(command)` | Runs inside the session sandbox; timeouts clamped. Declaring it requires a registered sandbox provider for the configured technology, or the build fails. |
| `HTTP` | `capabilities.http()` | `Http`: `fetch(url)` and `post(url, body, headers, timeout)` | `fetch` screens every redirect hop against private, link-local and unresolvable hosts, bounds redirects and body size. `post` is for operator-configured endpoints: http(s)-only and bounded, not private-host-screened. Failures are `UncheckedIOException`. |
| `LLM` | `capabilities.llms()` | `Llms`: `active()`, `fastest()`, `strongest()`, `atLeast(tier)` | Each returns an `LlmClient`; see §12 for the request shape. Use `ChatRequest.asControlCall(schema)` for an isolated decision with thinking off. |
| `HIL` | `capabilities.hil()` | `Hil.ask(question)` | Blocks until answered; the answer is validated against the question before you see it. §7. |
| `OUTPUT` | `capabilities.output()` | `Output.text(text)` | Writes into the agent's answer stream. Rarely needed; tool results and displays are the normal channel. |
| `TOOLS` | `capabilities.tools()` | `Tools`: `available()`, `invoke(name, argumentsJson, cancel)` | Call another plugin's tool by name. The call passes the same gate as a model call; an absent tool is a failure result, not an exception to depend on. |
| `CONVERSATIONS` | `capabilities.conversations()` | `Conversations.run(SubagentRequest, cancel)` | Runs a subagent over the *other* plugins' tools (never your own, never main-only tools) and returns its final message. Throws `IllegalStateException` when the nested turn fails. |
| `MODES` | `capabilities.modes()` | `Modes`: `current()`, `switchTo(mode)` | Read and switch the approval mode. The planning plugin uses it to leave plan mode; treat it with respect. |

Accessing a capability you did not declare, or one the embedder never granted, throws `IllegalStateException` naming the capability. There is no way to obtain one otherwise. The set is closed: a plugin cannot offer a new capability to other plugins; what it offers, it offers as tools.

## 5. The registrar

| Call | Contributes | When to use |
|---|---|---|
| `registerTool(tool)` | A tool the model can call | The main contribution. |
| `registerTool(tool, policy)` | A tool with a `PermissionPolicy` | When your plugin wants to tighten its own tool's baseline per call (never loosen; §8). |
| `registerInstructionSection(text)` | Text appended to the system instructions, after the core's sections, at session start | What the model must know from turn one: loaded memory, how to use your tools well. Keep it short; it is paid for on every request. |
| `registerProtocolProvider(provider)` | A model protocol | A provider plugin (§12). |
| `registerSandboxProvider(provider)` | A sandbox technology | A provider plugin (§12). |

A plugin may register nothing at all when it has nothing to offer (no skills found means no `skill` tool). The model is then not told about a feature it cannot use.

## 6. Writing a tool

```java
public interface Tool {
    String name();
    String description();
    ToolKind kind();
    default ToolKind kind(ToolParams params) { return kind(); }
    default boolean mainOnly() { return false; }
    ParameterSchema parameterSchema();
    Optional<String> validate(ToolParams params);
    PermissionDecision defaultPermission(ToolParams params);
    default String preview(ToolParams params) { return ""; }
    default String callSummary(ToolParams params) { return ""; }
    default String matchTarget(ToolParams params) { return callSummary(params); }
    ToolResult execute(ToolParams params, CancelToken cancel);
}
```

| Method | What to return | Who reads it |
|---|---|---|
| `name` | Lower snake case, unique across all plugins. | The model calls it by this name; rules and activity lines use it. |
| `description` | What it does, when to use it and when not, the constraints that make it fail, a worked example where the call is easy to get wrong. This is prompt engineering: small models follow it literally. | The model, on every request. |
| `kind` / `kind(params)` | `READ`, `SEARCH`, `EDIT`, `EXECUTE`, `NETWORK` or `OTHER`. Override the per-call variant when one tool has actions of different effect (the memory tool is `READ` on read, `EDIT` on a project save, `EXECUTE` on a user save). | The gate: plan mode blocks `EDIT` and `EXECUTE`; allow-edits softens `EDIT` only; hard limits and the per-command "always" apply to `EXECUTE`. Subagent inheritance and display are unaffected by kind. |
| `mainOnly` | `true` for a tool bound to main-conversation state (the task list, the plan gate). | The core: such tools are never inherited by subagents. |
| `parameterSchema` | A JSON Schema object as a string, passed to the model untouched. Describe every parameter; mark `required`. | The model. The core never interprets it. |
| `validate` | Empty for OK; otherwise one correctable sentence ("Parameter 'path' is required."). Cheap and synchronous. | The loop: a validation error becomes the tool result, and the model fixes its call next step. Never throw for a bad argument. |
| `defaultPermission` | The tool's baseline for *this* call: `ALLOW` for reads and searches, `ASK` for anything that changes or reaches out, `DENY` for a call you refuse outright. | The gate composes it with policies, rules and the mode. |
| `preview` | Exactly what is at stake, verbatim: a unified diff (`UnifiedDiffs.between(path, before, after)`), the command line, the URL. Empty when there is nothing to show. | The user, in the permission question. |
| `callSummary` | One line of what the call targets: a path, a pattern, a command. May abbreviate. | The activity line and the question text. |
| `matchTarget` | The **complete** text safety screening matches against: the full command, the full path. Override it whenever `callSummary` abbreviates, or rules and hard limits see only the abbreviation. | Rules, hard limits, the per-command "always" scope. |
| `execute` | `ToolResult.Success(llmContent, display)` or `ToolResult.Failure(llmContent)`. | The model reads `llmContent`; the front-end shows `display`. |

**Results the model can use.** The result is an observation the model reasons from, so make it self-describing: a header naming what was done, `(empty)` for nothing, a footer when output was cut (`[limit reached; refine or raise 'limit']`), and echo the resulting state where it helps (the edited region, the full checklist). Cap every result; never dump unbounded output into the history. Phrase a failure as text the model can act on next step ("No such file: x. Use glob to find files."). Poll `cancel.isCancelled()` in long work.

**Display payloads**: `Display.Text(line)` for a short result, `Display.Diff(unifiedDiff)` for a file change, `Display.Todo(items)` for a task list. Choose by what a person would want to see, not by what the model needs.

**Reading parameters**: `ToolParams` offers `string`, `integer`, `bool`, `stringList`, each returning `Optional`, and `rawJson()` for the whole object (the MCP proxy forwards it verbatim).

## 7. Asking the user

```java
Question question = new Question(
        "Which scope should the plugin install into?",   // one line, plain language
        "",                                              // preview, verbatim; empty when nothing is at stake
        QuestionKind.OPTION_LIST,                        // or CHECKBOX_LIST
        List.of(new Option("user", "User space", "available in every project"),
                new Option("project", "This project")));
Answer answer = capabilities.hil().ask(question);
String chosen = answer.selected().getFirst();            // ids only; the front-end never sees them
```

- At least two options, unique ids. There is no free-text and no empty answer: when "other" or "none" is legitimate, make it an option.
- The call blocks until the user answers; there is no timeout. Design questions that are worth blocking on.
- The answer is validated against the question before it reaches you (an invalid one from a misbehaving front-end fails loudly), so you may rely on the closed set.
- You alone interpret the ids. The front-end renders and returns; it never knows an option had a side effect.
- Do not ask permission questions yourself: return `ASK` from `defaultPermission` and let the gate ask, with your `preview`. Ask only when a task genuinely forks.

## 8. Attaching a policy

```java
registrar.registerTool(tool, params -> params.string("path").filter(p -> p.startsWith("secrets/")).isPresent()
        ? PermissionDecision.DENY
        : PermissionDecision.ALLOW);
```

The gate composes `baseline.tightenedBy(policy)`: a policy can turn a run into an ask or a deny, never the reverse. Returning `ALLOW` from a policy means "no opinion". Use a policy for per-call tightening that does not belong in the tool's own baseline; use rules in settings for anything the operator should control.

## 9. Instruction sections

Register one when the model needs to know something from the first turn that a tool description cannot carry: loaded content (memory), or a short discipline for using your tools. Plugin sections land after the core's sections and the environment block, in registration order; the most specific speaks last. Plugins do not contribute reminders: their tool descriptions already travel with every request.

## 10. A worked example

A plugin with one read-only tool that counts the lines of a file.

```java
package com.example.linecount;

import com.dmipi.coder.core.plugin.*;
import java.util.Set;

public final class LineCountPlugin implements Plugin {

    @Override
    public Set<CapabilityType> requires() {
        return Set.of(CapabilityType.FILE_SYSTEM);
    }

    @Override
    public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
        registrar.registerTool(new CountLinesTool(capabilities.fileSystem()));
    }
}
```

```java
package com.example.linecount;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.event.Display;
import com.dmipi.coder.core.domain.permissions.PermissionDecision;
import com.dmipi.coder.core.domain.tool.*;
import com.dmipi.coder.core.plugin.FileSystem;
import java.nio.file.Path;
import java.util.Optional;

final class CountLinesTool implements Tool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["path"],
              "properties": {
                "path": {"type": "string", "description": "The file to count, relative to the project directory."}
              }
            }""";

    private final FileSystem files;

    CountLinesTool(final FileSystem files) {
        this.files = files;
    }

    @Override public String name() { return "count_lines"; }

    @Override public String description() {
        return "Counts the lines of one project file. Use it instead of running wc through the shell. To see the content, use read_file.";
    }

    @Override public ToolKind kind() { return ToolKind.READ; }

    @Override public ParameterSchema parameterSchema() { return new ParameterSchema(SCHEMA); }

    @Override public Optional<String> validate(final ToolParams params) {
        if (params.string("path").filter(path -> !path.isBlank()).isEmpty()) {
            return Optional.of("Parameter 'path' is required.");
        }
        return Optional.empty();
    }

    @Override public PermissionDecision defaultPermission(final ToolParams params) { return PermissionDecision.ALLOW; }

    @Override public String callSummary(final ToolParams params) { return params.string("path").orElse(""); }

    @Override public ToolResult execute(final ToolParams params, final CancelToken cancel) {
        final String requested = params.string("path").orElseThrow();
        final Path path;
        try {
            path = files.resolve(requested);
        } catch (final IllegalArgumentException outsideTheProject) {
            return new ToolResult.Failure(outsideTheProject.getMessage());
        }
        if (!files.exists(path)) {
            return new ToolResult.Failure("No such file: " + requested + ". Use glob to find files.");
        }
        final long lines = files.read(path).lines().count();
        return new ToolResult.Success(requested + ": " + lines + " lines", new Display.Text("counted " + lines + " lines"));
    }
}
```

Register it like any other plugin: `.registerPlugin(new LineCountPlugin())`. The model now sees `count_lines` in its tool list; `read_file` and the rest are unaffected; a subagent inherits it too.

## 11. Testing a plugin

Drive the real `Coder` with scripted collaborators from `agent-core`'s test fixtures: `ScriptedClient` (an `LlmClient` playing back steps and recording every request), `ScriptedHil` (answers from a script, records the questions), `RecordingOut` (collects events). Register the scripted client through a tiny provider plugin, so the test goes through the same wiring as production.

```java
@Test
@DisplayName("count_lines reports the line count of a project file")
void should_count_lines() throws IOException {
    // Given: a project file, and a model that calls the tool then answers
    Files.writeString(projectDirectory.resolve("a.txt"), "one\ntwo\nthree\n");
    final ScriptedClient client = new ScriptedClient(List.of(
            ScriptedClient.toolCallStep("c1", "count_lines", "{\"path\": \"a.txt\"}"),
            ScriptedClient.textStep("done")));

    // When
    try (Coder coder = Coder.builder()
            .out(out)
            .hil(new ScriptedHil(List.of()))
            .model(new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000))
            .projectDirectory(projectDirectory)
            .registerPlugin(providerPlugin(client))
            .registerPlugin(new LineCountPlugin())
            .build()) {
        coder.runTurn("count", new CancelToken());
    }

    // Then: the model received the count as the tool result
    assertThat(client.requests().getLast().messages())
            .anySatisfy(message -> assertThat(message.content()).isEqualTo("a.txt: 3 lines"));
}

private static Plugin providerPlugin(final LlmClient client) {
    return (registrar, capabilities) -> registrar.registerProtocolProvider(new ProtocolProvider() {
        @Override public String protocol() { return "scripted"; }
        @Override public LlmClient connect(final ModelDeclaration declaration) { return client; }
    });
}
```

Test the behaviour, not the mirror: what the model receives, what the user is asked (the `ScriptedHil.asked()` list, with its preview), what events flow. Give every guard a test with the exact adversarial input it must stop (a path with `..`, an empty parameter, a cancelled token).

## 12. Providers

Providers contribute a mechanism the core selects by name. They contribute no tools and consume no capabilities.

**A protocol provider** implements `ProtocolProvider` (`protocol()`, `connect(declaration)`) and returns an `LlmClient`:

```java
void stream(ChatRequest request, CancelToken cancel, Consumer<LlmStreamEvent> events);
```

- `ChatRequest` carries `messages` (`ChatMessage(role, content, toolCalls, toolCallId)` with roles `SYSTEM`, `USER`, `ASSISTANT`, `TOOL`; factories `system`, `user`, `assistant`, `toolResult`), `tools` (`ToolSchema(name, description, parametersJson)`), and two advisory switches: `thinkingDisabled` and `responseSchemaJson`. Ignore what your server does not support.
- Emit, in order: `TextDelta`, `ThinkingDelta` (the reasoning stream, when there is one), `ToolCallDelta(index, id, name, argumentsDelta)` (fragments of one call share an index; id and name may come on the first fragment only), and exactly one `Finished(reason)` with `STOP`, `TOOL_CALLS`, `LENGTH` or `OTHER`.
- Poll `cancel` cooperatively. Raise `LlmException` for transport or protocol failure so the turn fails visibly. Guard against a dead stream: the built-in provider wraps the body in an idle guard; a second provider must bring its own until that moves into the core.
- Serialize deterministically (fixed field order, stable escaping): a resumed session replays the stored prompt byte-for-byte to keep the server's prompt cache warm, and that only works if the same request always produces the same bytes.

**A sandbox provider** implements `SandboxProvider` (`technology()`, `available()`, `confines()`, `create(spec)`) and `Sandbox` (`run`, `startBackground`, `technology`, `confines`, `close`):

- Translate the `SandboxSpec` faithfully: the project and additional directories writable, the rest read-only or invisible, the timeout enforced, and the `SandboxNetwork` (`Open`, `Isolated`, `Proxied(port, token)`) honoured. A contract you cannot honour must be refused at `create`, loudly, never pretended.
- Report `confines()` truthfully; it decides what the model is told.
- Probe at create: run a trivial command; for a confining technology, prove that a write outside the allowed paths fails.
- Return a `ShellResult` with `timedOut` or `cancelled` set when the process was killed, exit code -1.
- You may import the core's `infrastructure.shell.ProcessRunner` for timeout enforcement and capped capture; sandbox providers are exempt from the no-infrastructure rule because they are the trusted computing base. That exemption is the reason to keep a provider small and reviewable.

The built-in `OpenAiProviderPlugin`, `DirectSandboxPlugin`, `BubblewrapSandboxPlugin` and `PodmanSandboxPlugin` are the reference implementations.

## 13. Subagent types

You do not need a new plugin to add a subagent type; construct the subagents plugin with your own list:

```java
new SubagentsPlugin(List.of(
        new SubagentType(
                "docs",
                "Writes or updates documentation for a change.",
                "You are a documentation subagent inside a coding agent. Read the code the task points at and write clear documentation. Your final message is all the caller ever sees — make it complete.",
                Optional.of(Tier.STRONG),
                20)))
```

A type is content: name, description (advertised in the `task` tool), instructions, preferred tier (`Optional.empty()` means the active model), step budget. A plugin of your own may also hold the `CONVERSATIONS` capability and run subagents from a tool of its own; it then inherits the other plugins' tools, never its own.

## 14. Checklist before you ship

- `requires()` names the least you need; the plugin never touches a capability it did not declare.
- No imports of `infrastructure`, `application`, `api` or another plugin (run `ArchitectureTest`).
- Every tool: a description that tells the model when and when not; a schema describing every parameter; `validate` returning correctable sentences; a truthful `kind`; an honest `defaultPermission`; `preview` for anything that changes state; `matchTarget` overridden if `callSummary` abbreviates; capped, self-describing results.
- No unbounded output, no uncapped loops, cancellation polled.
- Tests through `Coder`, including the adversarial inputs.
- No new dependency, or an approved one.
