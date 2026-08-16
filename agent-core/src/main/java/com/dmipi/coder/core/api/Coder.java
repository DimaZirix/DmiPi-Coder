package com.dmipi.coder.core.api;

import com.dmipi.coder.core.application.permissions.PermissionGate;
import com.dmipi.coder.core.application.prompt.CorePrompt;
import com.dmipi.coder.core.application.prompt.EnvironmentFacts;
import com.dmipi.coder.core.application.prompt.PromptAssembler;
import com.dmipi.coder.core.application.prompt.PromptResources;
import com.dmipi.coder.core.domain.agent.AgentLoop;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.agent.ContextManager;
import com.dmipi.coder.core.domain.agent.Conversation;
import com.dmipi.coder.core.domain.agent.In;
import com.dmipi.coder.core.domain.agent.LoopGuards;
import com.dmipi.coder.core.domain.agent.NextSpeakerCheck;
import com.dmipi.coder.core.domain.agent.Reminders;
import com.dmipi.coder.core.domain.event.Out;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.hil.Hil;
import com.dmipi.coder.core.domain.llm.ChatMessage;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ModelRegistry;
import com.dmipi.coder.core.domain.permissions.HardLimits;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.domain.permissions.PermissionRule;
import com.dmipi.coder.core.domain.permissions.PermissionRules;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.tool.Tool;
import com.dmipi.coder.core.domain.tool.ToolRegistry;
import com.dmipi.coder.core.infrastructure.files.AnchoredFileSystem;
import com.dmipi.coder.core.infrastructure.http.GuardedHttpClient;
import com.dmipi.coder.core.infrastructure.json.JacksonToolParamsParser;
import com.dmipi.coder.core.infrastructure.sessions.SessionFingerprint;
import com.dmipi.coder.core.infrastructure.sessions.SessionStore;
import com.dmipi.coder.core.infrastructure.settings.Settings;
import com.dmipi.coder.core.infrastructure.settings.SettingsLoader;
import com.dmipi.coder.core.infrastructure.shell.SessionShell;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Http;
import com.dmipi.coder.core.plugin.Modes;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Configuration;
import com.dmipi.coder.core.plugin.Plugin;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The library facade: wires models, plugins, the gate and the loop from one fluent builder, so
 * a front-end only supplies its channels. Nothing is granted by default — every capability the
 * core gets is an explicit builder call.
 */
public final class Coder implements AutoCloseable {

    private final AgentLoop agentLoop;
    private final ModelRegistry models;
    private final PermissionGate gate;
    private final In in;
    private final AutoCloseable sessionShell;
    private final Conversation conversation;
    private final SessionStore sessions;
    private final String fingerprint;
    private volatile CancelToken currentTurn;

    private Coder(final AgentLoop agentLoop, final ModelRegistry models, final PermissionGate gate, final In in, final AutoCloseable sessionShell, final Conversation conversation, final SessionStore sessions, final String fingerprint) {
        this.agentLoop = agentLoop;
        this.models = models;
        this.gate = gate;
        this.in = in;
        this.sessionShell = sessionShell;
        this.conversation = conversation;
        this.sessions = sessions;
        this.fingerprint = fingerprint;
    }

