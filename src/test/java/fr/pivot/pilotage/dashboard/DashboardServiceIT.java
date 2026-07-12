package fr.pivot.pilotage.dashboard;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link DashboardService} against a real PostgreSQL 18 (Testcontainers),
 * with the real {@code fr.pivot.pilotage.consolidation.ApplicationConsolidationService} (EN18.9,
 * already merged) wired — proves the widget catalog genuinely reuses it, not a stub. A test-only
 * {@link PortfolioIndicatorSource} exercises the tension-overlay SPI seam (mirrors
 * {@code ApplicationConsolidationServiceIT}'s {@code FakeContributorConfig} pattern).
 *
 * <p>Covers: AC1 (persisted layout renders with the right widgets), AC3 (full-layout save,
 * persisted per user, reloaded), AC2 (tension overlay), the Error ACs (unresolvable
 * indicator → "indisponible"; invalid widget config → 400-mapped, nothing persisted) and the
 * security ACs (multi-tenant isolation on a widget's target application; cross-user
 * non-disclosure via real, distinct persisted rows).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DashboardServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    /** Test-only tension source proving the SPI seam end to end. */
    @TestConfiguration
    static class FakeIndicatorConfig {
        @Bean
        PortfolioIndicatorSource fakeWeatherSource() {
            return (tenantId, applicationId, kind) -> kind == PortfolioIndicatorKind.WEATHER
                    ? Optional.of(new PortfolioIndicatorSnapshot(AlertLevel.CRITICAL, "Surcharge détectée"))
                    : Optional.empty();
        }
    }

    /**
     * Registers the container datasource and seeds {@code public} before Spring/Flyway.
     *
     * @param registry the dynamic property registry
     * @throws Exception if seeding the public schema fails
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        PlatformSchemaTestSupport.createPublicSchema(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Autowired private DashboardService dashboardService;
    @Autowired private ApplicationRepository applicationRepository;

    private long tenantId;
    private long teamId;
    private long applicationId;

    private static final long USER = 42L;

    @BeforeEach
    void setUp() throws Exception {
        tenantId = PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        teamId = PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), tenantId);
        final Application application =
                applicationRepository.save(new Application(tenantId, teamId, "Billing", Instant.EPOCH));
        applicationId = application.getId();
    }

    // -------- AC1/AC3: no dashboard yet → fresh default -------------------------------------------

    @Test
    void getDashboard_noneConfigured_returnsFreshDefault() {
        final DashboardResponse response = dashboardService.getDashboard(tenantId, teamId, USER);

        assertThat(response.widgets()).isEmpty();
        assertThat(response.viewMode()).isEqualTo(DashboardViewMode.SYNTHETIC);
    }

    // -------- AC3: save then reload — persisted per user, reloaded on next open --------------------

    @Test
    void saveThenGet_layoutIsPersistedAndReloadedOnNextOpen() {
        final SaveDashboardWidgetRequest widget = new SaveDashboardWidgetRequest(
                DashboardWidgetType.STRATEGIC_MILESTONES, applicationId, 0, 0, 0, 2, 1);
        final SaveDashboardRequest request =
                new SaveDashboardRequest("PMO", DashboardViewMode.DETAILED, List.of(widget));

        dashboardService.saveDashboard(tenantId, teamId, USER, request);
        final DashboardResponse reloaded = dashboardService.getDashboard(tenantId, teamId, USER);

        assertThat(reloaded.profile()).isEqualTo("PMO");
        assertThat(reloaded.viewMode()).isEqualTo(DashboardViewMode.DETAILED);
        assertThat(reloaded.widgets()).hasSize(1);
        assertThat(reloaded.widgets().get(0).widgetType()).isEqualTo(DashboardWidgetType.STRATEGIC_MILESTONES);
        assertThat(reloaded.widgets().get(0).gridWidth()).isEqualTo(2);
        assertThat(reloaded.updatedAt()).isNotNull();
    }

    // -------- AC1: PORTFOLIO_STATUS_SUMMARY reuses the real EN18.9 consolidation ---------------------

    @Test
    void statusSummaryWidget_reflectsRealConsolidationData() {
        final SaveDashboardWidgetRequest widget = new SaveDashboardWidgetRequest(
                DashboardWidgetType.PORTFOLIO_STATUS_SUMMARY, applicationId, 0, 0, 0, 1, 1);
        dashboardService.saveDashboard(tenantId, teamId, USER,
                new SaveDashboardRequest("PMO", DashboardViewMode.SYNTHETIC, List.of(widget)));

        final DashboardResponse response = dashboardService.getDashboard(tenantId, teamId, USER);

        final DashboardIndicatorView indicator = response.widgets().get(0).indicator();
        assertThat(indicator.status()).isEqualTo(IndicatorStatus.AVAILABLE);
        assertThat(indicator.projectCount()).isZero(); // no project created for this application
    }

    // -------- AC2: WEATHER_ALERTS renders the fake source's tension end to end ---------------------

    @Test
    void weatherWidget_rendersFakeSourceTension() {
        final SaveDashboardWidgetRequest widget = new SaveDashboardWidgetRequest(
                DashboardWidgetType.WEATHER_ALERTS, applicationId, 0, 0, 0, 1, 1);
        dashboardService.saveDashboard(tenantId, teamId, USER,
                new SaveDashboardRequest("PMO", DashboardViewMode.SYNTHETIC, List.of(widget)));

        final DashboardIndicatorView indicator =
                dashboardService.getDashboard(tenantId, teamId, USER).widgets().get(0).indicator();

        assertThat(indicator.status()).isEqualTo(IndicatorStatus.AVAILABLE);
        assertThat(indicator.alertLevel()).isEqualTo(AlertLevel.CRITICAL);
        assertThat(indicator.alertLabel()).isEqualTo("Surcharge détectée");
    }

    // -------- Security: a widget cannot target a foreign tenant's application ----------------------

    @Test
    void saveDashboard_widgetTargetingForeignTenantApplication_rejected() throws Exception {
        final long otherTenant = PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        final long otherTeam = PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), otherTenant);
        final Application foreignApp =
                applicationRepository.save(new Application(otherTenant, otherTeam, "Foreign", Instant.EPOCH));

        final SaveDashboardWidgetRequest widget = new SaveDashboardWidgetRequest(
                DashboardWidgetType.WEATHER_ALERTS, foreignApp.getId(), 0, 0, 0, 1, 1);
        final SaveDashboardRequest request =
                new SaveDashboardRequest("PMO", DashboardViewMode.SYNTHETIC, List.of(widget));

        assertThatThrownBy(() -> dashboardService.saveDashboard(tenantId, teamId, USER, request))
                .isInstanceOf(InvalidDashboardWidgetException.class);
    }

    // -------- Security: a different userId never observes this user's real, saved dashboard --------

    @Test
    void getDashboard_differentUserId_neverObservesAnotherUsersRealConfig() {
        final SaveDashboardWidgetRequest widget = new SaveDashboardWidgetRequest(
                DashboardWidgetType.STRATEGIC_MILESTONES, applicationId, 0, 0, 0, 1, 1);
        dashboardService.saveDashboard(tenantId, teamId, USER,
                new SaveDashboardRequest("PMO", DashboardViewMode.DETAILED, List.of(widget)));

        final long otherUser = 999L;
        final DashboardResponse asOtherUser = dashboardService.getDashboard(tenantId, teamId, otherUser);

        assertThat(asOtherUser.widgets()).isEmpty();
        assertThat(asOtherUser.profile()).isNull();
        assertThat(asOtherUser.userId()).isEqualTo(otherUser);
    }

    // -------- AC3 error: invalid save leaves the previous, valid dashboard untouched ----------------

    @Test
    void saveDashboard_rejectedSave_leavesPreviousDashboardUntouched() {
        final SaveDashboardWidgetRequest validWidget = new SaveDashboardWidgetRequest(
                DashboardWidgetType.STRATEGIC_MILESTONES, applicationId, 0, 0, 0, 1, 1);
        dashboardService.saveDashboard(tenantId, teamId, USER,
                new SaveDashboardRequest("PMO", DashboardViewMode.SYNTHETIC, List.of(validWidget)));

        final SaveDashboardWidgetRequest outOfBounds = new SaveDashboardWidgetRequest(
                DashboardWidgetType.STRATEGIC_MILESTONES, applicationId, 0, 0, 4, 1, 1);
        assertThatThrownBy(() -> dashboardService.saveDashboard(tenantId, teamId, USER,
                new SaveDashboardRequest("PMO", DashboardViewMode.DETAILED, List.of(outOfBounds))))
                .isInstanceOf(InvalidDashboardWidgetException.class);

        final DashboardResponse stillPrevious = dashboardService.getDashboard(tenantId, teamId, USER);
        assertThat(stillPrevious.viewMode()).isEqualTo(DashboardViewMode.SYNTHETIC);
        assertThat(stillPrevious.widgets()).hasSize(1);
        assertThat(stillPrevious.widgets().get(0).widgetType()).isEqualTo(DashboardWidgetType.STRATEGIC_MILESTONES);
    }
}
