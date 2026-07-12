package fr.pivot.pilotage.portfolio;

import fr.pivot.pilotage.consolidation.ProjectNotFoundException;
import fr.pivot.pilotage.weather.ProjectWeather;
import fr.pivot.pilotage.weather.ProjectWeatherIndeterminateReason;
import fr.pivot.pilotage.weather.ProjectWeatherService;
import fr.pivot.pilotage.weather.ProjectWeatherStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WeatherProjectHealthProvider} (US23.2.1) — the adapter from US23.2.4's
 * {@link ProjectWeatherService} to this US's {@link ProjectHealthProvider} SPI, with the
 * dependency mocked. Covers the full {@link ProjectWeatherStatus} → {@link ProjectHealthStatus}
 * mapping, the {@code INDETERMINATE} → {@link Optional#empty()} fall-through (error AC), and the
 * defensive {@link ProjectNotFoundException} → {@link Optional#empty()} path.
 */
@ExtendWith(MockitoExtension.class)
class WeatherProjectHealthProviderTest {

    private static final long TENANT = 7L;
    private static final long PROJECT = 10L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 1, 6);

    @Mock
    private ProjectWeatherService projectWeatherService;

    private ProjectWeather weather(final ProjectWeatherStatus status) {
        return new ProjectWeather(PROJECT, TENANT, status, BigDecimal.valueOf(50), BigDecimal.valueOf(50),
                BigDecimal.ZERO, AS_OF, null);
    }

    // -------- AC: santé — SUNNY/CLOUDY/STORMY map to ON_TRACK/AT_RISK/CRITICAL --------------------

    @Test
    void healthOf_sunnyWeather_mapsToOnTrack() {
        when(projectWeatherService.computeWeather(TENANT, PROJECT)).thenReturn(weather(ProjectWeatherStatus.SUNNY));

        final WeatherProjectHealthProvider provider = new WeatherProjectHealthProvider(projectWeatherService);

        assertThat(provider.healthOf(TENANT, PROJECT))
                .contains(new ProjectHealthIndicator(ProjectHealthStatus.ON_TRACK));
    }

    @Test
    void healthOf_cloudyWeather_mapsToAtRisk() {
        when(projectWeatherService.computeWeather(TENANT, PROJECT)).thenReturn(weather(ProjectWeatherStatus.CLOUDY));

        final WeatherProjectHealthProvider provider = new WeatherProjectHealthProvider(projectWeatherService);

        assertThat(provider.healthOf(TENANT, PROJECT))
                .contains(new ProjectHealthIndicator(ProjectHealthStatus.AT_RISK));
    }

    @Test
    void healthOf_stormyWeather_mapsToCritical() {
        when(projectWeatherService.computeWeather(TENANT, PROJECT)).thenReturn(weather(ProjectWeatherStatus.STORMY));

        final WeatherProjectHealthProvider provider = new WeatherProjectHealthProvider(projectWeatherService);

        assertThat(provider.healthOf(TENANT, PROJECT))
                .contains(new ProjectHealthIndicator(ProjectHealthStatus.CRITICAL));
    }

    // -------- Error AC: INDETERMINATE weather falls through to the explicit NOT_SET, never --------
    // -------- a diverging "no data" vocabulary of its own ------------------------------------------

    @Test
    void healthOf_indeterminateWeather_isEmpty() {
        final ProjectWeather indeterminate = new ProjectWeather(PROJECT, TENANT,
                ProjectWeatherStatus.INDETERMINATE, null, null, null, null,
                ProjectWeatherIndeterminateReason.MISSING_STATUS_DATE);
        when(projectWeatherService.computeWeather(TENANT, PROJECT)).thenReturn(indeterminate);

        final WeatherProjectHealthProvider provider = new WeatherProjectHealthProvider(projectWeatherService);

        assertThat(provider.healthOf(TENANT, PROJECT)).isEmpty();
    }

    // -------- Defensive: an unresolvable project never propagates, just contributes nothing --------

    @Test
    void healthOf_projectNotFound_isEmpty() {
        when(projectWeatherService.computeWeather(TENANT, PROJECT))
                .thenThrow(new ProjectNotFoundException(PROJECT, TENANT));

        final WeatherProjectHealthProvider provider = new WeatherProjectHealthProvider(projectWeatherService);

        assertThat(provider.healthOf(TENANT, PROJECT)).isEmpty();
    }
}
