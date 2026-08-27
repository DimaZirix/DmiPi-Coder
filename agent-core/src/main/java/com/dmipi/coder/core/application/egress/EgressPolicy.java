package com.dmipi.coder.core.application.egress;

import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.hil.Hil;
import com.dmipi.coder.core.domain.hil.Option;
import com.dmipi.coder.core.domain.hil.Question;
import com.dmipi.coder.core.domain.hil.QuestionKind;
import com.dmipi.coder.core.domain.permissions.Mode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The core's egress control point: decides whether a sandboxed command may connect to a
 * hostname. Configured allowed hosts pass; an unknown host follows the live approval mode's
 * ask outcome — run allows, block denies, prompt asks the human once, the pending TCP connect
 * simply waiting while they decide. "Always" and "deny" answers are remembered for the session,
 * so a package manager's hundredth registry connection never re-asks.
 *
 * <p>Thread-safe: proxy connections ask from their own threads; parallel connections to the
 * same host coalesce into one question.
 */
public final class EgressPolicy {

    private static final String ALLOW_ONCE = "allow-once";
    private static final String ALLOW_ALWAYS = "allow-always";
    private static final String DENY = "deny";
    private static final String SUBDOMAIN_WILDCARD = "*.";

    private final List<String> allowedHosts;
    private final Hil hil;
    private final Supplier<Mode> mode;
    private final Set<String> sessionAllowed = ConcurrentHashMap.newKeySet();
    private final Set<String> sessionDenied = ConcurrentHashMap.newKeySet();

    /**
     * @param allowedHosts hosts that always pass: exact names, or {@code *.example.com} for any
     *     subdomain (not the apex). Normalized to lower case; blank entries are dropped.
     * @param mode the live approval mode — read per decision, so a mode switch applies at once.
     */
    public EgressPolicy(final List<String> allowedHosts, final Hil hil, final Supplier<Mode> mode) {
        this.allowedHosts = Objects.requireNonNull(allowedHosts, "allowedHosts").stream()
                .map(entry -> entry.strip().toLowerCase(Locale.ROOT))
                .filter(entry -> !entry.isEmpty())
                .toList();
        this.hil = Objects.requireNonNull(hil, "hil");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public boolean allows(final String hostname) {
        final String host = hostname.strip().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return false;
        }
        if (isConfigured(host) || sessionAllowed.contains(host)) {
            return true;
        }
        if (sessionDenied.contains(host)) {
            return false;
        }
        return switch (mode.get().askOutcome()) {
            case RUN -> true;
            case BLOCK -> false;
            case PROMPT -> askOnce(host);
        };
    }

    private boolean isConfigured(final String host) {
        return allowedHosts.stream()
                .anyMatch(entry -> matches(entry, host));
    }

    private static boolean matches(final String entry, final String host) {
        if (entry.startsWith(SUBDOMAIN_WILDCARD)) {
            return host.endsWith(entry.substring(1));
        }
        return host.equals(entry);
    }

    /** One question per host, however many connections are waiting on the answer. */
    private synchronized boolean askOnce(final String host) {
        if (sessionAllowed.contains(host)) {
            return true;
        }
        if (sessionDenied.contains(host)) {
            return false;
        }

        final Question question = new Question(
                "Allow the sandboxed command to connect to '" + host + "'?",
                host,
                QuestionKind.OPTION_LIST,
                List.of(
                        new Option(ALLOW_ONCE, "Allow once"),
                        new Option(ALLOW_ALWAYS, "Always allow this host this session"),
                        new Option(DENY, "Deny this host this session")));
        final Answer answer = hil.ask(question);
        if (question.rejection(answer).isPresent()) {
            return false;
        }
        return switch (answer.selected().getFirst()) {
            case ALLOW_ONCE -> true;
            case ALLOW_ALWAYS -> {
                sessionAllowed.add(host);
                yield true;
            }
            default -> {
                sessionDenied.add(host);
                yield false;
            }
        };
    }
}
