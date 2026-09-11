# LLM — Technical Companion

*Technical side of [FUNCTIONAL-OVERVIEW.md §12](FUNCTIONAL-OVERVIEW.md#12-models). Same plain style, but here we explain the implementation: the model registry and tier selection, the one LLM contract, the built-in OpenAI-compatible provider on the wire, and the isolated control calls.*

## 1. The idea in one paragraph

The core never speaks a wire protocol. It defines one contract, `LlmClient.stream(request, cancel, events)`, and matches every declared model to the provider plugin that speaks its protocol at build time; no match is a startup error. Every model carries a tier the operator assigned, and callers select by it: the conversation uses the active model, cheap internal checks use the fastest, subagent types ask for "at least" a tier, compaction uses the active model because the summary's quality is load-bearing. The built-in provider speaks the OpenAI chat-completions API with streaming, which is what most local servers offer, and adds the switches that make a local server behave: an idle-stream guard, a thinking switch, schema-constrained replies with graceful fallback, and an optional bearer key.

## 2. Data model

| Type | Fields | Set by | Purpose |
|---|---|---|---|
| `ModelDeclaration` | `name`, `protocol`, `endpoint`, `tier`, `contextWindow`, `options` | settings or the builder | One configured model. Names are unique; the first declared is active. |
| `ModelOptions` | `promptStyle` (default `GENERAL`), `idleTimeoutSeconds` (900), `apiKeyEnv` (empty), `thinking` (true), `structuredOutput` (`AUTO`) | settings, defaults otherwise | The optional wire settings. |
| `Tier` | `FAST` < `BALANCED` < `STRONG` | the operator | An ordering within the configured set, nothing more. |
| `PromptStyle` | `GENERAL`, `NATIVE`, `QWEN_CODER`, `QWEN_VL` | the operator | Selects the worked-examples resource; every style resolves to the general examples today. |
| `StructuredOutput` | `AUTO`, `OFF` | the operator | Whether `response_format` may be sent. |
| `ChatRequest` | `messages`, `tools`, `thinkingDisabled`, `responseSchemaJson` | the loop, control calls, plugins | One request. `asControlCall(schema)` sets thinking off and a schema. |
| `ChatMessage` | `role` (`SYSTEM`, `USER`, `ASSISTANT`, `TOOL`), `content`, `toolCalls`, `toolCallId` | the conversation | Only an assistant message carries tool calls; only a tool message carries a call id. |
| `LlmStreamEvent` | `TextDelta`, `ThinkingDelta`, `ToolCallDelta(index, id, name, argumentsDelta)`, `Finished(reason)` | the provider | What streams back. |
| `ConnectedModel` | `declaration`, `client` | the registry | A declaration resolved to its client. |

## 3. Registry and selection

```
 declarations (settings, builder)         protocol providers (plugins)
          │                                          │
          └──────────▶ ModelRegistry ◀───────────────┘
                        │  for each declaration: provider with protocol == declaration.protocol
                        │     none ──▶ IllegalStateException("… no registered provider speaks it")
                        │  duplicate name ──▶ error
                        ▼
              byName: name → ConnectedModel;  active = first declared (volatile, switchable)

 selection:  active()      the conversation's current model
             fastest()     lowest tier; ties by declaration order
             strongest()   highest tier
             atLeast(t)    lowest tier ≥ t; the strongest when none reaches t
```

The `Llms` capability handed to plugins is the same four selectors, bound late (after all plugins installed), so a plugin holds it at install and calls it from a tool. `Coder.activateModel` switches the active model between or during turns; the loop reads the active client at every step, and the context budget follows the new window.

## 4. The OpenAI-compatible provider on the wire

```
 OpenAiProtocolProvider.connect(declaration) ──▶ OpenAiClient
   POST <endpoint>/chat/completions           (trailing slash stripped from the endpoint)
   Authorization: Bearer $<apiKeyEnv>         only when apiKeyEnv is set; unset variable ⇒ startup error
   body: { model, stream: true, messages[], tools[],
           chat_template_kwargs: { enable_thinking: false }   when thinking is off for this call
           response_format: { type: json_schema, … }          when a schema is requested and structuredOutput = AUTO }
   ◀── SSE "data:" lines until [DONE]
        delta.content            ──▶ TextDelta
        delta.reasoning_content  ──▶ ThinkingDelta   (or delta.reasoning)
        delta.tool_calls[i]      ──▶ ToolCallDelta(index, id, name, argumentsDelta)
        finish_reason            ──▶ Finished(STOP | TOOL_CALLS | LENGTH | OTHER)
        stream ends bare         ──▶ Finished(OTHER) synthesized
```

- **Thinking off** for a call: always for control calls (`thinkingDisabled`), and for the conversation when the model declares `thinking: false`.
- **Idle guard.** The response body is wrapped in `IdleStreamGuard`: a read that sees no byte for `idleTimeoutSeconds` fails with the same `LlmException` path as any transport failure, so the turn fails visibly instead of hanging. It resets on every byte, so a model emitting one token every few seconds never trips it. There is no cap on total response time.
- **Structured output** is an optimization, never a dependency: under `AUTO` a server that rejects `response_format` or returns non-conforming text makes the caller fall back to text parsing; `OFF` never sends it.
- **Errors.** A non-2xx status raises `LlmException` with the status and a capped body. There is no retry; the turn fails and the next prompt continues the same history.
- **Deterministic serialization.** `OpenAiJson` writes the request with a fixed field order and stable escaping, and a test pins that the same `ChatRequest` serializes to identical bytes, including after a save-and-load round trip. This is what makes a resumed session cache-warm ([CONVERSATION-TECHNICAL.md §8](CONVERSATION-TECHNICAL.md#8-sessions-and-cache-stable-resume)).
- **Message shape** is the standard one: the system message at index 0, then user, assistant (with `tool_calls`) and tool (with `tool_call_id`) messages in order. Tool descriptions are not in the system prompt; they ride in `tools[]` on every request.

## 5. Control calls

Three places ask a model a question that is not the conversation. All three are isolated: a fresh request whose content is framed as data, never a continuation of the conversation that produced it; thinking off; a schema where useful.

| Call | Model | Request | Fallback |
|---|---|---|---|
| Next-speaker check | fastest | The step's final text, with the question "who should speak next?" and the schema `{"next": "model" \| "user"}`. | Text parse of a reply starting with "model". Anything unclear ends the turn: ending beats looping. |
| Web page summary | fastest | The stripped page (capped at 60 000 characters) and the tool's `prompt`, framed as untrusted content to summarize. | A summarizer error is the tool's failure. |
| Compaction snapshot | **active** | The older history and the compaction prompt asking for a `<state_snapshot>`. | Still over the window afterwards ⇒ the turn fails visibly. |

## 6. Boundaries and guarantees

- A declared model whose protocol no registered provider speaks fails the build with a message naming both.
- An API key never sits in a settings file; only the environment variable's name does, and a missing variable fails the build.
- The idle guard lives inside the OpenAI provider today; another provider brings its own until it moves into the core.
- Only native tool calls are decoded. Calls a model writes as text (`<tool_call>` tags, JSON in prose) are not parsed; the design for that fallback is recorded but not planned ([OPEN-QUESTIONS.md](OPEN-QUESTIONS.md)).
- Thinking is normalized only from a native reasoning field; think-tags inline in answer text are not extracted (planned, [tasks/T10](tasks/T10-think-tag-extraction.md)).
- `promptStyle` is accepted and stored, and today selects the general examples for every value; format-specific examples await the text-embedded tool-call parser they would teach.
