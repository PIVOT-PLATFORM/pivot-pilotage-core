package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Assignment;
import fr.pivot.pilotage.schedule.AssignmentRepository;
import fr.pivot.pilotage.schedule.Calendar;
import fr.pivot.pilotage.schedule.CalendarRepository;
import fr.pivot.pilotage.schedule.CalendarScope;
import fr.pivot.pilotage.schedule.DependencyLinkType;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.SchedulingMode;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskDependency;
import fr.pivot.pilotage.schedule.TaskDependencyRepository;
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link TaskEffortService} (US22.4.2 — durées, effort, planification auto vs
 * manuelle) against a real PostgreSQL 18 (Testcontainers), this module's Flyway migration applied.
 * One test per behavioural AC: AUTO recompute on a dependency/calendar change, MANUAL pinning +
 * variance, the work = duration × units relation, the 422 refusals that never overwrite the task, and
 * cross-tenant 404 non-disclosure. {@code public.tenants}/{@code public.teams} are seeded before
 * Flyway via {@link PlatformSchemaTestSupport}. The calendar is Mon-Fri 09:00-17:00 and the anchor is
 * a Monday in the past (no {@code now()}).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class TaskEffortServiceIT {

    private static final String WT = "{\"ranges\":[[\"09:00\",\"17:00\"]]}";
    private static final Instant MON_0900 =
            LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();
    /** One worked day (09:00-17:00 = 8h). */
    private static final int ONE_DAY = 8 * 60;

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
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private TaskEffortService service;

    private long tenantId;
    private long teamId;
    private Project project;

    /** Seeds a fresh tenant/team/application/project (with a Mon-Fri calendar) before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        teamId = PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), tenantId);
        project = newProjectWithCalendar(tenantId, teamId);
    }

    private Project newProjectWithCalendar(final long owner, final long team) {
        final Instant now = Instant.now();
        final Application app = applicationRepository.save(new Application(owner, team, "App", now));
        final Project p = projectRepository.save(new Project(app, owner, team, "P", now));
        final Calendar cal = calendarRepository.save(new Calendar(
                owner, team, p.getId(), CalendarScope.PROJECT, "Std", (short) 0b0011111, WT));
        p.setCalendar(cal);
        p.setStatusDate(LocalDate.of(2024, 1, 1));
        return projectRepository.save(p);
    }

    private Task leaf(final int position, final String name, final int durationMinutes, final boolean anchor) {
        final Task t = new Task(tenantId, teamId, project.getId(), position, name, NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        t.setDurationMinutes(durationMinutes);
        if (anchor) {
            t.setStartDate(MON_0900); // anchor the project start deterministically
        }
        return taskRepository.save(t);
    }

    private Task reload(final long taskId) {
        return taskRepository.findById(taskId).orElseThrow();
    }

    // -------- AC: AUTO recomputes when a dependency changes --------------------------------------

    @Test
    void auto_recomputesSuccessorWhenPredecessorDurationChanges() {
        final Task a = leaf(0, "A", ONE_DAY, true);
        final Task b = leaf(1, "B", ONE_DAY, false);
        dependencyRepository.save(new TaskDependency(tenantId, teamId, a.getId(), b.getId(),
                DependencyLinkType.FS, 0));

        // Baseline schedule: B follows A (one day). Capture B's early start.
        service.setDuration(tenantId, teamId, project.getId(), b.getId(), ONE_DAY);
        final Instant beforeEs = reload(b.getId()).getEarlyStart();

        // Lengthen A by one day: B (AUTO) must slip one worked day later.
        service.setDuration(tenantId, teamId, project.getId(), a.getId(), 2 * ONE_DAY);

        final Instant afterEs = reload(b.getId()).getEarlyStart();
        assertThat(afterEs).isAfter(beforeEs);
    }

    // -------- AC: AUTO recomputes when the calendar changes --------------------------------------

    @Test
    void auto_recomputesWhenProjectCalendarChanges() {
        final Task a = leaf(0, "A", ONE_DAY, true);
        final Task b = leaf(1, "B", ONE_DAY, false);
        dependencyRepository.save(new TaskDependency(tenantId, teamId, a.getId(), b.getId(),
                DependencyLinkType.FS, 0));
        service.setDuration(tenantId, teamId, project.getId(), b.getId(), ONE_DAY);
        final Instant beforeFinish = reload(b.getId()).getEarlyFinish();

        // Shorten the working day to 09:00-13:00 (4h): the same 8h task now spans two calendar days.
        final Calendar cal = calendarRepository.findById(project.getCalendar().getId()).orElseThrow();
        cal.setWorkingTime("{\"ranges\":[[\"09:00\",\"13:00\"]]}");
        calendarRepository.save(cal);

        // Any recompute-triggering edit re-runs the CPM against the new calendar.
        service.setDuration(tenantId, teamId, project.getId(), a.getId(), ONE_DAY);

        final Instant afterFinish = reload(b.getId()).getEarlyFinish();
        assertThat(afterFinish).isAfter(beforeFinish);
    }

    // -------- AC: MANUAL pins dates and the engine signals a variance ---------------------------

    @Test
    void manual_pinsDatesAndReportsVarianceWithoutOverwriting() {
        final Task a = leaf(0, "A", ONE_DAY, true);
        final Task b = leaf(1, "B", ONE_DAY, false);
        dependencyRepository.save(new TaskDependency(tenantId, teamId, a.getId(), b.getId(),
                DependencyLinkType.FS, 0));

        // Pin B two worked days *later* than the engine would schedule it, then switch it to MANUAL.
        final Instant pinned = MON_0900.plus(java.time.Duration.ofDays(3)); // Thu 09:00
        final Task bEntity = reload(b.getId());
        bEntity.setStartDate(pinned);
        bEntity.setFinishDate(pinned);
        taskRepository.save(bEntity);

        final TaskSchedulingResponse resp =
                service.setSchedulingMode(tenantId, teamId, project.getId(), b.getId(), SchedulingMode.MANUAL);

        // The pinned date is not overwritten and a variance is surfaced.
        assertThat(resp.schedulingMode()).isEqualTo(SchedulingMode.MANUAL);
        assertThat(resp.plannedManual()).isEqualTo(pinned);
        assertThat(resp.wouldBeAuto()).isNotNull().isBefore(pinned);
        assertThat(resp.deltaMinutes()).isGreaterThan(0);
        assertThat(reload(b.getId()).getStartDate()).isEqualTo(pinned);
    }

    // -------- AC: work = duration × units when units change --------------------------------------

    @Test
    void effort_derivesWorkAsDurationTimesUnits() {
        final Task a = leaf(0, "A", 2 * ONE_DAY, true); // 960 worked minutes

        // 50% units on a 960' task ⇒ 480' of work.
        final TaskSchedulingResponse half = service.setEffort(tenantId, teamId, project.getId(), a.getId(),
                "alice", new BigDecimal("50"));
        assertThat(half.workMinutes()).isEqualTo(2 * ONE_DAY / 2);

        // Raise the units to 150% ⇒ 1440' of work (relation re-derived).
        final TaskSchedulingResponse over = service.setEffort(tenantId, teamId, project.getId(), a.getId(),
                "alice", new BigDecimal("150"));
        assertThat(over.workMinutes()).isEqualTo(2 * ONE_DAY * 3 / 2);

        final Assignment saved = assignmentRepository
                .findAllByTaskIdAndTenantIdAndTeamId(a.getId(), tenantId, teamId).get(0);
        assertThat(saved.getUnitsPercent()).isEqualByComparingTo("150");
        assertThat(saved.getWorkMinutes()).isEqualTo(2 * ONE_DAY * 3 / 2);
    }

    // -------- AC: duration change re-derives an existing assignment's work ------------------------

    @Test
    void effort_workFollowsDurationWhenDurationChanges() {
        final Task a = leaf(0, "A", ONE_DAY, true);
        service.setEffort(tenantId, teamId, project.getId(), a.getId(), "bob", new BigDecimal("100"));

        // Doubling the duration doubles the derived work (relation preserved).
        final TaskSchedulingResponse resp =
                service.setDuration(tenantId, teamId, project.getId(), a.getId(), 2 * ONE_DAY);
        assertThat(resp.workMinutes()).isEqualTo(2 * ONE_DAY);
    }

    // -------- Error AC: negative duration → 422 without overwriting -------------------------------

    @Test
    void setDuration_negative_rejectedWithoutOverwriting() {
        final Task a = leaf(0, "A", ONE_DAY, true);

        assertThatExceptionOfType(InvalidTaskEffortException.class).isThrownBy(() ->
                service.setDuration(tenantId, teamId, project.getId(), a.getId(), -1));

        assertThat(reload(a.getId()).getDurationMinutes()).isEqualTo(ONE_DAY);
    }

    // -------- Error AC: zero duration on a non-milestone → 422 without overwriting ----------------

    @Test
    void setDuration_zeroOnNonMilestone_rejectedWithoutOverwriting() {
        final Task a = leaf(0, "A", ONE_DAY, true);

        assertThatExceptionOfType(InvalidTaskEffortException.class).isThrownBy(() ->
                service.setDuration(tenantId, teamId, project.getId(), a.getId(), 0));

        assertThat(reload(a.getId()).getDurationMinutes()).isEqualTo(ONE_DAY);
    }

    // -------- a milestone may legitimately carry a zero duration --------------------------------

    @Test
    void setDuration_zeroOnMilestone_accepted() {
        final Task m = new Task(tenantId, teamId, project.getId(), 0, "Kickoff", NodeKind.MILESTONE,
                false, TemporalPrecision.DAY, 0);
        m.setDurationMinutes(ONE_DAY);
        m.setStartDate(MON_0900);
        final Task saved = taskRepository.save(m);

        final TaskSchedulingResponse resp =
                service.setDuration(tenantId, teamId, project.getId(), saved.getId(), 0);
        assertThat(resp.durationMinutes()).isEqualTo(0);
    }

    // -------- Error AC: non-positive units → 422 without creating an assignment ------------------

    @Test
    void setEffort_nonPositiveUnits_rejectedWithoutOverwriting() {
        final Task a = leaf(0, "A", ONE_DAY, true);

        assertThatExceptionOfType(InvalidTaskEffortException.class).isThrownBy(() ->
                service.setEffort(tenantId, teamId, project.getId(), a.getId(), "carol", BigDecimal.ZERO));

        assertThat(assignmentRepository.findAllByTaskIdAndTenantIdAndTeamId(a.getId(), tenantId, teamId))
                .isEmpty();
    }

    // -------- Security AC: a task of another tenant is a 404 (non-disclosure) --------------------

    @Test
    void setDuration_crossTenant_isNotFound() {
        final Task a = leaf(0, "A", ONE_DAY, true);
        final long otherTenant = tenantId + 999L;

        assertThatExceptionOfType(WbsProjectNotFoundException.class).isThrownBy(() ->
                service.setDuration(otherTenant, teamId, project.getId(), a.getId(), ONE_DAY));
    }

    // -------- Security AC: a task of another team is a 404 (non-disclosure) ----------------------

    @Test
    void setSchedulingMode_crossTeam_isNotFound() {
        final Task a = leaf(0, "A", ONE_DAY, true);
        final long otherTeam = teamId + 999L;

        assertThatExceptionOfType(WbsProjectNotFoundException.class).isThrownBy(() ->
                service.setSchedulingMode(tenantId, otherTeam, project.getId(), a.getId(), SchedulingMode.MANUAL));
    }
}
