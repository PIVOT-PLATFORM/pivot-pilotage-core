package fr.pivot.pilotage.schedule.service;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Calendar;
import fr.pivot.pilotage.schedule.CalendarRepository;
import fr.pivot.pilotage.schedule.CalendarScope;
import fr.pivot.pilotage.schedule.DependencyLinkType;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskDependency;
import fr.pivot.pilotage.schedule.TaskDependencyRepository;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.schedule.engine.ScheduleException;
import fr.pivot.pilotage.schedule.engine.ScheduleResult;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link SchedulingService} (EN22.1b): load a project's temporal graph from a
 * real PostgreSQL 18 (Testcontainers), invoke the pure engine, persist the derived columns
 * ({@code early_*}/{@code late_*}, slack, {@code is_critical}, {@code wbs_code}) and read them back —
 * plus multi-tenant isolation. Calendar is Mon-Fri 09:00-17:00; the anchor project start is a
 * Monday in the past (no {@code now()}).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class SchedulingServiceIT {

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

    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private CalendarRepository calendarRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskDependencyRepository dependencyRepository;
    @Autowired
    private SchedulingService schedulingService;

    private long tenantId;

    /** Seeds a fresh tenant before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long seedTenant() throws Exception {
        return PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static final String WT = "{\"ranges\":[[\"09:00\",\"17:00\"]]}";
    private static final Instant MON_0900 =
            LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();

    private Project newProjectWithCalendar(final long owner) {
        final Instant now = Instant.now();
        final Application app = applicationRepository.save(new Application(owner, "App", now));
        final Project project = projectRepository.save(new Project(app, owner, "P", now));
        final Calendar cal = calendarRepository.save(new Calendar(
                owner, project.getId(), CalendarScope.PROJECT, "Std", (short) 0b0011111, WT));
        project.setCalendar(cal);
        project.setStatusDate(LocalDate.of(2024, 1, 1));
        return projectRepository.save(project);
    }

    private Task leaf(final long owner, final long projectId, final int position, final String name,
            final int durationMinutes) {
        final Task t = new Task(owner, projectId, position, name, NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        t.setDurationMinutes(durationMinutes);
        t.setStartDate(MON_0900); // anchor the project start deterministically
        return taskRepository.save(t);
    }

    // -------- AC: load → compute → persist derived columns; read them back --------------------

    @Test
    void scheduleProject_persistsDerivedColumnsAndWbs() {
        final Project project = newProjectWithCalendar(tenantId);
        final Task a = leaf(tenantId, project.getId(), 0, "A", 480);
        final Task b = leaf(tenantId, project.getId(), 1, "B", 480);
        final Task c = leaf(tenantId, project.getId(), 2, "C", 480);
        dependencyRepository.save(new TaskDependency(
                tenantId, a.getId(), b.getId(), DependencyLinkType.FS, 0));
        dependencyRepository.save(new TaskDependency(
                tenantId, b.getId(), c.getId(), DependencyLinkType.FS, 0));

        final ScheduleResult result = schedulingService.scheduleProject(project.getId(), tenantId);
        assertThat(result.criticalPath()).containsExactly(a.getId(), b.getId(), c.getId());

        // Re-read the tasks: derived columns are now populated (were null at rest in EN22.1a).
        final Task reloadedA = taskRepository.findByIdAndTenantId(a.getId(), tenantId).orElseThrow();
        final Task reloadedC = taskRepository.findByIdAndTenantId(c.getId(), tenantId).orElseThrow();

        assertThat(reloadedA.getEarlyStart()).isEqualTo(MON_0900);
        assertThat(reloadedA.getEarlyFinish())
                .isEqualTo(LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(17).toInstant());
        assertThat(reloadedA.getCritical()).isTrue();
        assertThat(reloadedA.getTotalSlackMinutes()).isZero();
        assertThat(reloadedA.getWbsCode()).isEqualTo("1");
        // Task C finishes on Wednesday (3rd working day).
        assertThat(reloadedC.getEarlyFinish())
                .isEqualTo(LocalDate.of(2024, 1, 3).atStartOfDay(ZoneOffset.UTC).plusHours(17).toInstant());
        assertThat(reloadedC.getWbsCode()).isEqualTo("3");
    }

    // -------- AC (error): a cycle propagates SCHEDULE_CYCLE, nothing persisted ----------------

    @Test
    void scheduleProject_cyclePropagatesError() {
        final Project project = newProjectWithCalendar(tenantId);
        final Task a = leaf(tenantId, project.getId(), 0, "A", 480);
        final Task b = leaf(tenantId, project.getId(), 1, "B", 480);
        dependencyRepository.save(new TaskDependency(
                tenantId, a.getId(), b.getId(), DependencyLinkType.FS, 0));
        dependencyRepository.save(new TaskDependency(
                tenantId, b.getId(), a.getId(), DependencyLinkType.FS, 0));

        assertThatThrownBy(() -> schedulingService.scheduleProject(project.getId(), tenantId))
                .isInstanceOf(ScheduleException.class);
    }

    // -------- AC (security): multi-tenant isolation ------------------------------------------

    @Test
    void scheduleProject_isolatesTenants() throws Exception {
        final long tenantT2 = seedTenant();
        final Project projectT1 = newProjectWithCalendar(tenantId);
        leaf(tenantId, projectT1.getId(), 0, "T1", 480);

        // Scheduling with the wrong tenant resolves to no project ⇒ tenant violation.
        assertThatThrownBy(() -> schedulingService.scheduleProject(projectT1.getId(), tenantT2))
                .isInstanceOf(ScheduleException.class);
    }
}
