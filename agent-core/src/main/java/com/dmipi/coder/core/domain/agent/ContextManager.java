package com.dmipi.coder.core.domain.agent;

import com.dmipi.coder.core.domain.event.Out;
import com.dmipi.coder.core.domain.event.OutEvent;
import com.dmipi.coder.core.domain.llm.ChatMessage;
import com.dmipi.coder.core.domain.llm.ChatRequest;
import com.dmipi.coder.core.domain.llm.LlmStreamEvent;
import com.dmipi.coder.core.domain.llm.ModelRegistry;
import com.dmipi.coder.core.domain.llm.Role;
import java.util.List;

/**
 * Keeps the conversation inside the active model's window. The budget is approximate (chars/4)
 * against a conservative threshold fraction; on crossing it, the older history is replaced by a
 * summary written by the <em>active</em> model — a cheap model summarizing badly would poison
 * everything after it — while the recent turns stay verbatim. Never silent: the front-end sees
 * a housekeeping event, and a history too big even after compaction fails the turn visibly.
 */
public final class ContextManager {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int KEEP_RECENT_MESSAGES = 8;
    private static final String SUMMARY_MARKER = "[State snapshot of the earlier conversation — the history above it was compacted away.]\n";
    private static final java.util.regex.Pattern SNAPSHOT = java.util.regex.Pattern.compile("(?s)<state_snapshot>(.*)</state_snapshot>");

    private final ModelRegistry models;
    private final double threshold;
    private final Out out;
    private final String summaryInstructions;

    /** @param threshold the fraction of the active model's window that triggers compaction, e.g. 0.7 */
    public ContextManager(final ModelRegistry models, final double threshold, final Out out, final String summaryInstructions) {
        if (threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("The compaction threshold must be in (0, 1], got " + threshold + ".");
        }
        this.models = models;
        this.threshold = threshold;
        this.out = out;
        this.summaryInstructions = summaryInstructions;
    }

    /** Compacts when the approximate budget crosses the threshold; throws when even compaction cannot fit the history. */
    public void maybeCompact(final Conversation conversation, final CancelToken cancel) {
        final int window = models.active().declaration().contextWindow();
        final int before = approxTokens(conversation.messages());
        if (before <= threshold * window) {
            return;
        }

        final int keepFrom = tailStart(conversation.messages());
        if (keepFrom > 1) {
            final String summary = summarize(conversation.messages().subList(1, keepFrom), cancel);
            if (cancel.isCancelled()) {
                // A cancelled summary stream may be a fragment; compacting with it would destroy history.
                return;
            }
            conversation.compact(keepFrom, ChatMessage.user(SUMMARY_MARKER + summary));
            out.event(new OutEvent.ContextCompacted(before, approxTokens(conversation.messages())));
        }

        final int after = approxTokens(conversation.messages());
        if (after > window) {
            throw new IllegalStateException("The conversation does not fit the model's context window of " + window + " tokens even after compaction (~" + after + " tokens).");
        }
    }

    /**
     * The first index of the verbatim tail — never splitting a tool call from its results. A
     * short history keeps only its newest message verbatim, so a tiny window can still shrink.
     */
    private static int tailStart(final List<ChatMessage> messages) {
        int start = messages.size() - KEEP_RECENT_MESSAGES;
        if (start <= 1) {
            start = messages.size() - 1;
        }
        while (start > 1 && messages.get(start).role() == Role.TOOL) {
            start--;
        }
        return Math.max(start, 1);
    }

    private String summarize(final List<ChatMessage> older, final CancelToken cancel) {
        final StringBuilder transcript = new StringBuilder();
        for (final ChatMessage message : older) {
            transcript.append(message.role().name()).append(": ").append(message.content()).append('\n');
        }
        final ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system(summaryInstructions), ChatMessage.user(transcript.toString())),
                List.of());
        final StringBuilder summary = new StringBuilder();
        models.active().client().stream(request, cancel, event -> {
            if (event instanceof LlmStreamEvent.TextDelta(final String text)) {
                summary.append(text);
            }
        });
        return snapshot(summary.toString().strip());
    }

    /** The state snapshot inside the markers, or the whole reply when the model did not wrap it. */
    private static String snapshot(final String reply) {
        final java.util.regex.Matcher matcher = SNAPSHOT.matcher(reply);
        return matcher.find() ? matcher.group(1).strip() : reply;
    }

    private static int approxTokens(final List<ChatMessage> messages) {
        int chars = 0;
        for (final ChatMessage message : messages) {
            chars += message.content().length();
        }
        return chars / CHARS_PER_TOKEN;
    }
}
