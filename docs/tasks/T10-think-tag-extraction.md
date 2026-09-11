# T10 — Extract think-tags written inline in answer text

**Area:** LLM · **Status:** planned · **Depends on:** — · **Implements:** [LLM-TECHNICAL.md §6](../LLM-TECHNICAL.md#6-boundaries-and-guarantees)

## In short
Normalize `<think>…</think>` blocks a model writes into its answer text into thinking events, so front-ends never see or parse the tags.

## Why it's needed
Some local servers deliver reasoning inline instead of in a reasoning field; today it appears in the answer stream as text.

## What to build
- A streaming splitter between the provider's text deltas and the loop that routes tagged spans to `ThinkingDelta`, holding back a partial tag.
- Keep the answer text and the history free of the tags.

## Out of scope
Text-embedded tool calls (open question 4).

## Acceptance criteria
- A streamed `<think>` block reaches the front-end as thinking events, split across deltas or not; the answer text and the saved session contain neither the tags nor their content.
- Build and tests pass.
