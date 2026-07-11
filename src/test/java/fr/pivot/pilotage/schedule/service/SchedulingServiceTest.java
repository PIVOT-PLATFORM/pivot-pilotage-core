package fr.pivot.pilotage.schedule.service;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Calendar;
import fr.pivot.pilotage.schedule.CalendarException;
import fr.pivot.pilotage.schedule.CalendarExceptionRepository;
import fr.pivot.pilotage.schedule.CalendarRepository;
import fr.pivot.pilotage.schedule.CalendarScope;
import fr.pivot.pilotage.schedule.ConstraintType;
import fr.pivot.pilotage.schedule.DependencyLinkType;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.SchedulingMode;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskConstraint;
import fr.pivot.pilotage.schedule.TaskConstraintRepository;
import fr.pivot.pilotage.schedule.TaskDependency;
import fr.pivot.pilotage.schedule.TaskDependencyRepository;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.schedule.engine.ScheduleException;
import fr.pivot.pilotage.schedule.engine.ScheduleInput;
import fr.pivot.pilotage.schedule.engine.ScheduleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SchedulingService} with mocked repositories (EN22.1b) — exercises the
 * entity → engine mapping branches (constraint kinds, calendar exceptions, SS/FF link types, MANUAL
 * inheritance, unknown-project rejection) without a database, complementing the Testcontainers IT.
 */
@ExtendWith(MockitoExtension.class)
class SchedulingServiceTest {

    private static final long TENANT = 7L;
    private static final long PROJECT = 100L;
    private static final long CAL = 55L;
    private static final String WT = "{\"ranges\":[[\"09:00\",\"17:00\"]]}";
    private static final Instant MON_0900 =
            LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();

    @Mock private ProjectRepository projectRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskDependencyRepository dependencyRepository;
    @Mock private TaskConstraintRepository constraintRepository;
    @Mock private CalendarRepository calendarRepository;
    @Mock private CalendarExceptionRepository calendarExceptionRepository;

    private SchedulingService service;

    @BeforeEach
    void setUp() {
        service = new SchedulingService(projectRepository, taskRepository, dependencyRepository,
                constraintRepository, calendarRepository, calendarExceptionRepository);
    }

