package fr.pivot.pilotage.weather;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WeatherRuleAuthorization} (US23.2.4 security AC): only PMO/portfolio
 * admin may modify the weather calculation rules; a project manager alone must be refused.
 */
class WeatherRuleAuthorizationTest {

    private final WeatherRuleAuthorization authorization = new WeatherRuleAuthorization();

    @Test
    void assertCanModifyRules_pmo_isAuthorized() {
        assertThatCode(() -> authorization.assertCanModifyRules(WeatherRuleRole.PMO)).doesNotThrowAnyException();
    }

    @Test
    void assertCanModifyRules_portfolioAdmin_isAuthorized() {
        assertThatCode(() -> authorization.assertCanModifyRules(WeatherRuleRole.PORTFOLIO_ADMIN))
                .doesNotThrowAnyException();
    }

    @Test
    void assertCanModifyRules_projectManagerAlone_isRefused() {
        assertThatThrownBy(() -> authorization.assertCanModifyRules(WeatherRuleRole.PROJECT_MANAGER))
                .isInstanceOf(UnauthorizedWeatherRuleChangeException.class)
                .hasMessageContaining("PROJECT_MANAGER");
    }
}
