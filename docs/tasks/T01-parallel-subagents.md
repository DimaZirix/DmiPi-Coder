# T01 — Parallel subagents

**Area:** Subagents · **Status:** planned · **Depends on:** open question 10 (asker naming) · **Implements:** [FUNCTIONAL-OVERVIEW.md §11](../FUNCTIONAL-OVERVIEW.md#11-delegating-to-subagents) · [PLUGINS-TECHNICAL.md §11](../PLUGINS-TECHNICAL.md#11-subagents)

## In short
Let several subagents run at the same time when the model requests several `task` calls in one step, instead of one after another.

## Why it's needed
Exploration tasks are independent and slow on local models; running three `explore` subagents sequentially triples the wait. The interface already anticipates it: questions are serialized on one lock and subagent output has its own channel.

## What to build
- Execute the `task` calls of one step concurrently, each on its own thread with the shared cancel token; collect results in call order.
- Add an `asker` to questions raised from subagent work and render it in the console.
- Bound concurrency (a builder setting, default 3).
- Cancellation cascades to every running subagent.

## Out of scope
Parallel execution of non-subagent tools; parallelism inside a subagent.

## Acceptance criteria
- Three `task` calls in one step run concurrently and their results land in call order.
- A question from one subagent blocks only that subagent; the others continue until they need the lock.
- Cancelling the turn stops every subagent.
- Build and tests pass.
