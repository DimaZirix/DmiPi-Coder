package com.dmipi.coder.core.testfixtures;

import com.dmipi.coder.core.domain.event.Out;
import com.dmipi.coder.core.domain.event.OutEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Records every out event for assertions. */
public final class RecordingOut implements Out {

    private final List<OutEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void event(final OutEvent event) {
        events.add(event);
    }

    public List<OutEvent> events() {
        return List.copyOf(events);
    }

    public List<Class<?>> kinds() {
        return events().stream()
                .<Class<?>>map(Object::getClass)
                .toList();
    }

    public String answerText() {
        return events().stream()
                .filter(OutEvent.AnswerDelta.class::isInstance)
                .map(event -> ((OutEvent.AnswerDelta) event).text())
                .reduce("", String::concat);
    }
}
