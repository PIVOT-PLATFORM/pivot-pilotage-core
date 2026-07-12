package fr.pivot.pilotage.dashboard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the small DTO/record types of US23.2.2 not otherwise exercised by
 * {@link DashboardServiceTest}: {@link DashboardIndicatorView#unavailable()}'s shape and
 * {@link PortfolioIndicatorSnapshot}'s validation (Error AC: an indicator without data is an
 * explicit state, never a bare null/blank).
 */
class DashboardDtoTest {

    @Test
    void indicatorView_unavailable_hasNoAlertAndNoPayload() {
        final DashboardIndicatorView view = DashboardIndicatorView.unavailable();

        assertThat(view.status()).isEqualTo(IndicatorStatus.UNAVAILABLE);
        assertThat(view.alertLevel()).isEqualTo(AlertLevel.NONE);
        assertThat(view.alertLabel()).isNull();
        assertThat(view.projectCount()).isNull();
        assertThat(view.projectsByStatus()).isNull();
        assertThat(view.milestones()).isNull();
    }

    @Test
    void indicatorSnapshot_rejectsBlankLabel() {
        assertThatThrownBy(() -> new PortfolioIndicatorSnapshot(AlertLevel.WARNING, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indicatorSnapshot_rejectsNullLevelOrLabel() {
        assertThatThrownBy(() -> new PortfolioIndicatorSnapshot(null, "Retard"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PortfolioIndicatorSnapshot(AlertLevel.WARNING, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void indicatorSnapshot_acceptsValidValues() {
        final PortfolioIndicatorSnapshot snapshot =
                new PortfolioIndicatorSnapshot(AlertLevel.CRITICAL, "Retard sur jalon stratégique");

        assertThat(snapshot.level()).isEqualTo(AlertLevel.CRITICAL);
        assertThat(snapshot.label()).isEqualTo("Retard sur jalon stratégique");
    }

    @Test
    void noOpIndicatorSource_alwaysReportsEmpty() {
        final NoOpPortfolioIndicatorSource source = new NoOpPortfolioIndicatorSource();

        assertThat(source.indicatorFor(1L, 2L, PortfolioIndicatorKind.PROGRESS)).isEmpty();
        assertThat(source.indicatorFor(1L, 2L, PortfolioIndicatorKind.WEATHER)).isEmpty();
    }

    @Test
    void apiError_carriesCodeAndMessage() {
        final ApiError error = new ApiError("VIEW_MODE_REQUIRED", "A view mode is required");

        assertThat(error.code()).isEqualTo("VIEW_MODE_REQUIRED");
        assertThat(error.message()).isEqualTo("A view mode is required");
    }

    @Test
    void invalidDashboardConfigException_carriesCode() {
        final InvalidDashboardConfigException ex =
                new InvalidDashboardConfigException("VIEW_MODE_REQUIRED", "required");

        assertThat(ex.code()).isEqualTo("VIEW_MODE_REQUIRED");
        assertThat(ex.getMessage()).isEqualTo("required");
    }

    @Test
    void invalidDashboardWidgetException_carriesCode() {
        final InvalidDashboardWidgetException ex =
                new InvalidDashboardWidgetException("WIDGET_TYPE_REQUIRED", "required");

        assertThat(ex.code()).isEqualTo("WIDGET_TYPE_REQUIRED");
        assertThat(ex.getMessage()).isEqualTo("required");
    }
}
