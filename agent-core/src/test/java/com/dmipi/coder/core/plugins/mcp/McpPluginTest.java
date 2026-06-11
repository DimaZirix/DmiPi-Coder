package com.dmipi.coder.core.plugins.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.hil.Answer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpPluginTest {

    private static final ModelDeclaration MODEL = new ModelDeclaration("test", "scripted", "", Tier.FAST, 8_000);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    private Path userDirectory;

    @TempDir
    private Path projectDirectory;

    private final RecordingOut out = new RecordingOut();
    private final List<String> sessionHeadersSeen = new CopyOnWriteArrayList<>();
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
    @DisplayName("declared servers contribute their tools as mcp__server__tool; a read-only tool runs without asking")
    void should_proxy_remote_tools() throws IOException {
        // Given: a configured server, and the model calling the read-only remote tool
        writeProjectConfig();
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "mcp__stub__lookup", "{\"query\": \"x\"}"),
                ScriptedClient.textStep("done")));
        final ScriptedHil hil = new ScriptedHil(List.of());

        // When
        runTurn(client, hil);

        // Then: both remote tools exist under the composed name, the call went through without asking
        assertThat(client.requests().getFirst().tools())
                .extracting(ToolSchema::name)
                .contains("mcp__stub__lookup", "mcp__stub__mutate");
        assertThat(hil.asked()).isEmpty();
        assertThat(client.requests().getLast().messages())
                .anySatisfy(message -> assertThat(message.content()).contains("remote says: found x"));

        // And the session id announced at initialize was carried on later requests
        assertThat(sessionHeadersSeen).contains("session-42");
    }

    @Test
    @DisplayName("a tool without the read-only hint asks, with the arguments as preview")
    void should_ask_for_a_mutating_remote_tool() throws IOException {
        // Given
        writeProjectConfig();
        final ScriptedClient client = new ScriptedClient(List.of(
                ScriptedClient.toolCallStep("c1", "mcp__stub__mutate", "{\"value\": 7}"),
                ScriptedClient.textStep("ok")));
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("deny")));

        // When
        runTurn(client, hil);

        // Then
        assertThat(hil.asked()).singleElement().satisfies(question -> {
            assertThat(question.question()).contains("mcp__stub__mutate");
            assertThat(question.preview()).contains("\"value\":7");
        });
    }

    @Test
    @DisplayName("an unreachable server is skipped with a warning — the session still starts")
    void should_skip_an_unreachable_server() throws IOException {
        // Given: a config pointing at a closed port
        Files.writeString(projectDirectory.resolve(".mcp.json"), """
                {"mcpServers": {"gone": {"type": "http", "url": "http://127.0.0.1:1/mcp"}}}""");
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        runTurn(client, new ScriptedHil(List.of()));

        // Then: no MCP tools, no failure
        assertThat(client.requests().getFirst().tools())
                .extracting(ToolSchema::name)
                .noneMatch(name -> name.startsWith("mcp__"));
    }

    @Test
    @DisplayName("a non-http transport in the config is skipped; the http one on the same file still loads")
    void should_skip_unsupported_transports() throws IOException {
        // Given
        Files.writeString(projectDirectory.resolve(".mcp.json"), """
                {"mcpServers": {
                  "local": {"type": "stdio", "command": "whatever"},
                  "stub": {"type": "http", "url": "%s"}
                }}""".formatted(url()));
        final ScriptedClient client = new ScriptedClient(List.of(ScriptedClient.textStep("hi")));

        // When
        runTurn(client, new ScriptedHil(List.of()));

        // Then
        assertThat(client.requests().getFirst().tools())
                .extracting(ToolSchema::name)
                .contains("mcp__stub__lookup")
                .noneMatch(name -> name.startsWith("mcp__local__"));
    }

    private void runTurn(final ScriptedClient client, final ScriptedHil hil) {
        try (Coder coder = Coder.builder()
                .out(out)
                .hil(hil)
                .model(MODEL)
                .userDirectory(userDirectory)
                .projectDirectory(projectDirectory)
                .registerPlugin(providerPlugin(client))
                .registerPlugin(new McpPlugin())
                .build()) {
            coder.runTurn("go", new CancelToken());
        }
    }

    private void writeProjectConfig() throws IOException {
        Files.writeString(projectDirectory.resolve(".mcp.json"), """
                {"mcpServers": {"stub": {"type": "http", "url": "%s"}}}""".formatted(url()));
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    /** A minimal streamable-http MCP server: JSON answers for initialize/list, an SSE answer for calls. */
    private void answer(final HttpExchange exchange) throws IOException {
        final JsonNode message = MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        final String method = message.path("method").stringValue();
        final String sessionHeader = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (sessionHeader != null) {
            sessionHeadersSeen.add(sessionHeader);
        }

        switch (method) {
            case "initialize" -> {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "session-42");
                respondJson(exchange, "{\"jsonrpc\": \"2.0\", \"id\": " + message.path("id").longValue() + ", \"result\": {\"capabilities\": {}}}");
            }
            case "notifications/initialized" -> {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            }
            case "tools/list" -> respondJson(exchange, """
                    {"jsonrpc": "2.0", "id": %d, "result": {"tools": [
                      {"name": "lookup", "description": "Looks something up.", "inputSchema": {"type": "object"}, "annotations": {"readOnlyHint": true}},
                      {"name": "mutate", "description": "Changes something.", "inputSchema": {"type": "object"}}
                    ]}}""".formatted(message.path("id").longValue()));
            case "tools/call" -> respondSse(exchange, """
                    {"jsonrpc": "2.0", "id": %d, "result": {"content": [{"type": "text", "text": "remote says: found x"}]}}"""
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
