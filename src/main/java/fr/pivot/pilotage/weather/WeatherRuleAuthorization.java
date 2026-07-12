package fr.pivot.pilotage.weather;

import org.springframework.stereotype.Service;

/**
 * Guards modification of the weather calculation rules (US23.2.4 security AC) — only {@link
 * WeatherRuleRole#PMO} or {@link WeatherRuleRole#PORTFOLIO_ADMIN} may change them; a project
 * manager alone must be refused.
 *
 * <p><strong>Scope note (Gate 1 self-challenge).</strong> This US keeps the calculation
 * thresholds fixed, homogeneous Java constants in {@link ProjectWeatherService} — no
 * tenant/organization customization is persisted or exposed here (backlog "Hors périmètre"). This
 * guard is therefore the seam a future rule-administration capability (once {@code
 * pivot-core-starter} publishes real roles) plugs into, exercised directly here so the AC is
 * genuinely tested now rather than left unimplemented (CLAUDE.md: "AC sans test = non
 * implémenté").
 */
@Service
public class WeatherRuleAuthorization {

    /**
     * Asserts the given role may modify the weather calculation rules.
     *
     * @param requesterRole the role attempting the change
     * @throws UnauthorizedWeatherRuleChangeException if {@code requesterRole} is neither {@link
     *                                                 WeatherRuleRole#PMO} nor {@link
     *                                                 WeatherRuleRole#PORTFOLIO_ADMIN}
     */
    public void assertCanModifyRules(final WeatherRuleRole requesterRole) {
        if (requesterRole != WeatherRuleRole.PMO && requesterRole != WeatherRuleRole.PORTFOLIO_ADMIN) {
            throw new UnauthorizedWeatherRuleChangeException(requesterRole);
        }
    }
}
