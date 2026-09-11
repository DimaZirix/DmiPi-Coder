# Sandbox — Technical Companion

*Technical side of [FUNCTIONAL-OVERVIEW.md §4](FUNCTIONAL-OVERVIEW.md#4-running-commands). Same plain style, but here we explain the implementation: the containment contract, the three providers, the network contract and the egress control point.*

## 1. The idea in one paragraph

Two independent fences stand between the model and your machine. The permission gate judges a command *before* it runs ([PERMISSIONS-TECHNICAL.md](PERMISSIONS-TECHNICAL.md)); the sandbox bounds what it can reach *while* it runs. The core owns everything that makes a promise: the contract (writable paths, timeouts, the resolved network), the decision that every command goes through the sandbox, the egress policy and its session memory, and the lifecycle. A **sandbox provider** supplies only the mechanism: "given this contract, launch this command contained." Providers are plugins of a special kind, part of the trusted computing base, chosen explicitly by name. Three ship: `direct` (no confinement, honestly labelled), `bubblewrap` (namespaces) and `podman` (containers).

## 2. Data model

| Type | Fields | Set by | Purpose |
|---|---|---|---|
| `SandboxSpec` | `projectDirectory`, `additionalWritableDirectories`, `defaultTimeout`, `maxTimeout`, `network` | the builder from settings | The contract handed to a provider. |
| `SandboxNetwork` | `Open()`, `Isolated()`, `Proxied(port, token)` | the core | The resolved network side of the contract. `Proxied` exists only once the egress proxy is running. |
| `ResourceLimits` | `memoryMax` ("2G", blank = off), `tasksMax` (0 = off) | the provider plugin's constructor | Optional bounds a confining provider enforces. |
| `ShellResult` | `exitCode`, `stdout`, `stderr`, `timedOut`, `cancelled` | the sandbox | A killed run reports exit code -1 and says why. |
| `Shell` (capability) | `run(command, cancel)`, `run(command, timeout, cancel)`, `runInBackground(command)` | the core, as `SessionShell` | What a plugin holding the shell capability can do. |
| `SandboxProvider` | `technology()`, `available()`, `confines()`, `create(spec)` | a provider plugin | The mechanism, selected by name. |
| `Sandbox` | `run(command, timeout, cancel)`, `startBackground(command)`, `technology()`, `confines()`, `close()` | the provider | One live containment per session. |

## 3. Components

```
 model ──▶ run_shell_command (ShellTool) ──▶ gate ──▶ Shell = SessionShell
                                                        │  clamps the timeout to maxTimeout
                                                        │  builds the sandbox lazily on first use
                                                        │  starts the egress proxy alongside it (controlled network)
                                                        ▼
                                             SandboxProvider.create(spec) ──▶ Sandbox
                                             direct | bubblewrap | podman         │ builds an argv
                                                                                  ▼
                                                                   ProcessRunner: enforces the timeout,
                                                                   kills the whole process tree, caps each
                                                                   stream at 1 MB, polls the cancel token
```

`ShellTool` labels the result for the model (`Command`, `Directory`, `Stdout`, `Stderr`, `Exit Code`, with `(empty)` placeholders), caps it at 30 000 characters, and keeps partial output on a timeout. With background commands enabled the tool exposes `is_background`; the parameter is absent from the schema otherwise, so a foreground-only agent never sees it.

## 4. The three providers

| | `direct` | `bubblewrap` | `podman` |
|---|---|---|---|
| Confines | no (`confines() == false`) | yes | yes |
| Requires | nothing | `bwrap`; `systemd-run` for limits | `podman`; `pasta` or `slirp4netns` for a proxied network |
| Filesystem | as the user | `--ro-bind / /`, `--dev /dev`, `--tmpfs /tmp`, `--bind` for the project and each additional directory | image filesystem; `--mount type=bind` for the project and each additional directory; `--workdir` = the project |
| Identity, privileges | as the user | `--unshare-ipc --unshare-uts`, `--die-with-parent` | `--userns=keep-id`, `--security-opt=no-new-privileges`, `--rm` per command |
| Isolated network | refused | `--unshare-net` | `--network=none` |
| Proxied network | refused | shared netns, `/etc/resolv.conf` bound to `/dev/null`, `HTTP_PROXY`/`HTTPS_PROXY` set to the proxy | a loopback-exposing netmode (below), `--dns 127.0.0.1`, proxy env pointing at the host-loopback address |
| Timeout | host-side kill | host-side kill | `--timeout` inside the container as well, because killing the host-side client does not reach conmon-supervised processes |
| Resource limits | none | `systemd-run --user --scope` with `MemoryMax=` / `TasksMax=` | `--memory` / `--pids-limit` |
| Probe at create | none | a trivial command must run; a write outside the allowed paths (the user's home, or `/usr` when home is allowed) must fail | a trivial command must run under the resolved netmode |

**Podman's host-loopback address.** The proxy listens on the host's loopback, which a container cannot see by default. `ProxyNetwork` picks pasta (`--map-host-loopback=<addr>`) when installed, else slirp4netns (`allow_host_loopback=true,cidr=<subnet>`), and refuses loudly when neither is present. The address is the `.2` of a random RFC 1918 /24 that the host does not route (checked against `/proc/net/route`; `10/8` preferred, then `172.16/12` and `192.168/16`), so no reachable subnet is shadowed. This is collision avoidance, not a secret: the per-session proxy token is the guard.

## 5. The network contract

```
 Builder                       SessionShell (first command)                      provider
 ────────                      ────────────────────────────                      ────────
 open (default) ─────────────▶ spec.network = Open ──────────────────────────▶ no flags
 isolateNetwork() ───────────▶ spec.network = Isolated ─────────────────────▶ --unshare-net / --network=none
 egressControl(hosts) ───────▶ start EgressProxy on 127.0.0.1:<port>, token
                               spec.withNetwork(Proxied(port, token)) ───────▶ DNS blackholed + proxy env
 any non-open + direct ──────▶ refused at build(): "does not confine, so it cannot control the network"
```

**The control point.** `EgressProxy` is a loopback HTTP proxy: plain requests are forwarded, `CONNECT` tunnels are judged by hostname and passed through without TLS interception. Every connection must present the session token as proxy credentials, so another process on the machine cannot borrow the policy. `EgressPolicy` decides per hostname:

```
 CONNECT registry.npmjs.org:443
   │
   ├─ configured allowlist (exact, or *.example.com for subdomains) ──▶ pass
   ├─ remembered this session: allowed ──▶ pass; denied ──▶ refuse
   └─ unknown ──▶ the live mode's ask outcome
                    RUN (allow all) ──▶ pass
                    BLOCK (don't ask) ──▶ refuse
                    PROMPT ──▶ hil.ask("The command wants to reach registry.npmjs.org — allow?",
                                       Allow once · Always this session · Deny)   ← the TCP connect waits
```

Questions come from proxy connection threads; parallel connections to one host coalesce into one question, and the question shares the one-at-a-time lock with the gate. The mode is read per decision, so `/plan` or a mode switch applies at once.

## 6. Lifecycle

- **Lazy.** Nothing is created until the first command; a session that never runs one allocates nothing.
- **Create.** The provider builds the sandbox and runs its probe; a sandbox that fails its probe is refused before any real command.
- **Background processes.** `runInBackground` returns a handle (`bg-1`, `bg-2`, …) and registers the process; the session kills every registered process at close. Registration and close are serialized so a process is either registered or refused.
- **Close.** Kills background processes, closes the sandbox, stops the proxy. After close, further commands are refused rather than resurrecting an untracked sandbox.

## 7. Honest limits

- **Egress enforcement is cooperative.** It works through the proxy environment and name resolution. Well-behaved tools comply; a hostile binary dialling raw IP addresses slips past. The v1 threat model is accidents and sloppy scripts, not adversaries. Hard packet-level isolation under a *controlled* network is a possible tightening, not a promise; a fully *isolated* network is already hard.
- **Podman background containers outlive the session.** The host-side kill never reaches conmon-supervised processes and background runs carry no `--timeout`. Foreground commands are bounded inside the container. The fix is a registry of container ids stopped at close ([tasks/T06](tasks/T06-podman-background-teardown.md)).
- **Bubblewrap keeps the PID namespace shared**, so a sandboxed command can list host processes. The trade: the session can tear the whole process tree down without a nested init.
- **Bubblewrap's `/tmp` is private per command**: nothing written there survives to the next command or reaches the host.
- **Podman's open network is podman's default NAT**, not the host network, so services on the host's loopback are not reachable from an open-network container.
- **The probe checks the filesystem half only.** Verifying at create that a disallowed host is unreachable is planned ([tasks/T05](tasks/T05-network-conformance-probe.md)).

## 8. Boundaries and guarantees

- Every shell command, from the model, a subagent or a plugin's own code, runs through the configured sandbox. No provider and no plugin can opt out.
- The requested timeout is always clamped to the configured maximum.
- The resolved network contract is the core's; a provider only translates it, and one that cannot honour it refuses at create (or the builder refuses at build for `direct`).
- The model is told the truth about confinement: the inside-sandbox section of the prompt appears only when the provider confines.
- Sandbox providers are the only plugins allowed to import the core's infrastructure (they are the confinement); every other plugin is confined by it. Register only providers you have reviewed.
