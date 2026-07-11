package fr.pivot.pilotage.roadmap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body of {@code POST /api/pilotage/tenants/{tenantId}/teams/{teamId}/projects/{projectId}
 * /roadmap/milestones} (US22.3.4) — creates a new strategic milestone (a {@code Task} with
 * {@code node_kind=MILESTONE}, never a separate entity, per the backlog note) with a single date.
 *
 * <p>{@code date} is mandatory but <strong>deliberately not {@code @NotNull}</strong> here — the AC
 * "Error: given a milestone without a date... the action is rejected with an explicit message"
 * needs a caller-facing message in the response body, and Spring Boot's default bean-validation
 * error body omits field messages unless {@code server.error.include-message}/
 * {@code include-binding-errors} are set to {@code always} (not configured in this module). A
 * {@code null} {@code date} is therefore rejected explicitly by {@code RoadmapService}
 * ({@link InvalidMilestoneDateException#missing(long)}), mirroring exactly how
 * {@code CreateInitiativeRequest.laneId()} handles the same class of AC (US22.3.1).
 *
 * <p>{@code laneId} is <strong>optional</strong>, unlike an initiative's mandatory lane — a
 * strategic milestone is often a project-wide marker (e.g. "go/no-go", a board review) with no
 * natural theme/team/objective lane, but may also be pinned to a specific lane at the caller's
 * discretion; both are valid roadmap-rapide placements. When supplied, it must resolve on this
 * project (same {@link LaneNotFoundException#invalid(long, long)} as an initiative's lane).
 *
 * @param name   the milestone name; required
 * @param date   the milestone's single date; required (see AC above; enforced by the service, not
 *               bean validation)
 * @param laneId the lane this milestone is optionally pinned to; {@code null} for a project-wide
 *               marker not tied to any lane
 */
public record CreateMilestoneRequest(
        @NotBlank @Size(max = 512) String name,
        LocalDate date,
        Long laneId) {
}
