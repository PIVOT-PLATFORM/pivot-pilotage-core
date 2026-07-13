package fr.pivot.pilotage.config;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link WebSocketConfig} — verifies the domain isolation contract without
 * requiring a running broker (see {@link WebSocketConfigIT} for the connectivity proof).
 */
class WebSocketConfigTest {

    /**
     * Security AC: this module's STOMP relay must only ever be scoped to its own domain
     * prefix, with a trailing separator so a similarly-named future domain (e.g.
     * "pilotagex") can never accidentally prefix-match and have its traffic relayed by this
     * module.
     */
    @Test
    void domainPrefixIsScopedToPilotageOnly() {
        assertThat(WebSocketConfig.DOMAIN_TOPIC_PREFIX)
                .isEqualTo("/topic/pilotage.")
                .startsWith("/topic/pilotage")
                .endsWith(".");
    }

    /**
     * Given a destination belonging to another domain, when compared against this module's
     * relay prefix, then it must never match — the isolation boundary enforced by Spring's
     * {@code AbstractBrokerMessageHandler.checkDestinationPrefix}.
     */
    @Test
    void otherDomainDestinationsNeverMatchThisModulePrefix() {
        assertThat("/topic/agilite.capacity-updated").doesNotStartWith(WebSocketConfig.DOMAIN_TOPIC_PREFIX);
        assertThat("/topic/collaboratif.board-updated").doesNotStartWith(WebSocketConfig.DOMAIN_TOPIC_PREFIX);
        assertThat("/topic/pilotagex.other").doesNotStartWith(WebSocketConfig.DOMAIN_TOPIC_PREFIX);
    }

    /**
     * Given a destination belonging to this module's own domain, then it must match its
     * relay prefix — the positive counterpart of the isolation check above.
     */
    @Test
    void ownDomainDestinationMatchesThisModulePrefix() {
        assertThat("/topic/pilotage.roadmap-updated").startsWith(WebSocketConfig.DOMAIN_TOPIC_PREFIX);
    }

    /**
     * With the relay enabled (default), the broker relay is registered for this module's
     * domain prefix and no in-process SimpleBroker is used.
     */
    @Test
    void relayEnabledRegistersStompBrokerRelay() {
        final MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class, RETURNS_DEEP_STUBS);
        new WebSocketConfig(true, "activemq", 61613, "*").configureMessageBroker(registry);

        verify(registry).enableStompBrokerRelay(WebSocketConfig.DOMAIN_TOPIC_PREFIX);
        verify(registry, never()).enableSimpleBroker(any());
    }

    /**
     * With the relay disabled (managed-min Cloud Run / tests — no ActiveMQ deployed), the same
     * domain prefix falls back to an in-process SimpleBroker so /topic/pilotage.* still works.
     */
    @Test
    void relayDisabledFallsBackToSimpleBroker() {
        final MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class, RETURNS_DEEP_STUBS);
        new WebSocketConfig(false, "unused", 61613, "*").configureMessageBroker(registry);

        verify(registry).enableSimpleBroker(WebSocketConfig.DOMAIN_TOPIC_PREFIX);
        verify(registry, never()).enableStompBrokerRelay(any());
    }
}
