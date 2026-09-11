# T04 — User-triggered compaction

**Area:** Conversation · **Status:** planned · **Depends on:** — · **Implements:** [FUNCTIONAL-OVERVIEW.md §14](../FUNCTIONAL-OVERVIEW.md#14-long-conversations) · [CONVERSATION-TECHNICAL.md §5](../CONVERSATION-TECHNICAL.md#5-context-budget-and-compaction)

## In short
A facade function and a `/compact` console command that run the same compaction the threshold triggers.

## Why it's needed
A user who is about to switch to a smaller-window model, or who knows the early history is noise, wants to compact now rather than wait for the threshold.

## What to build
- `Coder.compactNow()` calling the context manager's compaction unconditionally, between turns only.
- `/compact` mapped onto it; the `ContextCompacted` event reports the sizes as today.

## Out of scope
Selective compaction of a range.

## Acceptance criteria
- `/compact` between turns replaces the older history with a snapshot and prints the note; during a turn it is refused.
- Build and tests pass.
