package fr.pivot.pilotage.dashboard;

import fr.pivot.pilotage.consolidation.ApplicationConsolidation;
import fr.pivot.pilotage.consolidation.ApplicationConsolidationService;
import fr.pivot.pilotage.consolidation.ApplicationMilestone;
import fr.pivot.pilotage.consolidation.ApplicationNotFoundException;
import fr.pivot.pilotage.consolidation.ProjectPlanningStatus;
import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardService} with mocked collaborators (US23.2.2). Covers every AC:
 * rendering a persisted layout (AC1), the "no dashboard yet" default (AC1/AC3 corollary), tension
 * overlays (AC2), full-layout persistence (AC3), the "indisponible" state, save-time widget/config
 * validation ("widget inconnu, disposition hors bornes"), and the cross-user non-disclosure
 * property (security AC).
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final long TENANT = 7L;
    private static final long TEAM = 5L;
    private static final long USER = 42L;
    private static final long APPLICATION = 100L;

    @Mock private DashboardConfigRepository dashboardConfigRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private ApplicationConsolidationService consolidationService;

    private static void setId(final Object entity, final long id) {
        try {
            final Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Application application() {
        final Application app = new Application(TENANT, TEAM, "Billing", java.time.Instant.EPOCH);
        setId(app, APPLICATION);
        return app;
    }

    private DashboardService serviceWith(final PortfolioIndicatorSource... sources) {
        return new DashboardService(dashboardConfigRepository, applicationRepository, consolidationService,
                List.of(sources));
    }

    private ApplicationConsolidation consolidationOf(final int scheduled, final int planned, final int empty,
            final List<ApplicationMilestone> milestones) {
        final Map<ProjectPlanningStatus, Integer> byStatus = new EnumMap<>(ProjectPlanningStatus.class);
        byStatus.put(ProjectPlanningStatus.SCHEDULED, scheduled);
        byStatus.put(ProjectPlanningStatus.PLANNED, planned);
        byStatus.put(ProjectPlanningStatus.EMPTY, empty);
        return new ApplicationConsolidation(APPLICATION, "Billing", TENANT, scheduled + planned + empty, byStatus,
                null, null, milestones, List.of());
    }

    // -------- AC1/AC3: no dashboard configured yet → fresh default, never 404 --------------------

    @Test
    void getDashboard_noConfigYet_returnsFreshDefault() {
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.empty());

        final DashboardResponse response = serviceWith().getDashboard(TENANT, TEAM, USER);

        assertThat(response.userId()).isEqualTo(USER);
        assertThat(response.profile()).isNull();
        assertThat(response.viewMode()).isEqualTo(DashboardViewMode.SYNTHETIC);
        assertThat(response.widgets()).isEmpty();
        assertThat(response.updatedAt()).isNull();
    }

    // -------- Security: a different userId never observes another user's real config -------------

    @Test
    void getDashboard_differentUserId_neverQueriesAnotherUsersRow() {
        // Only USER's row exists; a request scoped to a different id must not even attempt to read it.
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, 999L))
                .thenReturn(Optional.empty());

        final DashboardResponse response = serviceWith().getDashboard(TENANT, TEAM, 999L);

        assertThat(response.widgets()).isEmpty();
        verify(dashboardConfigRepository, never()).findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER);
    }

    // -------- AC1: persisted layout renders with its widgets, sorted by position ------------------

    @Test
    void getDashboard_persistedConfig_rendersWidgetsSortedByPosition() {
        final DashboardConfig config =
                new DashboardConfig(TENANT, TEAM, USER, "PMO", DashboardViewMode.DETAILED);
        final DashboardWidget second = new DashboardWidget(TENANT, TEAM, APPLICATION,
                DashboardWidgetType.STRATEGIC_MILESTONES, 1, 0, 0, 1, 1);
        setId(second, 2L);
        final DashboardWidget first = new DashboardWidget(TENANT, TEAM, APPLICATION,
                DashboardWidgetType.PORTFOLIO_STATUS_SUMMARY, 0, 0, 0, 1, 1);
        setId(first, 1L);
        config.replaceWidgets("PMO", DashboardViewMode.DETAILED, List.of(second, first));
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.of(config));
        when(consolidationService.consolidate(TENANT, APPLICATION))
                .thenReturn(consolidationOf(0, 0, 0, List.of()));

        final DashboardResponse response = serviceWith().getDashboard(TENANT, TEAM, USER);

        assertThat(response.profile()).isEqualTo("PMO");
        assertThat(response.viewMode()).isEqualTo(DashboardViewMode.DETAILED);
        assertThat(response.widgets()).extracting(DashboardWidgetResponse::widgetType)
                .containsExactly(DashboardWidgetType.PORTFOLIO_STATUS_SUMMARY,
                        DashboardWidgetType.STRATEGIC_MILESTONES);
    }

    // -------- PORTFOLIO_STATUS_SUMMARY: available, with counts and no tension by default ----------

    @Test
    void render_statusSummary_availableWithProjectCounts() {
        final DashboardConfig config = configWithOneWidget(DashboardWidgetType.PORTFOLIO_STATUS_SUMMARY);
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.of(config));
        when(consolidationService.consolidate(TENANT, APPLICATION))
                .thenReturn(consolidationOf(2, 1, 0, List.of()));

        final DashboardWidgetResponse widget = serviceWith().getDashboard(TENANT, TEAM, USER).widgets().get(0);

        assertThat(widget.indicator().status()).isEqualTo(IndicatorStatus.AVAILABLE);
        assertThat(widget.indicator().alertLevel()).isEqualTo(AlertLevel.NONE);
        assertThat(widget.indicator().projectCount()).isEqualTo(3);
        assertThat(widget.indicator().projectsByStatus())
                .containsEntry("SCHEDULED", 2)
                .containsEntry("PLANNED", 1)
                .containsEntry("EMPTY", 0);
    }

    // -------- Error AC: unresolvable application → widget renders as "indisponible" ---------------

    @Test
    void render_statusSummary_unknownApplication_yieldsUnavailableNotAnError() {
        final DashboardConfig config = configWithOneWidget(DashboardWidgetType.PORTFOLIO_STATUS_SUMMARY);
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.of(config));
        when(consolidationService.consolidate(TENANT, APPLICATION))
                .thenThrow(new ApplicationNotFoundException(APPLICATION, TENANT));

        final DashboardWidgetResponse widget = serviceWith().getDashboard(TENANT, TEAM, USER).widgets().get(0);

        assertThat(widget.indicator().status()).isEqualTo(IndicatorStatus.UNAVAILABLE);
        assertThat(widget.indicator().projectCount()).isNull();
    }

    // -------- AC2: a tension source overlays PROGRESS on the status-summary widget -----------------

    @Test
    void render_statusSummary_tensionOverlayFromIndicatorSource() {
        final DashboardConfig config = configWithOneWidget(DashboardWidgetType.PORTFOLIO_STATUS_SUMMARY);
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.of(config));
        when(consolidationService.consolidate(TENANT, APPLICATION))
                .thenReturn(consolidationOf(0, 1, 0, List.of()));
        final PortfolioIndicatorSource tension = (tenantId, applicationId, kind) ->
                kind == PortfolioIndicatorKind.PROGRESS
                        ? Optional.of(new PortfolioIndicatorSnapshot(AlertLevel.WARNING, "Retard cumulé"))
                        : Optional.empty();

        final DashboardWidgetResponse widget =
                serviceWith(tension).getDashboard(TENANT, TEAM, USER).widgets().get(0);

        assertThat(widget.indicator().alertLevel()).isEqualTo(AlertLevel.WARNING);
        assertThat(widget.indicator().alertLabel()).isEqualTo("Retard cumulé");
    }

    // -------- STRATEGIC_MILESTONES: available with the consolidation's milestones ------------------

    @Test
    void render_strategicMilestones_availableWithMilestones() {
        final DashboardConfig config = configWithOneWidget(DashboardWidgetType.STRATEGIC_MILESTONES);
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.of(config));
        final ApplicationMilestone milestone =
                new ApplicationMilestone(1L, 10L, "Go-live", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        when(consolidationService.consolidate(TENANT, APPLICATION))
                .thenReturn(consolidationOf(0, 0, 0, List.of(milestone)));

        final DashboardWidgetResponse widget = serviceWith().getDashboard(TENANT, TEAM, USER).widgets().get(0);

        assertThat(widget.indicator().status()).isEqualTo(IndicatorStatus.AVAILABLE);
        assertThat(widget.indicator().milestones()).hasSize(1);
        assertThat(widget.indicator().milestones().get(0).name()).isEqualTo("Go-live");
        assertThat(widget.indicator().milestones().get(0).projectId()).isEqualTo(10L);
    }

    @Test
    void render_strategicMilestones_unknownApplication_yieldsUnavailable() {
        final DashboardConfig config = configWithOneWidget(DashboardWidgetType.STRATEGIC_MILESTONES);
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.of(config));
        when(consolidationService.consolidate(TENANT, APPLICATION))
                .thenThrow(new ApplicationNotFoundException(APPLICATION, TENANT));

        final DashboardWidgetResponse widget = serviceWith().getDashboard(TENANT, TEAM, USER).widgets().get(0);

        assertThat(widget.indicator().status()).isEqualTo(IndicatorStatus.UNAVAILABLE);
    }

    // -------- WEATHER_ALERTS: unavailable by default (no real source wired) ------------------------

    @Test
    void render_weatherAlerts_noSourceWired_isUnavailable() {
        final DashboardConfig config = configWithOneWidget(DashboardWidgetType.WEATHER_ALERTS);
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.of(config));

        final DashboardWidgetResponse widget =
                serviceWith(new NoOpPortfolioIndicatorSource()).getDashboard(TENANT, TEAM, USER).widgets().get(0);

        assertThat(widget.indicator().status()).isEqualTo(IndicatorStatus.UNAVAILABLE);
    }

    // -------- AC2: WEATHER_ALERTS renders a real source's tension when one is wired -----------------

    @Test
    void render_weatherAlerts_realSourceWired_rendersCriticalTension() {
        final DashboardConfig config = configWithOneWidget(DashboardWidgetType.WEATHER_ALERTS);
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.of(config));
        final PortfolioIndicatorSource weather = (tenantId, applicationId, kind) ->
                kind == PortfolioIndicatorKind.WEATHER
                        ? Optional.of(new PortfolioIndicatorSnapshot(AlertLevel.CRITICAL, "Surcharge équipe"))
                        : Optional.empty();

        final DashboardWidgetResponse widget =
                serviceWith(weather).getDashboard(TENANT, TEAM, USER).widgets().get(0);

        assertThat(widget.indicator().status()).isEqualTo(IndicatorStatus.AVAILABLE);
        assertThat(widget.indicator().alertLevel()).isEqualTo(AlertLevel.CRITICAL);
        assertThat(widget.indicator().alertLabel()).isEqualTo("Surcharge équipe");
    }

    @Test
    void resolveIndicator_firstNonEmptySourceWins() {
        final DashboardConfig config = configWithOneWidget(DashboardWidgetType.WEATHER_ALERTS);
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.of(config));
        final PortfolioIndicatorSource empty = (t, a, k) -> Optional.empty();
        final PortfolioIndicatorSource second =
                (t, a, k) -> Optional.of(new PortfolioIndicatorSnapshot(AlertLevel.WARNING, "second"));

        final DashboardWidgetResponse widget =
                serviceWith(empty, second).getDashboard(TENANT, TEAM, USER).widgets().get(0);

        assertThat(widget.indicator().alertLabel()).isEqualTo("second");
    }

    // -------- AC3 error: missing view mode → 400-mapped exception, nothing persisted ---------------

    @Test
    void saveDashboard_missingViewMode_throwsAndNeverPersists() {
        final SaveDashboardRequest request = new SaveDashboardRequest("PMO", null, List.of());

        assertThatThrownBy(() -> serviceWith().saveDashboard(TENANT, TEAM, USER, request))
                .isInstanceOf(InvalidDashboardConfigException.class)
                .extracting(e -> ((InvalidDashboardConfigException) e).code())
                .isEqualTo("VIEW_MODE_REQUIRED");

        verify(dashboardConfigRepository, never()).save(any());
    }

    // -------- AC3 error: unknown/missing widget type -------------------------------------------

    @Test
    void saveDashboard_widgetMissingType_throwsAndNeverPersists() {
        final SaveDashboardWidgetRequest widget =
                new SaveDashboardWidgetRequest(null, APPLICATION, 0, 0, 0, 1, 1);
        final SaveDashboardRequest request =
                new SaveDashboardRequest("PMO", DashboardViewMode.SYNTHETIC, List.of(widget));

        assertThatThrownBy(() -> serviceWith().saveDashboard(TENANT, TEAM, USER, request))
                .isInstanceOf(InvalidDashboardWidgetException.class)
                .extracting(e -> ((InvalidDashboardWidgetException) e).code())
                .isEqualTo("WIDGET_TYPE_REQUIRED");

        verify(dashboardConfigRepository, never()).save(any());
    }

    // -------- AC3 error: missing target application ----------------------------------------------

    @Test
    void saveDashboard_widgetMissingApplication_throws() {
        final SaveDashboardWidgetRequest widget = new SaveDashboardWidgetRequest(
                DashboardWidgetType.WEATHER_ALERTS, null, 0, 0, 0, 1, 1);
        final SaveDashboardRequest request =
                new SaveDashboardRequest("PMO", DashboardViewMode.SYNTHETIC, List.of(widget));

        assertThatThrownBy(() -> serviceWith().saveDashboard(TENANT, TEAM, USER, request))
                .isInstanceOf(InvalidDashboardWidgetException.class)
                .extracting(e -> ((InvalidDashboardWidgetException) e).code())
                .isEqualTo("WIDGET_APPLICATION_REQUIRED");
    }

    // -------- AC3 error: unknown/cross-tenant application ------------------------------------------

    @Test
    void saveDashboard_widgetUnknownApplication_throws() {
        when(applicationRepository.findByIdAndTenantIdAndTeamId(APPLICATION, TENANT, TEAM))
                .thenReturn(Optional.empty());
        final SaveDashboardWidgetRequest widget = new SaveDashboardWidgetRequest(
                DashboardWidgetType.WEATHER_ALERTS, APPLICATION, 0, 0, 0, 1, 1);
        final SaveDashboardRequest request =
                new SaveDashboardRequest("PMO", DashboardViewMode.SYNTHETIC, List.of(widget));

        assertThatThrownBy(() -> serviceWith().saveDashboard(TENANT, TEAM, USER, request))
                .isInstanceOf(InvalidDashboardWidgetException.class)
                .extracting(e -> ((InvalidDashboardWidgetException) e).code())
                .isEqualTo("WIDGET_APPLICATION_NOT_FOUND");

        verify(dashboardConfigRepository, never()).save(any());
    }

    // -------- AC3 error: disposition out of bounds --------------------------------------------------

    @Test
    void saveDashboard_widgetGridColumnOutOfBounds_throws() {
        assertOutOfBounds(new SaveDashboardWidgetRequest(DashboardWidgetType.WEATHER_ALERTS, APPLICATION, 0, 0, 4, 1, 1));
    }

    @Test
    void saveDashboard_widgetGridWidthOutOfBounds_throws() {
        assertOutOfBounds(new SaveDashboardWidgetRequest(DashboardWidgetType.WEATHER_ALERTS, APPLICATION, 0, 0, 0, 5, 1));
    }

    @Test
    void saveDashboard_widgetGridColumnPlusWidthExceedsGrid_throws() {
        assertOutOfBounds(new SaveDashboardWidgetRequest(DashboardWidgetType.WEATHER_ALERTS, APPLICATION, 0, 0, 3, 2, 1));
    }

    @Test
    void saveDashboard_widgetGridHeightZero_throws() {
        assertOutOfBounds(new SaveDashboardWidgetRequest(DashboardWidgetType.WEATHER_ALERTS, APPLICATION, 0, 0, 0, 1, 0));
    }

    @Test
    void saveDashboard_widgetNegativePosition_throws() {
        assertOutOfBounds(
                new SaveDashboardWidgetRequest(DashboardWidgetType.WEATHER_ALERTS, APPLICATION, -1, 0, 0, 1, 1));
    }

    private void assertOutOfBounds(final SaveDashboardWidgetRequest widget) {
        final SaveDashboardRequest request =
                new SaveDashboardRequest("PMO", DashboardViewMode.SYNTHETIC, List.of(widget));

        assertThatThrownBy(() -> serviceWith().saveDashboard(TENANT, TEAM, USER, request))
                .isInstanceOf(InvalidDashboardWidgetException.class)
                .extracting(e -> ((InvalidDashboardWidgetException) e).code())
                .isEqualTo("WIDGET_DISPOSITION_OUT_OF_BOUNDS");

        verify(dashboardConfigRepository, never()).save(any());
    }

    // -------- AC3 success: creates a new dashboard, persists and returns it rendered ---------------

    @Test
    void saveDashboard_newDashboard_persistsAndReturnsRendered() {
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.empty());
        when(applicationRepository.findByIdAndTenantIdAndTeamId(APPLICATION, TENANT, TEAM))
                .thenReturn(Optional.of(application()));
        when(consolidationService.consolidate(TENANT, APPLICATION))
                .thenReturn(consolidationOf(0, 0, 0, List.of()));
        when(dashboardConfigRepository.save(any()))
                .thenAnswer(invocation -> simulateJpaSave(invocation.getArgument(0)));
        final SaveDashboardWidgetRequest widgetRequest = new SaveDashboardWidgetRequest(
                DashboardWidgetType.PORTFOLIO_STATUS_SUMMARY, APPLICATION, 0, 0, 0, 2, 1);
        final SaveDashboardRequest request =
                new SaveDashboardRequest("PMO", DashboardViewMode.SYNTHETIC, List.of(widgetRequest));

        final DashboardResponse response = serviceWith().saveDashboard(TENANT, TEAM, USER, request);

        assertThat(response.profile()).isEqualTo("PMO");
        assertThat(response.viewMode()).isEqualTo(DashboardViewMode.SYNTHETIC);
        assertThat(response.widgets()).hasSize(1);
        assertThat(response.widgets().get(0).gridWidth()).isEqualTo(2);
        verify(dashboardConfigRepository).save(any());
    }

    // -------- AC3 success: saving again fully replaces the previous widget list --------------------

    @Test
    void saveDashboard_existingDashboard_replacesWidgetsEntirely() {
        final DashboardConfig existing =
                new DashboardConfig(TENANT, TEAM, USER, "PMO", DashboardViewMode.SYNTHETIC);
        existing.replaceWidgets("PMO", DashboardViewMode.SYNTHETIC, List.of(
                new DashboardWidget(TENANT, TEAM, APPLICATION, DashboardWidgetType.WEATHER_ALERTS, 0, 0, 0, 1, 1)));
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.of(existing));
        when(applicationRepository.findByIdAndTenantIdAndTeamId(APPLICATION, TENANT, TEAM))
                .thenReturn(Optional.of(application()));
        when(consolidationService.consolidate(TENANT, APPLICATION))
                .thenReturn(consolidationOf(0, 0, 0, List.of()));
        when(dashboardConfigRepository.save(any()))
                .thenAnswer(invocation -> simulateJpaSave(invocation.getArgument(0)));
        final SaveDashboardWidgetRequest widgetRequest = new SaveDashboardWidgetRequest(
                DashboardWidgetType.STRATEGIC_MILESTONES, APPLICATION, 0, 0, 0, 1, 1);
        final SaveDashboardRequest request =
                new SaveDashboardRequest("Sponsor", DashboardViewMode.DETAILED, List.of(widgetRequest));

        final DashboardResponse response = serviceWith().saveDashboard(TENANT, TEAM, USER, request);

        assertThat(response.profile()).isEqualTo("Sponsor");
        assertThat(response.viewMode()).isEqualTo(DashboardViewMode.DETAILED);
        assertThat(response.widgets()).extracting(DashboardWidgetResponse::widgetType)
                .containsExactly(DashboardWidgetType.STRATEGIC_MILESTONES);
    }

    // -------- AC3: a null widgets list clears the dashboard rather than erroring -------------------

    @Test
    void saveDashboard_nullWidgetsList_treatedAsEmptyList() {
        when(dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(TENANT, TEAM, USER))
                .thenReturn(Optional.empty());
        when(dashboardConfigRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        final SaveDashboardRequest request = new SaveDashboardRequest("PMO", DashboardViewMode.SYNTHETIC, null);

        final DashboardResponse response = serviceWith().saveDashboard(TENANT, TEAM, USER, request);

        assertThat(response.widgets()).isEmpty();
        verify(applicationRepository, never()).findByIdAndTenantIdAndTeamId(anyLong(), anyLong(), anyLong());
    }

    // -------- helper ---------------------------------------------------------------------------

    private DashboardConfig configWithOneWidget(final DashboardWidgetType type) {
        final DashboardConfig config =
                new DashboardConfig(TENANT, TEAM, USER, "PMO", DashboardViewMode.SYNTHETIC);
        final DashboardWidget widget = new DashboardWidget(TENANT, TEAM, APPLICATION, type, 0, 0, 0, 1, 1);
        setId(widget, 1L);
        config.replaceWidgets("PMO", DashboardViewMode.SYNTHETIC, List.of(widget));
        return config;
    }

    /**
     * Simulates the id assignment a real JPA {@code save()} performs (IDENTITY columns are
     * populated immediately on insert) — {@link DashboardConfigRepository#save} is mocked in these
     * unit tests, so nothing actually assigns ids; without this, {@link DashboardService#renderWidget}
     * would unbox a {@code null} {@code Long} into the response's primitive {@code long id}.
     */
    private static DashboardConfig simulateJpaSave(final DashboardConfig config) {
        if (config.getId() == null) {
            setId(config, 1L);
        }
        long nextWidgetId = 1L;
        for (final DashboardWidget widget : config.getWidgets()) {
            if (widget.getId() == null) {
                setId(widget, nextWidgetId++);
            }
        }
        return config;
    }
}
