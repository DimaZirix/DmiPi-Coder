package com.dmipi.coder.core.plugins.claudemarketplace;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ProtocolProvider;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.llm.ToolSchema;
import com.dmipi.coder.core.plugin.Capabilities;
import com.dmipi.coder.core.plugin.Plugin;
import com.dmipi.coder.core.plugin.PluginRegistrar;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedClient;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ClaudeMarketplacePluginTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    private Path marketplace;

    @TempDir
    private Path userDirectory;

    @TempDir
    private Path projectDirectory;

    private final RecordingOut out = new RecordingOut();
    private HttpServer server;

    @BeforeEach
    void startStubMcpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::answer);
        server.start();
    }

    @AfterEach
    void stopStubMcpServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("a marketplace plugin's SKILL.md becomes a skill, and its .mcp.json becomes proxied tools")
    void should_load_skills_and_servers_from_the_marketplace() throws IOException {
        // Given: a java-standards skill and a log-reader MCP server, laid out as a Claude marketplace
        writeSkill("java-standards", "senior-java-developer", """
                ---
                name: senior-java-developer
                description: Apply senior Java standards.
                ---
                Prefer immutability and guard clauses.""");
        writeMcpConfig("log-reader", """
                {"mcpServers": {"log-reader": {"type": "http", "url": "%s"}}}""".formatted(url()));
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "skill", "{\"name\": \"senior-java-developer\"}"),
                ScriptedClient.textStep("done")));

        // When
        runTurn(client);

        // Then: the skill is listed and loadable, and the remote tool is proxied under mcp__log-reader__*
        final ToolSchema skillTool = toolNamed(client, "skill");
        assertThat(skillTool.description()).contains("senior-java-developer: Apply senior Java standards.");
        assertThat(client.requests().getFirst().tools())
                .extracting(ToolSchema::name)
                .contains("mcp__log-reader__lookup");
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("Prefer immutability and guard clauses."));
    }

    @Test
    @DisplayName("an unreachable marketplace server is skipped — the session still starts")
    void should_skip_an_unreachable_server() throws IOException {
        // Given: a config pointing at a closed port
        writeMcpConfig("dead-plugin", """
                {"mcpServers": {"gone": {"type": "http", "url": "http://127.0.0.1:1/mcp"}}}""");
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        runTurn(client);

        // Then: no MCP tools, no failure
        assertThat(client.requests().getFirst().tools())
                .extracting(ToolSchema::name)
                .noneMatch(name -> name.startsWith("mcp__"));
    }

    @Test
    @DisplayName("a non-http transport in a marketplace config is skipped; the http one still loads")
    void should_skip_unsupported_transports() throws IOException {
        // Given
        writeMcpConfig("mixed", """
                {"mcpServers": {
                  "local": {"type": "stdio", "command": "whatever"},
                  "stub": {"type": "http", "url": "%s"}
                }}""".formatted(url()));
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        runTurn(client);

        // Then
        assertThat(client.requests().getFirst().tools())
                .extracting(ToolSchema::name)
                .contains("mcp__stub__lookup")
                .noneMatch(name -> name.startsWith("mcp__local__"));
    }

    @Test
    @DisplayName("with no marketplace directories, nothing is contributed and startup is clean")
    void should_contribute_nothing_without_a_marketplace() {
        // Given: an empty root list
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .userDirectory(userDirectory)
                .projectDirectory(projectDirectory)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new ClaudeMarketplacePlugin(List.of()))
                .build()) {
            coder.runTurn("go", new CancelToken());
        }

        // Then: no skill tool, no mcp tools
        assertThat(client.requests().getFirst().tools())
                .extracting(ToolSchema::name)
                .doesNotContain("skill")
                .noneMatch(name -> name.startsWith("mcp__"));
    }

    private void runTurn(final ScriptedClient client) {
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(new ScriptedHil(List.of()))
                .model(MODEL)
                .userDirectory(userDirectory)
                .projectDirectory(projectDirectory)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new ClaudeMarketplacePlugin(List.of(marketplace)))
                .build()) {
            coder.runTurn("go", new CancelToken());
        }
    }

    private static ToolSchema toolNamed(final ScriptedClient client, final String name) {
        return client.requests().getFirst().tools().stream()
                .filter(schema -> schema.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private void writeSkill(final String pluginName, final String skillName, final String content) throws IOException {
        final Path directory = marketplace.resolve(pluginName + "/skills/" + skillName);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), content);
    }

    private void writeMcpConfig(final String pluginName, final String json) throws IOException {
        final Path directory = marketplace.resolve(pluginName);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(".mcp.json"), json);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    /** A minimal streamable-http MCP server: JSON answers for initialize/list, an SSE answer for calls. */
    private void answer(final HttpExchange exchange) throws IOException {
        final JsonNode message = MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        final String method = message.path("method").stringValue();

        switch (method) {
            case "initialize" -> {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
                respondJson(exchange, "{\"jsonrpc\": \"2.0\", \"id\": " + message.path("id").longValue() + ", \"result\": {\"capabilities\": {}}}");
            }
            case "notifications/initialized" -> {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            }
            case "tools/list" -> respondJson(exchange, """
                    {"jsonrpc": "2.0", "id": %d, "result": {"tools": [
                      {"name": "lookup", "description": "Looks something up.", "inputSchema": {"type": "object"}, "annotations": {"readOnlyHint": true}}
                    ]}}""".formatted(message.path("id").longValue()));
            case "tools/call" -> respondSse(exchange, """
                    {"jsonrpc": "2.0", "id": %d, "result": {"content": [{"type": "text", "text": "remote ok"}]}}"""
                    .formatted(message.path("id").longValue()));
            default -> {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
            }
        }
    }

    private static void respondJson(final HttpExchange exchange, final String body) throws IOException {
        respond(exchange, "application/json", body);
    }

    private static void respondSse(final HttpExchange exchange, final String message) throws IOException {
        respond(exchange, "text/event-stream", "event: message\ndata: " + message + "\n\n");
    }

    private static void respond(final HttpExchange exchange, final String contentType, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    private static Plugin providerPlugin(final ScriptedClient client) {
        return new Plugin() {

            @Override
            public void install(final PluginRegistrar registrar, final Capabilities capabilities) {
                registrar.registerProtocolProvider(new ProtocolProvider() {

                    @Override
                    public String protocol() {
                        return "scripted";
                    }

                    @Override
                    public LlmClient connect(final ModelDeclaration declaration) {
                        return client;
                    }
                });
            }
        };
    }
}
