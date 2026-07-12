package fr.pivot.pilotage.portfolio;

import fr.pivot.pilotage.consolidation.ApplicationConsolidation;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, single-application group of the consolidated portfolio view (US23.2.1) — the portfolio
 * aggregates <strong>multiple</strong> applications (per this US's Gate 1 note: reuse EN18.9's
 * per-application consolidation mechanism across the tenant's whole application set), each grouping
 * its projects.
 *
 * @param applicationId   the {@code pilotage.application.id}
 * @param applicationName the application name
 * @param consolidation   the application's EN18.9 roll-up, reused <strong>unchanged</strong> (project
 *                        count per {@code ProjectPlanningStatus}, temporal window, unified strategic
 *                        milestones each tagged with their {@code projectId}) — the "jalons" and
 *                        "dates clés" dimensions of this US's AC, computed once by
 *                        {@code ApplicationConsolidationService} and never re-derived here
 * @param projects        the application's projects, each carrying the "santé"/"avancement"/"phases"
 *                        dimensions this US adds on top of EN18.9 (immutable)
 */
public record PortfolioApplicationEntry(
        long applicationId,
        String applicationName,
        ApplicationConsolidation consolidation,
        List<PortfolioProjectEntry> projects) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of {@code projects} so the entry is
     * fully immutable (SpotBugs {@code EI_EXPOSE_REP}).
     *
     * @throws NullPointerException if any non-primitive argument is {@code null}
     */
    public PortfolioApplicationEntry {
        Objects.requireNonNull(applicationName, "applicationName");
        Objects.requireNonNull(consolidation, "consolidation");
        projects = List.copyOf(Objects.requireNonNull(projects, "projects"));
    }
}
