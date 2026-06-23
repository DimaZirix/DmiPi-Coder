package com.dmipi.coder.core.plugins.openai;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.llm.ChatRequest;
import com.dmipi.coder.core.domain.llm.LlmClient;
import com.dmipi.coder.core.domain.llm.LlmException;
import com.dmipi.coder.core.domain.llm.LlmStreamEvent;
import com.dmipi.coder.core.domain.llm.ModelDeclaration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAI-compatible chat-completions transport: one streaming POST per model call, SSE
 * lines decoded into stream events. The declaration's name is the server-side model id, its
 * endpoint the base URL (e.g. {@code http://localhost:8080/v1}).
 */
final class OpenAiClient implements LlmClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String DATA_PREFIX = "data:";
    private static final String DONE = "[DONE]";
    private static final int ERROR_BODY_CAP = 2_000;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ModelDeclaration declaration;
    private final URI completionsUri;

    OpenAiClient(final ModelDeclaration declaration) {
        this.declaration = declaration;
        final String base = declaration.endpoint().endsWith("/") ? declaration.endpoint().substring(0, declaration.endpoint().length() - 1) : declaration.endpoint();
        this.completionsUri = URI.create(base + "/chat/completions");
    }

    @Override
    public void stream(final ChatRequest request, final CancelToken cancel, final Consumer<LlmStreamEvent> events) {
        final HttpResponse<InputStream> response = send(OpenAiJson.writeRequest(mapper, declaration.name(), request));
        final InputStream guarded = new IdleStreamGuard(response.body(), Duration.ofSeconds(declaration.options().idleTimeoutSeconds()));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(guarded, StandardCharsets.UTF_8))) {
            readStream(reader, cancel, events);
        } catch (final IOException failure) {
            throw new LlmException("The stream from " + completionsUri + " failed: " + failure.getMessage(), failure);
        }
    }

    private HttpResponse<InputStream> send(final String body) {
        final HttpRequest http = HttpRequest.newBuilder(completionsUri)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        final HttpResponse<InputStream> response;
        try {
            response = httpClient.send(http, HttpResponse.BodyHandlers.ofInputStream());
        } catch (final IOException failure) {
            throw new LlmException("Could not reach " + completionsUri + ": " + failure.getMessage(), failure);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while calling " + completionsUri + ".", interrupted);
        }
        if (response.statusCode() / 100 != 2) {
            throw new LlmException("Model '" + declaration.name() + "' answered HTTP " + response.statusCode() + " at " + completionsUri + ": " + errorBody(response));
        }
        return response;
    }

    private void readStream(final BufferedReader reader, final CancelToken cancel, final Consumer<LlmStreamEvent> events) throws IOException {
        boolean finished = false;
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            if (cancel.isCancelled()) {
                return;
            }
            if (!line.startsWith(DATA_PREFIX)) {
                continue;
            }

            final String payload = line.substring(DATA_PREFIX.length()).strip();
            if (payload.equals(DONE)) {
                break;
            }
            finished |= emitChunk(payload, events);
        }
        if (!finished && !cancel.isCancelled()) {
            events.accept(new LlmStreamEvent.Finished(LlmStreamEvent.FinishReason.OTHER));
        }
    }

    private boolean emitChunk(final String payload, final Consumer<LlmStreamEvent> events) {
        try {
            return OpenAiJson.emitChunk(mapper.readTree(payload), events);
        } catch (final JacksonException invalid) {
            throw new LlmException("Model '" + declaration.name() + "' sent an unparsable stream chunk: " + payload);
        }
    }

    private String errorBody(final HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            final String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            return text.length() > ERROR_BODY_CAP ? text.substring(0, ERROR_BODY_CAP) : text;
        } catch (final IOException unreadable) {
            return "(the error body could not be read)";
        }
    }
}
