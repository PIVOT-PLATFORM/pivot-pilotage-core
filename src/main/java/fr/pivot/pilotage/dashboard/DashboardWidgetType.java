package fr.pivot.pilotage.dashboard;

/**
 * Catalog of widget types a dashboard (US23.2.2) may be configured with. Unlike
 * {@code pilotage.lane.name} (deliberately free text, no taxonomy), a widget type is a closed,
 * server-validated set: the AC explicitly requires an unknown widget type to be rejected ("Error :
 * given une configuration de widget invalide (widget inconnu...)"), so this cannot be a free label.
 *
 * <p><strong>Every entry is currently application-scoped</strong> ({@link DashboardWidget#getApplicationId()}
 * is {@code NOT NULL} in the schema) — each widget renders data for exactly one
 * {@code pilotage.application}. A future, non-scoped widget type would require relaxing that
 * column; not needed by any widget type defined today (no speculative schema change made ahead of
 * an actual need, cf. CLAUDE.md — mirrors the repo-wide "no fictitious future-proofing" posture,
 * e.g. {@code pilotage.lane} deletion left unimplemented until an actual US requires it).
 *
 * <p>PO Agent + Architect decision (Gate 1, sous-spécification technique du fichier backlog —
 * catalogue non énuméré dans les AC) : trois types couvrant les indicateurs cités par la Note
 * d'implémentation ("les alertes s'appuient sur les indicateurs déjà calculés par les autres US du
 * portefeuille — avancement, météo") plus les jalons stratégiques déjà exposés par EN18.9.
 */
public enum DashboardWidgetType {

    /**
     * Application-level project status roll-up (project count, projects per
     * {@code ProjectPlanningStatus}) — reuses EN18.9's
     * {@code fr.pivot.pilotage.consolidation.ApplicationConsolidationService} directly, already
     * merged. "Avancement" tension overlay (AC2) is sourced through {@link PortfolioIndicatorSource}
     * (kind {@link PortfolioIndicatorKind#PROGRESS}).
     */
    PORTFOLIO_STATUS_SUMMARY,

    /**
     * "Météo" tension widget (retard / dépassement / surcharge). The threshold computation itself
     * is explicitly out of scope for US23.2.2 (delegated to the future US23.2.4 météo calc source,
     * per the backlog file's "Hors périmètre") — this widget only <em>displays</em> whatever
     * {@link PortfolioIndicatorSource} (kind {@link PortfolioIndicatorKind#WEATHER}) reports, and
     * renders {@code UNAVAILABLE} while no real source is wired (today: only the no-op default,
     * {@link NoOpPortfolioIndicatorSource}).
     */
    WEATHER_ALERTS,

    /**
     * An application's unified strategic milestones — reuses EN18.9's
     * {@code ApplicationConsolidation#strategicMilestones()} directly, already merged.
     */
    STRATEGIC_MILESTONES
}
