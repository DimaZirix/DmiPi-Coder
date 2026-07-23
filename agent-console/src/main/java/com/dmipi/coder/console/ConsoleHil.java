package com.dmipi.coder.console;

import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.hil.Hil;
import com.dmipi.coder.core.domain.hil.Option;
import com.dmipi.coder.core.domain.hil.Question;
import com.dmipi.coder.core.domain.hil.QuestionKind;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Renders a HIL question as numbered options and reads the choice, enforcing the shape — exactly
 * one for an option list, at least one for a checkbox list — before returning. It never answers
 * itself and never interprets what an option means; it maps numbers back to the asker's ids.
 */
public final class ConsoleHil implements Hil {

    private final BufferedReader input;
    private final PrintWriter output;

    public ConsoleHil(final BufferedReader input, final PrintWriter output) {
        this.input = input;
        this.output = output;
    }

    @Override
    public Answer ask(final Question question) {
        output.println();
        output.println(question.question());
        if (!question.preview().isBlank()) {
            question.preview().lines().forEach(line -> output.println("    " + line));
        }
        final List<Option> options = question.options();
        for (int i = 0; i < options.size(); i++) {
            final Option option = options.get(i);
            output.println("  " + (i + 1) + ") " + option.label() + (option.detail().isBlank() ? "" : " — " + option.detail()));
        }
        output.print(question.kind() == QuestionKind.CHECKBOX_LIST ? "Select (comma-separated numbers): " : "Select (number): ");
        output.flush();

        while (true) {
            final String line = readLine();
            if (line == null) {
                throw new IllegalStateException("Standard input closed while the question '" + question.question() + "' was open — no answer is possible.");
            }
            final List<String> selected = parse(line, options, question.kind());
            if (selected != null) {
                return new Answer(selected);
            }
            output.print("Please enter " + (question.kind() == QuestionKind.CHECKBOX_LIST ? "one or more valid numbers, comma-separated" : "a single valid number") + ": ");
            output.flush();
        }
    }

    /** The selected option ids, or null when the line does not fit the question's shape. */
    private static List<String> parse(final String line, final List<Option> options, final QuestionKind kind) {
        final List<String> ids = new ArrayList<>();
        for (final String token : Arrays.stream(line.trim().split(",")).map(String::trim).filter(part -> !part.isBlank()).toList()) {
            final int index = parseIndex(token, options.size());
            if (index < 0 || ids.contains(options.get(index).id())) {
                return null;
            }
            ids.add(options.get(index).id());
        }
        if (ids.isEmpty()) {
            return null;
        }
        if (kind == QuestionKind.OPTION_LIST && ids.size() != 1) {
            return null;
        }
        return ids;
    }

    private static int parseIndex(final String token, final int optionCount) {
        try {
            final int number = Integer.parseInt(token);
            return number >= 1 && number <= optionCount ? number - 1 : -1;
        } catch (final NumberFormatException notANumber) {
            return -1;
        }
    }

    /** The next input line, or null when input has ended — a failure counts as ended, but says why. */
    private String readLine() {
        try {
            return input.readLine();
        } catch (final java.io.IOException failure) {
            output.println("(standard input failed: " + failure.getMessage() + ")");
            return null;
        }
    }
}
