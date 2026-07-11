package fr.pivot.pilotage.roadmap;

import java.time.Instant;

/**
 * Response DTO returned once, at creation time, by {@code POST .../roadmap/share-links}
 * (US22.3.5).
 *
 * <p><strong>The only moment the raw token is ever exposed.</strong> After this response, only
 * its SHA-256 hash is retrievable server-side ({@link RoadmapShareLink#getTokenHash()}) — the
 * caller (an authorized editor) is responsible for copying {@link #token()} into the shareable
 * URL immediately; it cannot be recovered later (mirrors {@code pivot-core}'s opaque
 * {@code AccessToken} discipline).
 *
 * @param id        the share link id (used to revoke it later via {@code DELETE
 *                  .../share-links/{id}})
 * @param token     the raw, unhashed bearer token — embed it in the public share URL
 *                  ({@code /public/roadmap-shares/{token}}); never persisted, never returned again
 * @param createdAt the creation timestamp
 * @param expiresAt the optional expiry timestamp, or {@code null} if the link never expires on
 *                  its own
 */
public record CreateShareLinkResponse(long id, String token, Instant createdAt, Instant expiresAt) {
}