    private static void setId(final Object entity, final long id) {
        try {
            final Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Project project(final Calendar cal, final SchedulingMode mode) {
        final Project p = new Project(null, TENANT, "P", MON_0900);
        setId(p, PROJECT);
        p.setCalendar(cal);
        p.setSchedulingMode(mode);
        p.setStatusDate(LocalDate.of(2024, 1, 1));
        return p;
    }

    private Calendar calendar() {
        final Calendar c = new Calendar(TENANT, PROJECT, CalendarScope.PROJECT, "Std",
                (short) 0b0011111, WT);
        setId(c, CAL);
        return c;
    }

    private Task task(final long id, final int pos, final int duration, final NodeKind kind) {
        final Task t = new Task(TENANT, PROJECT, pos, "T" + id, kind, false,
                TemporalPrecision.DAY, 0);
        setId(t, id);
        t.setDurationMinutes(duration);
        t.setStartDate(MON_0900);
        return t;
    }

    private void wireCommon(final Calendar cal, final List<Task> tasks) {
        when(projectRepository.findByIdAndTenantId(PROJECT, TENANT))
                .thenReturn(Optional.of(project(cal, SchedulingMode.AUTO)));
        when(taskRepository.findAllByProjectIdAndTenantId(PROJECT, TENANT)).thenReturn(tasks);
        when(calendarRepository.findAllByTenantId(TENANT)).thenReturn(List.of(cal));
        lenient().when(calendarExceptionRepository.findAllByCalendarIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(List.of());
        lenient().when(constraintRepository.findByTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(dependencyRepository.findAllByPredecessorTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(List.of());
        lenient().when(taskRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void scheduleProject_computesAndPersistsWithSsDependencyAndConstraint() {
        final Calendar cal = calendar();
        final Task a = task(1, 0, 480, NodeKind.LEAF);
        final Task b = task(2, 1, 480, NodeKind.LEAF);
        wireCommon(cal, List.of(a, b));
        // A → B start-to-start with a 60-min lag.
        when(dependencyRepository.findAllByPredecessorTaskIdAndTenantId(1L, TENANT))
                .thenReturn(List.of(new TaskDependency(TENANT, 1L, 2L, DependencyLinkType.SS, 60)));
        // A carries an SNET constraint.
        when(constraintRepository.findByTaskIdAndTenantId(1L, TENANT)).thenReturn(Optional.of(
                new TaskConstraint(TENANT, 1L, ConstraintType.SNET, MON_0900, null)));

        final ScheduleResult r = service.scheduleProject(PROJECT, TENANT);
        assertThat(r.task(1).earlyStart()).isEqualTo(MON_0900);
        assertThat(r.task(2).earlyStart())
                .isEqualTo(MON_0900.plusSeconds(3600)); // start + 1 worked hour
        // Derived columns were written back onto the entities.
        assertThat(a.getEarlyStart()).isEqualTo(MON_0900);
        assertThat(a.getWbsCode()).isEqualTo("1");
    }

    @Test
    void scheduleProject_honoursCalendarExceptionAndFinishToFinish() {
        final Calendar cal = calendar();
        final Task a = task(1, 0, 480, NodeKind.LEAF);
        final Task b = task(2, 1, 480, NodeKind.LEAF);
        wireCommon(cal, List.of(a, b));
        when(dependencyRepository.findAllByPredecessorTaskIdAndTenantId(1L, TENANT))
                .thenReturn(List.of(new TaskDependency(TENANT, 1L, 2L, DependencyLinkType.FF, 0)));
        // Tuesday is a holiday.
        when(calendarExceptionRepository.findAllByCalendarIdAndTenantId(CAL, TENANT))
                .thenReturn(List.of(new CalendarException(
                        TENANT, CAL, LocalDate.of(2024, 1, 2), false, null)));

        final ScheduleResult r = service.scheduleProject(PROJECT, TENANT);
        // Both finish on the same day (FF), skipping Tuesday.
        assertThat(r.task(2).earlyFinish()).isEqualTo(r.task(1).earlyFinish());
    }

    @Test
    void scheduleProject_manualTaskInheritsProjectModeAndEmitsVariance() {
        final Calendar cal = calendar();
        when(projectRepository.findByIdAndTenantId(PROJECT, TENANT))
                .thenReturn(Optional.of(project(cal, SchedulingMode.MANUAL)));
        final Task a = task(1, 0, 480, NodeKind.LEAF);
        a.setSchedulingMode(null); // inherit project MANUAL
        a.setStartDate(MON_0900.plusSeconds(86400)); // pinned a day later
        when(taskRepository.findAllByProjectIdAndTenantId(PROJECT, TENANT)).thenReturn(List.of(a));
        when(calendarRepository.findAllByTenantId(TENANT)).thenReturn(List.of(cal));
        lenient().when(calendarExceptionRepository.findAllByCalendarIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(List.of());
        lenient().when(constraintRepository.findByTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(dependencyRepository.findAllByPredecessorTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(List.of());
        lenient().when(taskRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        final ScheduleResult r = service.scheduleProject(PROJECT, TENANT);
        assertThat(r.variances()).isNotEmpty();
    }

    @Test
    void buildInput_isProducedFromRepositories() {
        final Calendar cal = calendar();
        wireCommon(cal, List.of(task(1, 0, 480, NodeKind.LEAF)));
        final ScheduleInput input = service.buildInput(PROJECT, TENANT);
        assertThat(input.tenantId()).isEqualTo(TENANT);
        assertThat(input.tasks()).hasSize(1);
    }

    @Test
    void scheduleProject_unknownProjectForTenantRejected() {
        when(projectRepository.findByIdAndTenantId(PROJECT, TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.scheduleProject(PROJECT, TENANT))
                .isInstanceOf(ScheduleException.class);
    }

    @Test
    void scheduleProject_projectWithoutCalendarUsesStandardBusinessFallback() {
        final Project p = project(null, SchedulingMode.AUTO); // no calendar
        when(projectRepository.findByIdAndTenantId(PROJECT, TENANT)).thenReturn(Optional.of(p));
        final Task a = task(1, 0, 480, NodeKind.LEAF);
        when(taskRepository.findAllByProjectIdAndTenantId(PROJECT, TENANT)).thenReturn(List.of(a));
        when(calendarRepository.findAllByTenantId(TENANT)).thenReturn(List.of());
        lenient().when(constraintRepository.findByTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(dependencyRepository.findAllByPredecessorTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(List.of());
        when(taskRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        final ScheduleResult r = service.scheduleProject(PROJECT, TENANT);
        assertThat(r.task(1)).isNotNull(); // fell back to the standard business calendar
    }

    @Test
    void toNode_rejectsCrossTenantTask() {
        final Calendar cal = calendar();
        final Task foreign = task(1, 0, 480, NodeKind.LEAF);
        setForeignTenant(foreign);
        wireCommon(cal, List.of(foreign));
        assertThatThrownBy(() -> service.scheduleProject(PROJECT, TENANT))
                .isInstanceOf(ScheduleException.class);
    }

    private static void setForeignTenant(final Task t) {
        try {
            final Field f = Task.class.getDeclaredField("tenantId");
            f.setAccessible(true);
            f.set(t, 999L);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void scheduleProject_allConstraintKindsMapWithoutError() {
        final Calendar cal = calendar();
        final Task a = task(1, 0, 480, NodeKind.LEAF);
        wireCommon(cal, List.of(a));
        for (final ConstraintType ct : List.of(ConstraintType.ASAP, ConstraintType.ALAP,
                ConstraintType.MSO, ConstraintType.MFO, ConstraintType.FNET,
                ConstraintType.FNLT, ConstraintType.SNLT)) {
            final Instant date = ct == ConstraintType.ASAP || ct == ConstraintType.ALAP
                    ? null : MON_0900.plusSeconds(86400 * 2);
            when(constraintRepository.findByTaskIdAndTenantId(1L, TENANT)).thenReturn(Optional.of(
                    new TaskConstraint(TENANT, 1L, ct, date, null)));
            final ScheduleResult r = service.scheduleProject(PROJECT, TENANT);
            assertThat(r.task(1)).as("constraint %s", ct).isNotNull();
        }
    }
}
