package fr.pivot.pilotage.calendar;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Assignment;
import fr.pivot.pilotage.schedule.AssignmentRepository;
import fr.pivot.pilotage.schedule.Calendar;
import fr.pivot.pilotage.schedule.CalendarExceptionRepository;
import fr.pivot.pilotage.schedule.CalendarRepository;
import fr.pivot.pilotage.schedule.CalendarScope;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.schedule.engine.CalendarWorkingTime;
import fr.pivot.pilotage.schedule.engine.WorkingCalendar;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link CalendarService} (US22.4.5) against a real PostgreSQL 18
 * (Testcontainers) with this module's Flyway migration applied. One test per acceptance criterion:
 * calendar CRUD, exception add/remove, a public holiday (day off) removed from worked time via the
 * pure engine, an exceptionally-worked day added, the resource&gt;task&gt;project resolution priority
 * (D7), the {@code end < start} rejection (422-equivalent), working-time validation, and multi-tenant
 * isolation (404-equivalent). {@code public.tenants}/{@code public.teams} are seeded before Flyway via
 * {@link PlatformSchemaTestSupport}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class CalendarServiceIT {

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
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private CalendarRepository calendarRepository;
    @Autowired private CalendarExceptionRepository exceptionRepository;
    @Autowired private CalendarService calendarService;

    private long tenantId;
    private long teamId;
    private long projectId;

    /** Seeds a fresh tenant/team/application/project before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = seedTenant();
        teamId = seedTeam(tenantId);
        projectId = newProject(tenantId, teamId).getId();
    }

    private long seedTenant() throws Exception {
        return PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long seedTeam(final long owner) throws Exception {
        return PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), owner);
    }

    private Project newProject(final long owner, final long team) {
        final Instant now = Instant.now();
        final Application app = applicationRepository.save(new Application(owner, team, "App", now));
        return projectRepository.save(new Project(app, owner, team, "P", now));
    }

    private static List<WorkingTimeRange> nineToFive() {
        return List.of(new WorkingTimeRange(9, 17));
    }

    private static List<Integer> monToFri() {
        return List.of(1, 2, 3, 4, 5);
    }

    // -------- AC: calendar CRUD lifecycle ---------------------------------------------------------

    @Test
    void create_read_update_delete_calendar() {
        final CalendarResponse created = calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.PROJECT, projectId, "Std", monToFri(), nineToFive()));

        assertThat(created.calendarId()).isPositive();
        assertThat(created.scope()).isEqualTo(CalendarScope.PROJECT);
        assertThat(created.workingDays()).containsExactly(1, 2, 3, 4, 5);
        assertThat(created.ranges()).containsExactly(new WorkingTimeRange(9, 17));

        final CalendarResponse read = calendarService.read(tenantId, teamId, created.calendarId());
        assertThat(read.name()).isEqualTo("Std");

        final CalendarResponse updated = calendarService.update(tenantId, teamId, created.calendarId(),
                new UpdateCalendarRequest("Std-2", List.of(1, 2, 3), List.of(new WorkingTimeRange(8, 12))));
        assertThat(updated.name()).isEqualTo("Std-2");
        assertThat(updated.workingDays()).containsExactly(1, 2, 3);
        assertThat(updated.ranges()).containsExactly(new WorkingTimeRange(8, 12));

        calendarService.delete(tenantId, teamId, created.calendarId());
        assertThat(calendarRepository.findByIdAndTenantIdAndTeamId(created.calendarId(), tenantId, teamId))
                .isEmpty();
    }

    @Test
    void list_returnsTenantTeamCalendars() {
        calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.PROJECT, projectId, "A", monToFri(), nineToFive()));
        calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.RESOURCE, null, "R", monToFri(), nineToFive()));

        assertThat(calendarService.list(tenantId, teamId)).hasSize(2);
    }

    // -------- AC: exceptions can be added and removed --------------------------------------------

    @Test
    void addException_interval_expandsPerDay_thenRemove() {
        final long calId = calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.PROJECT, projectId, "C", monToFri(), nineToFive()))
                .calendarId();

        final List<CalendarExceptionResponse> added = calendarService.addException(tenantId, teamId, calId,
                new AddExceptionRequest(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3), false, null));

        assertThat(added).hasSize(3);
        assertThat(added).allMatch(e -> !e.working());
        assertThat(calendarService.listExceptions(tenantId, teamId, calId)).hasSize(3);

        calendarService.removeException(tenantId, teamId, calId, added.get(0).exceptionId());
        assertThat(calendarService.listExceptions(tenantId, teamId, calId)).hasSize(2);
    }

    // -------- AC: a public holiday (day off) is removed from worked time (via the engine) ---------

    @Test
    void holidayException_removesThatDayFromWorkedTime() {
        final long calId = calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.PROJECT, projectId, "C", monToFri(), nineToFive()))
                .calendarId();
        // Friday 2026-05-01 declared off (Labour Day).
        calendarService.addException(tenantId, teamId, calId,
                new AddExceptionRequest(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1), false, null));

        final WorkingCalendar wc = engineViewOf(calId);
        // 2026-05-01 is a Friday: a working day per the mask, but exception makes it non-working.
        assertThat(wc.isWorkingDay(LocalDate.of(2026, 5, 1))).isFalse();
        // 2026-04-30 (Thursday) stays a working day.
        assertThat(wc.isWorkingDay(LocalDate.of(2026, 4, 30))).isTrue();

        // A 24-worked-hour task starting Thu 2026-04-30 09:00 skips the Friday holiday: it lands
        // Thu (8h) + Mon (8h) + Tue (8h) → Tue 2026-05-05 17:00, not Mon.
        final Instant start = LocalDate.of(2026, 4, 30).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();
        final Instant finish = wc.advance(start, 24 * 60);
        assertThat(finish).isEqualTo(
                LocalDate.of(2026, 5, 5).atStartOfDay(ZoneOffset.UTC).plusHours(17).toInstant());
    }

    // -------- AC: an exceptionally-worked day (with ranges) is added ------------------------------

    @Test
    void exceptionalWorkingDay_addsThatDayToWorkedTime() {
        final long calId = calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.PROJECT, projectId, "C", monToFri(), nineToFive()))
                .calendarId();
        // Saturday 2026-05-02 exceptionally worked 09:00-13:00.
        calendarService.addException(tenantId, teamId, calId, new AddExceptionRequest(
                LocalDate.of(2026, 5, 2), LocalDate.of(2026, 5, 2), true, List.of(new WorkingTimeRange(9, 13))));

        final WorkingCalendar wc = engineViewOf(calId);
        assertThat(wc.isWorkingDay(LocalDate.of(2026, 5, 2))).isTrue();

        final CalendarExceptionResponse ex = calendarService.listExceptions(tenantId, teamId, calId).get(0);
        assertThat(ex.working()).isTrue();
        assertThat(ex.ranges()).containsExactly(new WorkingTimeRange(9, 13));
    }

    // -------- AC: resource > task > project resolution priority (D7) ------------------------------

    @Test
    void resolveEffective_appliesResourceOverTaskOverProject() {
        final long projectCal = calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.PROJECT, projectId, "proj-cal", monToFri(), nineToFive()))
                .calendarId();
        final long taskCal = calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.TASK, projectId, "task-cal", monToFri(), nineToFive()))
                .calendarId();
        final long resourceCal = calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.RESOURCE, null, "alice", monToFri(), nineToFive()))
                .calendarId();

        attachProjectCalendar(projectId, projectCal);
        final Task task = newTask(taskCal);
        assignmentRepository.save(new Assignment(tenantId, teamId, task.getId(), "alice", new BigDecimal("100")));

        // resourceRef "alice" → resource calendar wins
        final EffectiveCalendarResponse r = calendarService.resolveEffective(
                tenantId, teamId, projectId, task.getId(), "alice");
        assertThat(r.resolvedFrom()).isEqualTo(CalendarScope.RESOURCE);
        assertThat(r.calendarId()).isEqualTo(resourceCal);

        // no resourceRef → task calendar wins
        final EffectiveCalendarResponse t = calendarService.resolveEffective(
                tenantId, teamId, projectId, task.getId(), null);
        assertThat(t.resolvedFrom()).isEqualTo(CalendarScope.TASK);
        assertThat(t.calendarId()).isEqualTo(taskCal);

        // task without its own calendar → project calendar wins
        final Task bare = newTask(null);
        final EffectiveCalendarResponse p = calendarService.resolveEffective(
                tenantId, teamId, projectId, bare.getId(), null);
        assertThat(p.resolvedFrom()).isEqualTo(CalendarScope.PROJECT);
        assertThat(p.calendarId()).isEqualTo(projectCal);
    }

    @Test
    void resolveEffective_resourceRefNotAssigned_fallsBackToTaskOrProject() {
        final long projectCal = calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.PROJECT, projectId, "proj-cal", monToFri(), nineToFive()))
                .calendarId();
        calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.RESOURCE, null, "bob", monToFri(), nineToFive()));
        attachProjectCalendar(projectId, projectCal);
        final Task task = newTask(null);
        // "bob" has a resource calendar but is not assigned to this task → resource level ignored.
        final EffectiveCalendarResponse r = calendarService.resolveEffective(
                tenantId, teamId, projectId, task.getId(), "bob");
        assertThat(r.resolvedFrom()).isEqualTo(CalendarScope.PROJECT);
        assertThat(r.calendarId()).isEqualTo(projectCal);
    }

    @Test
    void resolveEffective_noCalendarAnywhere_throwsNotFound() {
        final Task task = newTask(null);
        assertThatExceptionOfType(CalendarNotFoundException.class).isThrownBy(() ->
                calendarService.resolveEffective(tenantId, teamId, projectId, task.getId(), null));
    }

    @Test
    void resolveEffective_unknownTask_throwsNotFound() {
        assertThatExceptionOfType(CalendarNotFoundException.class).isThrownBy(() ->
                calendarService.resolveEffective(tenantId, teamId, projectId, 999_999L, null));
    }

    // -------- Error AC: exception with end date before start date → 422-equivalent ----------------

    @Test
    void addException_endBeforeStart_rejected() {
        final long calId = calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.PROJECT, projectId, "C", monToFri(), nineToFive()))
                .calendarId();

        assertThatExceptionOfType(InvalidCalendarException.class).isThrownBy(() ->
                calendarService.addException(tenantId, teamId, calId, new AddExceptionRequest(
                        LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 1), false, null)));
    }

    // -------- Error AC: working-time range end not after start → 422-equivalent -------------------

    @Test
    void create_invalidWorkingRange_rejected() {
        assertThatExceptionOfType(InvalidCalendarException.class).isThrownBy(() ->
                calendarService.create(tenantId, teamId, new CreateCalendarRequest(
                        CalendarScope.PROJECT, projectId, "C", monToFri(), List.of(new WorkingTimeRange(17, 9)))));
    }

    @Test
    void create_emptyWorkingDaysMask_rejected() {
        assertThatExceptionOfType(InvalidCalendarException.class).isThrownBy(() ->
                calendarService.create(tenantId, teamId, new CreateCalendarRequest(
                        CalendarScope.PROJECT, projectId, "C", List.of(8), nineToFive())));
    }

    // -------- Security AC: cross-tenant / cross-team isolation (404-equivalent) --------------------

    @Test
    void crossTenant_readCalendar_throwsNotFound() throws Exception {
        final long calId = calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.PROJECT, projectId, "C", monToFri(), nineToFive()))
                .calendarId();
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);

        assertThatExceptionOfType(CalendarNotFoundException.class).isThrownBy(() ->
                calendarService.read(otherTenant, otherTeam, calId));
    }

    @Test
    void crossTenant_addException_throwsNotFound() throws Exception {
        final long calId = calendarService.create(tenantId, teamId,
                new CreateCalendarRequest(CalendarScope.PROJECT, projectId, "C", monToFri(), nineToFive()))
                .calendarId();
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);

        assertThatExceptionOfType(CalendarNotFoundException.class).isThrownBy(() ->
                calendarService.addException(otherTenant, otherTeam, calId, new AddExceptionRequest(
                        LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1), false, null)));
    }

    @Test
    void create_underForeignProject_throwsNotFound() throws Exception {
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);

        assertThatExceptionOfType(CalendarNotFoundException.class).isThrownBy(() ->
                calendarService.create(otherTenant, otherTeam, new CreateCalendarRequest(
                        CalendarScope.PROJECT, projectId, "C", monToFri(), nineToFive())));
    }

    // -------- helpers -----------------------------------------------------------------------------

    /** Rebuilds the pure-engine view of a calendar the same way {@code SchedulingService} does. */
    private WorkingCalendar engineViewOf(final long calId) {
        final Calendar c = calendarRepository.findByIdAndTenantIdAndTeamId(calId, tenantId, teamId).orElseThrow();
        final List<int[]> ranges = CalendarWorkingTime.parse(c.getWorkingTime(),
                List.of(new int[] {9 * 60, 17 * 60}));
        final Map<LocalDate, WorkingCalendar.DayExceptionModel> exceptions = new java.util.HashMap<>();
        exceptionRepository.findAllByCalendarIdAndTenantId(calId, tenantId).forEach(ex -> {
            final List<int[]> exRanges = ex.getWorking()
                    ? CalendarWorkingTime.parse(ex.getWorkingTime(), List.of()) : List.of();
            exceptions.put(ex.getExceptionDate(),
                    new WorkingCalendar.DayExceptionModel(ex.getWorking(), exRanges));
        });
        return new WorkingCalendar(c.getId(), c.getWorkingDaysMask(), ranges, exceptions);
    }

    private void attachProjectCalendar(final long project, final long calendarId) {
        final Project p = projectRepository.findByIdAndTenantIdAndTeamId(project, tenantId, teamId).orElseThrow();
        final Calendar c = calendarRepository.findByIdAndTenantIdAndTeamId(calendarId, tenantId, teamId).orElseThrow();
        p.setCalendar(c);
        projectRepository.save(p);
    }

    private Task newTask(final Long calendarId) {
        final Task t = new Task(tenantId, teamId, projectId, 0, "T", NodeKind.LEAF, Boolean.FALSE,
                TemporalPrecision.DAY, 0);
        t.setCalendarId(calendarId);
        return taskRepository.save(t);
    }
}
