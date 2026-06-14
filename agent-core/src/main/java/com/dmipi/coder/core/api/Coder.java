package com.dmipi.coder.core.api;

import com.dmipi.coder.core.application.permissions.PermissionGate;
import com.dmipi.coder.core.application.prompt.PromptAssembler;
import com.dmipi.coder.core.application.prompt.PromptResources;
import com.dmipi.coder.core.domain.agent.AgentLoop;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.agent.ContextManager;
import com.dmipi.coder.core.domain.agent.Conversation;
import com.dmipi.coder.core.domain.agent.In;
import com.dmipi.coder.core.domain.agent.NextSpeakerCheck;
import com.dmipi.coder.core.domain.event.Out;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.hil.Hil;
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
import com.dmipi.coder.core.infrastructure.sessions.SessionStore;
import com.dmipi.coder.core.infrastructure.settings.Settings;
import com.dmipi.coder.core.infrastructure.settings.SettingsLoader;
import com.dmipi.coder.core.infrastructure.shell.SessionShell;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Http;
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
    private final Out out;
    private final In in;
    private final AutoCloseable sessionShell;
    private final Conversation conversation;
    private final SessionStore sessions;
    private volatile CancelToken currentTurn;

    private Coder(final AgentLoop agentLoop, final ModelRegistry models, final PermissionGate gate, final Out out, final In in, final AutoCloseable sessionShell, final Conversation conversation, final SessionStore sessions) {
        this.agentLoop = agentLoop;
        this.models = models;
        this.gate = gate;
        this.out = out;
        this.in = in;
        this.sessionShell = sessionShell;
        this.conversation = conversation;
        this.sessions = sessions;
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

    /** Persists the dialogue (never the instructions) under the given name; overwrites a previous save of that name. */
    public void saveSession(final String name) {
        store().save(name, conversation.messages());
    }

    /**
     * Continues a saved session: its dialogue is appended under the freshly built instructions.
     * Only a conversation with no history yet can resume — resume first, then talk.
     */
    public void resumeSession(final String name) {
        if (conversation.messages().size() > 1) {
            throw new IllegalStateException("This conversation already has history — resume before the first turn.");
        }
        store().load(name).forEach(conversation::add);
    }

    private SessionStore store() {
        if (sessions == null) {
            throw new IllegalStateException("Session persistence is not granted: enable it with Builder.enableSessions().");
        }
        return sessions;
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

        private Builder() {
        }

        /** The core system instructions; plugin instruction sections are appended after them. */
        public Builder instructions(final String instructions) {
            this.instructions = Objects.requireNonNull(instructions, "instructions");
            return this;
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
            this.userDirectory = userDirectory.toAbsolutePath().normalize();
            return this;
        }

        /** The current path — the project worked on; the anchor for project-scope locations. */
        public Builder projectDirectory(final Path projectDirectory) {
            this.projectDirectory = projectDirectory.toAbsolutePath().normalize();
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
                models.removeIf(existing -> existing.name().equals(declared.name()));
                models.add(declared);
            }
            settings.mode().ifPresent(this::mode);
            settings.sandboxTechnology().ifPresent(this::sandbox);
            additionalWritableDirectories.addAll(settings.additionalWritableDirectories());
            settings.shellDefaultTimeout().ifPresent(timeout -> shellDefaultTimeout = timeout);
            settings.shellMaxTimeout().ifPresent(timeout -> shellMaxTimeout = timeout);
            permissionRules.addAll(settings.permissionRules());
            return this;
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
                final Capabilities granted = new Capabilities(hil, text -> out.event(new OutEvent.AnswerDelta(text)), lateBound.llms(), new Configuration(userDirectory, projectDirectory), lateBound.tools(), new AnchoredFileSystem(projectDirectory), new AnchoredFileSystem(userDirectory), http, lateBound.shell(), conversationsEngine.forPlugin(toolsByPlugin.size()));
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

            final Conversation conversation = new Conversation(systemInstructions(catalog, sessionShell));
            final ContextManager contextManager = new ContextManager(registry, compactionThreshold, out);
            final NextSpeakerCheck nextSpeaker = nextSpeakerCheck ? new NextSpeakerCheck(registry) : null;
            final AgentLoop loop = new AgentLoop(conversation, registry, toolRegistry, gate, paramsParser, out, maxStepsPerTurn, contextManager, nextSpeaker);
            final SessionStore sessions = sessionsGranted ? new SessionStore(projectDirectory.resolve(".coder/sessions")) : null;
            return new Coder(loop, registry, gate, out, in, sessionShell, conversation, sessions);
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

        private String systemInstructions(final PluginCatalog catalog, final SessionShell sessionShell) {
            // Slot order: core → conditional (sandbox/git) → plugin sections last. Later phases
            // insert worked examples and environment between the conditionals and the plugins.
            return new PromptAssembler()
                    .add(instructions)
                    .add(sandboxSection(sessionShell))
                    .add(gitSection())
                    .addAll(catalog.instructionSections())
                    .assemble();
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
            return java.nio.file.Files.isDirectory(projectDirectory.resolve(".git"))
                    ? PromptResources.load("git-repository.md")
                    : "";
        }
    }
}
