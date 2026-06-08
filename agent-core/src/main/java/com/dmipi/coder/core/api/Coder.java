package com.dmipi.coder.core.api;

import com.dmipi.coder.core.application.permissions.PermissionGate;
import com.dmipi.coder.core.domain.agent.AgentLoop;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.agent.Conversation;
import com.dmipi.coder.core.domain.agent.In;
import com.dmipi.coder.core.domain.event.Out;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.hil.Hil;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ModelRegistry;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.domain.shell.SandboxProvider;
import com.dmipi.coder.core.domain.shell.SandboxSpec;
import com.dmipi.coder.core.domain.tool.ToolRegistry;
import com.dmipi.coder.core.infrastructure.files.AnchoredFileSystem;
import com.dmipi.coder.core.infrastructure.json.JacksonToolParamsParser;
import com.dmipi.coder.core.infrastructure.shell.SessionShell;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.CapabilityType;
import com.dmipi.coder.core.plugin.Configuration;
import com.dmipi.coder.core.plugin.Plugin;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
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
    private volatile CancelToken currentTurn;

    private Coder(final AgentLoop agentLoop, final ModelRegistry models, final PermissionGate gate, final Out out, final In in, final AutoCloseable sessionShell) {
        this.agentLoop = agentLoop;
        this.models = models;
        this.gate = gate;
        this.out = out;
        this.in = in;
        this.sessionShell = sessionShell;
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

        public Coder build() {
            Objects.requireNonNull(out, "The out channel is required.");
            Objects.requireNonNull(hil, "The HIL channel is required.");
            if (models.isEmpty()) {
                throw new IllegalStateException("At least one model must be declared.");
            }

            final PermissionGate gate = new PermissionGate(hil, mode);
            final LateBound lateBound = new LateBound();
            final Capabilities granted = new Capabilities(hil, text -> out.event(new OutEvent.AnswerDelta(text)), lateBound.llms(), new Configuration(userDirectory, projectDirectory), lateBound.tools(), new AnchoredFileSystem(projectDirectory), lateBound.shell());

            final PluginCatalog catalog = new PluginCatalog();
            for (final Plugin plugin : plugins) {
                plugin.install(catalog, granted.restrictedTo(plugin.requires()));
            }

            final ModelRegistry registry = new ModelRegistry(models, catalog.protocolProviders());
            final ToolRegistry toolRegistry = new ToolRegistry(catalog.tools());
            catalog.policies().forEach(gate::registerPolicy);
            final JacksonToolParamsParser paramsParser = new JacksonToolParamsParser(JsonMapper.builder().build());
            final SessionShell sessionShell = resolveShell(catalog);
            lateBound.bind(registry, toolRegistry, gate, paramsParser, sessionShell);

            final Conversation conversation = new Conversation(systemInstructions(catalog));
            final AgentLoop loop = new AgentLoop(conversation, registry, toolRegistry, gate, paramsParser, out, maxStepsPerTurn);
            return new Coder(loop, registry, gate, out, in, sessionShell);
        }

        /** Builds the session shell from the configured sandbox provider, or fails clearly when a shell-using plugin has none. */
        private SessionShell resolveShell(final PluginCatalog catalog) {
            final Optional<SandboxProvider> provider = catalog.sandboxProviders()
                    .stream()
                    .filter(candidate -> candidate.technology().equals(sandboxTechnology))
                    .findFirst();
            if (provider.isPresent()) {
                return new SessionShell(provider.orElseThrow(), new SandboxSpec(projectDirectory, List.of(), shellDefaultTimeout, shellMaxTimeout));
            }
            if (plugins.stream().anyMatch(plugin -> plugin.requires().contains(CapabilityType.SHELL))) {
                throw new IllegalStateException("A plugin requires the shell capability, but no sandbox provider for technology '" + sandboxTechnology + "' is registered. Register a sandbox provider plugin (e.g. DirectSandboxPlugin) or set a different technology via Builder.sandbox(...).");
            }
            return null;
        }

        private String systemInstructions(final PluginCatalog catalog) {
            final String sections = catalog.instructionSections()
                    .stream()
                    .collect(Collectors.joining("\n\n"));
            if (sections.isBlank()) {
                return instructions;
            }
            return instructions.isBlank() ? sections : instructions + "\n\n" + sections;
        }
    }
}
