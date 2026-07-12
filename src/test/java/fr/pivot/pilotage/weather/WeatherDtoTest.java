package fr.pivot.pilotage.weather;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the weather DTOs (US23.2.4): the {@link ProjectWeather} null-status guard and
 * the A11y contract of {@link ProjectWeatherStatus} — every constant carries both a non-blank
 * label and icon token, never color alone (RGAA 4 / WCAG 2.1 AA).
 */
class WeatherDtoTest {

    @Test
    void projectWeather_rejectsNullStatus() {
        assertThatThrownBy(() -> new ProjectWeather(1L, 7L, null, null, null, null,
                LocalDate.of(2024, 1, 1), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void projectWeather_acceptsIndeterminateWithNullMetrics() {
        final ProjectWeather weather = new ProjectWeather(1L, 7L, ProjectWeatherStatus.INDETERMINATE,
                null, null, null, null, ProjectWeatherIndeterminateReason.MISSING_STATUS_DATE);

        assertThat(weather.status()).isEqualTo(ProjectWeatherStatus.INDETERMINATE);
        assertThat(weather.actualProgressPercent()).isNull();
        assertThat(weather.asOfDate()).isNull();
    }

    @Test
    void everyStatus_carriesNonBlankLabelAndIcon_neverColorAlone() {
        for (final ProjectWeatherStatus status : ProjectWeatherStatus.values()) {
            assertThat(status.label()).as("label of %s", status).isNotBlank();
            assertThat(status.icon()).as("icon of %s", status).isNotBlank();
        }
    }

    @Test
    void statusLabelsAndIcons_areDistinctAcrossStatuses() {
        final ProjectWeatherStatus[] statuses = ProjectWeatherStatus.values();
        final long distinctLabels =
                java.util.Arrays.stream(statuses).map(ProjectWeatherStatus::label).distinct().count();
        final long distinctIcons =
                java.util.Arrays.stream(statuses).map(ProjectWeatherStatus::icon).distinct().count();

        assertThat(distinctLabels).isEqualTo(statuses.length);
        assertThat(distinctIcons).isEqualTo(statuses.length);
    }
}
