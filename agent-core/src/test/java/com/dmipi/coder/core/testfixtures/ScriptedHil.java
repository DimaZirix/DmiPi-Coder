package com.dmipi.coder.core.testfixtures;

import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.hil.Hil;
import com.dmipi.coder.core.domain.hil.Question;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Answers questions from a script and records what was asked. */
public final class ScriptedHil implements Hil {

    private final Deque<Answer> script = new ArrayDeque<>();
    private final List<Question> asked = new ArrayList<>();

    public ScriptedHil(final List<Answer> answers) {
        script.addAll(answers);
    }

    @Override
    public Answer ask(final Question question) {
        asked.add(question);
        if (script.isEmpty()) {
            throw new IllegalStateException("Unexpected HIL question: " + question.question());
        }
        return script.pop();
    }

    public List<Question> asked() {
        return List.copyOf(asked);
    }
}
