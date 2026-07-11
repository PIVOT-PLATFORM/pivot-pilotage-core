package fr.pivot.pilotage.roadmap;

import java.time.Instant;

/**
 * Response DTO for a {@link RoadmapShareLink} used by the authenticated management endpoints
 * (list) of US22.3.5 — <strong>never carries the token or its hash</strong>, only metadata useful
 * to decide whether/which link to revoke.
 *
 * @param id        the share link id
 * @param createdAt the creation timestamp
 * @param expiresAt the optional expiry timestamp, or {@code null} if the link never expires on
 *                  its own
 * @param revokedAt the revocation timestamp, or {@code null} if never revoked
 * @param active    {@code true} if the link currently grants read-only access (not revoked, and
 *                  not past its optional expiry) — same predicate the public endpoint enforces
 */
public record ShareLinkResponse(long id, Instant createdAt, Instant expiresAt, Instant revokedAt, boolean active) {

    /**
     * Maps a {@link RoadmapShareLink} entity to its response DTO.
     *
     * @param link the persisted share link
     * @return the mapped response
     */
    static ShareLinkResponse from(final RoadmapShareLink link) {
        return new ShareLinkResponse(link.getId(), link.getCreatedAt(), link.getExpiresAt(), link.getRevokedAt(),
                link.isActive());
    }
}
