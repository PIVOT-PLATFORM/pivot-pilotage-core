package fr.pivot.pilotage.dashboard;

/**
 * Tension level of a rendered dashboard indicator (US23.2.2, AC2 — "des alertes correspondantes
 * sont déclenchées et affichées sur les widgets concernés"). Deliberately not a raw color/boolean:
 * the A11y AC requires alerts to never be conveyed by color alone ("icône/texte associé... annoncées
 * par lecteur d'écran") — a named enum plus {@link DashboardIndicatorView#alertLabel()} gives
 * {@code pivot-pilotage-ui} both a stable semantic value (icon selection, {@code aria-live}
 * severity) and caller-facing text, never color as the sole signal.
 */
public enum AlertLevel {

    /** No tension detected (or no alert-capable data source wired yet). */
    NONE,

    /** Moderate tension — worth the user's attention, not yet critical. */
    WARNING,

    /** Severe tension — requires prompt attention. */
    CRITICAL
}
