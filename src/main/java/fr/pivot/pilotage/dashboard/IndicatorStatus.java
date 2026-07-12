package fr.pivot.pilotage.dashboard;

/**
 * Availability of a rendered widget's underlying indicator data (US23.2.2, Error AC — "given un
 * indicateur sans données, system l'affiche comme «indisponible» (état explicite) plutôt que vide
 * ou nul"). {@link DashboardIndicatorView} always carries one of these two values explicitly —
 * never a bare {@code null}/empty payload the frontend would have to interpret itself.
 */
public enum IndicatorStatus {

    /** The widget's underlying data resolved successfully (even if the value itself is "empty",
     *  e.g. an application with zero strategic milestones — that is a legitimate value, not an
     *  unavailability). */
    AVAILABLE,

    /**
     * No data could be resolved for this widget — either its target application no longer exists
     * for the tenant/team, or no {@link PortfolioIndicatorSource} had anything to report.
     */
    UNAVAILABLE
}
