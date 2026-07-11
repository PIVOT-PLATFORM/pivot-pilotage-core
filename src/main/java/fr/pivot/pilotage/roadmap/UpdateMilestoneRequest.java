package fr.pivot.pilotage.roadmap;

import java.time.LocalDate;

/**
 * Request body of {@code PATCH /api/pilotage/tenants/{tenantId}/teams/{teamId}/projects/{projectId}
 * /roadmap/milestones/{milestoneId}} (US22.3.4) — moves a milestone to a new date and/or reassigns
 * it to a different lane; this is the single write path for the AC "given a milestone, when its
 * date changes on the Gantt side, then the roadmap reflects the change" — there is only one row
 * ({@code fr.pivot.pilotage.schedule.Task}) to update, read back identically by both views.
 *
 * <p>Partial-update semantics: every field is optional and a {@code null} value means "leave
 * unchanged" — mirrors {@link UpdateInitiativePlacementRequest} exactly. This request never clears
 * the date back to {@code null} (out of scope: the AC only covers moving an already-dated
 * milestone, never un-dating one — a missing date is only rejected at creation, see
 * {@link InvalidMilestoneDateException#missing(long)}).
 *
 * @param date   the new milestone date, or {@code null} to leave unchanged
 * @param laneId the new lane id, or {@code null} to leave the current lane (possibly unset)
 *               unchanged
 */
public record UpdateMilestoneRequest(LocalDate date, Long laneId) {
}
