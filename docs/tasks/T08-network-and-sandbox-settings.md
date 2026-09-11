# T08 — Network and sandbox knobs in the settings file

**Area:** Configuration · **Status:** planned · **Depends on:** open question 2 · **Implements:** [USER-MANUAL.md §6](../USER-MANUAL.md#6-the-sandbox)

## In short
Expose network mode, allowed hosts, resource limits and the podman image in `.coder/settings.json`, so the console user gets them without Java.

## Why it's needed
Everything exists on the builder and the provider constructors; the console user cannot reach any of it.

## What to build
- Settings keys: `sandbox.network` (`open`, `isolated`, `controlled`), `sandbox.allowedHosts[]`, `sandbox.limits.memoryMax`, `sandbox.limits.tasksMax`, `sandbox.image`.
- The builder applies network settings as it applies the technology; providers registered by the console take limits and image from settings at build.
- Unknown values fail loudly naming the key, as every other setting does.

## Out of scope
A settings-driven plugin list (open question 3).

## Acceptance criteria
- A project settings file with `controlled` and an allowlist produces the same behaviour as `Builder.egressControl`.
- `direct` with a non-open network still fails at build with the existing message.
- Build and tests pass.
