package fr.pivot.pilotage.portfolio;

import fr.pivot.pilotage.consolidation.ProjectPlanningStatus;
import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Phase;
import fr.pivot.pilotage.schedule.PhaseRepository;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskProgress;
import fr.pivot.pilotage.schedule.TaskProgressRepository;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link PortfolioConsolidationService} (US23.2.1) against a real PostgreSQL
 * 18 (Testcontainers) and the real {@code fr.pivot.pilotage.consolidation.
 * ApplicationConsolidationService} bean (EN18.9, unmocked) — proves the two services compose
 * correctly end to end: the portfolio groups every tenant application, each carrying EN18.9's
 * unchanged roll-up (jalons/dates clés) plus this US's per-project santé/avancement/phases. Also
 * proves multi-tenant isolation (AC: "seuls les projets des équipes du tenant... apparaissent"), the
 * error AC (a project without enough data for a computed indicator reports the explicit {@code
 * NOT_SET} — either because no leaf task carries a progress record yet, exercised here via the real
 * {@link WeatherProjectHealthProvider}/{@link NoOpProjectHealthProvider} beans, both unmocked), and
 * — separately — that a project with a full temporal/progress data set surfaces the real,
 * US23.2.4-computed health indicator (not a placeholder). Timestamps are anchored in the past —
 * never {@code now()}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class PortfolioConsolidationServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

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

    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskProgressRepository taskProgressRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private PortfolioConsolidationService portfolioConsolidationService;

    private long tenantId;
    private long teamId;

    private static final Instant ANCHOR = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

    /** Seeds a fresh tenant and team before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = seedTenant();
        teamId = seedTeam(tenantId);
    }

    private long seedTenant() throws Exception {
        return PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long seedTeam(final long owner) throws Exception {
        return PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), owner);
    }

    private Application newApplication(final long owner, final long team, final String name) {
        return applicationRepository.save(new Application(owner, team, name, ANCHOR));
    }

    private Project newProject(final long owner, final long team, final Application app, final String name) {
        return projectRepository.save(new Project(app, owner, team, name, ANCHOR));
    }

    private Task leaf(final long owner, final long team, final long projectId) {
        final Task t = new Task(owner, team, projectId, 0, "leaf", NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        t.setStartDate(ANCHOR);
        return taskRepository.save(t);
    }

    private Task leafWithWindow(final long owner, final long team, final long projectId, final LocalDate start,
            final LocalDate finish) {
        final Task t = new Task(owner, team, projectId, 0, "leaf", NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        t.setStartDate(start.atStartOfDay(ZoneOffset.UTC).toInstant());
        t.setFinishDate(finish.atStartOfDay(ZoneOffset.UTC).toInstant());
        return taskRepository.save(t);
    }

    private Task sharedMilestone(final long owner, final long team, final long projectId, final String name,
            final LocalDate fuzzyStart, final LocalDate fuzzyEnd) {
        final Task t = new Task(owner, team, projectId, 0, name, NodeKind.MILESTONE, true,
                TemporalPrecision.DAY, 0);
        t.setFuzzyPeriodStart(fuzzyStart);
        t.setFuzzyPeriodEnd(fuzzyEnd);
        return taskRepository.save(t);
    }

    private void progress(final long owner, final long team, final long taskId, final int percent) {
        taskProgressRepository.save(new TaskProgress(owner, team, taskId, BigDecimal.valueOf(percent)));
    }

    private Phase newPhase(final long owner, final long team, final long projectId, final String name,
            final int position) {
        return phaseRepository.save(new Phase(owner, team, projectId, null, name, position));
    }

    // -------- AC: santé/avancement/phases/jalons/dates clés consolidated across applications ------

    @Test
    void consolidate_multiApplicationPortfolio_aggregatesAllFiveDimensions() {
        final Application app = newApplication(tenantId, teamId, "Billing");
        final Project v1 = newProject(tenantId, teamId, app, "v1");

        final Task l1 = leaf(tenantId, teamId, v1.getId());
        progress(tenantId, teamId, l1.getId(), 40);
        final Task l2 = leaf(tenantId, teamId, v1.getId());
        progress(tenantId, teamId, l2.getId(), 60);
        sharedMilestone(tenantId, teamId, v1.getId(), "Go-live v1",
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1));
        newPhase(tenantId, teamId, v1.getId(), "Cadrage", 0);
        newPhase(tenantId, teamId, v1.getId(), "Réalisation", 1);

        final PortfolioResponse response = portfolioConsolidationService.consolidate(tenantId);

        assertThat(response.applications()).hasSize(1);
        final PortfolioApplicationEntry appEntry = response.applications().get(0);
        assertThat(appEntry.applicationName()).isEqualTo("Billing");
        // jalons + dates clés — EN18.9's roll-up, reused unchanged. windowStart reflects the
        // earliest task across the project (the leaf tasks' ANCHOR date), not just the milestone.
        assertThat(appEntry.consolidation().strategicMilestones()).hasSize(1);
        assertThat(appEntry.consolidation().windowStart()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(appEntry.consolidation().windowFinish()).isEqualTo(LocalDate.of(2026, 3, 1));

        assertThat(appEntry.projects()).hasSize(1);
        final PortfolioProjectEntry projectEntry = appEntry.projects().get(0);
        assertThat(projectEntry.projectId()).isEqualTo(v1.getId());
        assertThat(projectEntry.teamId()).isEqualTo(teamId);
        // avancement — average of the two leaf tasks' progress.
        assertThat(projectEntry.progressPercent()).isEqualByComparingTo(BigDecimal.valueOf(50));
        // phases — ordered by position.
        assertThat(projectEntry.phases()).extracting(PortfolioPhaseEntry::name)
                .containsExactly("Cadrage", "Réalisation");
        // santé — no statusDate set on this project → weather is INDETERMINATE → explicit
        // NOT_SET (error AC); see consolidate_projectWithComputableWeather_surfacesRealHealthIndicator
        // below for the real, computed indicator on a project with a full data set.
        assertThat(projectEntry.health().status()).isEqualTo(ProjectHealthStatus.NOT_SET);
        assertThat(projectEntry.planningStatus()).isEqualTo(ProjectPlanningStatus.SCHEDULED);
    }

    // -------- AC: santé is the real, computed indicator — proves end-to-end wiring of the ----------
    // -------- real WeatherProjectHealthProvider bean (US23.2.4), not just the NOT_SET fallback -----

    @Test
    void consolidate_projectWithComputableWeather_surfacesRealHealthIndicator() {
        final Application app = newApplication(tenantId, teamId, "Billing");
        final Project project = newProject(tenantId, teamId, app, "OnTrack");
        projectRepository.save(setStatusDate(project, LocalDate.of(2026, 1, 6)));

        // A 10-day window (2026-01-01..2026-01-11), evaluated at day 6 → 50% expected progress.
        final Task task = leafWithWindow(tenantId, teamId, project.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 11));
        progress(tenantId, teamId, task.getId(), 50);

        final PortfolioResponse response = portfolioConsolidationService.consolidate(tenantId);

        final PortfolioProjectEntry entry = response.applications().get(0).projects().get(0);
        // actual (50) == expected (50) → variance 0 → SUNNY (weather) → ON_TRACK (health), computed
        // by the real fr.pivot.pilotage.weather.ProjectWeatherService, never a placeholder NOT_SET.
        assertThat(entry.health().status()).isEqualTo(ProjectHealthStatus.ON_TRACK);
    }

    private static Project setStatusDate(final Project project, final LocalDate statusDate) {
        project.setStatusDate(statusDate);
        return project;
    }

    // -------- Security AC: only the calling tenant's applications/projects ever appear ------------

    @Test
    void consolidate_crossTenantData_neverAppears() throws Exception {
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);
        final Application otherApp = newApplication(otherTenant, otherTeam, "OtherTenantApp");
        newProject(otherTenant, otherTeam, otherApp, "otherProject");

        newApplication(tenantId, teamId, "MyApp");

        final PortfolioResponse response = portfolioConsolidationService.consolidate(tenantId);

        assertThat(response.applications()).extracting(PortfolioApplicationEntry::applicationName)
                .containsExactly("MyApp");
    }

    // -------- AC: an application with no project yet is still listed (empty projects) -------------

    @Test
    void consolidate_applicationWithNoProject_listedWithEmptyProjects() {
        newApplication(tenantId, teamId, "Empty");

        final PortfolioResponse response = portfolioConsolidationService.consolidate(tenantId);

        assertThat(response.applications()).hasSize(1);
        assertThat(response.applications().get(0).projects()).isEmpty();
    }

    // -------- AC: a tenant with no application yet gets an empty (never null) portfolio ------------

    @Test
    void consolidate_tenantWithNoApplication_returnsEmptyPortfolio() {
        final PortfolioResponse response = portfolioConsolidationService.consolidate(tenantId);

        assertThat(response.applications()).isEmpty();
    }
}
