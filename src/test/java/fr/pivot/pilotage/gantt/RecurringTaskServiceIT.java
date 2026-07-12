package fr.pivot.pilotage.gantt;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Calendar;
import fr.pivot.pilotage.schedule.CalendarRepository;
import fr.pivot.pilotage.schedule.CalendarScope;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link RecurringTaskService} (US22.4.6 — jalons &amp; tâches périodiques)
 * against a real PostgreSQL 18 (Testcontainers), this module's Flyway migration applied. One test
 * per behavioural/error/security AC: occurrence generation respecting the working calendar
 * (US22.4.5), the milestone-vs-leaf occurrence classification, the missing-recurrence and
 * too-many-occurrences 422s, the single audit-trace-per-batch security AC, WBS parent promotion, and
 * cross-tenant 404 non-disclosure. {@code public.tenants}/{@code public.teams} are seeded before
 * Flyway via {@link PlatformSchemaTestSupport}. The calendar is Mon-Fri 09:00-17:00.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RecurringTaskServiceIT {

    private static final String WT = "{\"ranges\":[[\"09:00\",\"17:00\"]]}";
    /** One worked day (09:00-17:00 = 8h). */
    private static final int ONE_DAY = 8 * 60;
    /** Monday 2024-01-01 — a working day, no calendar shift expected. */
    private static final LocalDate MONDAY = LocalDate.of(2024, 1, 1);
    /** Saturday 2024-01-06 — a non-working day, every weekly occurrence must shift to Monday. */
    private static final LocalDate SATURDAY = LocalDate.of(2024, 1, 6);

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
    @Autowired private RecurringTaskService service;

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
        return projectRepository.save(p);
    }

    private CreateRecurringTaskRequest weekly(final LocalDate first, final int occurrenceCount,
            final Integer durationMinutes) {
        return new CreateRecurringTaskRequest("Comité hebdo", null, first, RecurrenceFrequency.WEEKLY, null,
                occurrenceCount, durationMinutes);
    }

    // -------- AC2: occurrences generated per the calendar, shifted off non-working days -------------

    @Test
    void createRecurringTask_weeklyOnNonWorkingDay_shiftsEveryOccurrenceToNextWorkingDay() {
        final RecurringTaskResponse response =
                service.createRecurringTask(tenantId, teamId, project.getId(), weekly(SATURDAY, 3, null));

        assertThat(response.series().nodeKind()).isEqualTo(NodeKind.RECURRING);
        assertThat(response.series().nodeKindLabel()).isEqualTo(WbsTaskResponse.labelFor(NodeKind.RECURRING));
        assertThat(response.occurrences()).hasSize(3);
        for (final WbsTaskResponse occurrence : response.occurrences()) {
            assertThat(occurrence.parentTaskId()).isEqualTo(response.series().taskId());
            assertThat(occurrence.wbsCode()).isNotNull();
            assertThat(dayOfWeek(occurrence.startDate())).isEqualTo(DayOfWeek.MONDAY);
        }
        // Every occurrence is zero-duration (no durationMinutes supplied) → auto-classified milestone
        // (US22.4.6 AC1 rule reused for occurrences).
        assertThat(response.occurrences()).allSatisfy(o -> {
            assertThat(o.nodeKind()).isEqualTo(NodeKind.MILESTONE);
            assertThat(o.nodeKindLabel()).isEqualTo(WbsTaskResponse.labelFor(NodeKind.MILESTONE));
        });
    }

    // -------- AC2: a positive duration yields LEAF occurrences spanning start→finish ----------------

    @Test
    void createRecurringTask_withDuration_generatesLeafOccurrencesSpanningTheDuration() {
        final RecurringTaskResponse response = service.createRecurringTask(tenantId, teamId, project.getId(),
                new CreateRecurringTaskRequest("Sprint review", null, MONDAY, RecurrenceFrequency.DAILY, null, 2,
                        ONE_DAY));

        assertThat(response.occurrences()).hasSize(2);
        for (final WbsTaskResponse occurrence : response.occurrences()) {
            assertThat(occurrence.nodeKind()).isEqualTo(NodeKind.LEAF);
            assertThat(occurrence.durationMinutes()).isEqualTo(ONE_DAY);
            assertThat(occurrence.finishDate()).isAfter(occurrence.startDate());
        }
    }

    // -------- AC2: the series carries the persisted recurrence rule ---------------------------------

    @Test
    void createRecurringTask_persistsRecurrenceRuleOnTheSeries() {
        service.createRecurringTask(tenantId, teamId, project.getId(), weekly(MONDAY, 4, null));

        final List<Task> seriesTasks = taskRepository.findAllByProjectIdAndTenantIdAndTeamIdAndNodeKind(
                project.getId(), tenantId, teamId, NodeKind.RECURRING);
        assertThat(seriesTasks).hasSize(1);
        assertThat(seriesTasks.get(0).getRecurrenceRule())
                .isEqualTo("FREQ=WEEKLY;INTERVAL=1;COUNT=4;DTSTART=" + MONDAY);
    }

    // -------- AC2: creating under a parent promotes it to SUMMARY -----------------------------------

    @Test
    void createRecurringTask_underParent_promotesParentToSummary() {
        final Task parent = taskRepository.save(new Task(tenantId, teamId, project.getId(), 0, "Phase",
                NodeKind.LEAF, false, fr.pivot.pilotage.schedule.TemporalPrecision.DAY, 0));

        service.createRecurringTask(tenantId, teamId, project.getId(),
                new CreateRecurringTaskRequest("Comité hebdo", parent.getId(), MONDAY, RecurrenceFrequency.WEEKLY,
                        null, 2, null));

        assertThat(taskRepository.findById(parent.getId()).orElseThrow().getNodeKind()).isEqualTo(NodeKind.SUMMARY);
    }

    // -------- Error AC: missing frequency → 422 with an explicit message ----------------------------

    @Test
    void createRecurringTask_missingFrequency_rejected() {
        final CreateRecurringTaskRequest request =
                new CreateRecurringTaskRequest("Comité hebdo", null, MONDAY, null, null, 3, null);

        assertThatExceptionOfType(InvalidRecurrenceException.class)
                .isThrownBy(() -> service.createRecurringTask(tenantId, teamId, project.getId(), request))
                .withMessageContaining("frequency");
    }

    // -------- Error AC: missing/non-positive occurrence count → 422 with an explicit message --------

    @Test
    void createRecurringTask_nonPositiveOccurrenceCount_rejected() {
        final CreateRecurringTaskRequest request =
                new CreateRecurringTaskRequest("Comité hebdo", null, MONDAY, RecurrenceFrequency.WEEKLY, null, 0,
                        null);

        assertThatExceptionOfType(InvalidRecurrenceException.class)
                .isThrownBy(() -> service.createRecurringTask(tenantId, teamId, project.getId(), request));
    }

    // -------- Error AC: occurrence count exceeding the generation cap → 422 -------------------------

    @Test
    void createRecurringTask_tooManyOccurrences_rejected() {
        final CreateRecurringTaskRequest request = new CreateRecurringTaskRequest("Comité hebdo", null, MONDAY,
                RecurrenceFrequency.DAILY, null, RecurringTaskService.MAX_OCCURRENCES + 1, null);

        assertThatExceptionOfType(InvalidRecurrenceException.class)
                .isThrownBy(() -> service.createRecurringTask(tenantId, teamId, project.getId(), request))
                .withMessageContaining(String.valueOf(RecurringTaskService.MAX_OCCURRENCES));

        // Nothing persisted — the guard runs before any write.
        assertThat(taskRepository.findAllByProjectIdAndTenantIdAndTeamId(project.getId(), tenantId, teamId))
                .isEmpty();
    }

    // -------- Error AC: an unresolved parent → 404-equivalent ---------------------------------------

    @Test
    void createRecurringTask_unknownParent_rejected() {
        final CreateRecurringTaskRequest request = new CreateRecurringTaskRequest("Comité hebdo", 999_999L, MONDAY,
                RecurrenceFrequency.WEEKLY, null, 2, null);

        assertThatExceptionOfType(WbsTaskNotFoundException.class)
                .isThrownBy(() -> service.createRecurringTask(tenantId, teamId, project.getId(), request));
    }

    // -------- Security AC: cross-tenant project is a 404-equivalent (non-disclosure) ----------------

    @Test
    void createRecurringTask_crossTenant_isNotFound() {
        final long otherTenant = tenantId + 999L;

        assertThatExceptionOfType(WbsProjectNotFoundException.class).isThrownBy(() ->
                service.createRecurringTask(otherTenant, teamId, project.getId(), weekly(MONDAY, 3, null)));
    }

    // -------- Security AC: the batch is traced as a SINGLE action, not one per occurrence -----------

    @Test
    void createRecurringTask_tracesTheWholeBatchAsOneLogLine() {
        final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RecurringTaskService.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.createRecurringTask(tenantId, teamId, project.getId(), weekly(MONDAY, 5, null));

            final List<ILoggingEvent> events = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("event=recurring_task_created"))
                    .toList();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getFormattedMessage()).contains("generatedOccurrences=5");
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static DayOfWeek dayOfWeek(final Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).getDayOfWeek();
    }
}
