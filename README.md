# dmipi-coder

A coding agent that runs against local LLMs. Written in Java, no framework.

Point it at an OpenAI-compatible endpoint (llama.cpp, LM Studio, vLLM, whatever)
and it can read and edit files, run shell commands, search the web, keep notes,
plan, spawn subagents and load skills and MCP servers, all behind a permission
gate that asks before doing anything risky.

## Layout

- `agent-core` is the engine plus the built-in plugins: files, shell, web, memory,
  planning, skills, MCP, a Claude-plugin installer, subagents, sandbox providers
  (direct, bubblewrap, podman) and the OpenAI protocol. It's meant to be embeddable;
  a front-end talks to it over three channels: input, output events, and
  human-in-the-loop questions.
- `agent-console` is a terminal front-end. It only renders those channels and adds a
  few slash commands (`/plan`, `/llm`, `/resume`, `/exit`). No agent logic of its own.

## Build and run

Needs Java 25 and Maven.
