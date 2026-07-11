package fr.pivot.pilotage.roadmap;

import java.time.LocalDate;

/**
 * Request body of {@code PATCH /api/pilotage/tenants/{tenantId}/teams/{teamId}/projects/{projectId}
 * /roadmap/initiatives/{initiativeId}} (US22.3.1) — moves and/or resizes an initiative, and
 * optionally reassigns it to a different lane (a vertical drag between lanes is the same
 * "moving the bar" gesture as a horizontal drag along the time axis; the AC does not restrict
 * "déplacement" to one axis).
 *
 * <p>Partial-update semantics: every field is optional and a {@code null} value means "leave
 * unchanged" — this request never clears a field back to {@code null} (out of scope: the AC only
 * covers moving/resizing an already-placed or newly-placed initiative, never un-scheduling one).
 * {@code fuzzyPeriodStart}/{@code fuzzyPeriodEnd} must be supplied together when either is
 * present — see {@link InvalidInitiativePeriodException}.
 *
 * @param laneId           the new lane id, or {@code null} to leave the current lane unchanged
 * @param fuzzyPeriodStart the new approximate period lower bound, or {@code null} to leave
 *                         unchanged
 * @param fuzzyPeriodEnd   the new approximate period upper bound, or {@code null} to leave
 *                         unchanged
 */
public record UpdateInitiativePlacementRequest(Long laneId, LocalDate fuzzyPeriodStart, LocalDate fuzzyPeriodEnd) {
}
