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

```bash
mvn test
mvn -q -pl agent-console -am compile exec:java -Dexec.mainClass=com.dmipi.coder.console.ConsoleMain
```

Out of the box it expects a model on `http://localhost:8080/v1`. To change that, or add
more models, drop a `.coder/settings.json` in your project or home directory:

```json
{
  "models": [
    {
      "name": "local",
      "protocol": "openai",
      "endpoint": "http://localhost:1234/v1",
      "tier": "fast",
      "contextWindow": 32000
    }
  ]
}
```

The same file can pick a sandbox (`"sandbox": {"technology": "bubblewrap"}`),
set permission rules, and tune shell timeouts.

## Dependencies

`agent-core` sticks to well-known libraries, and only ones I've explicitly decided to
take on. So far that's Jackson and java-diff-utils.
