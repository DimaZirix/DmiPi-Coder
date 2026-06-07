# dmipi-coder

A coding agent that runs against local LLMs. Written in Java.

Right now this is just the core: the agent loop, conversation state, permission gate
and the plugin/capability model. Front-ends talk to it over three channels: input,
output events, and human-in-the-loop questions.

There's no front-end yet, that's next, followed by the built-in plugins (files, shell, and so on).

## Build

Needs Java 25 and Maven.

```bash
mvn test
```

## Dependencies

I'm keeping `agent-core` on well-known libraries only, and only ones I've explicitly
decided to take on. So far that's Jackson.
