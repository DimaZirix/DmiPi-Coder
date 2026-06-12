package com.dmipi.coder.console;

import com.dmipi.coder.core.api.Coder;
import com.dmipi.coder.core.domain.permissions.Mode;
import java.io.PrintWriter;
import java.util.List;

/**
 * Maps a console command onto an interface function — the core never sees the command itself.
 * Each command is pure sugar over {@link Coder}: switch mode, resume, list/select models, quit.
 */
enum SlashCommand {

    PLAN("/plan") {
        @Override
        void run(final String args, final Coder coder, final PrintWriter out) {
            final boolean on = !args.equalsIgnoreCase("off");
            coder.switchMode(on ? Mode.PLAN : Mode.DEFAULT);
            out.println(on ? "(plan mode on — mutations are blocked until you approve a plan)" : "(plan mode off)");
        }
    },
    RESUME("/resume") {
        @Override
        void run(final String args, final Coder coder, final PrintWriter out) {
            if (args.isBlank()) {
                out.println("Saved sessions: " + String.join(", ", coder.sessions()));
                out.println("Usage: /resume <name>");
                return;
            }
            coder.resumeSession(args.trim());
            out.println("(resumed session '" + args.trim() + "')");
        }
    },
    LLM("/llm") {
        @Override
        void run(final String args, final Coder coder, final PrintWriter out) {
            if (args.isBlank()) {
                coder.models().forEach(model -> out.println(
                        (model.name().equals(coder.activeModel().name()) ? "* " : "  ") + model.name() + " (" + model.tier() + ")"));
                return;
            }
            coder.activateModel(args.trim());
            out.println("(active model: " + coder.activeModel().name() + ")");
        }
    },
    EXIT("/exit") {
        @Override
        void run(final String args, final Coder coder, final PrintWriter out) {
            // Handled by the driver, which stops the loop; nothing to do here.
        }
    };

    private final String keyword;

    SlashCommand(final String keyword) {
        this.keyword = keyword;
    }

    abstract void run(String args, Coder coder, PrintWriter out);

    String keyword() {
        return keyword;
    }

    /** Runs the command in the line and returns true when it was one; a non-command line returns false. */
    static boolean dispatch(final String line, final Coder coder, final PrintWriter out) {
        final String trimmed = line.strip();
        for (final SlashCommand command : values()) {
            if (trimmed.equals(command.keyword) || trimmed.startsWith(command.keyword + " ")) {
                try {
                    command.run(trimmed.substring(command.keyword.length()).strip(), coder, out);
                } catch (final RuntimeException failure) {
                    out.println("(command failed: " + failure.getMessage() + ")");
                }
                out.flush();
                return true;
            }
        }
        return false;
    }

    static boolean isExit(final String line) {
        return line.strip().equals(EXIT.keyword);
    }

    static List<String> keywords() {
        return java.util.Arrays.stream(values()).map(SlashCommand::keyword).toList();
    }
}
