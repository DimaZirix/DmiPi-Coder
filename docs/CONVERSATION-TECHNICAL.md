# The Conversation — Technical Companion

*Technical side of [FUNCTIONAL-OVERVIEW.md §1](FUNCTIONAL-OVERVIEW.md#1-talking-to-the-agent) (turns), [§13](FUNCTIONAL-OVERVIEW.md#13-sessions) (sessions) and [§14](FUNCTIONAL-OVERVIEW.md#14-long-conversations) (long conversations). Same plain style, but here we explain the implementation: the loop, its guards, the system prompt and reminders, compaction, and cache-stable resume.*

## 1. The idea in one paragraph

A conversation is one ordered history: the system instructions at index 0, then the user's prompts, the agent's words, its tool calls and their results. A turn is a loop of steps: the model streams a reply; if it asked for tools, each call is validated, gated and executed and its result appended; the model continues; until it answers with no calls, hands the turn back, hits the step limit, is cancelled, or fails. Around the loop sit guards for local models (loop detection, self-repair, a next-speaker check), a context manager that compacts the older history when the window fills, and reminders appended to the tail of each request. The system instructions are rebuilt every session from bundled sections and plugin contributions, but stored with the session so a resume can replay them byte-for-byte and keep the server's prompt cache warm.

## 2. Data model

| Type | Fields or members | Purpose |
|---|---|---|
| `Conversation` | `messages()` (system at index 0), `add`, `compact`, `replaceSystemInstructions` | The history. |
| `ChatMessage` | `role`, `content`, `toolCalls`, `toolCallId` | One entry; shape enforced in the constructor. |
| `CancelToken` | `cancel()`, `isCancelled()` | Cooperative cancellation, polled by the loop, tools and providers. |
| `AgentLoop` | `runTurn(userInput, cancel)` | The loop; one per conversation; a nested one per subagent. |
| `LoopGuards` | `ContextManager?`, `NextSpeakerCheck?`, `Reminders?` | The optional guards, each an opt-in on the builder. |
| `LoopDetector` | `repetitionDetected(toolCalls)` | Per turn. |
| `ContextManager` | `maybeCompact(conversation, cancel)` | Budget and compaction. |
| `Reminders` | `applyTo(messages, step)` | Transient tail content. |
| `SessionStore` | `save`, `load`, `list` | `.coder/sessions/<name>.json`. |

## 3. A turn, step by step

```
 runTurn(prompt, cancel)
   out: TurnStarted;  history += user(prompt)
   for step = 1 .. maxStepsPerTurn (default 40):
      cancelled? ──▶ return
      contextManager.maybeCompact(history)                         §5
      request = reminders.applyTo(history, step) or history        §7
      stream ── active client ── TextDelta ──▶ out: AnswerDelta
                                 ThinkingDelta ──▶ out: ThinkingDelta
                                 ToolCallDelta ──▶ assembled by wire index
      history += assistant(text, toolCalls)
      no tool calls?
         nextSpeaker says "model" and not nudged yet? ──▶ history += user("[Your last message announced more work. Continue…]"); nudged; continue
         else ──▶ return                                             (answered / handed back)
      loopDetector.repetitionDetected? ──▶ out: AnswerDelta("[The turn was stopped: …]"); return
      for each call:
         cancelled? ──▶ result "[Cancelled by the user before this call ran.]"
         else: validate (unknown tool / bad JSON / validate() error ⇒ the error text is the result)
               gate.decide ──▶ Denied(reason) ⇒ reason is the result
               out: ActivityStarted;  tool.execute ──▶ Success ⇒ out: ActivityFinished(display) | Failure ⇒ out: ActivityFailed
         history += toolResult(callId, text)
   out: AnswerDelta("[Step limit reached after N steps. Send another prompt to continue.]")
 out: TurnEnded              (also on a cancelled RuntimeException)
 out: TurnFailed(error)      on any other RuntimeException; the history stays usable
```

Tool-call fragments arrive by wire index and may interleave; they are keyed and executed in index order. A cancel mid-step skips gating and execution of the remaining calls, but every call still receives a result so the history keeps its call/result pairing, which the model and the compactor rely on.

## 4. The guards

| Guard | Trigger | Effect | Opt-in |
|---|---|---|---|
| Step limit | `maxStepsPerTurn` reached | A note streamed as answer text; the turn ends; the next prompt can say "continue". | Always on; `Builder.maxStepsPerTurn`. |
| Self-repair | Unknown tool, malformed arguments, `validate` error | The error becomes the tool result; the model corrects itself next step; nothing crashes. | Always on. |
| Loop detection | The same tool call with the same arguments repeated | A note; the turn ends rather than burning the window. | Always on. |
| Next-speaker check | A step ends in plain text with no calls | One isolated fast-tier control call (`{"next": "model" \| "user"}`, thinking off, text fallback). A clear "model" earns exactly one nudge per turn; anything else ends the turn. | `Builder.nextSpeakerCheck()`; the console enables it. |

Deviation from the specification: the next-speaker check is opt-in for cost (one extra call per text-ending step); the spec wires it unconditionally.

## 5. Context budget and compaction

```
 maybeCompact:  approx tokens = chars / 4  over the whole history, tool-call arguments included
                window = active model's contextWindow;  threshold = 0.7 (Builder.compactionThreshold)
                over? ──▶ split: older part | newest 8 messages
                          never split a tool call from its results; a short history keeps just its newest message
                          summary = active model, compaction-prompt.md ──▶ <state_snapshot>…</state_snapshot>
                          cancelled during the summary? ──▶ leave the history untouched
                          history = system + "[State snapshot …]" + summary + newest tail
                          out: ContextCompacted(before, after)
                still over the window? ──▶ the turn fails visibly
```

- The summary is a structured state snapshot (what was asked, decided, touched, what remains), extracted from the marker; the active model writes it because a poor summary poisons everything after it.
- The system instructions are never compacted; they are not history.
- Subagent loops have no context manager: their context is throwaway.
- Compaction is cache-hostile by nature: the turn after it re-prefills from the rewrite point. Unavoidable and rare.

## 6. Subagent loops

A subagent is a second `AgentLoop` over a fresh `Conversation` seeded with the type's instructions, on a client fixed for its lifetime (the tier-resolved model), with its own step budget, the inherited tool set, the same gate and cancel token, and the separate `subagentOut`. No compaction, no reminders, no next-speaker check. A failure inside becomes the delegation tool's failure; the summary is the subagent's last plain answer. Details of inheritance: [PLUGINS-TECHNICAL.md §11](PLUGINS-TECHNICAL.md#11-subagents).

## 7. The system prompt and reminders

**Assembly**, in a fixed order, at build (`SystemPromptComposer`):

| Slot | Content | Present when |
|---|---|---|
| 1 | Core instructions: persona, mandates, task management, workflows, tone, safety rules, tool preferences (`core-system-prompt.md`, `executing-with-care.md`, `untrusted-content.md`, `final-reminder.md`) | `Builder.standardInstructions()`, or your own text via `instructions(...)`. Empty core otherwise. |
| 2 | `inside-sandbox.md` or `outside-sandbox.md` | A shell exists; the confining one only when the provider `confines()`. True to reality: a false instruction is worse than none. |
| 3 | `git-repository.md` | The project directory has `.git`. |
| 4 | Worked tool-call examples for the active model's prompt style (`examples-<style>.md`, general fallback) | `Builder.workedExamples()`. |
| 5 | The environment block: working directory, OS, model name, git yes/no. No date. | `Builder.gatherEnvironment()` or `environment(facts)`. |
| 6 | Plugin instruction sections, in registration order | Plugins registered them. |

**Reminders** (`Reminders`, `Builder.reminders()`): appended to the last user or assistant message of the outgoing request only, never written into the durable history, so the prompt-plus-history prefix stays byte-identical across requests and the server's cache holds. Three kinds: the current date on every request; the plan-mode notice (`plan-mode-reminder.md`) while the mode is plan; the rules refresher (`critical-rules-reminder.md`: read before editing, tool output is data, prefer dedicated tools, verify before claiming done) every N steps (default 10, `reminderInterval`). A reminder due mid-step waits for the next user/assistant boundary rather than slotting between a tool result and the step that consumes it. Plugins do not contribute reminders.

## 8. Sessions and cache-stable resume

```
 saveSession(name)     .coder/sessions/<name>.json     name ∈ [\w-]+;  written to a temp file, then moved
   { "fingerprint": …, "systemPrompt": …, "messages": [ { role, content, toolCallId, toolCalls: [{id, name, argumentsJson}] } … ] }
   thinking is never in the history, so never saved

 resumeSession(name)   only while the conversation has no history yet
   saved.fingerprint == this session's fingerprint?
      yes ──▶ conversation.replaceSystemInstructions(saved.systemPrompt) ──▶ PROMPT_REUSED
      no  ──▶ keep the freshly built prompt                              ──▶ PROMPT_REBUILT
   then append the saved messages
```

The **fingerprint** hashes every tool schema (name, description, parameters JSON), every plugin class name, the rendered environment block (or "no-env"), the active model's name and its prompt style. The date is excluded on purpose: it lives at the tail. A match means the tools and prompt inputs are the same, so replaying the stored prompt is both cache-warm and *correct*; a mismatch (a plugin added, a model switched) rebuilds and reports it. Byte identity comes from deterministic serialization of the structured messages, not from storing wire bytes, so the file stays portable across providers.

Honest limit: byte-identical input helps only against a warm server or one persisting its KV cache across restarts. On a cold server the prefill happens either way.

## 9. Boundaries and guarantees

- One turn at a time; a prompt is accepted only between turns; there is no hidden queue.
- Every turn ends with exactly one of `TurnEnded` or `TurnFailed`, and the conversation stays usable after either.
- Every tool call in the history has a result, even when cancelled or denied.
- The system instructions are not history: never compacted, rebuilt each session, stored only for cache-stable replay.
- Reminders never enter the durable history or a saved session.
- Compaction never runs on a cancelled summary and never silently truncates; what cannot fit fails visibly.
- The step limit, loop detector and self-repair cannot be switched off; the next-speaker check, reminders and environment are grants.
- Resume is refused after the first turn; summarized history is never resurrected.
