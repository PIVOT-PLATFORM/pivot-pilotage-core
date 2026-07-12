package fr.pivot.pilotage.weather;

/**
 * Minimal, <strong>local</strong> role placeholder used only to gate modification of the weather
 * calculation rules (US23.2.4 security AC). It is deliberately narrow in scope — not a
 * general-purpose platform role model.
 *
 * <p><strong>Temporary — same "gap" pattern already used elsewhere in this repo</strong> (cf.
 * {@code tenantId} taken as an explicit argument instead of a {@code TenantContext}, CLAUDE.md
 * §gap, {@code TODO-SETUP.md} §5): the real role/permission model is owned by {@code pivot-core}
 * and will arrive through {@code fr.pivot:pivot-core-starter} once that artifact is consumable.
 * Once available, a real role type (or a mapping from it) replaces this enum without changing
 * {@link WeatherRuleAuthorization}'s contract.
 */
public enum WeatherRuleRole {

    /** A project's own manager — not authorized to change the homogeneous calculation rules. */
    PROJECT_MANAGER,

    /** Portfolio Management Office — authorized to change the calculation rules. */
    PMO,

    /** Portfolio administrator — authorized to change the calculation rules. */
    PORTFOLIO_ADMIN
}
