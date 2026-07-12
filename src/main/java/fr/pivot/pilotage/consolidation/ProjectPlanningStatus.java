package fr.pivot.pilotage.consolidation;

import fr.pivot.pilotage.schedule.Task;

import java.util.List;

/**
 * Derived planning status of a project (EN18.9), computed <strong>only</strong> from data the
 * pilotage domain owns — the persisted temporal graph (EN22.1). It is <em>not</em> a persisted
 * lifecycle column on {@code pilotage.project}: EN18.1 defined no such column, and EN18.9 adds no
 * new schema (frozen Gate 1 scope). The status is folded in projection so the consolidation can
 * report "projects per status" without inventing a domain field.
 *
 * <p>Any richer, business lifecycle status (e.g. budget-driven or governance-driven) is owned by
 * <em>other</em> modules and must arrive through {@link ApplicationDataContributor} over the PIVOT
 * bus (ADR-006/ADR-008), never by traversing an inter-module FK.
 */
public enum ProjectPlanningStatus {

    /** The project carries no task in the temporal graph — nothing planned yet. */
    EMPTY,

    /**
     * The project carries tasks but none has a precise (engine-scheduled) window yet — planned at
     * the roadmap altitude only.
     */
    PLANNED,

    /** The project carries at least one task with a precise start/finish window (scheduled). */
    SCHEDULED;

    /**
     * Derives a project's {@link ProjectPlanningStatus} from its tasks — the single formula shared by
     * {@link ApplicationConsolidationService} (EN18.9, per-application roll-up) and
     * {@code fr.pivot.pilotage.portfolio.PortfolioConsolidationService} (US23.2.1, multi-application
     * portfolio view) so the "avancement/phases" status a project reports is computed identically
     * wherever it is consolidated. No task → {@link #EMPTY}; at least one task with a precise
     * start/finish window → {@link #SCHEDULED}; otherwise → {@link #PLANNED}.
     *
     * @param tasks the project's tasks (temporal graph, EN22.1)
     * @return the derived planning status
     */
    public static ProjectPlanningStatus deriveFrom(final List<Task> tasks) {
        if (tasks.isEmpty()) {
            return EMPTY;
        }
        for (final Task task : tasks) {
            if (task.getStartDate() != null || task.getFinishDate() != null) {
                return SCHEDULED;
            }
        }
        return PLANNED;
    }
}
