# T07 — Project memory walked up to the repository root

**Area:** Memory · **Status:** planned · **Depends on:** open question 6 · **Implements:** [FUNCTIONAL-OVERVIEW.md §7](../FUNCTIONAL-OVERVIEW.md#7-memory) · [PLUGINS-TECHNICAL.md §7](../PLUGINS-TECHNICAL.md#7-memory)

## In short
Load memory files from every directory between the repository root and the project directory, outer first, so a monorepo subproject inherits repository-wide memory.

## Why it's needed
Teams keep one `AGENTS.md` at the repository root; a subproject opened as the project directory does not see it today.

## What to build
- Repository-root detection as an environment fact under its own grant.
- A read-only file system anchored at the root, granted to the memory plugin under a builder opt-in.
- Load order: user, root, intermediate directories, project; most specific last.

## Out of scope
Saving to any file outside the project directory.

## Acceptance criteria
- With the grant, a root `AGENTS.md` appears in the instructions before the project's own; without it, nothing outside the project is read.
- Build and tests pass.
