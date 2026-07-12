package fr.pivot.pilotage.dashboard;

/**
 * Rendering mode of a persisted dashboard (US23.2.2, AC "une vue synthétique ou détaillée adaptée
 * au profil s'affiche"). Chosen and persisted by the user alongside their widget layout — this
 * module does not derive it automatically from a role/profile (see {@link DashboardConfig}
 * JavaDoc, "profile" rationale): the user picks the mode that matches how they use their own
 * dashboard.
 */
public enum DashboardViewMode {

    /** Compact, high-level rendering — key indicators only. */
    SYNTHETIC,

    /** Expanded rendering — full widget detail. */
    DETAILED
}
