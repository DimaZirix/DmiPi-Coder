# T09 — A launcher script or runnable jar for the console

**Area:** Console · **Status:** planned · **Depends on:** — · **Implements:** [USER-MANUAL.md §2](../USER-MANUAL.md#2-running-the-console)

## In short
One command to start the console in any project directory, instead of assembling a classpath by hand.

## Why it's needed
The manual's command line needs Maven to print a classpath and a `cd` into the target project; a `coder` script on the path, or `java -jar`, is what users expect.

## What to build
- Either a shaded/executable jar of `agent-console` (adds a build plugin, no runtime dependency), or a `bin/coder` script that resolves the installed jars from the local Maven repository.
- The project directory stays the current working directory.

## Out of scope
Packaging for operating-system package managers.

## Acceptance criteria
- `coder` (or `java -jar agent-console.jar`) started in a project directory runs the console on that project.
- No new runtime dependency.
- Build and tests pass.
