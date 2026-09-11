# T02 — LLM advisors for the permission gate

**Area:** Permissions · **Status:** planned · **Depends on:** — · **Implements:** [FUNCTIONAL-OVERVIEW.md §2](../FUNCTIONAL-OVERVIEW.md#2-approving-what-the-agent-does) · [PERMISSIONS-TECHNICAL.md §8](../PERMISSIONS-TECHNICAL.md#8-boundaries-and-guarantees)

## In short
Let a fast-tier model classify a shell command as read-only so that, where the operator delegated it, the gate runs it without asking.

## Why it's needed
In default mode every `git status`, `ls` and `mvn -q test` asks. Rules cover the common ones, but a classifier catches the long tail without loosening anything the operator did not delegate.

## What to build
- An advisor seam on the gate: consulted only when the composed decision is *ask* and configuration delegates that class of call.
- An isolated control call to the fastest model (thinking off, schema `{"verdict": "read_only" | "mutating" | "unsure"}`) that sees the command as data.
- A verdict converts *ask* to *run* only for `read_only`; `unsure` and `mutating` fall back to the human. Never past a deny rule, a policy or a hard limit.
- A settings key to delegate (`advisors.autoAllowReadOnlyShell: true`), off by default.

## Out of scope
Advisors for edits or network calls; advisors that can deny.

## Acceptance criteria
- With delegation on, a command the advisor calls read-only runs without a question; the same command with delegation off asks.
- A deny rule still blocks a command the advisor calls read-only.
- An `unsure` verdict asks the human.
- Build and tests pass.
