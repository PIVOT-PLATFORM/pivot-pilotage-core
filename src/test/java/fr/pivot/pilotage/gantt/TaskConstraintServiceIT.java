package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Calendar;
import fr.pivot.pilotage.schedule.CalendarRepository;
import fr.pivot.pilotage.schedule.CalendarScope;
import fr.pivot.pilotage.schedule.ConstraintType;
import fr.pivot.pilotage.schedule.DependencyLinkType;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskConstraintRepository;
import fr.pivot.pilotage.schedule.TaskDependency;
import fr.pivot.pilotage.schedule.TaskDependencyRepository;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.schedule.engine.SchedulingWarning;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link TaskConstraintService} (US22.4.4 — «&nbsp;Contraintes de date &amp;
 * échéances&nbsp;») against a real PostgreSQL 18 (Testcontainers), this module's Flyway migration
 * applied. One test per behavioural AC: an MFO constraint honoured, a constraint overridden by a hard
 * dependency (the dependency stays honoured, a typed {@code CONSTRAINT_CONFLICT} warning is surfaced —
 * exercising the {@code ScheduleEngine} fix this US required), a deadline miss ({@code
 * DEADLINE_MISSED}) that never blocks the recompute, the 422 refusal of a date-bearing type without a
 * date, the read-only preview surfacing a warning without a fresh write, and cross-tenant/team 404
 * non-disclosure. Calendar is Mon-Fri 09:00-17:00, anchor is a Monday in the past (no {@code now()}).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class TaskConstraintServiceIT {

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
    @Autowired private TaskConstraintRepository constraintRepository;
    @Autowired private TaskConstraintService service;

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
            t.setStartDate(MON_0900);
        }
        return taskRepository.save(t);
    }

    // -------- GET default: no persisted row ⇒ ASAP, no date, no deadline, no warnings -------------

    @Test
    void get_noConstraintPersisted_defaultsToAsapWithNoWarnings() {
        final Task a = leaf(0, "A", ONE_DAY, true);

        final TaskConstraintResponse resp = service.get(tenantId, teamId, project.getId(), a.getId());

        assertThat(resp.constraintType()).isEqualTo(ConstraintType.ASAP);
        assertThat(resp.constraintDate()).isNull();
        assertThat(resp.deadline()).isNull();
        assertThat(resp.warnings()).isEmpty();
    }

    // -------- AC: an MFO constraint is honoured (no conflicting hard dependency) -------------------

    @Test
    void upsert_mfoHonoured_noConflictWarning() {
        final Task a = leaf(0, "A", ONE_DAY, true);
        // Finish on the anchor day itself (09:00 + 8h worked = 17:00 same day) — freely honourable.
        final Instant mfoFinish = MON_0900.plus(Duration.ofHours(8));

        final TaskConstraintResponse resp = service.upsert(tenantId, teamId, project.getId(), a.getId(),
                new UpsertTaskConstraintRequest(ConstraintType.MFO, mfoFinish, null));

        assertThat(resp.constraintType()).isEqualTo(ConstraintType.MFO);
        assertThat(resp.constraintDate()).isEqualTo(mfoFinish);
        assertThat(resp.warnings()).noneMatch(
                w -> w.type() == SchedulingWarning.WarningType.CONSTRAINT_CONFLICT);
        assertThat(taskRepository.findById(a.getId()).orElseThrow().getEarlyFinish()).isEqualTo(mfoFinish);
    }

    // -------- Error AC: constraint incompatible with a dependency → dependency honoured + typed ----
    // -------- CONSTRAINT_CONFLICT warning, the dependency is never broken (exercises the engine fix) -

    @Test
    void upsert_msoConflictsWithHardDependency_dependencyHonouredAndWarned() {
        final Task a = leaf(0, "A", ONE_DAY, true); // Mon 09:00 -> Tue 09:00 (8h worked)
        final Task b = leaf(1, "B", ONE_DAY, false);
        dependencyRepository.save(new TaskDependency(tenantId, teamId, a.getId(), b.getId(),
                DependencyLinkType.FS, 0));
        // First recompute so the FS dependency drives B's early start to Tue 09:00.
        service.get(tenantId, teamId, project.getId(), b.getId());

        // B "must start on" the anchor Monday — earlier than what the hard FS dependency allows.
        final TaskConstraintResponse resp = service.upsert(tenantId, teamId, project.getId(), b.getId(),
                new UpsertTaskConstraintRequest(ConstraintType.MSO, MON_0900, null));

        // The dependency wins: B still starts Tuesday (never pulled before its predecessor).
        final Task fresh = taskRepository.findById(b.getId()).orElseThrow();
        assertThat(fresh.getEarlyStart()).isAfter(MON_0900);
        // ... but the conflict is explicitly signalled, never silently swallowed.
        assertThat(resp.warnings()).anyMatch(
                w -> w.type() == SchedulingWarning.WarningType.CONSTRAINT_CONFLICT);
    }

    // -------- AC: a deadline miss raises an alert without blocking the recompute --------------------

    @Test
    void upsert_deadlineMissed_warnsButNeverBlocks() {
        final Task a = leaf(0, "A", 3 * ONE_DAY, true); // finishes Thu, well past a Monday deadline

        final TaskConstraintResponse resp = service.upsert(tenantId, teamId, project.getId(), a.getId(),
                new UpsertTaskConstraintRequest(ConstraintType.ASAP, null, MON_0900));

        assertThat(resp.warnings()).anyMatch(
                w -> w.type() == SchedulingWarning.WarningType.DEADLINE_MISSED);
        // The task still has a computed schedule — the deadline never blocked the recompute.
        assertThat(taskRepository.findById(a.getId()).orElseThrow().getEarlyFinish()).isNotNull();
    }

    // -------- Error AC: a date-bearing type without a date is rejected, nothing persisted -----------

    @Test
    void upsert_dateBearingTypeWithoutDate_rejectedWithoutPersisting() {
        final Task a = leaf(0, "A", ONE_DAY, true);

        assertThatExceptionOfType(InvalidTaskConstraintException.class).isThrownBy(() ->
                service.upsert(tenantId, teamId, project.getId(), a.getId(),
                        new UpsertTaskConstraintRequest(ConstraintType.SNET, null, null)));

        assertThat(constraintRepository.findByTaskIdAndTenantIdAndTeamId(a.getId(), tenantId, teamId))
                .isEmpty();
    }

    // -------- AC: ASAP/ALAP never require a date ------------------------------------------------

    @Test
    void upsert_asapWithoutDate_accepted() {
        final Task a = leaf(0, "A", ONE_DAY, true);

        final TaskConstraintResponse resp = service.upsert(tenantId, teamId, project.getId(), a.getId(),
                new UpsertTaskConstraintRequest(ConstraintType.ASAP, null, null));

        assertThat(resp.constraintType()).isEqualTo(ConstraintType.ASAP);
        assertThat(resp.constraintDate()).isNull();
    }

    // -------- a stray date on ASAP/ALAP is cleared server-side, not rejected ----------------------

    @Test
    void upsert_asapWithStrayDate_dateIsCleared() {
        final Task a = leaf(0, "A", ONE_DAY, true);

        final TaskConstraintResponse resp = service.upsert(tenantId, teamId, project.getId(), a.getId(),
                new UpsertTaskConstraintRequest(ConstraintType.ASAP, MON_0900, null));

        assertThat(resp.constraintDate()).isNull();
    }

    // -------- Security AC: a conflict raised by a write stays visible read-only on a later GET -----

    @Test
    void get_afterUpsert_stillReportsWarningWithoutAnotherWrite() {
        final Task a = leaf(0, "A", 3 * ONE_DAY, true);
        service.upsert(tenantId, teamId, project.getId(), a.getId(),
                new UpsertTaskConstraintRequest(ConstraintType.ASAP, null, MON_0900));

        // A plain read (no mutation) must still surface the current DEADLINE_MISSED warning.
        final TaskConstraintResponse resp = service.get(tenantId, teamId, project.getId(), a.getId());

        assertThat(resp.warnings()).anyMatch(
                w -> w.type() == SchedulingWarning.WarningType.DEADLINE_MISSED);
    }

    // -------- Security AC: constraint on a task of another tenant is a 404 (non-disclosure) --------

    @Test
    void get_crossTenant_isNotFound() {
        final Task a = leaf(0, "A", ONE_DAY, true);
        final long otherTenant = tenantId + 999L;

        assertThatExceptionOfType(WbsProjectNotFoundException.class).isThrownBy(() ->
                service.get(otherTenant, teamId, project.getId(), a.getId()));
    }

    // -------- Security AC: constraint on a task of another team is a 404 (non-disclosure) ----------

    @Test
    void upsert_crossTeam_isNotFound() {
        final Task a = leaf(0, "A", ONE_DAY, true);
        final long otherTeam = teamId + 999L;

        assertThatExceptionOfType(WbsProjectNotFoundException.class).isThrownBy(() ->
                service.upsert(tenantId, otherTeam, project.getId(), a.getId(),
                        new UpsertTaskConstraintRequest(ConstraintType.ASAP, null, null)));
    }

    // -------- Security AC: unknown task on an otherwise visible project is a 404 --------------------

    @Test
    void get_unknownTask_isNotFound() {
        assertThatExceptionOfType(WbsTaskNotFoundException.class).isThrownBy(() ->
                service.get(tenantId, teamId, project.getId(), 999_999L));
    }
}
