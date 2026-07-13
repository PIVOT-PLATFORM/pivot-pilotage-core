package fr.pivot.pilotage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP broker relay configuration for the Pilotage domain (EN07.3 — ActiveMQ persistence
 * KahaDB, multi-repo).
 *
 * <p>Relays STOMP traffic to the shared ActiveMQ broker (owned and configured by
 * {@code pivot-core}: KahaDB persistence, {@code DLQ.pilotage}, memory/store limits, internal-
 * only console) instead of an in-process broker. This is the cross-module-core event bus.
 *
 * <p><strong>Why a WebSocket endpoint is registered here too:</strong> Spring's {@code
 * @EnableWebSocketMessageBroker} infrastructure ({@code SubProtocolWebSocketHandler}) requires
 * at least one registered STOMP endpoint to start at all — with zero endpoints the application
 * context fails to refresh ({@code IllegalStateException: No handlers}), verified empirically
 * while building this class. A minimal endpoint is therefore unavoidable plumbing to have the
 * relay itself exist, not scope creep: this module's own stack table already anticipates
 * browser realtime collaboration on roadmap/Gantt ("WebSocket (STOMP) — prévu pour la
 * collaboration live roadmap/Gantt", see this repo's {@code CLAUDE.md}), so this only moves
 * that already-planned plumbing slightly earlier.
 *
 * <p><strong>Security — no authentication yet (deliberate, documented gap):</strong> this
 * repo has no {@code SecurityConfig} and no opaque-token validation at all yet — {@code
 * fr.pivot:pivot-core-starter} is not published/consumable (see {@code CLAUDE.md}), so no
 * module in this bootstrap phase can validate a bearer token, REST or WebSocket alike. The
 * {@code /ws/pilotage} endpoint registered below is consequently unauthenticated, exactly like
 * every other endpoint in this repo today — not a new gap introduced by this Enabler. It must
 * not carry real user data until an auth interceptor (mirroring {@code pivot-core}'s
 * {@code StompAuthChannelInterceptor} pattern once the starter is consumable) is added — this
 * is a hard prerequisite for the first realtime US, not for this Enabler.
 *
 * <p><strong>Domain isolation ({@code /topic/pilotage.} prefix):</strong> {@link
 * MessageBrokerRegistry#enableStompBrokerRelay(String...)} only relays messages whose
 * destination starts with one of the given prefixes — anything else is silently not
 * forwarded by this JVM's relay handler (see {@code
 * org.springframework.messaging.simp.AbstractBrokerMessageHandler#checkDestinationPrefix}).
 * Scoping this module to {@code /topic/pilotage.} (trailing dot) means this application can
 * never relay another domain's traffic (agilite, collaboratif), even by accident — this is
 * the enforced isolation boundary for this Enabler's AC, applied independently in each
 * module-core. Broker-side ACL (rejecting a connection that tries to (re)subscribe to another
 * domain's topic at the transport level) is a documented, accepted follow-up gap, not built
 * here — consistent with this codebase's existing practice of flagging known gaps rather than
 * over-building (see e.g. {@code pivot-core/docker-compose.prod.yml}'s note on unauthenticated
 * Redis).
 *
 * <p><strong>Destination naming — dot, not slash:</strong> the backlog AC describes topics as
 * {@code /topic/pilotage/**} (prose intent: "all topics under this domain"). The actual
 * destinations used here are dot-separated after the prefix (e.g. {@code
 * /topic/pilotage.roadmap-updated}), because ActiveMQ's wildcard destination matching — used
 * broker-side for the {@code DLQ.pilotage} dead-letter policy ({@code topic="pilotage.>"}) —
 * only matches dot-delimited hierarchy segments. A slash-based destination becomes one opaque
 * segment to that matcher and would never match the wildcard.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * STOMP destination prefix relayed by this module — see the class-level JavaDoc for the
     * isolation and naming rationale. Package-private for direct assertion from tests.
     */
    static final String DOMAIN_TOPIC_PREFIX = "/topic/pilotage.";

    private final boolean relayEnabled;
    private final String relayHost;
    private final int relayPort;
    private final String allowedOrigins;

    /**
     * Creates the configuration with the shared broker's connection coordinates.
     *
     * @param relayEnabled   whether to relay to the external ActiveMQ broker (default {@code
     *                       true}). Set {@code false} — as {@code application-test.yml} and the
     *                       managed-min Cloud Run stack do — to fall back to an in-process
     *                       {@code SimpleBroker} when no ActiveMQ is deployed (nothing publishes
     *                       cross-module events yet, so the relay is not required to function).
     * @param relayHost      hostname of the shared ActiveMQ broker (STOMP transport)
     * @param relayPort      STOMP port of the shared ActiveMQ broker
     * @param allowedOrigins CORS-allowed origins for the WebSocket handshake
     */
    public WebSocketConfig(
            @Value("${pivot.activemq.relay-enabled:true}") final boolean relayEnabled,
            @Value("${pivot.activemq.relay-host}") final String relayHost,
            @Value("${pivot.activemq.relay-port}") final int relayPort,
            @Value("${pivot.cors.allowed-origins}") final String allowedOrigins) {
        this.relayEnabled = relayEnabled;
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * Configures the STOMP broker relay, scoped to this module's domain prefix, and the
     * application destination prefix for future {@code @MessageMapping} handlers.
     *
     * @param registry the message broker registry to configure
     */
    @Override
    public void configureMessageBroker(final MessageBrokerRegistry registry) {
        if (relayEnabled) {
            registry.enableStompBrokerRelay(DOMAIN_TOPIC_PREFIX)
                    .setRelayHost(relayHost)
                    .setRelayPort(relayPort)
                    .setSystemHeartbeatSendInterval(10000)
                    .setSystemHeartbeatReceiveInterval(10000);
        } else {
            // No external broker deployed (e.g. managed-min Cloud Run / tests): keep the same
            // domain-scoped prefix on an in-process SimpleBroker so /topic/pilotage.* still works
            // (single instance — nothing publishes cross-module events yet).
            registry.enableSimpleBroker(DOMAIN_TOPIC_PREFIX);
        }
        registry.setApplicationDestinationPrefixes("/app/pilotage");
    }

    /**
     * Registers the minimal WebSocket endpoint required for the broker relay infrastructure
     * to start (see the class-level JavaDoc). No {@code @MessageMapping} handler exists yet
     * behind {@code /app/pilotage}, and nothing publishes real data on {@code /topic/pilotage.}
     * yet, so this endpoint currently carries no functional traffic — it exists only so the
     * relay itself can be wired and proven to connect (EN07.3's scope).
     *
     * @param registry the STOMP endpoint registry
     */
    @Override
    public void registerStompEndpoints(final StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/pilotage")
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }
}
