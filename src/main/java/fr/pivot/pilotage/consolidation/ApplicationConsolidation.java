package fr.pivot.pilotage.consolidation;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable "consolidation by Application" (EN18.9) — the application-level roll-up of the data of
 * <strong>all</strong> its projects. It aggregates only what the pilotage domain owns (project
 * count, projects per derived planning status, the global temporal window and the unified strategic
 * milestones — all read from the EN22.1 temporal graph via the existing repositories/projection),
 * plus the per-application aggregates other modules push through {@link ApplicationDataContributor}
 * over the PIVOT bus. <strong>No inter-module FK is ever traversed</strong> (ADR-006/ADR-008).
 *
 * <p>Every collection accessor returns a defensive, unmodifiable copy (SpotBugs
 * {@code EI_EXPOSE_REP}, applied preventively — a record accessor exposes the field directly under
 * {@code --release 24}).
 *
 * @param applicationId       the consolidated application id
 * @param applicationName     the application name
 * @param tenantId            the owning tenant's {@code public.tenants.id} (isolation boundary)
 * @param projectCount        number of projects attached to the application
 * @param projectsByStatus    project count per derived {@link ProjectPlanningStatus} (immutable)
 * @param windowStart         earliest planned start across all projects, or {@code null} if none
 * @param windowFinish        latest planned finish across all projects, or {@code null} if none
 * @param strategicMilestones the union of the projects' shared-in-roadmap milestones (immutable,
 *                            ordered by project then node id)
 * @param contributions       per-application aggregates contributed by other modules over the bus
 *                            (immutable; empty when only the no-op contributor is wired)
 */
public record ApplicationConsolidation(
        long applicationId,
        String applicationName,
        long tenantId,
        int projectCount,
        Map<ProjectPlanningStatus, Integer> projectsByStatus,
        LocalDate windowStart,
        LocalDate windowFinish,
        List<ApplicationMilestone> strategicMilestones,
        List<ApplicationAggregateContribution> contributions) {

    /**
     * Canonical constructor taking defensive, unmodifiable copies of every collection so the
     * consolidation is fully immutable.
     *
     * @throws NullPointerException if any collection is {@code null}
     */
    public ApplicationConsolidation {
        final Map<ProjectPlanningStatus, Integer> statusCopy =
                new EnumMap<>(ProjectPlanningStatus.class);
        statusCopy.putAll(Objects.requireNonNull(projectsByStatus, "projectsByStatus"));
        projectsByStatus = java.util.Collections.unmodifiableMap(statusCopy);
        strategicMilestones =
                List.copyOf(Objects.requireNonNull(strategicMilestones, "strategicMilestones"));
        contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
    }
}
