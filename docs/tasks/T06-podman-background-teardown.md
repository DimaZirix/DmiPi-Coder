# T06 — Stop background podman containers at session close

**Area:** Sandbox · **Status:** planned · **Depends on:** — · **Implements:** [SANDBOX-TECHNICAL.md §7](../SANDBOX-TECHNICAL.md#7-honest-limits)

## In short
Track the container id of every background podman command and `podman stop` them when the session closes.

## Why it's needed
Killing the host-side podman client never reaches the conmon-supervised process, so a background dev server outlives the session today.

## What to build
- Start background containers with `--cidfile` or `--name` and record the id in the podman sandbox.
- On `close`, `podman stop` (then `rm`) each recorded container, best effort, before the existing teardown.

## Out of scope
Timeouts for background commands.

## Acceptance criteria
- A background command's container is gone after `close`.
- Build and tests pass; the runtime test runs on a host with podman.
