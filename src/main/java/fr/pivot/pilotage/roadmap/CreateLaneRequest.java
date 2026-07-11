package fr.pivot.pilotage.roadmap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body of {@code POST /api/pilotage/tenants/{tenantId}/teams/{teamId}/projects/{projectId}
 * /roadmap/lanes} (US22.3.1) — creates a new lane on a project's roadmap-rapide view.
 *
 * <p>The display position is never client-supplied: the service always appends the new lane at
 * the end of the project's existing lanes (US22.3.1 leaves lane reordering out of scope).
 *
 * @param name the lane label (theme / team / objective); required, max 255 chars to match the
 *             {@code pilotage.lane.name} column
 */
public record CreateLaneRequest(@NotBlank @Size(max = 255) String name) {
}
