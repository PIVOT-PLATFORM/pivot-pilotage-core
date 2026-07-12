package fr.pivot.pilotage.weather;

import fr.pivot.pilotage.consolidation.ApplicationNotFoundException;
import fr.pivot.pilotage.consolidation.ProjectNotFoundException;
import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link ProjectWeatherService} (US23.2.4) against a real PostgreSQL 18
 * (Testcontainers). Proves: the homogeneous SUNNY/CLOUDY/STORMY classification computed purely
 * through tenant-scoped {@code pilotage} repositories; the missing-data error case (never a
 * misleading default); the batch application-scoped path surfacing the exact same per-project
 * result as the single path (no divergent recomputation); and multi-tenant isolation (a foreign
 * tenant's project/application is invisible → 404 equivalent). Timestamps are anchored in the
 * past — never {@code now()}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ProjectWeatherServiceIT {

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
    @Autowired private ProjectWeatherService weatherService;

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

    private Project newProject(final long owner, final long team, final Application app, final String name,
            final LocalDate statusDate) {
        final Project p = new Project(app, owner, team, name, ANCHOR);
        p.setStatusDate(statusDate);
        return projectRepository.save(p);
    }

    private Task newLeafWithWindow(final long owner, final long team, final long projectId,
            final LocalDate start, final LocalDate end) {
        final Task t = new Task(owner, team, projectId, 0, "leaf", NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        t.setFuzzyPeriodStart(start);
        t.setFuzzyPeriodEnd(end);
        return taskRepository.save(t);
    }

    private void newProgress(final long owner, final long team, final long taskId, final BigDecimal percent) {
        taskProgressRepository.save(new TaskProgress(owner, team, taskId, percent));
    }

    // -------- AC1: homogeneous rules against real persisted data ---------------------------------

    @Test
    void computeWeather_onTrackProject_yieldsSunny() {
        final Application app = newApplication(tenantId, teamId, "Billing");
        final Project project = newProject(tenantId, teamId, app, "v1", LocalDate.of(2024, 1, 6));
        final Task task = newLeafWithWindow(tenantId, teamId, project.getId(),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 11));
        newProgress(tenantId, teamId, task.getId(), new BigDecimal("50.00"));

        final ProjectWeather weather = weatherService.computeWeather(tenantId, project.getId());

        assertThat(weather.status()).isEqualTo(ProjectWeatherStatus.SUNNY);
        assertThat(weather.actualProgressPercent()).isEqualByComparingTo("50.00");
        assertThat(weather.expectedProgressPercent()).isEqualByComparingTo("50.00");
        assertThat(weather.indeterminateReason()).isNull();
    }

    @Test
    void computeWeather_severelyBehindProject_yieldsStormy() {
        final Application app = newApplication(tenantId, teamId, "Billing");
        final Project project = newProject(tenantId, teamId, app, "v1", LocalDate.of(2024, 1, 6));
        final Task task = newLeafWithWindow(tenantId, teamId, project.getId(),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 11));
        newProgress(tenantId, teamId, task.getId(), new BigDecimal("10.00"));

        final ProjectWeather weather = weatherService.computeWeather(tenantId, project.getId());

        assertThat(weather.status()).isEqualTo(ProjectWeatherStatus.STORMY);
    }

    // -------- Error: insufficient data → INDETERMINATE, never a misleading default ---------------

    @Test
    void computeWeather_noProgressRecord_yieldsIndeterminateMissingProgress() {
        final Application app = newApplication(tenantId, teamId, "Billing");
        final Project project = newProject(tenantId, teamId, app, "v1", LocalDate.of(2024, 1, 6));
        newLeafWithWindow(tenantId, teamId, project.getId(), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 11));

        final ProjectWeather weather = weatherService.computeWeather(tenantId, project.getId());

        assertThat(weather.status()).isEqualTo(ProjectWeatherStatus.INDETERMINATE);
        assertThat(weather.indeterminateReason()).isEqualTo(ProjectWeatherIndeterminateReason.MISSING_PROGRESS);
    }

    @Test
    void computeWeather_noStatusDate_yieldsIndeterminateMissingStatusDate() {
        final Application app = newApplication(tenantId, teamId, "Billing");
        final Project project = newProject(tenantId, teamId, app, "v1", null);

        final ProjectWeather weather = weatherService.computeWeather(tenantId, project.getId());

        assertThat(weather.indeterminateReason())
                .isEqualTo(ProjectWeatherIndeterminateReason.MISSING_STATUS_DATE);
    }

    // -------- AC2: batch application path mirrors the single-project computation exactly ---------

    @Test
    void computeWeatherForApplication_matchesPerProjectComputation_orderedById() {
        final Application app = newApplication(tenantId, teamId, "Billing");
        final Project onTrack = newProject(tenantId, teamId, app, "v1", LocalDate.of(2024, 1, 6));
        final Project noData = newProject(tenantId, teamId, app, "v2", null);
        final Task task = newLeafWithWindow(tenantId, teamId, onTrack.getId(),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 11));
        newProgress(tenantId, teamId, task.getId(), new BigDecimal("50.00"));

        final List<ProjectWeather> batch = weatherService.computeWeatherForApplication(tenantId, app.getId());
        final ProjectWeather individual = weatherService.computeWeather(tenantId, onTrack.getId());

        final long expectedFirst = Math.min(onTrack.getId(), noData.getId());
        final long expectedSecond = Math.max(onTrack.getId(), noData.getId());
        assertThat(batch).extracting(ProjectWeather::projectId).containsExactly(expectedFirst, expectedSecond);
        final ProjectWeather onTrackFromBatch = batch.stream()
                .filter(w -> w.projectId() == onTrack.getId()).findFirst().orElseThrow();
        assertThat(onTrackFromBatch).isEqualTo(individual);
    }

    // -------- Security: a foreign tenant's project/application is invisible (404 equivalent) ------

    @Test
    void computeWeather_foreignTenantProject_notAccessible() throws Exception {
        final long tenantT2 = seedTenant();
        final Application app = newApplication(tenantId, teamId, "Billing");
        final Project project = newProject(tenantId, teamId, app, "v1", LocalDate.of(2024, 1, 6));

        assertThatThrownBy(() -> weatherService.computeWeather(tenantT2, project.getId()))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void computeWeatherForApplication_foreignTenantApplication_notAccessible() throws Exception {
        final long tenantT2 = seedTenant();
        final Application app = newApplication(tenantId, teamId, "Billing");
        newProject(tenantId, teamId, app, "v1", LocalDate.of(2024, 1, 6));

        assertThatThrownBy(() -> weatherService.computeWeatherForApplication(tenantT2, app.getId()))
                .isInstanceOf(ApplicationNotFoundException.class);
    }

    // -------- Error: unknown project/application → not-found exceptions ---------------------------

    @Test
    void computeWeather_unknownProject_throws() {
        assertThatThrownBy(() -> weatherService.computeWeather(tenantId, 999_999L))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void computeWeatherForApplication_unknownApplication_throws() {
        assertThatThrownBy(() -> weatherService.computeWeatherForApplication(tenantId, 999_999L))
                .isInstanceOf(ApplicationNotFoundException.class);
    }
}
