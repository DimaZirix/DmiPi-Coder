# T03 — Web front-end

**Area:** Front-ends · **Status:** planned · **Depends on:** — · **Implements:** [INTERFACE-TECHNICAL.md §5](../INTERFACE-TECHNICAL.md#5-building-another-front-end)

## In short
A browser front-end implementing the same three channels over a connection, with nothing web-specific entering the core.

## Why it's needed
The console is the reference, but a browser can show thinking as a collapsible block, render diffs richly, and serve several workspaces.

## What to build
- A server module that wires a `Coder` per workspace, streams `OutEvent`s over a connection, and relays `Question`s with their stable option ids.
- Sessions, users and authentication as the web app's own concern.
- The constraint that binds now: the core stays as it is.

## Out of scope
Multi-user collaboration on one conversation.

## Acceptance criteria
- The same conversation behaves identically in the console and the web front-end.
- No change to `agent-core` beyond what the headless test front-end already needs.
- Build and tests pass.