    /** Releases session resources — currently the sandbox, if one was created. */
    @Override
    public void close() {
        if (sessionShell != null) {
            try {
                sessionShell.close();
            } catch (final Exception ignored) {
                // best-effort teardown at session end
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Runs one full agent turn for the given user input; cancel via the token. */
    public void runTurn(final String userInput, final CancelToken cancel) {
        currentTurn = cancel;
        try {
            agentLoop.runTurn(userInput, cancel);
        } finally {
            currentTurn = null;
        }
    }

    /** Reads prompts from the in channel until it is exhausted, one turn per prompt. */
    public void run() {
        if (in == null) {
            throw new IllegalStateException("No in channel configured: set Builder.in(...) or drive turns with runTurn(...).");
        }
        for (Optional<String> prompt = in.nextPrompt(); prompt.isPresent(); prompt = in.nextPrompt()) {
            if (!prompt.orElseThrow().isBlank()) {
                runTurn(prompt.orElseThrow(), new CancelToken());
            }
        }
    }

    /** Cancels the turn {@link #run()} is currently executing, if any. */
    public void cancelCurrentTurn() {
        final CancelToken current = currentTurn;
        if (current != null) {
            current.cancel();
        }
    }

    public List<ModelDeclaration> models() {
        return models.declarations();
    }

    public ModelDeclaration activeModel() {
        return models.active().declaration();
    }

    public void activateModel(final String name) {
        models.activate(name);
    }

    public Mode mode() {
        return gate.mode();
    }

    public void switchMode(final Mode mode) {
        gate.switchMode(mode);
    }

    /** The saved session names, under the session grant. */
    public List<String> sessions() {
        return store().list();
    }

    /** Persists the system prompt, a fingerprint of its inputs, and the dialogue under the given name; overwrites a previous save. */
    public void saveSession(final String name) {
        final List<ChatMessage> all = conversation.messages();
        store().save(name, all.getFirst().content(), fingerprint, all.subList(1, all.size()));
    }

    /**
     * Continues a saved session. When its fingerprint matches this session's — same tools,
     * plugins, environment and model — the saved system prompt is replayed byte-for-byte so the
     * server's prompt cache survives the resume; otherwise the current prompt is kept. Only a
     * conversation with no history yet can resume — resume first, then talk.
     */
    public ResumeResult resumeSession(final String name) {
        if (conversation.messages().size() > 1) {
            throw new IllegalStateException("This conversation already has history — resume before the first turn.");
        }
        final SessionStore.SavedSession saved = store().load(name);
        final boolean reuse = saved.fingerprint().equals(fingerprint);
        if (reuse) {
            conversation.replaceSystemInstructions(saved.systemPrompt());
        }
        saved.messages().forEach(conversation::add);
        return reuse ? ResumeResult.PROMPT_REUSED : ResumeResult.PROMPT_REBUILT;
    }

    private SessionStore store() {
        if (sessions == null) {
            throw new IllegalStateException("Session persistence is not granted: enable it with Builder.enableSessions().");
        }
        return sessions;
    }

    /**
     * The Hil handed to plugins enforces {@link com.dmipi.coder.core.domain.hil.Question#rejection}
     * mechanically — the domain promises askers a closed answer set, so a misbehaving front-end
     * fails here, loudly, instead of leaking an invalid selection into plugin code.
     */
    private static Hil validatingHil(final Hil hil) {
        return question -> {
            final var answer = hil.ask(question);
            question.rejection(answer).ifPresent(reason -> {
                throw new IllegalStateException("The front-end returned an invalid answer to '" + question.question() + "': " + reason);
            });
            return answer;
        };
    }

    /** Collects the configuration; {@link #build()} assembles and installs everything. */
    public static final class Builder {

        private static final int DEFAULT_MAX_STEPS = 40;

        private final List<ModelDeclaration> models = new ArrayList<>();
        private final List<Plugin> plugins = new ArrayList<>();
        private String instructions = "";
        private In in;
        private Out out;
        private Hil hil;
        private Mode mode = Mode.DEFAULT;
        private int maxStepsPerTurn = DEFAULT_MAX_STEPS;
        private Path userDirectory = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        private Path projectDirectory = Path.of("").toAbsolutePath().normalize();
        private String sandboxTechnology = "direct";
        private Duration shellDefaultTimeout = Duration.ofSeconds(120);
        private Duration shellMaxTimeout = Duration.ofSeconds(600);
        private final List<Path> additionalWritableDirectories = new ArrayList<>();
        private final List<PermissionRule> permissionRules = new ArrayList<>();
        private Http http = new GuardedHttpClient();
        private Out subagentOut = event -> {
        };
        private boolean sessionsGranted;
        private double compactionThreshold = 0.7;
        private boolean nextSpeakerCheck;
        private boolean gatherEnvironment;
        private EnvironmentFacts environment;
        private boolean workedExamples;
        private boolean remindersEnabled;
        private int reminderInterval = 10;

        private Builder() {
        }

        /** The core system instructions; plugin instruction sections are appended after them. */
        public Builder instructions(final String instructions) {
            this.instructions = Objects.requireNonNull(instructions, "instructions");
            return this;
        }

        /** The bundled standard instruction set — what a front-end uses unless it brings its own. */
        public Builder standardInstructions() {
            return instructions(CorePrompt.standard());
        }

        public Builder in(final In in) {
            this.in = in;
            return this;
        }

        public Builder out(final Out out) {
            this.out = out;
            return this;
        }

        public Builder hil(final Hil hil) {
            this.hil = hil;
            return this;
        }

        public Builder model(final ModelDeclaration declaration) {
            models.add(Objects.requireNonNull(declaration, "declaration"));
            return this;
        }

        public Builder registerPlugin(final Plugin plugin) {
            plugins.add(Objects.requireNonNull(plugin, "plugin"));
            return this;
        }

        public Builder mode(final Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Builder maxStepsPerTurn(final int maxStepsPerTurn) {
            if (maxStepsPerTurn <= 0) {
                throw new IllegalArgumentException("maxStepsPerTurn must be positive, got " + maxStepsPerTurn + ".");
            }
            this.maxStepsPerTurn = maxStepsPerTurn;
            return this;
        }

        /** The per-user anchor; conventional user-scope locations derive from it. */
        public Builder userDirectory(final Path userDirectory) {
            this.userDirectory = Objects.requireNonNull(userDirectory, "userDirectory").toAbsolutePath().normalize();
            return this;
        }

        /** The current path — the project worked on; the anchor for project-scope locations. */
        public Builder projectDirectory(final Path projectDirectory) {
            this.projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory").toAbsolutePath().normalize();
            return this;
        }

        /**
         * The grant to read the user settings file ({@code .coder/settings.json} under the user
         * directory) — read now, applied onto the builder, so call it after setting the anchors
         * and before project settings or explicit overrides. Missing file → nothing changes.
         */
        public Builder loadUserSettings() {
            return apply(SettingsLoader.load(userDirectory));
        }

        /** The grant to read the project settings file; where both scopes speak, the later call wins — project after user. */
        public Builder loadProjectSettings() {
            return apply(SettingsLoader.load(projectDirectory));
        }

        private Builder apply(final Settings settings) {
            for (final ModelDeclaration declared : settings.models()) {
                // Replace in place: the first declared model is the active one, and an override
                // of its endpoint must not demote it to the end of the list.
                final int existing = indexOfModel(declared.name());
                if (existing >= 0) {
                    models.set(existing, declared);
                } else {
                    models.add(declared);
                }
            }
            settings.mode().ifPresent(this::mode);
            settings.sandboxTechnology().ifPresent(this::sandbox);
            additionalWritableDirectories.addAll(settings.additionalWritableDirectories());
            settings.shellDefaultTimeout().ifPresent(timeout -> shellDefaultTimeout = timeout);
            settings.shellMaxTimeout().ifPresent(timeout -> shellMaxTimeout = timeout);
            permissionRules.addAll(settings.permissionRules());
            return this;
        }

        private int indexOfModel(final String name) {
            for (int i = 0; i < models.size(); i++) {
                if (models.get(i).name().equals(name)) {
                    return i;
                }
            }
            return -1;
        }

        /** Adds a permission rule directly, as an alternative to declaring it in settings. */
        public Builder permissionRule(final PermissionRule rule) {
            permissionRules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        /** Selects the sandbox technology by name; a shell-using agent needs a matching provider plugin. Defaults to {@code direct}. */
        public Builder sandbox(final String technology) {
            this.sandboxTechnology = Objects.requireNonNull(technology, "technology");
            return this;
        }

        /** The default and maximum timeout for shell commands; a requested timeout is clamped to the maximum. */
        public Builder shellTimeouts(final Duration defaultTimeout, final Duration maxTimeout) {
            this.shellDefaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
            this.shellMaxTimeout = Objects.requireNonNull(maxTimeout, "maxTimeout");
            return this;
        }

        /** Replaces the guarded default http capability — an embedder or test seam, not a way to relax the guards lightly. */
        public Builder http(final Http http) {
            this.http = Objects.requireNonNull(http, "http");
            return this;
        }

        /** The separate out channel subagent activity streams through; without one, subagent events are dropped. */
        public Builder subagentOut(final Out subagentOut) {
            this.subagentOut = Objects.requireNonNull(subagentOut, "subagentOut");
            return this;
        }

        /** The grant to persist sessions under {@code .coder/sessions} in the project; ungranted, save/resume fail loudly. */
        public Builder enableSessions() {
            this.sessionsGranted = true;
            return this;
        }

        /** The fraction of the active model's window that triggers compaction; conservative by default (0.7). */
        public Builder compactionThreshold(final double compactionThreshold) {
            this.compactionThreshold = compactionThreshold;
            return this;
        }

        /** The grant to gather environment facts (cwd, OS, model, git) into the system prompt; ungranted, no environment block. */
        public Builder gatherEnvironment() {
            this.gatherEnvironment = true;
            return this;
        }

        /** Supplies environment facts explicitly instead of gathering them — a test tells the model a fake host, and it learns nothing real. */
        public Builder environment(final EnvironmentFacts environment) {
            this.environment = Objects.requireNonNull(environment, "environment");
            return this;
        }

        /** Includes worked tool-call examples for the active model's style in the system prompt; off by default (empty core). */
        public Builder workedExamples() {
            this.workedExamples = true;
            return this;
        }

        /** Enables transient tail reminders (date, plan-mode notice, and a rules refresher every N steps); off by default. */
        public Builder reminders() {
            this.remindersEnabled = true;
            return this;
        }

        /** The reminder cadence — the rules refresher fires every N steps; default 10. */
        public Builder reminderInterval(final int reminderInterval) {
            if (reminderInterval <= 0) {
                throw new IllegalArgumentException("reminderInterval must be positive, got " + reminderInterval + ".");
            }
            this.reminderInterval = reminderInterval;
            return this;
        }

        /**
         * Enables the next-speaker check: a step ending in plain text is judged by the fast tier
         * — a stalled "I will now…" gets one nudge to continue. Off by default: it costs one
         * extra model call per turn ending. Worth enabling for local models that stop mid-work.
         */
        public Builder nextSpeakerCheck() {
            this.nextSpeakerCheck = true;
            return this;
        }

        public Coder build() {
            Objects.requireNonNull(out, "The out channel is required.");
            Objects.requireNonNull(hil, "The HIL channel is required.");
            if (models.isEmpty()) {
                throw new IllegalStateException("At least one model must be declared.");
            }

            final PermissionGate gate = new PermissionGate(hil, mode, new PermissionRules(permissionRules), new HardLimits());
            final LateBound lateBound = new LateBound();
            final ConversationsEngine conversationsEngine = new ConversationsEngine();

            final PluginCatalog catalog = new PluginCatalog();
            final List<List<Tool>> toolsByPlugin = new ArrayList<>();
            for (final Plugin plugin : plugins) {
                final int before = catalog.tools().size();
                final Capabilities granted = new Capabilities(validatingHil(hil), text -> out.event(new OutEvent.AnswerDelta(text)), lateBound.llms(), new Configuration(userDirectory, projectDirectory), lateBound.tools(), new AnchoredFileSystem(projectDirectory), new AnchoredFileSystem(userDirectory), http, lateBound.shell(), conversationsEngine.forPlugin(toolsByPlugin.size()), modesCapability(gate));
                plugin.install(catalog, granted.restrictedTo(plugin.requires()));
                toolsByPlugin.add(catalog.tools().subList(before, catalog.tools().size()));
            }

            final ModelRegistry registry = new ModelRegistry(models, catalog.protocolProviders());
            final ToolRegistry toolRegistry = new ToolRegistry(catalog.tools());
            catalog.policies().forEach(gate::registerPolicy);
            final JacksonToolParamsParser paramsParser = new JacksonToolParamsParser(JsonMapper.builder().build());
            final SessionShell sessionShell = resolveShell(catalog);
            lateBound.bind(registry, toolRegistry, gate, paramsParser, sessionShell);
            conversationsEngine.bind(registry, gate, paramsParser, subagentOut, toolsByPlugin);

            final EnvironmentFacts environment = resolveEnvironment(registry);
            final Conversation conversation = new Conversation(systemInstructions(catalog, sessionShell, registry, environment));
            final ContextManager contextManager = new ContextManager(registry, compactionThreshold, out, PromptResources.load("compaction-prompt.md"));
            final NextSpeakerCheck nextSpeaker = nextSpeakerCheck ? new NextSpeakerCheck(registry) : null;
            final LoopGuards guards = new LoopGuards(contextManager, nextSpeaker, resolveReminders(gate));
            final AgentLoop loop = new AgentLoop(conversation, registry, toolRegistry, gate, paramsParser, out, maxStepsPerTurn, guards);
            final SessionStore sessions = sessionsGranted ? new SessionStore(projectDirectory.resolve(".coder/sessions")) : null;
            return new Coder(loop, registry, gate, in, sessionShell, conversation, sessions, fingerprint(toolRegistry, registry, environment));
        }

        /** A stable hash of the prompt/tool inputs; a resumed session with the same fingerprint can replay its saved prompt. */
        private String fingerprint(final ToolRegistry toolRegistry, final ModelRegistry registry, final EnvironmentFacts environment) {
            final List<String> parts = new ArrayList<>();
            toolRegistry.schemas().forEach(schema -> parts.add(schema.name() + ":" + schema.description() + ":" + schema.parametersJson()));
            plugins.forEach(plugin -> parts.add(plugin.getClass().getName()));
            parts.add(environment == null ? "no-env" : environment.render());
            parts.add(registry.active().declaration().name());
            parts.add(registry.active().declaration().promptStyle().name());
            return SessionFingerprint.of(parts);
        }

        /** The modes capability, backed by the gate — read and switch the approval mode. */
        private static Modes modesCapability(final PermissionGate gate) {
            return new Modes() {

                @Override
                public Mode current() {
                    return gate.mode();
                }

                @Override
                public void switchTo(final Mode mode) {
                    gate.switchMode(mode);
                }
            };
        }

        /** The reminders component when granted, or null — the date, plan-mode notice, and periodic rules refresher, appended at the tail. */
        private Reminders resolveReminders(final PermissionGate gate) {
            if (!remindersEnabled) {
                return null;
            }
            return new Reminders(
                    reminderInterval,
                    PromptResources.load("critical-rules-reminder.md"),
                    PromptResources.load("plan-mode-reminder.md"),
                    gate::mode,
                    () -> java.time.LocalDate.now().toString());
        }

        /** Builds the session shell from the configured sandbox provider, or fails clearly when a shell-using plugin has none. */
        private SessionShell resolveShell(final PluginCatalog catalog) {
            final Optional<SandboxProvider> provider = catalog.sandboxProviders()
                    .stream()
                    .filter(candidate -> candidate.technology().equals(sandboxTechnology))
                    .findFirst();
            if (provider.isPresent()) {
                return new SessionShell(provider.orElseThrow(), new SandboxSpec(projectDirectory, additionalWritableDirectories, shellDefaultTimeout, shellMaxTimeout));
            }
            if (plugins.stream().anyMatch(plugin -> plugin.requires().contains(CapabilityType.SHELL))) {
                throw new IllegalStateException("A plugin requires the shell capability, but no sandbox provider for technology '" + sandboxTechnology + "' is registered. Register a sandbox provider plugin (e.g. DirectSandboxPlugin) or set a different technology via Builder.sandbox(...).");
            }
            return null;
        }

        private String systemInstructions(final PluginCatalog catalog, final SessionShell sessionShell, final ModelRegistry registry, final EnvironmentFacts environmentFacts) {
            // Slot order: core → conditional (sandbox/git) → worked examples → environment → plugins.
            return new PromptAssembler()
                    .add(instructions)
                    .add(sandboxSection(sessionShell))
                    .add(gitSection())
                    .add(examplesSection(registry))
                    .add(environmentFacts == null ? "" : environmentFacts.render())
                    .addAll(catalog.instructionSections())
                    .assemble();
        }

        /** Worked examples in the active model's style, when granted; a style with no bundled resource falls back to the general workflow examples. */
        private String examplesSection(final ModelRegistry registry) {
            if (!workedExamples) {
                return "";
            }
            final String styled = "examples-" + registry.active().declaration().promptStyle().resourceSuffix() + ".md";
            return PromptResources.load(PromptResources.exists(styled) ? styled : "examples-general.md");
        }

        /** The environment facts to render, under the gather grant or explicit override; null when neither is set. */
        private EnvironmentFacts resolveEnvironment(final ModelRegistry registry) {
            if (environment != null) {
                return environment;
            }
            if (!gatherEnvironment) {
                return null;
            }
            return new EnvironmentFacts(
                    projectDirectory.toString(),
                    System.getProperty("os.name", "unknown"),
                    registry.active().declaration().name(),
                    isGitRepository());
        }

        /** The sandbox section, true to reality — present only with a shell, inside vs. outside by the provider's confinement. */
        private static String sandboxSection(final SessionShell sessionShell) {
            if (sessionShell == null) {
                return "";
            }
            return PromptResources.load(sessionShell.confines() ? "inside-sandbox.md" : "outside-sandbox.md");
        }

        /** The git section, present only when the project directory is a git repository. */
        private String gitSection() {
            return isGitRepository() ? PromptResources.load("git-repository.md") : "";
        }

        private boolean isGitRepository() {
            return java.nio.file.Files.isDirectory(projectDirectory.resolve(".git"));
        }
    }
}
