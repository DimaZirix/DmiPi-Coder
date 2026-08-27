package com.dmipi.coder.core.application.egress;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmipi.coder.core.domain.hil.Answer;
import com.dmipi.coder.core.domain.permissions.Mode;
import com.dmipi.coder.core.testfixtures.ScriptedHil;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EgressPolicyTest {

    @Test
    @DisplayName("a configured host passes without asking")
    void should_pass_a_configured_host_without_asking() {
        // Given
        final ScriptedHil hil = new ScriptedHil(List.of());
        final EgressPolicy policy = new EgressPolicy(List.of("registry.npmjs.org"), hil, () -> Mode.DEFAULT);

        // When / Then
        assertThat(policy.allows("registry.npmjs.org")).isTrue();
        assertThat(policy.allows("Registry.NPMJS.org ")).isTrue();
        assertThat(hil.asked()).isEmpty();
    }

    @Test
    @DisplayName("a subdomain wildcard matches subdomains, not the apex and not a lookalike suffix")
    void should_match_subdomain_wildcards_but_not_the_apex() {
        // Given: don't-ask mode, so anything not configured is denied rather than asked
        final EgressPolicy policy = new EgressPolicy(List.of("*.example.com"), new ScriptedHil(List.of()), () -> Mode.DONT_ASK);

        // When / Then
        assertThat(policy.allows("api.example.com")).isTrue();
        assertThat(policy.allows("deep.api.example.com")).isTrue();
        assertThat(policy.allows("example.com")).isFalse();
        assertThat(policy.allows("evilexample.com")).isFalse();
    }

    @Test
    @DisplayName("an unknown host follows the mode: allow-all runs, don't-ask blocks — neither asks")
    void should_follow_the_mode_for_unknown_hosts_without_asking() {
        // Given
        final ScriptedHil hil = new ScriptedHil(List.of());

        // When / Then
        assertThat(new EgressPolicy(List.of(), hil, () -> Mode.ALLOW_ALL).allows("anything.dev")).isTrue();
        assertThat(new EgressPolicy(List.of(), hil, () -> Mode.DONT_ASK).allows("anything.dev")).isFalse();
        assertThat(hil.asked()).isEmpty();
    }

    @Test
    @DisplayName("'always this session' is remembered — the hundredth connection never re-asks")
    void should_remember_an_always_answer() {
        // Given
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("allow-always")));
        final EgressPolicy policy = new EgressPolicy(List.of(), hil, () -> Mode.DEFAULT);

        // When
        final boolean first = policy.allows("registry.npmjs.org");
        final boolean second = policy.allows("registry.npmjs.org");

        // Then
        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(hil.asked()).hasSize(1);
    }

    @Test
    @DisplayName("a denied host is remembered for the session")
    void should_remember_a_denied_host() {
        // Given
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("deny")));
        final EgressPolicy policy = new EgressPolicy(List.of(), hil, () -> Mode.DEFAULT);

        // When
        final boolean first = policy.allows("tracker.evil");
        final boolean second = policy.allows("tracker.evil");

        // Then
        assertThat(first).isFalse();
        assertThat(second).isFalse();
        assertThat(hil.asked()).hasSize(1);
    }

    @Test
    @DisplayName("'allow once' is exactly once — the next connection asks again")
    void should_ask_again_after_an_allow_once() {
        // Given
        final ScriptedHil hil = new ScriptedHil(List.of(Answer.of("allow-once"), Answer.of("deny")));
        final EgressPolicy policy = new EgressPolicy(List.of(), hil, () -> Mode.DEFAULT);

        // When / Then
        assertThat(policy.allows("api.dev")).isTrue();
        assertThat(policy.allows("api.dev")).isFalse();
        assertThat(hil.asked()).hasSize(2);
    }

    @Test
    @DisplayName("a blank hostname is refused outright")
    void should_refuse_a_blank_hostname() {
        final EgressPolicy policy = new EgressPolicy(List.of(), new ScriptedHil(List.of()), () -> Mode.ALLOW_ALL);
        assertThat(policy.allows("  ")).isFalse();
    }
}
