# T05 — Sandbox conformance probe, network half

**Area:** Sandbox · **Status:** planned · **Depends on:** — · **Implements:** [SANDBOX-TECHNICAL.md §6](../SANDBOX-TECHNICAL.md#6-lifecycle)

## In short
At sandbox creation, verify that a disallowed host is unreachable under an isolated or controlled network, so a lying network confinement is caught before any real command.

## Why it's needed
The filesystem half of the probe already refuses a sandbox whose outside write succeeds. The network half is the same honesty for the other fence.

## What to build
- Under `Isolated`: a connection attempt to a well-known address must fail.
- Under `Proxied`: a direct-by-hostname connection must fail (DNS blackholed) while a connection through the proxy to an allowed host succeeds.
- Refuse the sandbox loudly on either failure.

## Out of scope
Proving packet-level isolation under a controlled network (open question 5).

## Acceptance criteria
- Bubblewrap and podman with an isolated network fail the probe when isolation is faked, pass when real.
- Build and tests pass; the runtime tests run on a host with the tools installed.
