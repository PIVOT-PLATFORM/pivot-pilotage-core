package fr.pivot.pilotage.schedule.projection;

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
import fr.pivot.pilotage.schedule.service.SchedulingService;
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

/**
 * Integration tests for {@link PlanProjectionService} (EN22.1c) against a real PostgreSQL 18
 * (Testcontainers): the full <em>load → compute (EN22.1b) → project</em> chain, the shared-milestone
 * non-divergence invariant (same id in macro and detail after a real recalculation), and
 * multi-tenant isolation (a foreign tenant's project is not projected — the service-level 404
 * equivalent). Anchored to a Monday in the past; no {@code now()}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class PlanProjectionServiceIT {

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
    @Autowired private CalendarRepository calendarRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskDependencyRepository dependencyRepository;
    @Autowired private SchedulingService schedulingService;
    @Autowired private PlanProjectionService projectionService;

    private long tenantId;

    private static final String WT = "{\"ranges\":[[\"09:00\",\"17:00\"]]}";
    private static final Instant MON_0900 =
            LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();

    /** Seeds a fresh tenant before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = seedTenant();
    }

    private long seedTenant() throws Exception {
        return PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

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
        t.setStartDate(MON_0900);
        return taskRepository.save(t);
    }

    // -------- AC: load → compute (EN22.1b) → project the detail view ---------------------------

    @Test
    void loadComputeProject_detailCarriesDerivedSchedule() {
        final Project project = newProjectWithCalendar(tenantId);
        final Task a = leaf(tenantId, project.getId(), 0, "A", 480);
        final Task b = leaf(tenantId, project.getId(), 1, "B", 480);
        dependencyRepository.save(new TaskDependency(
                tenantId, a.getId(), b.getId(), DependencyLinkType.FS, 0));

        // EN22.1b computes and persists the derived columns…
        schedulingService.scheduleProject(project.getId(), tenantId);
        // …EN22.1c projects them without recomputing.
        final PlanView detail = projectionService
                .project(project.getId(), tenantId, Altitude.DETAIL, Layout.GANTT).orElseThrow();

        assertThat(detail.nodes()).extracting(PlanNodeView::nodeId)
                .containsExactly(a.getId(), b.getId());
        assertThat(detail.nodes().get(0).critical()).isTrue();
        assertThat(detail.nodes().get(0).wbsCode()).isEqualTo("1");
        assertThat(detail.nodes().get(0).startDate()).isEqualTo(MON_0900);
        assertThat(detail.dependencies()).hasSize(1);
        assertThat(detail.dependencies().get(0).linkType()).isEqualTo(DependencyLinkType.FS);
    }

    // -------- AC: shared milestone → same id in macro AND detail (non-divergence) -------------

    @Test
    void sharedMilestone_sameIdInBothViews_noDuplication() {
        final Project project = newProjectWithCalendar(tenantId);
        final Task milestone = new Task(tenantId, project.getId(), 0, "Go-live",
                NodeKind.MILESTONE, true, TemporalPrecision.DAY, 0);
        milestone.setDurationMinutes(0);
        milestone.setStartDate(MON_0900);
        milestone.setFinishDate(MON_0900);
        milestone.setFuzzyPeriodStart(LocalDate.of(2024, 1, 1));
        milestone.setFuzzyPeriodEnd(LocalDate.of(2024, 3, 31));
        final Task saved = taskRepository.save(milestone);
        schedulingService.scheduleProject(project.getId(), tenantId);

        final PlanView macro = projectionService
                .project(project.getId(), tenantId, Altitude.MACRO, Layout.TIMELINE).orElseThrow();
        final PlanView detail = projectionService
                .project(project.getId(), tenantId, Altitude.DETAIL, Layout.GANTT).orElseThrow();

        assertThat(macro.nodes()).extracting(PlanNodeView::nodeId).containsExactly(saved.getId());
        assertThat(detail.nodes()).extracting(PlanNodeView::nodeId).containsExactly(saved.getId());
        // Same single record — never duplicated; macro reads fuzzy, detail reads precise.
        assertThat(macro.nodes().get(0).nodeId()).isEqualTo(detail.nodes().get(0).nodeId());
        assertThat(macro.nodes().get(0).fuzzyPeriodStart()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(detail.nodes().get(0).startDate()).isEqualTo(MON_0900);
    }

    // -------- Security: a foreign tenant's project is not projected (empty ⇒ 404) --------------

    @Test
    void foreignTenant_projectNotProjected() throws Exception {
        final long tenantT2 = seedTenant();
        final Project projectT1 = newProjectWithCalendar(tenantId);
        leaf(tenantId, projectT1.getId(), 0, "T1", 480);

        assertThat(projectionService.project(projectT1.getId(), tenantT2, Altitude.DETAIL, Layout.GANTT))
                .isEmpty();
    }
}
