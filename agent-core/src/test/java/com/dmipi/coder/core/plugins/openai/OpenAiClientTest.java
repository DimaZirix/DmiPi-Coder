package com.dmipi.coder.core.plugins.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.llm.ChatMessage;
import com.dmipi.coder.core.domain.llm.ChatRequest;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.LlmException;
import com.dmipi.coder.core.domain.llm.LlmStreamEvent;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import com.dmipi.coder.core.domain.llm.ModelOptions;
import com.dmipi.coder.core.domain.llm.Tier;
import com.dmipi.coder.core.domain.llm.ToolCall;
import com.dmipi.coder.core.domain.llm.ToolSchema;
import com.dmipi.coder.core.testfixtures.RecordingOut;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiClientTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> receivedBodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> receivedAuth = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private volatile List<String> sseLines = List.of();
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            receivedAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final byte[] body = String.join("\n", sseLines).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("text deltas stream in order and the finish reason arrives as its own event")
    void should_stream_text_deltas_and_finish() {
        // Given
        sseLines = List.of(
                data("{\"choices\": [{\"delta\": {\"content\": \"Hel\"}}]}"),
                data("{\"choices\": [{\"delta\": {\"content\": \"lo\"}}]}"),
                data("{\"choices\": [{\"delta\": {}, \"finish_reason\": \"stop\"}]}"),
                data("[DONE]"));

        // When
        final List<LlmStreamEvent> events = stream(new ChatRequest(List.of(ChatMessage.user("hi")), List.of()));

        // Then
        assertThat(events).containsExactly(new LlmStreamEvent.TextDelta("Hel"), new LlmStreamEvent.TextDelta("lo"), new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.STOP));
    }

    @Test
    @DisplayName("a reasoning stream arrives as thinking deltas, separate from the answer")
    void should_stream_reasoning_as_thinking() {
        // Given
        sseLines = List.of(
                data("{\"choices\": [{\"delta\": {\"reasoning_content\": \"hmm\"}}]}"),
                data("{\"choices\": [{\"delta\": {\"content\": \"answer\"}, \"finish_reason\": \"stop\"}]}"),
                data("[DONE]"));

        // When
        final List<LlmStreamEvent> events = stream(new ChatRequest(List.of(ChatMessage.user("hi")), List.of()));

        // Then
        assertThat(events).containsExactly(new LlmStreamEvent.ThinkingDelta("hmm"), new LlmStreamEvent.TextDelta("answer"), new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.STOP));
    }

    @Test
    @DisplayName("tool-call fragments carry index, id, name and argument pieces for the core to assemble")
    void should_stream_tool_call_fragments() {
        // Given
        sseLines = List.of(
                data("{\"choices\": [{\"delta\": {\"tool_calls\": [{\"index\": 0, \"id\": \"c1\", \"function\": {\"name\": \"echo\", \"arguments\": \"{\\\"te\"}}]}}]}"),
                data("{\"choices\": [{\"delta\": {\"tool_calls\": [{\"index\": 0, \"function\": {\"arguments\": \"xt\\\": \\\"hi\\\"}\"}}]}}]}"),
                data("{\"choices\": [{\"delta\": {}, \"finish_reason\": \"tool_calls\"}]}"),
                data("[DONE]"));

        // When
        final List<LlmStreamEvent> events = stream(new ChatRequest(List.of(ChatMessage.user("hi")), List.of()));

        // Then
        assertThat(events).containsExactly(
                new LlmStreamEvent.ToolCallDelta(0, "c1", "echo", "{\"te"),
                new LlmStreamEvent.ToolCallDelta(0, "", "", "xt\": \"hi\"}"),
                new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.TOOL_CALLS));
    }

    @Test
    @DisplayName("the request carries the model id, streaming, every message role, and the tool schemas as trees")
    void should_serialize_the_request() {
        // Given
        sseLines = List.of(data("{\"choices\": [{\"delta\": {}, \"finish_reason\": \"stop\"}]}"), data("[DONE]"));
        final ChatRequest request = new ChatRequest(
                List.of(
                        ChatMessage.system("SYS"),
                        ChatMessage.user("hi"),
                        ChatMessage.assistant("calling", List.of(new ToolCall("c1", "echo", "{\"text\": \"hi\"}"))),
                        ChatMessage.toolResult("c1", "echoed")),
                List.of(new ToolSchema("echo", "Echoes text.", "{\"type\": \"object\", \"required\": [\"text\"]}")));

        // When
        stream(request);

        // Then
        final JsonNode body = mapper.readTree(receivedBodies.getFirst());
        assertThat(body.path("model").stringValue()).isEqualTo("qwen");
        assertThat(body.path("stream").booleanValue()).isTrue();
        assertThat(body.path("messages").path(0).path("role").stringValue()).isEqualTo("system");
        assertThat(body.path("messages").path(2).path("tool_calls").path(0).path("function").path("name").stringValue()).isEqualTo("echo");
        assertThat(body.path("messages").path(3).path("tool_call_id").stringValue()).isEqualTo("c1");
        assertThat(body.path("tools").path(0).path("function").path("parameters").path("required").path(0).stringValue()).isEqualTo("text");
    }

    @Test
    @DisplayName("a non-2xx answer raises an exception naming the status and the body")
    void should_fail_on_a_server_error() {
        // Given
        status = 500;
        sseLines = List.of("busted");

        // When / Then
        assertThatExceptionOfType(LlmException.class)
                .isThrownBy(() -> stream(new ChatRequest(List.of(ChatMessage.user("hi")), List.of())))
                .withMessageContaining("500")
                .withMessageContaining("busted");
    }

    @Test
    @DisplayName("an error object inside the stream raises an exception")
    void should_fail_on_an_in_stream_error() {
        // Given
        sseLines = List.of(data("{\"error\": {\"message\": \"context overflow\"}}"));

        // When / Then
        assertThatExceptionOfType(LlmException.class)
                .isThrownBy(() -> stream(new ChatRequest(List.of(ChatMessage.user("hi")), List.of())))
                .withMessageContaining("context overflow");
    }

    @Test
    @DisplayName("a stream that ends without a finish reason still finishes, as OTHER")
    void should_finish_as_other_when_the_stream_just_ends() {
        // Given
        sseLines = List.of(data("{\"choices\": [{\"delta\": {\"content\": \"cut\"}}]}"));

        // When
        final List<LlmStreamEvent> events = stream(new ChatRequest(List.of(ChatMessage.user("hi")), List.of()));

        // Then
        assertThat(events).containsExactly(new LlmStreamEvent.TextDelta("cut"), new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.OTHER));
    }

    @Test
    @DisplayName("full stack: a Coder with the OpenAI provider plugin answers a turn from the server")
    void should_run_a_turn_through_the_provider_plugin() {
        // Given
        sseLines = List.of(
                data("{\"choices\": [{\"delta\": {\"content\": \"Hello from the model\"}}]}"),
                data("{\"choices\": [{\"delta\": {}, \"finish_reason\": \"stop\"}]}"),
                data("[DONE]"));
        final RecordingOut out = new RecordingOut();
        final Coder coder = Coder.builder()
                .instructions("You are a test agent.")
                .out(out)
                .hil(new ScriptedHil(List.of(Answer.of("deny"))))
                .model(declaration())
                .registerPlugin(new OpenAiProviderPlugin())
                .build();

        // When
        coder.runTurn("say hello", new CancelToken());

        // Then
        assertThat(out.answerText()).isEqualTo("Hello from the model");
    }

    @Test
    @DisplayName("with apiKeyEnv set, the request carries an Authorization: Bearer header from that env var")
    void should_send_the_api_key_from_the_env_var() {
        // Given: PATH is always set; use it as the key source
        sseLines = List.of(data("{\"choices\": [{\"delta\": {}, \"finish_reason\": \"stop\"}]}"), data("[DONE]"));
        final ModelDeclaration keyed = new ModelDeclaration("qwen", "openai", declaration().endpoint(), Tier.FAST, 8_000,
                ModelOptions.defaults().withApiKeyEnv("PATH"));

        // When
        new OpenAiClient(keyed).stream(new ChatRequest(List.of(ChatMessage.user("hi")), List.of()), new CancelToken(), event -> {
        });

        // Then
        assertThat(receivedAuth).singleElement().isEqualTo("Bearer " + System.getenv("PATH"));
        // And without apiKeyEnv, no header
        stream(new ChatRequest(List.of(ChatMessage.user("hi")), List.of()));
        assertThat(receivedAuth).element(1).isNull();
    }

    @Test
    @DisplayName("apiKeyEnv naming a missing env var fails loudly at construction, not silently unauthenticated")
    void should_fail_when_the_api_key_env_var_is_missing() {
        final ModelDeclaration keyed = new ModelDeclaration("qwen", "openai", declaration().endpoint(), Tier.FAST, 8_000,
                ModelOptions.defaults().withApiKeyEnv("DEFINITELY_NOT_A_REAL_ENV_VAR_XYZ"));
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> new OpenAiClient(keyed))
                .withMessageContaining("apiKeyEnv");
    }

    private List<LlmStreamEvent> stream(final ChatRequest request) {
        final LlmClient client = new OpenAiClient(declaration());
        final List<LlmStreamEvent> events = new ArrayList<>();
        client.stream(request, new CancelToken(), events::add);
        return events;
    }

    private ModelDeclaration declaration() {
        return new ModelDeclaration("qwen", "openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", Tier.FAST, 8_000);
    }

    private static String data(final String payload) {
        return "data: " + payload + "\n";
    }
}
