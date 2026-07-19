package com.dmipi.coder.core.plugins.claudeplugins;

import com.dmipi.coder.core.domain.agent.CancelToken;
import com.dmipi.coder.core.domain.shell.ShellResult;
import com.dmipi.coder.core.plugin.Shell;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The materialized source of a Claude-format plugin, read through the shell capability. A git
 * source ({@code scheme://…}, {@code git@…}, or a {@code .git} suffix) is shallow-cloned into a
 * temporary directory removed on {@link #close()}; a local directory is read in place. All reads
 * run inside the session sandbox, so the source is subject to the same confinement as any command.
 */
final class PluginSource implements AutoCloseable {

    private static final Pattern GIT_SOURCE = Pattern.compile("^(https?|ssh|git)://.*|^git@.*|.*\\.git$");

    private final Shell shell;
    private final CancelToken cancel;
    private final String root;
    private final String cloneDirectory; // null for a local directory source — nothing to clean up

    private PluginSource(final Shell shell, final CancelToken cancel, final String root, final String cloneDirectory) {
        this.shell = shell;
        this.cancel = cancel;
        this.root = root;
        this.cloneDirectory = cloneDirectory;
    }

    static PluginSource open(final Shell shell, final String source, final CancelToken cancel) {
        if (GIT_SOURCE.matcher(source).matches()) {
            return cloned(shell, source, cancel);
        }
        final PluginSource local = new PluginSource(shell, cancel, source, null);
        if (!local.directoryExists("")) {
            throw new InstallFailure("Source '" + source + "' is neither a git URL nor an existing directory.");
        }
        return local;
    }

    private static PluginSource cloned(final Shell shell, final String source, final CancelToken cancel) {
        final ShellResult temporary = shell.run("mktemp -d", cancel);
        if (!temporary.succeeded()) {
            throw new InstallFailure("Could not create a temporary directory for cloning '" + source + "': " + temporary.stderr().strip());
        }
        final String directory = temporary.stdout().strip();
        final ShellResult clone = shell.run("git clone --depth 1 --quiet -- " + quote(source) + " " + quote(directory), cancel);
        if (!clone.succeeded()) {
            shell.run("rm -rf -- " + quote(directory), cancel);
            throw new InstallFailure("git clone of '" + source + "' failed: " + clone.stderr().strip());
        }
        return new PluginSource(shell, cancel, directory, directory);
    }

    boolean directoryExists(final String relative) {
        return shell.run("test -d " + quote(path(relative)), cancel).succeeded();
    }

    boolean fileExists(final String relative) {
        return shell.run("test -f " + quote(path(relative)), cancel).succeeded();
    }

    String read(final String relative) {
        final ShellResult result = shell.run("cat -- " + quote(path(relative)), cancel);
        if (!result.succeeded()) {
            throw new InstallFailure("Could not read '" + path(relative) + "' from the source: " + result.stderr().strip());
        }
        return result.stdout();
    }

    /** Every regular file under the given directory, as sorted paths relative to it. */
    List<String> filesUnder(final String relative) {
        final String directory = path(relative);
        final ShellResult result = shell.run("find " + quote(directory) + " -type f", cancel);
        if (!result.succeeded()) {
            throw new InstallFailure("Could not list '" + directory + "' in the source: " + result.stderr().strip());
        }
        return result.stdout()
                .lines()
                .map(String::strip)
                .filter(line -> line.startsWith(directory + "/"))
                .map(line -> line.substring(directory.length() + 1))
                .sorted()
                .toList();
    }

    /** The top-level directories carrying installable content — a skills directory or an .mcp.json. */
    List<String> pluginDirectories() {
        final ShellResult result = shell.run(
                "find " + quote(root) + " -mindepth 2 -maxdepth 2 \\( -name .mcp.json -o -type d -name skills \\)", cancel);
        if (!result.succeeded()) {
            return List.of();
        }
        return result.stdout()
                .lines()
                .map(String::strip)
                .filter(line -> line.startsWith(root + "/"))
                .map(line -> line.substring(root.length() + 1))
                .map(line -> line.substring(0, line.indexOf('/')))
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public void close() {
        if (cloneDirectory != null) {
            // Cleanup is best-effort: a leftover temporary clone is harmless, a failed install is not.
            shell.run("rm -rf -- " + quote(cloneDirectory), cancel);
        }
    }

    private String path(final String relative) {
        return relative.isEmpty() ? root : root + "/" + relative;
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
