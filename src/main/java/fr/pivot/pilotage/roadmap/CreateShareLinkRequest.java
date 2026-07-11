package fr.pivot.pilotage.roadmap;

import java.time.Instant;

/**
 * Request body of {@code POST /api/pilotage/tenants/{tenantId}/teams/{teamId}/projects/{projectId}
 * /roadmap/share-links} (US22.3.5) — creates a new read-only roadmap share link.
 *
 * <p>{@code expiresAt} is optional and <strong>deliberately not bean-validated</strong> with
 * {@code @Future}: a past-or-present value is rejected explicitly by
 * {@code RoadmapShareService} ({@link InvalidShareLinkExpiryException}, mapped to 400 with an
 * {@link ApiError} body carrying a caller-facing message) — same reasoning already established by
 * {@link CreateInitiativeRequest} for {@code fuzzyPeriodStart}/{@code fuzzyPeriodEnd}: Spring
 * Boot's default bean-validation error body omits field-level messages unless
 * {@code server.error.include-message}/{@code include-binding-errors} are set to {@code always}
 * (not configured in this module).
 *
 * @param expiresAt optional absolute expiry timestamp; {@code null} means the link never expires
 *                  on its own and can only be ended by explicit revocation
 */
public record CreateShareLinkRequest(Instant expiresAt) {
}
