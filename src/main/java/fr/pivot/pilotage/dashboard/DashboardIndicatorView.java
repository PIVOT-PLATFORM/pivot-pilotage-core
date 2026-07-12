package fr.pivot.pilotage.dashboard;

import java.util.List;
import java.util.Map;

/**
 * Rendered indicator payload of one dashboard widget (US23.2.2). Always carries an explicit
 * {@link #status()} (AC error: "un indicateur sans données... l'affiche comme «indisponible»
 * (état explicite) plutôt que vide ou nul") and, when tension applies, an {@link #alertLevel()}
 * paired with a caller-facing {@link #alertLabel()} — never color-only (A11y AC).
 *
 * <p>Exactly one of {@link #projectsByStatus()}/{@link #milestones()} is populated, depending on
 * the owning widget's {@link DashboardWidgetType} (documented per accessor below); both are
 * {@code null} for {@link DashboardWidgetType#WEATHER_ALERTS} and whenever
 * {@link #status()} is {@link IndicatorStatus#UNAVAILABLE}. A generic, widget-type-keyed payload
 * (rather than a subtype per widget) mirrors the opaque-payload idiom already used by EN18.9's
 * {@code ApplicationAggregateContribution} for the same reason: the widget catalog is expected to
 * grow, and this response shape does not need to change to accommodate a new one, beyond adding a
 * new accessor.
 *
 * @param status         whether this widget's data resolved
 * @param alertLevel     tension level; {@link AlertLevel#NONE} when not applicable or resolved
 * @param alertLabel     human-readable tension description, or {@code null} when
 *                       {@code alertLevel} is {@link AlertLevel#NONE}
 * @param projectCount   populated only for {@link DashboardWidgetType#PORTFOLIO_STATUS_SUMMARY}
 *                       when {@link #status()} is {@link IndicatorStatus#AVAILABLE}
 * @param projectsByStatus populated only for {@link DashboardWidgetType#PORTFOLIO_STATUS_SUMMARY}
 *                         when {@link #status()} is {@link IndicatorStatus#AVAILABLE} — project
 *                         count keyed by {@code ProjectPlanningStatus} name
 * @param milestones     populated only for {@link DashboardWidgetType#STRATEGIC_MILESTONES} when
 *                       {@link #status()} is {@link IndicatorStatus#AVAILABLE} (an empty list is a
 *                       legitimate "no strategic milestones yet" value, distinct from unavailable)
 */
public record DashboardIndicatorView(
        IndicatorStatus status,
        AlertLevel alertLevel,
        String alertLabel,
        Integer projectCount,
        Map<String, Integer> projectsByStatus,
        List<StrategicMilestoneView> milestones) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of {@code projectsByStatus}/
     * {@code milestones} when present (SpotBugs {@code EI_EXPOSE_REP}/{@code EI_EXPOSE_REP2},
     * mirrors {@code fr.pivot.pilotage.consolidation.ApplicationConsolidation}) — both are
     * legitimately {@code null} depending on the owning widget's type (see class doc), so
     * {@code null} is preserved rather than rejected.
     */
    public DashboardIndicatorView {
        projectsByStatus = projectsByStatus == null ? null : Map.copyOf(projectsByStatus);
        milestones = milestones == null ? null : List.copyOf(milestones);
    }

    /**
     * Builds the shared "no data" view used whenever a widget's target application no longer
     * resolves, or (for {@link DashboardWidgetType#WEATHER_ALERTS}) no
     * {@link PortfolioIndicatorSource} has anything to report.
     *
     * @return an {@link IndicatorStatus#UNAVAILABLE} view with no alert and no payload
     */
    static DashboardIndicatorView unavailable() {
        return new DashboardIndicatorView(IndicatorStatus.UNAVAILABLE, AlertLevel.NONE, null, null, null, null);
    }
}
