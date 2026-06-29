package com.dmipi.coder.core.plugin;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.ShellResult;
import java.time.Duration;
import java.util.Optional;

/**
 * The shell capability: run a command inside the session sandbox. The confinement contract and
 * the timeout bounds are the core's; a plugin holding this capability only asks for a command to
 * be run. The requested timeout is clamped to the configured maximum.
 */
public interface Shell {

    /** Runs the command with the configured default timeout. */
    ShellResult run(String command, CancelToken cancel);

    /** Runs the command with the requested timeout, clamped to the configured maximum. */
    ShellResult run(String command, Optional<Duration> timeout, CancelToken cancel);

    /** Starts the command in the background and returns a handle; the process is killed at session end. */
    String runInBackground(String command);
}
