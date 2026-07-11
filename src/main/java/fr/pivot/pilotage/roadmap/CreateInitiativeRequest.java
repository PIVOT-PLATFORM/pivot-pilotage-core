package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.schedule.TemporalPrecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body of {@code POST /api/pilotage/tenants/{tenantId}/teams/{teamId}/projects/{projectId}
 * /roadmap/initiatives} (US22.3.1) — creates a new initiative (a {@code Task}, never a separate
 * entity, per the backlog note) posed on a lane.
 *
 * <p>{@code laneId} is mandatory but <strong>deliberately not {@code @NotNull}</strong> here — the
 * AC "Error: given an initiative without a target lane, when I try to save it, then the action is
 * rejected and a message indicates a lane is required" needs a caller-facing message in the
 * response body, and Spring Boot's default bean-validation error body omits field messages unless
 * {@code server.error.include-message}/{@code include-binding-errors} are set to {@code always}
 * (not configured in this module). A {@code null} {@code laneId} is therefore rejected explicitly
 * by {@code RoadmapService} ({@link LaneNotFoundException#missing(long)}), giving full control over
 * the message via {@link ApiError} — same 400 status, same {@link RoadmapExceptionHandler}
 * mapping as a non-null but unknown/foreign {@code laneId}
 * ({@link LaneNotFoundException#invalid(long, long)}).
 *
 * <p>{@code fuzzyPeriodStart}/{@code fuzzyPeriodEnd} are both optional — the AC "a bar is created
 * without requiring tasks or precise dates" allows an initiative with no period at all. When
 * supplied, both bounds must be given together (a lone bound is rejected as an
 * {@link InvalidInitiativePeriodException}, 400) and the end must not precede the start.
 *
 * @param name              the initiative name; required
 * @param laneId            the target lane id; required (see AC above; enforced by the service,
 *                          not bean validation)
 * @param fuzzyPeriodStart  the approximate period lower bound; optional
 * @param fuzzyPeriodEnd    the approximate period upper bound; optional
 * @param temporalPrecision the effective altitude grain; optional — defaults to
 *                          {@link TemporalPrecision#QUARTER} when omitted (roadmap-rapide is a
 *                          macro tool; US22.3.2 owns the fine-grained scale selection UI and may
 *                          later send an explicit value without any contract change here)
 */
public record CreateInitiativeRequest(
        @NotBlank @Size(max = 512) String name,
        Long laneId,
        LocalDate fuzzyPeriodStart,
        LocalDate fuzzyPeriodEnd,
        TemporalPrecision temporalPrecision) {
}
