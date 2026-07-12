package fr.pivot.pilotage.schedule.service;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Calendar;
import fr.pivot.pilotage.schedule.CalendarException;
import fr.pivot.pilotage.schedule.CalendarExceptionRepository;
import fr.pivot.pilotage.schedule.CalendarRepository;
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
import fr.pivot.pilotage.schedule.engine.CalendarWorkingTime;
import fr.pivot.pilotage.schedule.engine.ConstraintKind;
import fr.pivot.pilotage.schedule.engine.DependencyEdge;
import fr.pivot.pilotage.schedule.engine.LinkType;
import fr.pivot.pilotage.schedule.engine.NodeType;
import fr.pivot.pilotage.schedule.engine.ScheduleEngine;
import fr.pivot.pilotage.schedule.engine.ScheduleException;
import fr.pivot.pilotage.schedule.engine.ScheduleErrorCode;
import fr.pivot.pilotage.schedule.engine.ScheduleInput;
import fr.pivot.pilotage.schedule.engine.ScheduleResult;
import fr.pivot.pilotage.schedule.engine.TaskMode;
import fr.pivot.pilotage.schedule.engine.TaskNode;
import fr.pivot.pilotage.schedule.engine.TaskSchedule;
import fr.pivot.pilotage.schedule.engine.WorkingCalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped Spring service (EN22.1b) that loads a project's temporal graph via the EN22.1a
 * repositories, invokes the pure {@link ScheduleEngine}, and persists the derived columns
 * ({@code early_*}/{@code late_*}, slack, {@code is_critical}) plus the derived {@code wbs_code}.
 *
 * <p>The engine core stays free of Spring/JPA; this service is the sole seam that maps entities onto
 * engine value objects and back. Every read path is tenant-scoped; a project belonging to another
 * tenant simply resolves to no tasks (the caller-facing 404 mapping lives at the controller layer,
 * out of EN22.1b scope). The standard business calendar (Mon-Fri, 09:00-17:00) is the fallback when
 * a project carries no calendar rows.
 *
 * <p>{@link #previewSchedule(long, long)} (US22.4.4) reuses the same {@link #buildInput(long, long)}
 * mapping but skips {@link #persistDerived(long, long, ScheduleResult)} — a pure read that never
 * mutates the stored schedule.
 */
@Service
public class SchedulingService {

    /** Default whole-hour business ranges (09:00-17:00) used when a calendar has no explicit ranges. */
    private static final List<int[]> DEFAULT_RANGES = List.of(new int[] {9 * 60, 17 * 60});

    /** Default Mon-Fri working-days bitmask when a calendar row omits one. */
    private static final int DEFAULT_MASK = 0b0011111;

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final TaskDependencyRepository dependencyRepository;
    private final TaskConstraintRepository constraintRepository;
    private final CalendarRepository calendarRepository;
    private final CalendarExceptionRepository calendarExceptionRepository;
    private final ScheduleEngine engine;

    /**
     * Constructs the service with its repositories and a fresh engine instance.
     *
     * @param projectRepository           project repository
     * @param taskRepository              task repository
     * @param dependencyRepository        dependency repository
     * @param constraintRepository        constraint repository
     * @param calendarRepository          calendar repository
     * @param calendarExceptionRepository calendar-exception repository
     */
    public SchedulingService(final ProjectRepository projectRepository,
            final TaskRepository taskRepository,
            final TaskDependencyRepository dependencyRepository,
            final TaskConstraintRepository constraintRepository,
            final CalendarRepository calendarRepository,
            final CalendarExceptionRepository calendarExceptionRepository) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.dependencyRepository = dependencyRepository;
        this.constraintRepository = constraintRepository;
        this.calendarRepository = calendarRepository;
        this.calendarExceptionRepository = calendarExceptionRepository;
        this.engine = new ScheduleEngine();
    }

    /**
     * Loads, schedules and persists a project's temporal graph, tenant-scoped.
     *
     * @param projectId the project id
     * @param tenantId  the owning tenant id (isolation boundary)
     * @return the CPM result (also persisted onto the tasks' derived columns)
     * @throws ScheduleException on cycle / tenant violation / unknown calendar
     */
    @Transactional
    public ScheduleResult scheduleProject(final long projectId, final long tenantId) {
        final ScheduleInput input = buildInput(projectId, tenantId);
        final ScheduleResult result = engine.schedule(input);
        persistDerived(projectId, tenantId, result);
        return result;
    }

    /**
     * Runs the CPM from the persisted graph <strong>without persisting</strong> the derived columns
     * (US22.4.4) — used by read-only endpoints (e.g. the constraint/deadline conflict view) that need
     * the engine's <em>current</em> warnings without side-effecting the stored schedule on a mere
     * {@code GET}. Computed, never stored twice, same posture as the summary rollups (EN22.1c).
     *
     * @param projectId the project id
     * @param tenantId  the owning tenant id (isolation boundary)
     * @return the CPM result, transient (not persisted)
     * @throws ScheduleException on cycle / tenant violation / unknown calendar
     */
    @Transactional(readOnly = true)
    public ScheduleResult previewSchedule(final long projectId, final long tenantId) {
        return engine.schedule(buildInput(projectId, tenantId));
    }

    /**
     * Resolves a project's default working calendar (EN22.1a calendar rows plus exceptions, falling
     * back to the standard Mon-Fri 09:00-17:00 business calendar when none is configured) &mdash; a
     * read-only accessor reused by any Gantt feature that needs to snap a wall-clock instant into
     * working time (US22.4.6 &mdash; periodic-task occurrence generation) without re-deriving
     * calendar loading (Étape 0, reuse not reinvention) or re-running a full CPM pass.
     *
     * @param projectId the project id
     * @param tenantId  the owning tenant id (isolation boundary)
     * @return the project's effective default {@link WorkingCalendar}
     * @throws ScheduleException if the project is not visible to the tenant
     */
    @Transactional(readOnly = true)
    public WorkingCalendar defaultCalendar(final long projectId, final long tenantId) {
        final Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> new ScheduleException(ScheduleErrorCode.TENANT_VIOLATION,
                        "project " + projectId + " not found for tenant " + tenantId));
        final long defaultCalendarId = project.getCalendar() != null ? project.getCalendar().getId() : -1L;
        return buildCalendars(projectId, tenantId, defaultCalendarId).get(defaultCalendarId);
    }

    /**
     * Builds the engine input from the persisted graph (tenant-scoped). Package-visible so tests can
     * exercise the mapping without a database.
     *
     * @param projectId the project id
     * @param tenantId  the tenant id
     * @return the engine input
     */
    ScheduleInput buildInput(final long projectId, final long tenantId) {
        final Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> new ScheduleException(ScheduleErrorCode.TENANT_VIOLATION,
                        "project " + projectId + " not found for tenant " + tenantId));
        final List<Task> tasks = taskRepository.findAllByProjectIdAndTenantId(projectId, tenantId);
        final List<TaskDependency> deps = new ArrayList<>();
        for (final Task t : tasks) {
            deps.addAll(dependencyRepository.findAllByPredecessorTaskIdAndTenantId(t.getId(), tenantId));
        }

        final Map<Long, String> wbs = WbsNumbering.derive(tasks);
        final long defaultCalendarId = project.getCalendar() != null ? project.getCalendar().getId() : -1L;
        final Map<Long, WorkingCalendar> calendars = buildCalendars(projectId, tenantId, defaultCalendarId);
        final SchedulingMode projectMode = project.getSchedulingMode();
        final Instant projectStart = resolveProjectStart(project, tasks);

        final List<TaskNode> nodes = new ArrayList<>();
        for (final Task t : tasks) {
            nodes.add(toNode(t, wbs.get(t.getId()), projectMode, tenantId, defaultCalendarId));
        }
        final List<DependencyEdge> edges = new ArrayList<>();
        for (final TaskDependency d : deps) {
            final long edgeId = d.getId() != null ? d.getId() : 0L;
            final long lag = d.getLagMinutes() != null ? d.getLagMinutes() : 0L;
            edges.add(new DependencyEdge(edgeId, d.getPredecessorTaskId(), d.getSuccessorTaskId(),
                    toLinkType(d.getLinkType()), lag));
        }

        return new ScheduleInput(projectId, tenantId,
                project.getStatusDate() != null ? project.getStatusDate().atStartOfDay(ZoneOffset.UTC).toInstant() : projectStart,
                projectStart, defaultCalendarId, nodes, edges, calendars);
    }

    private Instant resolveProjectStart(final Project project, final List<Task> tasks) {
        // Earliest pinned start among tasks, else the status date, else the epoch-anchored default.
        Instant earliest = null;
        for (final Task t : tasks) {
            if (t.getStartDate() != null && (earliest == null || t.getStartDate().isBefore(earliest))) {
                earliest = t.getStartDate();
            }
        }
        if (earliest != null) {
            return earliest;
        }
        if (project.getStatusDate() != null) {
            return project.getStatusDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return LocalDate.of(2020, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Map<Long, WorkingCalendar> buildCalendars(final long projectId, final long tenantId,
            final long defaultCalendarId) {
        final Map<Long, WorkingCalendar> map = new HashMap<>();
        final List<Calendar> calendars = calendarRepository.findAllByTenantId(tenantId);
        for (final Calendar c : calendars) {
            if (c.getProjectId() != null && c.getProjectId() != projectId) {
                continue;
            }
            map.put(c.getId(), toWorkingCalendar(c, tenantId));
        }
        if (map.isEmpty() || !map.containsKey(defaultCalendarId)) {
            map.put(defaultCalendarId, WorkingCalendar.standardBusiness(defaultCalendarId));
        }
        return map;
    }

    private WorkingCalendar toWorkingCalendar(final Calendar c, final long tenantId) {
        final int mask = c.getWorkingDaysMask() != null ? c.getWorkingDaysMask() : DEFAULT_MASK;
        final List<int[]> ranges = CalendarWorkingTime.parse(c.getWorkingTime(), DEFAULT_RANGES);
        final Map<LocalDate, WorkingCalendar.DayExceptionModel> exceptions = new HashMap<>();
        for (final CalendarException ex : calendarExceptionRepository
                .findAllByCalendarIdAndTenantId(c.getId(), tenantId)) {
            final List<int[]> exRanges = ex.getWorking()
                    ? CalendarWorkingTime.parse(ex.getWorkingTime(), List.of()) : List.of();
            exceptions.put(ex.getExceptionDate(),
                    new WorkingCalendar.DayExceptionModel(ex.getWorking(), exRanges));
        }
        return new WorkingCalendar(c.getId(), mask, ranges, exceptions);
    }

    private TaskNode toNode(final Task t, final String wbsPath, final SchedulingMode projectMode,
            final long tenantId, final long defaultCalendarId) {
        if (!t.getTenantId().equals(tenantId)) {
            throw new ScheduleException(ScheduleErrorCode.TENANT_VIOLATION,
                    "task " + t.getId() + " belongs to tenant " + t.getTenantId() + ", not " + tenantId);
        }
        final SchedulingMode effective = t.getSchedulingMode() != null ? t.getSchedulingMode() : projectMode;
        final TaskMode mode = effective == SchedulingMode.MANUAL ? TaskMode.MANUAL : TaskMode.AUTO;
        final long calId = t.getCalendarId() != null ? t.getCalendarId() : defaultCalendarId;
        final long duration = t.getDurationMinutes() != null ? t.getDurationMinutes() : 0L;

        ConstraintKind kind = null;
        Instant constraintDate = null;
        Instant deadline = null;
        final var maybe = constraintRepository.findByTaskIdAndTenantId(t.getId(), tenantId);
        if (maybe.isPresent()) {
            final TaskConstraint c = maybe.get();
            kind = toConstraintKind(c.getConstraintType());
            constraintDate = c.getConstraintDate();
            deadline = c.getDeadline();
        }
        return new TaskNode(t.getId(), wbsPath, t.getParentTaskId(), toNodeType(t.getNodeKind()),
                duration, mode, calId, kind, constraintDate, deadline,
                t.getStartDate(), t.getFinishDate());
    }

    private void persistDerived(final long projectId, final long tenantId, final ScheduleResult result) {
        final List<Task> tasks = taskRepository.findAllByProjectIdAndTenantId(projectId, tenantId);
        final Map<Long, String> wbs = WbsNumbering.derive(tasks);
        for (final Task t : tasks) {
            t.setWbsCode(wbs.get(t.getId()));
            final TaskSchedule ts = result.task(t.getId());
            if (ts != null) {
                t.setEarlyStart(ts.earlyStart());
                t.setEarlyFinish(ts.earlyFinish());
                t.setLateStart(ts.lateStart());
                t.setLateFinish(ts.lateFinish());
                t.setTotalSlackMinutes((int) ts.totalFloatMinutes());
                t.setFreeSlackMinutes((int) ts.freeFloatMinutes());
                t.setCritical(ts.critical());
            }
        }
        taskRepository.saveAll(tasks);
    }

    // ----------------------------------------------------------------- enum mapping -----------

    private static NodeType toNodeType(final NodeKind kind) {
        return switch (kind) {
            case SUMMARY -> NodeType.SUMMARY;
            case LEAF -> NodeType.LEAF;
            case MILESTONE -> NodeType.MILESTONE;
            case RECURRING -> NodeType.RECURRING;
        };
    }

    private static LinkType toLinkType(final DependencyLinkType type) {
        return switch (type) {
            case FS -> LinkType.FS;
            case SS -> LinkType.SS;
            case FF -> LinkType.FF;
            case SF -> LinkType.SF;
        };
    }

    private static ConstraintKind toConstraintKind(final ConstraintType type) {
        return switch (type) {
            case ASAP -> ConstraintKind.ASAP;
            case ALAP -> ConstraintKind.ALAP;
            case MSO -> ConstraintKind.MSO;
            case MFO -> ConstraintKind.MFO;
            case SNET -> ConstraintKind.SNET;
            case SNLT -> ConstraintKind.SNLT;
            case FNET -> ConstraintKind.FNET;
            case FNLT -> ConstraintKind.FNLT;
        };
    }
}
