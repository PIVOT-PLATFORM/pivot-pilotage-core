package fr.pivot.pilotage.portfolio;

import fr.pivot.pilotage.consolidation.ProjectPlanningStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, single-project row of the consolidated portfolio view (US23.2.1) — the drill-down unit
 * the AC refers to ("il navigue vers le détail du projet correspondant"). Carries the three indicator
 * kinds the drill-down AC names (santé, avancement, jalons — the latter surfaced at the owning
 * {@code ApplicationConsolidation.strategicMilestones()}, each already tagged with its
 * {@code projectId}) plus the phases dimension, so every indicator in the view traces back to
 * exactly one project.
 *
 * <p>{@link #teamId} is carried explicitly (not just implied by nesting under an application) because
 * the drill-down target — {@code fr.pivot.pilotage.roadmap.RoadmapController}'s existing
 * {@code /tenants/{tenantId}/teams/{teamId}/projects/{projectId}/roadmap} contract — needs it to
 * build the URL; this US never duplicates that controller's content, only its addressing key.
 *
 * @param projectId       the {@code pilotage.project.id} — the drill-down target identifier
 * @param projectName     the project name
 * @param teamId          the owning team's {@code public.teams.id} — required to address the
 *                        drill-down target (roadmap module, E22)
 * @param planningStatus  the project's derived planning status (EN18.9 {@code ProjectPlanningStatus},
 *                        reused as-is — never recomputed twice)
 * @param health          the project's health ("santé/météo") indicator; {@code NOT_SET} when none
 *                        is available yet (error AC)
 * @param progressPercent the project's average leaf-task completion in {@code [0, 100]} — the
 *                        "avancement" dimension; {@link BigDecimal#ZERO} when no leaf task carries a
 *                        progress record yet
 * @param phases          the project's phases, ordered by display position (immutable)
 */
public record PortfolioProjectEntry(
        long projectId,
        String projectName,
        long teamId,
        ProjectPlanningStatus planningStatus,
        ProjectHealthIndicator health,
        BigDecimal progressPercent,
        List<PortfolioPhaseEntry> phases) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of {@code phases} so the entry is
     * fully immutable (SpotBugs {@code EI_EXPOSE_REP}).
     *
     * @throws NullPointerException if any non-primitive argument is {@code null}
     */
    public PortfolioProjectEntry {
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(planningStatus, "planningStatus");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(progressPercent, "progressPercent");
        phases = List.copyOf(Objects.requireNonNull(phases, "phases"));
    }
}
