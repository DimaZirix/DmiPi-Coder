package com.dmipi.coder.core.domain.hil;

/**
 * The HIL channel: the core asks the user a question and blocks until the answer. The core
 * serializes questions — a front-end is never shown two at once — and nothing answers on the
 * user's behalf: there is no timeout. What an answer means belongs to whoever asked; the
 * front-end only renders and returns the selection.
 */
public interface Hil {

    /**
     * Presents the question and returns the user's selection. The implementation must enforce
     * the shape rules before returning: exactly one selected id for an option list, at least
     * one for a checkbox list, ids only from the offered options — {@link Question#rejection}
     * is the check to satisfy.
     */
    Answer ask(Question question);
}
