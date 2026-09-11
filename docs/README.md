# dmipi-coder — Documentation

*The doc-set index: a short recap of how the agent works, then which document to read when. Start with the Functional Overview if you want to know what it does; the User Manual if you want to run it; the Plugin Development Guide if you want to extend it.*

dmipi-coder is a coding agent for local language models. You point it at an OpenAI-compatible server and a project, describe a task, and it reads the project, edits files and runs commands, asking you before anything sensitive. It is written in plain Java with two runtime libraries, and it is made of plugins you can leave out: what it can do is exactly the set you switch on.

## How it works, in short

1. You talk through a **front-end**. The console is the built one; a program can embed the core directly. Every front-end implements the same three channels: prompts in, one ordered stream of typed events out (answer text, activity lines, thinking), and questions to you when a decision is yours.
2. A prompt starts a **turn**. The model works in steps, reading, editing and running through tools, until it answers, hands the turn back, hits the step limit, is cancelled, or fails. Every ending is explicit.
3. Everything the agent can do comes from **plugins**. Each declares the capabilities it needs (files, shell, web, a model, a question to you) and gets only those. No plugin, no ability; no file plugins, no file access.
4. Every sensitive action passes the **permission gate** under the active **approval mode**: ask, run, or block. Your rules and the hard limits hold in every mode. Shell commands also run inside a **sandbox**.
5. **Models** are declared in a settings file with a protocol and a tier. The conversation uses the active one; cheap checks use the fastest; you can switch mid-conversation.
6. Long work stays inside the model's window through capped results, delegation to **subagents**, and **compaction**. Standing knowledge lives in **memory** files you own; skills and MCP servers add content and tools; sessions are autosaved and can be resumed.

## The documents

| Document | What it is | Read it when |
|---|---|---|
| [FUNCTIONAL-OVERVIEW.md](FUNCTIONAL-OVERVIEW.md) | Start here. Plain-language description of every feature as you see it; no implementation. | You want to know what the agent does, or you are checking a behaviour. |
| [USER-MANUAL.md](USER-MANUAL.md) | Build, run, configure: models, modes, rules, sandbox, memory, skills, MCP, Claude plugins, sessions; embedding the core; troubleshooting. | You are setting the agent up, or embedding it. |
| [PLUGIN-CATALOG.md](PLUGIN-CATALOG.md) | Every built-in plugin: tools, capabilities, options, and what you lose without it. | You are choosing what the agent may do. |
| [PLUGIN-DEVELOPMENT.md](PLUGIN-DEVELOPMENT.md) | The plugin contract: interface, capabilities, tools, questions, policies, providers, a worked example, testing. | You are writing a plugin, a protocol provider or a sandbox provider. |
| [INTERFACE-TECHNICAL.md](INTERFACE-TECHNICAL.md) | The three channels, the event and question types, how the console renders them, how to build another front-end. | You touch a front-end or the facade. |
| [CONVERSATION-TECHNICAL.md](CONVERSATION-TECHNICAL.md) | The turn loop, its guards, the system prompt and reminders, compaction, sessions and cache-stable resume. | You touch the loop, the prompt or history size. |
| [PERMISSIONS-TECHNICAL.md](PERMISSIONS-TECHNICAL.md) | The gate's decision order, modes, rules, hard limits, the "always" memory, plan mode. | Anything security-relevant. |
| [SANDBOX-TECHNICAL.md](SANDBOX-TECHNICAL.md) | The containment contract, the three providers, the network contract and egress control, honest limits. | You touch shell execution or confinement. |
| [PLUGINS-TECHNICAL.md](PLUGINS-TECHNICAL.md) | Install-time wiring, least privilege, the built-ins' internals (files, web, memory, skills, MCP, installer, subagents, planning). | You touch the plugin layer or a built-in plugin. |
| [LLM-TECHNICAL.md](LLM-TECHNICAL.md) | The model registry and tiers, the LLM contract, the OpenAI provider on the wire, control calls. | You add a model, a protocol, or change what is sent. |
| [OPEN-QUESTIONS.md](OPEN-QUESTIONS.md) | Cross-cutting decisions still open, each with a proposed default. | Before deciding a design point. |
| [tasks/TASKS.md](tasks/TASKS.md) | The planned work, one file per task. | You are picking up the next piece. |
| [wireframes/README.md](wireframes/README.md) | Style-independent schematics of the console screens. | You want to see what is on a screen at a glance. |

## Conventions of this set

- Every page opens in plain words before any table or schema. The overview is black-box; the technical companions explain the how with block schemas and link back up.
- What is built and what is planned are marked apart: *(planned)* in the overview, a task file for each planned item, and open questions where a decision is still missing. Nothing here describes a feature as existing that does not.
- The project's own words are used throughout: turn, step, tool, plugin, capability, question, mode, gate, sandbox, tier, session, memory, skill, subagent, compaction.
- Where the code deviates from the original specification it was built from, the companion says so (the next-speaker check is opt-in; the MCP plugin needs the file system; the console exposes no network settings).
