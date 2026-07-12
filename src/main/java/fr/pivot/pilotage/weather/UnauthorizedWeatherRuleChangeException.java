package fr.pivot.pilotage.weather;

/**
 * Thrown by {@link WeatherRuleAuthorization#assertCanModifyRules(WeatherRuleRole)} when the
 * requesting role is not authorized to change the weather calculation rules (US23.2.4 security
 * AC — only PMO/portfolio admin may do so).
 *
 * <p>Maps to HTTP 403 at the future controller layer, once {@code pivot-core-starter} publishes
 * real role information ({@code TODO-SETUP.md} §5) — the same deferred-controller pattern already
 * used by {@code fr.pivot.pilotage.consolidation.ApplicationNotFoundException} (→ 404).
 */
public class UnauthorizedWeatherRuleChangeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for an unauthorized role.
     *
     * @param role the requesting role that was refused
     */
    public UnauthorizedWeatherRuleChangeException(final WeatherRuleRole role) {
        super("Role " + role + " is not authorized to modify the weather calculation rules");
    }
}
